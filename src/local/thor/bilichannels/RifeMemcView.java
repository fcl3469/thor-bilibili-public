package local.thor.bilichannels;

import android.content.Context;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.view.*;
import java.util.Locale;

/** MediaCodec -> HardwareBuffer -> GPU/NPU RIFE -> Vulkan swapchain. SDR only. */
final class RifeMemcView extends SurfaceView implements SurfaceHolder.Callback {
 private static final java.util.Map<Surface,Session> INPUTS=new java.util.WeakHashMap<>();
 static boolean handles(Surface surface){synchronized(INPUTS){return INPUTS.containsKey(surface);}}
 static Display outputDisplay(Surface surface){synchronized(INPUTS){Session s=INPUTS.get(surface);return s==null?null:s.display;}}
 static void invalidate(Surface surface){synchronized(INPUTS){Session s=INPUTS.get(surface);if(s!=null)s.revision++;}}
 static void playing(Surface surface,boolean playing){synchronized(INPUTS){Session s=INPUTS.get(surface);if(s!=null){s.playbackPaused=!playing;s.revision++;}}}
 static void speed(Surface surface,float speed){synchronized(INPUTS){Session s=INPUTS.get(surface);if(s!=null){s.playbackSpeed=speed;s.revision++;}}}
 static void frameMetadata(Surface surface,long ptsUs,long releaseNs){synchronized(INPUTS){Session s=INPUTS.get(surface);if(s!=null)s.frameClock.record(releaseNs,ptsUs,s.revision);}}
 static boolean decoderFailed(Surface surface,String reason){synchronized(INPUTS){Session s=INPUTS.get(surface);if(s==null||s.closed)return false;s.worker.post(()->s.fail(new IllegalStateException("硬件解码输出不兼容："+reason)));return true;}}
 static void format(Surface surface,androidx.media3.common.Format format){synchronized(INPUTS){Session s=INPUTS.get(surface);if(s==null)return;androidx.media3.common.ColorInfo c=format.colorInfo;if(c!=null){int standard=c.colorSpace==androidx.media3.common.C.COLOR_SPACE_BT709?1:c.colorSpace==androidx.media3.common.C.COLOR_SPACE_BT2020?6:c.colorSpace==androidx.media3.common.C.COLOR_SPACE_BT601?2:0;int range=c.colorRange==androidx.media3.common.C.COLOR_RANGE_FULL?1:2;int transfer=c.colorTransfer==androidx.media3.common.C.COLOR_TRANSFER_ST2084?7:c.colorTransfer==androidx.media3.common.C.COLOR_TRANSFER_HLG?8:3;s.dataSpace=(standard<<16)|(transfer<<22)|(range<<27);}s.rotation=format.rotationDegrees;if(format.frameRate>0)s.formatFps=format.frameRate;}}
 private final MemcView.Listener listener;
 private final Handler ui=new Handler(Looper.getMainLooper());
 private Session session;
 private volatile Surface input;
 private volatile long source,generated,original,missed,inferenceUs;
 private volatile long processingUs;
 private volatile String backendDescription="初始化中",automaticReason="";private volatile boolean adaptiveAdjusting;
 private volatile int width,height,surfaceWidth,surfaceHeight;
 private volatile float sourceFps;
 private long rateAt,rateSource,rateOutput;
 private float inputRate,outputRate;
 RifeMemcView(Context context,MemcView.Listener listener){super(context);this.listener=listener;getHolder().addCallback(this);}
 Surface inputSurface(){return input;}
 void videoSize(int w,int h){width=w;height=h;}
 void sourceFrameRate(float fps){if(fps>0&&Math.abs(fps-sourceFps)>.1f)sourceFps=fps;}

