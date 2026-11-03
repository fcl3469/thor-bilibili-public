package local.thor.bilichannels;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Experimental SDR 30->60 motion-compensated renderer. All GL calls stay on GLSurfaceView's thread. */
final class GlMemcView extends GLSurfaceView implements GLSurfaceView.Renderer {

 private static final int LOW_W=320, LOW_H=180, FLOW_W=160, FLOW_H=90;
 private static final String VS="attribute vec2 aPos; varying vec2 uv; void main(){uv=(aPos+1.0)*0.5; gl_Position=vec4(aPos,0.0,1.0);}";
 private static final String COPY_OES="#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 uv; uniform samplerExternalOES tex; uniform mat4 transform; void main(){gl_FragColor=texture2D(tex,(transform*vec4(uv,0.0,1.0)).xy);}";
 private static final String COPY="precision mediump float; varying vec2 uv; uniform sampler2D tex; void main(){gl_FragColor=texture2D(tex,uv);}";
 // Estimate forward motion on 160x90 cells. Each cell searches a 9x9 window in 320x180 copies.
 // The output RG components contain signed low-resolution displacement; B is match confidence.
 private static final String FLOW="precision highp float; varying vec2 uv; uniform sampler2D oldTex; uniform sampler2D newTex; uniform vec2 invLow;"
  +"float lum(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}"
  +"void main(){float best=1e9; vec2 bestD=vec2(0.0);"
  +"for(int y=-4;y<=4;y++){for(int x=-4;x<=4;x++){vec2 d=vec2(float(x),float(y))*invLow; float e=0.0;"
  +"for(int j=-1;j<=1;j+=2){for(int i=-1;i<=1;i+=2){vec2 p=clamp(uv+vec2(float(i),float(j))*invLow,vec2(0.0),vec2(1.0));"
  +"e+=abs(lum(texture2D(oldTex,p).rgb)-lum(texture2D(newTex,clamp(p+d,vec2(0.0),vec2(1.0))).rgb));}}"
  +"e+=0.002*float(x*x+y*y); if(e<best){best=e;bestD=vec2(float(x),float(y));}}}"
  +"gl_FragColor=vec4((bestD+4.0)/8.0,clamp(1.0-best*0.5,0.0,1.0),1.0);}";
 private static final String WARP="precision highp float; varying vec2 uv; uniform sampler2D oldTex; uniform sampler2D newTex; uniform sampler2D flowTex; uniform vec2 invLow;"
  +"void main(){vec4 flow=texture2D(flowTex,uv); vec2 move=(flow.rg*8.0-4.0)*invLow*flow.b;"
  +"vec4 a=texture2D(oldTex,clamp(uv-move*0.5,vec2(0.0),vec2(1.0)));"
  +"vec4 b=texture2D(newTex,clamp(uv+move*0.5,vec2(0.0),vec2(1.0))); gl_FragColor=0.5*(a+b);}";
 private static final HandlerThread CLOCK_THREAD=new HandlerThread("thor-memc-clock");
 private static final Handler CLOCK;static{CLOCK_THREAD.start();CLOCK=new Handler(CLOCK_THREAD.getLooper());}
 private final Handler main=new Handler(Looper.getMainLooper());
 private final MemcView.Listener listener;
 private final FloatBuffer quad=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
 private final AtomicInteger available=new AtomicInteger();
 private SurfaceTexture inputTexture;
 private Surface inputSurface;
 private int external,programOes,programCopy,programFlow,programWarp;
 private final int[] fullTex=new int[2],fullFbo=new int[2],lowTex=new int[2],lowFbo=new int[2];
 private int flowTex,flowFbo,middleTex,middleFbo,videoW,videoH,outW,outH,shownTexture,previous=-1;
 private long lastPts;private volatile int generation;private volatile boolean halfway,usable;
 private volatile long sourceFrames,generatedFrames,missedFrames,lastRenderUs,originalFrames;
 private volatile boolean middlePending;
 private long rateAt,rateSource,rateOutput;private float inputFps,outputFps;
 float inputFps(){return inputFps;}float outputFps(){return outputFps;}
 void sampleRates(){long now=SystemClock.elapsedRealtime(),output=originalFrames+generatedFrames;if(rateAt>0&&now-rateAt<1000)return;if(rateAt>0){float seconds=(now-rateAt)/1000f;inputFps=(sourceFrames-rateSource)/seconds;outputFps=(output-rateOutput)/seconds;}rateAt=now;rateSource=sourceFrames;rateOutput=output;}
 long[] counters(){return new long[]{sourceFrames,generatedFrames,originalFrames,missedFrames,lastRenderUs,videoW,videoH};}
 private final float[] matrix=new float[16];

