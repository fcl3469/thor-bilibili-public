package local.thor.bilichannels;

import android.content.Context;
import android.media.Image;
import android.view.Surface;
import java.io.*;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import java.security.MessageDigest;

/** JNI handles belong to one worker. No inference on the media or UI looper. */
final class RifeNative {
 static final String LOAD_ERROR;
 static {String error=null;try{System.loadLibrary("thor_rife");}catch(LinkageError e){error=e.toString();}LOAD_ERROR=error;}
 static boolean available(){return LOAD_ERROR==null;}
 static boolean hybridSupported(){return android.os.Build.VERSION.SDK_INT>=31&&("SM8550".equalsIgnoreCase(android.os.Build.SOC_MODEL)||"SM8750".equalsIgnoreCase(android.os.Build.SOC_MODEL));}
 static boolean lowPowerMtk(){return android.os.Build.VERSION.SDK_INT>=31&&RifeDevicePolicy.lowPowerMtk(android.os.Build.SOC_MODEL,android.os.Build.HARDWARE);}
 static int lowEstimate(Context c){return RifeDevicePolicy.estimateLongEdge(c.getSharedPreferences("MainActivity",0).getInt("rifeMtkLongEdge",192));}
 static boolean apuRequested(Context c){return lowPowerMtk()&&c.getSharedPreferences("MainActivity",0).getBoolean("rifeMtkApu",true);}
 static boolean elite(){return android.os.Build.VERSION.SDK_INT>=31&&"SM8750".equalsIgnoreCase(android.os.Build.SOC_MODEL);}
 // User-facing RIFE is automatic. Legacy manual preferences never override it.
 static int stages(Context context){return stages(context,Float.NaN);}
 static int estimateHeight(Context context){return estimateHeight(context,Float.NaN);}
 private static volatile int benchmarkStages; // Instrumentation-only, never persisted or exposed in menus.
 static boolean benchmarkForced(){return benchmarkStages==2||benchmarkStages==4;}
 static int stages(Context context,float fps){return stages(context,fps,false);}
 static int stages(Context context,float fps,boolean preferFast){return !hybridSupported()?2:benchmarkForced()?benchmarkStages:preferFast?2:RifeDevicePolicy.stages(fps);}
 static int estimateHeight(Context context,float fps){return estimateHeight(context,fps,false);}
 static int estimateHeight(Context context,float fps,boolean preferFast){return lowPowerMtk()?lowEstimate(context):stages(context,fps,preferFast)==4?540:720;}
 static synchronized String modelDirectory(Context context)throws Exception{return modelDirectory(context,Float.NaN,false);}
 private static String modelDirectory(Context context,float fps,boolean preferFast)throws Exception{
  File directory=new File(context.getFilesDir(),"rife-v4.12-lite");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create model directory");
  java.util.Properties hashes=new java.util.Properties();try(InputStream in=context.getAssets().open("rife-v4.12-lite/sha256.properties")){hashes.load(in);}
  for(String name:new String[]{"flownet.param","flownet.bin"}){
   String expected=hashes.getProperty(name);File target=new File(directory,name);
   if(expected==null)throw new IOException("Missing model digest");
   if(target.isFile()&&expected.equals(digest(target)))continue;
   File temp=new File(directory,name+".tmp");
   try(InputStream in=context.getAssets().open("rife-v4.12-lite/"+name);OutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
   if(!expected.equals(digest(temp))||!temp.renameTo(target))throw new IOException("Model integrity/copy failure");
  }
  if(hybridSupported()){
   try{
    File qnn=new File(context.getFilesDir(),"rife-qnn");java.util.Properties manifest=new java.util.Properties();
    try(InputStream in=context.getAssets().open("rife-qnn/sha256.properties")){manifest.load(in);}
    for(String name:manifest.stringPropertyNames()){
     if(name.contains("..")||name.startsWith("/"))throw new IOException("Unsafe QNN asset path");
     File target=new File(qnn,name);String expected=manifest.getProperty(name);
     if(target.isFile()&&expected.equals(digest(target)))continue;
     if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("QNN directory creation failed");
     File temp=new File(target.getPath()+".tmp");try(InputStream in=context.getAssets().open("rife-qnn/"+name);OutputStream out=new FileOutputStream(temp)){byte[] bytes=new byte[65536];int count;while((count=in.read(bytes))!=-1)out.write(bytes,0,count);}
     if(!expected.equals(digest(temp))||!temp.renameTo(target))throw new IOException("QNN integrity/copy failure");
    }
    configureHybrid(qnn.getAbsolutePath(),context.getApplicationInfo().nativeLibraryDir,estimateHeight(context,fps,preferFast),stages(context,fps,preferFast));
   }catch(Exception e){android.util.Log.w("ThorRIFE","QNN preparation unavailable; Vulkan retained",e);configureHybrid("","",720,2);}
  }
  return directory.getAbsolutePath();
 }
 private static String digest(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
 static void submit(long handle,Image image,int formatDataSpace)throws IOException{
  android.graphics.Rect crop=image.getCropRect();
  int dataSpace=image.getDataSpace();if(dataSpace==0)dataSpace=formatDataSpace;
  // HardwareBuffer access does not implicitly wait the decoder acquire fence.
  // Wait on this worker, never map planes or ask for CPU-readable gralloc usage.
  try(SyncFence fence=image.getFence()){
   // ImageReader may return an empty fence when decoding already completed.
   // Some framework versions return false from await() for that empty object.
   if(fence.isValid()&&!fence.await(java.time.Duration.ofSeconds(2)))throw new IllegalStateException("Decoder GPU fence timed out");
  }
  try(HardwareBuffer buffer=image.getHardwareBuffer()){
   if(buffer==null)throw new IllegalStateException("Decoder did not provide a hardware buffer");
   nativeSubmit(handle,buffer,crop.left,crop.top,crop.width(),crop.height(),dataSpace);
  }
 }
 static long createSession(Context context,Surface output,android.view.Display display)throws Exception{return createSession(context,output,display,Float.NaN);}
 static long createSession(Context context,Surface output,android.view.Display display,float fps)throws Exception{return createSession(context,output,display,fps,false);}
 static synchronized long createSession(Context context,Surface output,android.view.Display display,float fps,boolean preferFast)throws Exception{
  android.graphics.Point size=new android.graphics.Point();
  if(display!=null)display.getRealSize(size);
  if(size.x<=0||size.y<=0){android.util.DisplayMetrics metrics=context.getResources().getDisplayMetrics();size.set(metrics.widthPixels,metrics.heightPixels);}
  String model=modelDirectory(context,fps,preferFast),apu="";
  if(apuRequested(context))try{apu=apuDirectory(context);}catch(Exception e){android.util.Log.w("ThorRIFE","APU assets unavailable; low-resolution Vulkan retained",e);}
  return create(output,model,Math.max(size.x,size.y),Math.min(size.x,size.y),lowPowerMtk()?lowEstimate(context):0,apu);
 }
 private static synchronized String apuDirectory(Context context)throws Exception{
  File root=new File(context.getFilesDir(),"rife-apu");java.util.Properties hashes=new java.util.Properties();
  try(InputStream in=context.getAssets().open("rife-apu/sha256.properties")){hashes.load(in);}
  if(!root.isDirectory()&&!root.mkdirs())throw new IOException("APU directory creation failed");
  for(String name:hashes.stringPropertyNames()){
   if(name.contains("/")||name.contains(".."))throw new IOException("Unsafe APU asset");
   File target=new File(root,name);String expected=hashes.getProperty(name);
   if(target.isFile()&&expected.equals(digest(target)))continue;
   File temp=new File(root,name+".tmp");try(InputStream in=context.getAssets().open("rife-apu/"+name);OutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
   if(!expected.equals(digest(temp))||!temp.renameTo(target))throw new IOException("APU asset integrity failure");
  }
  return root.getAbsolutePath();
 }
 private static native long create(Surface output,String modelDirectory,int displayLongEdge,int displayShortEdge,int estimateLongEdge,String apuDirectory);
 static native void configureHybrid(String modelDirectory,String libraryDirectory,int estimateHeight,int stages);
 static native String description(long handle);
 static native void destroy(long handle);
 static native void reset(long handle);
 static native void resize(long handle,int width,int height);
 static native void nativeSubmit(long handle,HardwareBuffer buffer,int left,int top,int cropWidth,int cropHeight,int dataSpace);
 static native void submitImage(long handle,long image,int dataSpace);
 static native long interpolate(long handle);
 /** 0 previous endpoint; 1 synthesized frame; 2 current endpoint. */
 static native void present(long handle,int which);
}
