package local.thor.bilichannels;

import android.app.Service;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Bound to visible playback; PCM/ASR/translation never owns an audio output. */
public final class SubtitleService extends Service {
 static final int PREPARE=1,DECODE=2,CANCEL=3,STOP=4,CLOCK=5,STATUS=10,READY=11,RESULT=12,ERROR=13;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"thor-subtitle-models"));private volatile int revision;private volatile boolean closing,nativeLoaded,batchAsr,shortFirstRead;private long recognizer,translator;
 private static final class Position {final int session,revision,position;final long at;Position(int session,int revision,int position){this.session=session;this.revision=revision;this.position=position;at=SystemClock.elapsedRealtime();}}
 private volatile Position position;private int activeSession,ioRevision=-1;private String ioSource="";
 private JSONObject decoded;private String decodedSource="";private int decodedStart=-1,decodedSamples,decodedRevision=-1,decodedSession;
 private final SubtitleAudioPrefetch audioCache=new SubtitleAudioPrefetch(this::readAudio);private final java.util.concurrent.atomic.AtomicInteger audioId=new java.util.concurrent.atomic.AtomicInteger();
 private final Messenger binder=new Messenger(new Handler(Looper.getMainLooper(),message->{
  if(closing)return true;final Messenger client=message.replyTo;final Bundle data=message.getData();final int session=data.getInt("session"),job=data.getInt("job");
  if(message.what==CLOCK){if(session==activeSession&&data.getInt("position",-1)>=0)position=new Position(session,revision,data.getInt("position"));return true;}
  if(message.what==STOP||message.what==CANCEL){revision++;position=null;audioCache.cancel();if(nativeLoaded)SubtitleNative.cancelAudio();return true;}
  if(message.what==PREPARE){activeSession=session;position=null;}
  final int request=revision;
  if(message.what==PREPARE)worker.execute(()->{
   try{
    if(recognizer!=0&&translator!=0){reply(client,READY,session,job,new Bundle());return;}
    File models=SubtitleModels.prepare(this,value->stage(client,session,job,value));if(closing)return;
    stage(client,session,job,"正在启动本地识别，首次启动约需十几秒");
    nativeLoaded=true;batchAsr=new File(models,"encoder.onnx").isFile();recognizer=SubtitleNative.createRecognizer(models.getPath(),getApplicationInfo().nativeLibraryDir);
    if(closing)return;stage(client,session,job,"正在启动日语→中文直译");
    translator=SubtitleNative.createTranslator(SubtitleModels.directModel(this,value->stage(client,session,job,value)).getPath());
    reply(client,READY,session,job,new Bundle());
   }catch(Throwable error){failure(client,session,job,error);}
  });
  else if(message.what==DECODE)worker.execute(()->decode(client,session,job,request,data));
  return true;
 }));
 @Override public IBinder onBind(Intent intent){return binder.getBinder();}
 @Override public boolean onUnbind(Intent intent){return false;}
 @Override public void onDestroy(){shutdown();super.onDestroy();}
 private void decode(Messenger client,int session,int job,int request,Bundle data){
  try{
   long asrMillis=0,translationMillis=0;
   if(closing||request!=revision)return;if(recognizer==0||translator==0)throw new IOException("字幕模型尚未就绪");
   int requested=data.getInt("start"),window=requested,start=Math.max(0,window-500),duration=Math.min(9000,data.getInt("duration")-start);shortFirstRead=batchAsr&&!data.getBoolean("startup",true);
   if(duration<=0)throw new IOException("视频时长尚未确定，请稍后重试");String source=data.getString("source","");
   stage(client,session,job,"正在预取字幕音轨 · "+MainActivity.clockText(window));
   long waitingAt=SystemClock.elapsedRealtime();SubtitleAudioPrefetch.Chunk audio=audioCache.acquire(source,window,data.getInt("duration"));long waitMillis=SystemClock.elapsedRealtime()-waitingAt;if(closing||request!=revision)return;
   Position latest=position;boolean obsolete=false;
   if(batchAsr&&latest!=null&&latest.session==session&&latest.revision==request&&SystemClock.elapsedRealtime()-latest.at<3000){
    int selected=SubtitleSchedule.translationWindow(window,latest.position/8000*8000,audio.start,(audio.pcm.length+15)/16+1,data.getInt("duration"));
    if(selected<0)obsolete=true;else window=selected;
   }
   start=Math.max(0,window-500);duration=Math.min(9000,data.getInt("duration")-start);
   int from=(start-audio.start)*16,to=Math.min(audio.pcm.length,from+duration*16);short[] pcm=obsolete?new short[0]:Arrays.copyOfRange(audio.pcm,from,Math.max(from,to));int actual=pcm.length/16;JSONArray cues=new JSONArray();boolean signal=false;for(short value:pcm)if(Math.abs((int)value)>4){signal=true;break;}
   if(actual>0&&signal){
    stage(client,session,job,"正在本地识别日语 · "+MainActivity.clockText(window));
    long asrAt=SystemClock.elapsedRealtime();JSONObject asr;
    if(batchAsr){
     if(decoded==null||decodedRevision!=request||decodedSession!=session||decodedStart!=audio.start||decodedSamples!=audio.pcm.length||!decodedSource.equals(source)){
      decoded=new JSONObject(SubtitleNative.recognize(recognizer,audio.pcm));decodedSource=source;decodedStart=audio.start;decodedSamples=audio.pcm.length;decodedRevision=request;decodedSession=session;
     }
     asr=decoded;
    }else asr=new JSONObject(SubtitleNative.recognize(recognizer,pcm));asrMillis=SystemClock.elapsedRealtime()-asrAt;
    for(SubtitleTimeline.Part part:SubtitleTimeline.parts(asr,batchAsr?audio.start:start,batchAsr?audio.pcm.length/16:actual,window)){
     if(closing||request!=revision)return;stage(client,session,job,"正在本地翻译中文 · "+MainActivity.clockText(part.start));
     long translationAt=SystemClock.elapsedRealtime();String chinese=SubtitleNative.translate(translator,part.japanese).trim();translationMillis+=SystemClock.elapsedRealtime()-translationAt;
     cues.put(new JSONObject().put("start",part.start).put("end",part.end).put("ja",part.japanese).put("zh",chinese));
    }
   }
   if(closing||request!=revision)return;Bundle result=new Bundle();result.putInt("start",window);result.putInt("request_start",requested);result.putBoolean("obsolete",obsolete);result.putString("cues",cues.toString());result.putLong("audio_wait_ms",waitMillis);result.putLong("audio_fetch_ms",audio.fetchMillis);result.putLong("asr_ms",asrMillis);result.putLong("translation_ms",translationMillis);result.putInt("audio_batch",audio.window);reply(client,RESULT,session,job,result);
  }catch(Throwable error){if(!closing&&request==revision)failure(client,session,job,error);}
 }
 private SubtitleAudioPrefetch.Chunk readAudio(String source,int window,int total,BooleanSupplier cancelled)throws Exception{
  File wave=new File(getCacheDir(),"subtitle-prefetch-"+System.identityHashCode(this)+"-"+audioId.incrementAndGet()+".wav");ParcelFileDescriptor descriptor=null;
  try{
   if(closing||cancelled.getAsBoolean())throw new CancellationException();String uri=source;
   if(uri.startsWith("content:")){descriptor=getContentResolver().openFileDescriptor(android.net.Uri.parse(uri),"r");if(descriptor==null)throw new IOException("音轨文件无法读取");uri="fd://"+descriptor.getFd();}
   boolean fresh=ioRevision!=revision||!ioSource.equals(source),shortRead=fresh&&shortFirstRead;ioRevision=revision;ioSource=source;
   int start=Math.max(0,window-(shortRead?3000:500)),duration=shortRead?Math.min(total,window+16500)-start:Math.min(SubtitleSchedule.BATCH_MS,total-start);if(duration<=0)throw new IOException("字幕音轨已结束");
   if(cancelled.getAsBoolean())throw new CancellationException();long at=SystemClock.elapsedRealtime();SubtitleNative.extractAudio(uri,wave.getPath(),DoviPlayer.certificates(this),BiliApi.UA,source.startsWith("https:"),start,duration);
   if(closing||cancelled.getAsBoolean())throw new CancellationException();SubtitleWave audio=SubtitleWave.read(wave);return new SubtitleAudioPrefetch.Chunk(source,window,start,audio.pcm,SystemClock.elapsedRealtime()-at);
  }finally{wave.delete();if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}}
 }
 private void stage(Messenger target,int session,int job,String value){Bundle data=new Bundle();data.putString("status",value);reply(target,STATUS,session,job,data);}
 private void failure(Messenger target,int session,int job,Throwable error){Bundle data=new Bundle();String text=error instanceof UnsatisfiedLinkError?"本机暂不支持字幕推理库":error instanceof TimeoutException?"本地翻译等待超时，可关闭字幕后重试":error.getMessage();data.putString("status",text==null?"字幕准备失败，可关闭后重试":text);reply(target,ERROR,session,job,data);}
 private void reply(Messenger target,int type,int session,int job,Bundle data){if(target==null||closing)return;data.putInt("session",session);data.putInt("job",job);Message message=Message.obtain(null,type);message.setData(data);try{target.send(message);}catch(RemoteException ignored){}}
 private synchronized void shutdown(){
  if(closing)return;closing=true;revision++;audioCache.close();if(nativeLoaded)try{SubtitleNative.cancelAudio();}catch(Throwable ignored){}
  worker.execute(()->{try{if(translator!=0)SubtitleNative.closeTranslator(translator);if(recognizer!=0)SubtitleNative.closeRecognizer(recognizer);}finally{stopSelf();}});worker.shutdown();
 }
}