 GlMemcView(Context context,MemcView.Listener listener){super(context);this.listener=listener;quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);setEGLContextClientVersion(2);setEGLConfigChooser(8,8,8,8,16,0);setRenderer(this);setRenderMode(RENDERMODE_WHEN_DIRTY);setPreserveEGLContextOnPause(false);}
 Surface inputSurface(){return usable?inputSurface:null;}
 void videoSize(int w,int h){if(w<=0||h<=0)return;queueEvent(()->{if(w!=videoW||h!=videoH){try{videoW=w;videoH=h;previous=-1;lastPts=0;generation++;allocate();}catch(RuntimeException e){fail(e.getClass().getSimpleName()+": "+e.getMessage());}}});}
 String statistics(){return String.format(Locale.ROOT,"输入 %d · 中间 %d · 输出 %d · 漏帧 %d",sourceFrames,generatedFrames,originalFrames+generatedFrames,missedFrames);}
 @Override public void surfaceDestroyed(SurfaceHolder holder){usable=false;generation++;listener.inputLost();super.surfaceDestroyed(holder);Surface old=inputSurface;SurfaceTexture oldTexture=inputTexture;inputSurface=null;inputTexture=null;if(oldTexture!=null)PlayerEngine.releaseSurface(old,oldTexture);}

 @Override public void onSurfaceCreated(GL10 ignored,EGLConfig config){
  try{
   releaseTextures();Surface old=inputSurface;SurfaceTexture oldTexture=inputTexture;inputSurface=null;inputTexture=null;boolean hadOld=oldTexture!=null;
   programOes=program(COPY_OES);programCopy=program(COPY);programFlow=program(FLOW);programWarp=program(WARP);
   int[] t=new int[1];GLES20.glGenTextures(1,t,0);external=t[0];GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,external);
   GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
   GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
   GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
   GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
   inputTexture=new SurfaceTexture(external);inputTexture.setOnFrameAvailableListener(st->{available.incrementAndGet();requestRender();});
   inputSurface=new Surface(inputTexture);available.set(0);shownTexture=0;previous=-1;lastPts=0;halfway=middlePending=false;generation++;usable=true;
   if(videoW>0&&videoH>0)allocate();
   Surface ready=inputSurface;main.post(()->{if(hadOld){listener.inputLost();PlayerEngine.releaseSurface(old,oldTexture);}if(usable&&inputSurface==ready)listener.inputReady(ready);});
  }catch(RuntimeException e){fail(e.getClass().getSimpleName()+": "+e.getMessage());}
 }
 @Override public void onSurfaceChanged(GL10 ignored,int width,int height){outW=width;outH=height;GLES20.glViewport(0,0,width,height);}
 @Override public void onDrawFrame(GL10 ignored){
  if(!usable||inputTexture==null)return;
  long started=System.nanoTime();
  try{
   int arrivals=available.getAndSet(0);
   if(arrivals>0){
    missedFrames+=Math.max(0,arrivals-1);long receivedAt=SystemClock.uptimeMillis();
    inputTexture.updateTexImage();long pts=inputTexture.getTimestamp();inputTexture.getTransformMatrix(matrix);
    if(videoW<=0||videoH<=0||fullTex[0]==0)return;
    long interval=pts-lastPts;
    if(previous>=0&&(interval<=0||interval>100000000L)){previous=-1;halfway=middlePending=false;generation++;}
    if(middlePending){missedFrames++;halfway=middlePending=false;}
    int next=previous<0?0:1-previous;
    drawOes(fullFbo[next],videoW,videoH);
    sourceFrames++;lastPts=pts;
    if(previous<0||interval<25000000L){generation++;previous=next;drawCopy(fullTex[next],0,outW,outH);originalFrames++;return;}
    drawCopy(fullTex[previous],lowFbo[previous],LOW_W,LOW_H);
    drawCopy(fullTex[next],lowFbo[next],LOW_W,LOW_H);
    drawFlow(previous,next);
    drawCopy(fullTex[previous],0,outW,outH);originalFrames++;
    previous=next;halfway=false;middlePending=true;final int ticket=++generation;
    long midpoint=receivedAt+Math.max(8,Math.min(25,interval/2000000L));
    CLOCK.postDelayed(()->{if(usable&&generation==ticket){halfway=true;requestRender();}},Math.max(0,midpoint-SystemClock.uptimeMillis()));
   }else if(halfway&&previous>=0){
    halfway=middlePending=false;
    int old=1-previous;drawWarp(old,previous);drawCopy(middleTex,0,outW,outH);generatedFrames++;
   }else if(shownTexture!=0){drawCopy(shownTexture,0,outW,outH);}
  }catch(RuntimeException e){fail(e.getClass().getSimpleName()+": "+e.getMessage());}
  finally{lastRenderUs=(System.nanoTime()-started)/1000;}
 }
 private void drawOes(int fbo,int width,int height){GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);GLES20.glViewport(0,0,width,height);GLES20.glUseProgram(programOes);bind(external,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,programOes,"tex",0);GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programOes,"transform"),1,false,matrix,0);quad(programOes);}
 private void drawCopy(int source,int fbo,int width,int height){if(fbo==0){shownTexture=source;}GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);GLES20.glViewport(0,0,width,height);GLES20.glUseProgram(programCopy);bind(source,GLES20.GL_TEXTURE_2D,programCopy,"tex",0);quad(programCopy);}
 private void drawFlow(int old,int next){GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,flowFbo);GLES20.glViewport(0,0,FLOW_W,FLOW_H);GLES20.glUseProgram(programFlow);bind(lowTex[old],GLES20.GL_TEXTURE_2D,programFlow,"oldTex",0);bind(lowTex[next],GLES20.GL_TEXTURE_2D,programFlow,"newTex",1);GLES20.glUniform2f(GLES20.glGetUniformLocation(programFlow,"invLow"),1f/LOW_W,1f/LOW_H);quad(programFlow);}
 private void drawWarp(int old,int next){GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,middleFbo);GLES20.glViewport(0,0,videoW,videoH);GLES20.glUseProgram(programWarp);bind(fullTex[old],GLES20.GL_TEXTURE_2D,programWarp,"oldTex",0);bind(fullTex[next],GLES20.GL_TEXTURE_2D,programWarp,"newTex",1);bind(flowTex,GLES20.GL_TEXTURE_2D,programWarp,"flowTex",2);GLES20.glUniform2f(GLES20.glGetUniformLocation(programWarp,"invLow"),1f/LOW_W,1f/LOW_H);quad(programWarp);}
 private void bind(int texture,int target,int program,String name,int unit){GLES20.glActiveTexture(GLES20.GL_TEXTURE0+unit);GLES20.glBindTexture(target,texture);GLES20.glUniform1i(GLES20.glGetUniformLocation(program,name),unit);}
 private void quad(int program){int a=GLES20.glGetAttribLocation(program,"aPos");quad.position(0);GLES20.glEnableVertexAttribArray(a);GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,0,quad);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisableVertexAttribArray(a);}
 private void allocate(){
  if(videoW<=0||videoH<=0)return;
  if((long)videoW*videoH>3840L*2160L)throw new IllegalStateException("source exceeds 4K MEMC limit");
  int[] limit=new int[1];GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,limit,0);
  if(videoW>limit[0]||videoH>limit[0])throw new IllegalStateException("GPU texture limit "+limit[0]);
  releaseFrameTextures();shownTexture=0;for(int i=0;i<2;i++){fullTex[i]=texture(videoW,videoH);fullFbo[i]=framebuffer(fullTex[i]);lowTex[i]=texture(LOW_W,LOW_H);lowFbo[i]=framebuffer(lowTex[i]);}
  flowTex=texture(FLOW_W,FLOW_H);flowFbo=framebuffer(flowTex);middleTex=texture(videoW,videoH);middleFbo=framebuffer(middleTex);
  previous=-1;lastPts=0;halfway=middlePending=false;
 }
 private int texture(int w,int h){int[] id=new int[1];GLES20.glGenTextures(1,id,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id[0]);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);int error=GLES20.glGetError();if(error!=GLES20.GL_NO_ERROR)throw new IllegalStateException("texture "+w+"x"+h+" GL "+error);return id[0];}
 private int framebuffer(int texture){int[] f=new int[1];GLES20.glGenFramebuffers(1,f,0);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,f[0]);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture,0);int status=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);if(status!=GLES20.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("FBO "+status);return f[0];}
 private int program(String fragment){int v=shader(GLES20.GL_VERTEX_SHADER,VS),f=shader(GLES20.GL_FRAGMENT_SHADER,fragment),p=GLES20.glCreateProgram();GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);int[] result=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,result,0);GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);if(result[0]==0)throw new IllegalStateException("MEMC link: "+GLES20.glGetProgramInfoLog(p));return p;}
 private int shader(int type,String source){int id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,source);GLES20.glCompileShader(id);int[] result=new int[1];GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,result,0);if(result[0]==0)throw new IllegalStateException("MEMC shader: "+GLES20.glGetShaderInfoLog(id));return id;}
 private void releaseFrameTextures(){for(int i=0;i<2;i++){if(fullFbo[i]!=0)GLES20.glDeleteFramebuffers(1,new int[]{fullFbo[i]},0);if(lowFbo[i]!=0)GLES20.glDeleteFramebuffers(1,new int[]{lowFbo[i]},0);if(fullTex[i]!=0)GLES20.glDeleteTextures(1,new int[]{fullTex[i]},0);if(lowTex[i]!=0)GLES20.glDeleteTextures(1,new int[]{lowTex[i]},0);fullFbo[i]=lowFbo[i]=fullTex[i]=lowTex[i]=0;}if(flowFbo!=0)GLES20.glDeleteFramebuffers(1,new int[]{flowFbo},0);if(middleFbo!=0)GLES20.glDeleteFramebuffers(1,new int[]{middleFbo},0);if(flowTex!=0)GLES20.glDeleteTextures(1,new int[]{flowTex},0);if(middleTex!=0)GLES20.glDeleteTextures(1,new int[]{middleTex},0);flowFbo=middleFbo=flowTex=middleTex=0;}
 private void releaseTextures(){releaseFrameTextures();for(int p:new int[]{programOes,programCopy,programFlow,programWarp})if(p!=0)GLES20.glDeleteProgram(p);programOes=programCopy=programFlow=programWarp=0;if(external!=0)GLES20.glDeleteTextures(1,new int[]{external},0);external=0;}
 private void fail(String reason){usable=false;generation++;android.util.Log.e("ThorMEMC",reason);main.post(()->listener.failed(reason));}
}
