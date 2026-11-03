package local.thor.bilichannels;

import android.content.Context;
import android.view.Surface;
import android.widget.FrameLayout;

/** Stable player-facing facade. Switch only after player release is queued. */
final class MemcView extends FrameLayout {
 interface Listener { void inputReady(Surface surface); void inputLost(); void failed(String reason); }
 static final String GL="gl", RIFE="rife";
 private final Listener listener;
 private GlMemcView gl;
 private RifeMemcView rife;
 private String backend;
 private int epoch;
 private int precision,stages;private boolean apu;
 MemcView(Context context,Listener listener){super(context);this.listener=listener;selectBackend(selected(context));}
 static String selected(Context context){String value=context.getSharedPreferences(MainActivity.class.getSimpleName(),Context.MODE_PRIVATE).getString("memcBackend",GL);return RIFE.equals(value)?RIFE:GL;}
 static String label(String value){return RIFE.equals(value)?"RIFE AI · GPU / NPU":"OpenGL 运动补偿";}
 String backend(){return backend;}
 void selectBackend(String value){
  value=RIFE.equals(value)?RIFE:GL;int requested=RifeNative.estimateHeight(getContext());
  int requestedStages=RifeNative.stages(getContext());
  if(value.equals(backend)&&(!RIFE.equals(value)||(precision==requested&&stages==requestedStages&&apu==RifeNative.apuRequested(getContext()))))return;
  precision=requested;stages=requestedStages;apu=RifeNative.apuRequested(getContext());
  final int token=++epoch;removeAllViews();gl=null;rife=null;backend=value;
  Listener guarded=new Listener(){
   public void inputReady(Surface s){if(epoch==token)listener.inputReady(s);}
   public void inputLost(){if(epoch==token)listener.inputLost();}
   public void failed(String reason){if(epoch==token)listener.failed(reason);}
  };
  if(RIFE.equals(value)){rife=new RifeMemcView(getContext(),guarded);addView(rife,new LayoutParams(-1,-1));}
  else{gl=new GlMemcView(getContext(),guarded);addView(gl,new LayoutParams(-1,-1));}
 }
 Surface inputSurface(){return rife!=null?rife.inputSurface():gl.inputSurface();}
 void videoSize(int w,int h){if(rife!=null)rife.videoSize(w,h);else gl.videoSize(w,h);}
 void sourceFrameRate(float fps){if(rife!=null)rife.sourceFrameRate(fps);}
 void sampleRates(){if(rife!=null)rife.sampleRates();else gl.sampleRates();}
 float inputFps(){return rife!=null?rife.inputFps():gl.inputFps();}
 float outputFps(){return rife!=null?rife.outputFps():gl.outputFps();}
 long[] counters(){return rife!=null?rife.counters():gl.counters();}
 boolean adjusting(){return rife!=null&&rife.adjusting();}
 String statistics(){return label(backend)+" · "+(rife!=null?rife.statistics():gl.statistics());}
 GlMemcView glBackend(){return gl;}
 void queueEvent(Runnable r){if(gl==null)throw new IllegalStateException("GL diagnostics require OpenGL");gl.queueEvent(r);}
}