 long[] counters(){return new long[]{source,generated,original,missed,inferenceUs,width,height};}
 void sampleRates(){long now=SystemClock.elapsedRealtime(),out=original+generated;if(rateAt>0&&now-rateAt<1000)return;if(rateAt>0){float dt=(now-rateAt)/1000f;inputRate=(source-rateSource)/dt;outputRate=(out-rateOutput)/dt;}rateAt=now;rateSource=source;rateOutput=out;}
 float inputFps(){return inputRate;}float outputFps(){return outputRate;}
 boolean adjusting(){return adaptiveAdjusting;}
 String statistics(){return String.format(Locale.ROOT,"RIFE %s%s · 片源 %d×%d\n输入 %d · 中间 %d · 输出 %d · 漏帧 %d · 帧处理 %.1f ms",backendDescription,automaticReason.isEmpty()?"":" · "+automaticReason,width,height,source,generated,original+generated,missed,processingUs/1000f);}
 @Override public void surfaceCreated(SurfaceHolder holder){
  Session s=new Session(holder.getSurface());session=s;s.worker.post(s::start);
 }
 @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);surfaceWidth=w;surfaceHeight=h;Session s=session;if(s!=null)s.resize(w,h);}
 @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){Session s=session;if(s!=null)s.resize(getWidth(),getHeight());}
 @Override public void surfaceDestroyed(SurfaceHolder holder){
  synchronized(INPUTS){INPUTS.remove(input);}input=null;Session s=session;session=null;if(s==null)return;s.closed=true;listener.inputLost();
  // The media queue must detach the decoder before ImageReader and its Surface are closed.
  PlayerEngine.afterSurfaceDetach(()->s.worker.post(s::dispose));
 }
 private final class Session {
  final HandlerThread thread=new HandlerThread("thor-rife-vulkan",android.os.Process.THREAD_PRIORITY_DISPLAY);
  final Handler worker;final Surface output;final Display display;
  volatile boolean closed;volatile int revision,dataSpace,rotation;volatile boolean playbackPaused=true;int observedRevision;
  ImageReader reader;RifeImageReader nativeReader;long handle,lastPts;int ticket,slowFrames;
  final RifeFrameClock frameClock=new RifeFrameClock();
  long lastMediaPts=Long.MIN_VALUE;volatile float playbackSpeed=1f;
  PerformanceHintManager.Session hintSession;long hintTarget=15666666L;
  int lastWidth,lastHeight,activeStages,activeEstimate;volatile float formatFps;float votedRate;int filmIntervals;
  boolean pending,qualityBand;final RifeFrameBudget frameBudget=new RifeFrameBudget();
  Session(Surface output){this.output=output;display=RifeMemcView.this.getDisplay();thread.start();worker=new Handler(thread.getLooper());}
  void resize(int w,int h){if(w<=0||h<=0)return;worker.post(()->{if(closed||handle==0)return;try{RifeNative.resize(handle,w,h);backendDescription=RifeNative.description(handle);}catch(Exception e){fail(e);}});}
  void hint(long actual,long interval){
   if(hintSession==null)return;
   // The intermediate is due at half a source period, including display margin.
   try{long target=Math.max(1000000L,interval/2-1000000L);if(Math.abs(target-hintTarget)>500000L){hintSession.updateTargetWorkDuration(target);hintTarget=target;}hintSession.reportActualWorkDuration(actual);}
   catch(RuntimeException e){android.util.Log.w("ThorRIFE","ADPF reporting unavailable",e);try{hintSession.close();}catch(RuntimeException ignored){}hintSession=null;}
  }
  void start(){try{
   if(closed)return;
   if(Build.VERSION.SDK_INT<31)throw new IllegalStateException("RIFE GPU 直通需要 Android 12+，请使用 OpenGL");
   if(!RifeNative.available())throw new IllegalStateException("此安装包或设备不支持 RIFE 原生库");
   try{PerformanceHintManager hints=getContext().getSystemService(PerformanceHintManager.class);if(hints!=null)hintSession=hints.createHintSession(new int[]{android.os.Process.myTid()},hintTarget);android.util.Log.i("ThorRIFE","ADPF active="+(hintSession!=null));}catch(RuntimeException e){android.util.Log.i("ThorRIFE","ADPF unavailable",e);}
   // Publish the decoder input immediately. Select/init the native profile
   // when the first decoded Format supplies its real frame rate.
   // MediaCodec overrides the default buffer dimensions to the actual decoded dimensions.
   if(Build.VERSION.SDK_INT<33){nativeReader=new RifeImageReader(worker,()->frame(null));}
   else{
   reader=ImageReader.newInstance(1920,1080,ImageFormat.PRIVATE,4,android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
   reader.setOnImageAvailableListener(this::frame,worker);
   }
   ui.post(()->{if(!closed&&session==this){input=nativeReader!=null?nativeReader.surface:reader.getSurface();synchronized(INPUTS){INPUTS.put(input,this);}listener.inputReady(input);}});
  }catch(Exception|LinkageError e){fail(e);}}
  RifeImageReader.Frame acquire(ImageReader sourceReader){
   if(nativeReader!=null)return nativeReader.acquire();Image image=sourceReader.acquireLatestImage();return image==null?null:new RifeImageReader.Frame(image);
  }
  void frame(ImageReader sourceReader){
   if(closed)return;long received=SystemClock.uptimeMillis(),acquireAt=System.nanoTime();
   try(RifeImageReader.Frame image=acquire(sourceReader)){
    if(image==null||closed)return;
    int epoch=revision;if(rotation!=0)throw new IllegalStateException("旋转视频暂请使用 OpenGL 方案");
    long acquireUs=(System.nanoTime()-acquireAt)/1000,pts=image.timestamp,surfaceInterval=pts-lastPts;
    long mediaPts=frameClock.take(pts,epoch);
    // Surface timestamps are render deadlines and can be adjusted to VSYNC.
    // Use the corresponding media PTS for the true frame duration and speed.
    boolean mapped=mediaPts!=Long.MIN_VALUE&&lastMediaPts!=Long.MIN_VALUE;
    long interval=mapped?(long)((mediaPts-lastMediaPts)*1000.0/playbackSpeed):surfaceInterval;
    int w=image.width,h=image.height;
    float fps=formatFps>0?formatFps:sourceFps;
    if(fps<=0&&mapped){float observed=1000000f/(mediaPts-lastMediaPts);filmIntervals=observed>=23.5f&&observed<=25.5f?filmIntervals+1:0;if(filmIntervals>=6)fps=sourceFps=observed;}
    boolean film=RifeDevicePolicy.stages(fps)==4;if(film!=qualityBand){qualityBand=film;frameBudget.reset();automaticReason="";}
    int wantedStages=RifeNative.stages(getContext(),fps,frameBudget.fast),wantedEstimate=RifeNative.estimateHeight(getContext(),fps,frameBudget.fast);
    boolean profileChanged=handle==0||wantedStages!=activeStages||wantedEstimate!=activeEstimate;
    if(profileChanged){
     pending=false;ticket++;if(handle!=0){adaptiveAdjusting=true;RifeNative.destroy(handle);handle=0;}
     handle=RifeNative.createSession(getContext(),output,getDisplay(),fps,frameBudget.fast);
     if(handle==0)throw new IllegalStateException("RIFE/Vulkan 初始化失败");
     activeStages=wantedStages;activeEstimate=wantedEstimate;slowFrames=0;
     if(surfaceWidth>0&&surfaceHeight>0)RifeNative.resize(handle,surfaceWidth,surfaceHeight);
     android.util.Log.i("ThorRIFE","source profile fps="+fps+" stages="+activeStages+" estimate="+activeEstimate);
    }
    float vote=RifeDevicePolicy.outputRate(fps);if(Math.abs(vote-votedRate)>.1f){output.setFrameRate(vote,Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);votedRate=vote;}
    boolean discontinuity=profileChanged||epoch!=observedRevision||lastPts==0||interval<=0||interval>100000000L||w!=lastWidth||h!=lastHeight;
    if(pending){if(!discontinuity)missed++;pending=false;}ticket++;
    if(discontinuity)RifeNative.reset(handle);
    else if(interval>50000000L)missed+=Math.max(0,Math.round(interval/33333333.0)-1);
    observedRevision=epoch;
    // A is already resident when B arrives. Queue A before importing/encoding B,
    // allowing its presentation to proceed while the GPU/NPU prepare the pair.
    long workStarted=acquireAt,presentUs=0;
    if(!discontinuity&&interval>=25000000L&&!playbackPaused){
     if(closed||revision!=epoch)return;long at=System.nanoTime();RifeNative.present(handle,2);presentUs=(System.nanoTime()-at)/1000;original++;
    }
    long importAt=System.nanoTime(),importCpuAt=Debug.threadCpuTimeNanos();image.submit(handle,dataSpace);long importUs=(System.nanoTime()-importAt)/1000,importCpuUs=(Debug.threadCpuTimeNanos()-importCpuAt)/1000;image.close();backendDescription=RifeNative.description(handle);source++;width=w;height=h;lastWidth=w;lastHeight=h;lastPts=pts;lastMediaPts=mediaPts;
    if(discontinuity||interval<25000000L||playbackPaused){if(!closed&&revision==epoch){RifeNative.present(handle,2);original++;adaptiveAdjusting=false;}return;}
    // One-frame lookahead: show A when B arrives, then synthesize A/B at half a period.
    if(closed||revision!=epoch)return;
    long inferCpuAt=Debug.threadCpuTimeNanos();inferenceUs=RifeNative.interpolate(handle);long inferCpuUs=(Debug.threadCpuTimeNanos()-inferCpuAt)/1000;
    processingUs=acquireUs+importUs+presentUs+inferenceUs;hint(System.nanoTime()-workStarted,interval);
    if(source%30==0)android.util.Log.i("ThorRIFE","timing us acquire="+acquireUs+" import="+importUs+"/cpu="+importCpuUs+" present="+presentUs+" inference="+inferenceUs+"/cpu="+inferCpuUs+" source="+source+" generated="+generated+" media_period="+interval/1000+" surface_period="+surfaceInterval/1000+" pts_mapped="+mapped);
    if(closed||revision!=epoch)return;
    long sourceBudget=fps>0?(long)(1000000.0/(fps*playbackSpeed)):interval/1000;
    if(activeStages==4&&!RifeNative.benchmarkForced()&&frameBudget.record(processingUs,sourceBudget,generated)){pending=false;ticket++;adaptiveAdjusting=true;automaticReason="自动调整为流畅优先";android.util.Log.i("ThorRIFE","automatic profile: 4-stage -> 2-stage; work_us="+processingUs+" budget_us="+sourceBudget);return;}
    if(processingUs>interval/1000)slowFrames++;else slowFrames=0;
    if(slowFrames>=12&&!RifeNative.benchmarkForced())throw new IllegalStateException("RIFE 持续超过帧预算，请选择 OpenGL 方案");
    long midpoint=received+interval/2000000L;
    // Late work is dropped, never queued into an ever-growing video/audio delay.
    if(SystemClock.uptimeMillis()>received+interval/1000000L){missed++;return;}
    // A due frame must be presented before returning to queued decoder events.
    // Posting it at "now" lets an older image callback invalidate it first.
    if(SystemClock.uptimeMillis()>=midpoint){RifeNative.present(handle,1);generated++;return;}
    pending=true;final int current=ticket;
    worker.postAtTime(()->{if(closed||revision!=epoch||ticket!=current||!pending)return;pending=false;try{RifeNative.present(handle,1);generated++;}catch(Exception e){fail(e);}},Math.max(midpoint,SystemClock.uptimeMillis()));
   }catch(Exception|LinkageError e){fail(e);}
  }
  void fail(Throwable e){
   if(closed)return;closed=true;adaptiveAdjusting=false;
   android.util.Log.e("ThorRIFE","RIFE backend failed",e);
   ui.post(()->{if(session==this){synchronized(INPUTS){INPUTS.remove(input);}input=null;listener.failed("RIFE: "+e.getMessage());PlayerEngine.afterSurfaceDetach(()->worker.post(this::dispose));}});
  }
  void dispose(){closed=true;worker.removeCallbacksAndMessages(null);if(hintSession!=null){try{hintSession.close();}catch(RuntimeException ignored){}hintSession=null;}if(reader!=null){reader.close();reader=null;}if(nativeReader!=null){nativeReader.close();nativeReader=null;}if(handle!=0){RifeNative.destroy(handle);handle=0;}thread.quitSafely();}
 }
}
