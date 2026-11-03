package local.thor.bilichannels;

import android.content.Context;import android.os.ParcelFileDescriptor;import android.view.Surface;
import java.io.*;import java.security.KeyStore;import java.util.*;

/** Original-file DV P5 decoding and display-aware HDR/SDR mapping, owned by the media looper. */
final class DoviPlayer {
 private static boolean loaded;private long handle;private ParcelFileDescriptor descriptor;private boolean playing,audio;private final boolean hdr;
 final Surface surface;private static final Map<Surface,DoviPlayer> ACTIVE=new WeakHashMap<>();
 static boolean supportsHdr(android.view.Display display){if(display==null)return false;for(int type:display.getHdrCapabilities().getSupportedHdrTypes())if(type==android.view.Display.HdrCapabilities.HDR_TYPE_HDR10||type==android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)return true;return false;}
 DoviPlayer(Context context,Surface surface,String uri,boolean audio,int start,int width,int height,android.view.Display display)throws Exception{
  this.surface=surface;this.audio=audio;hdr=supportsHdr(display);load();String source=uri;
  if(uri.startsWith("content:")){descriptor=context.getContentResolver().openFileDescriptor(android.net.Uri.parse(uri),"r");if(descriptor==null)throw new IOException("Cannot open selected video");source="fd://"+descriptor.getFd();}
  int renderWidth=0,renderHeight=0;if(RifeMemcView.handles(surface)&&width>0&&height>0){float scale=Math.min(1,1920f/Math.max(width,height));renderWidth=Math.max(2,Math.round(width*scale/2)*2);renderHeight=Math.max(2,Math.round(height*scale/2)*2);}
  float peak=hdr?display.getHdrCapabilities().getDesiredMaxLuminance():203;if(!Float.isFinite(peak)||peak<100)peak=1000;peak=Math.min(10000,peak);
  try{handle=nativeOpen(context.getApplicationContext(),surface,source,certificates(context),BiliApi.UA,audio,start,renderWidth,renderHeight,hdr,peak);synchronized(ACTIVE){ACTIVE.put(surface,this);}}catch(Exception error){if(descriptor!=null)descriptor.close();throw error;}
 }
 private static synchronized void load(){if(!loaded){System.loadLibrary("mpv");System.loadLibrary("thor_dovi");loaded=true;}}
 static synchronized String certificates(Context context)throws Exception{
  File target=new File(context.getCacheDir(),"dovi-system-ca.pem");if(target.isFile()&&target.length()>1000)return target.getPath();KeyStore store=KeyStore.getInstance("AndroidCAStore");store.load(null);ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(PrintWriter out=new PrintWriter(bytes)){Enumeration<String> aliases=store.aliases();while(aliases.hasMoreElements()){java.security.cert.Certificate certificate=store.getCertificate(aliases.nextElement());if(certificate==null)continue;out.println("-----BEGIN CERTIFICATE-----");out.println(Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(certificate.getEncoded()));out.println("-----END CERTIFICATE-----");}}TorrentFiles.write(target,bytes.toByteArray());return target.getPath();
 }
 String value(String key){return handle==0?null:nativeProperty(handle,key);}
 double number(String key){try{return Double.parseDouble(value(key));}catch(Exception ignored){return 0;}}
 boolean flag(String key){return "yes".equals(value(key));}
 void playing(boolean yes){playing=yes;set("pause",yes?"no":"yes");}
 void audio(boolean yes){audio=yes;set("aid",yes?"auto":"no");}
 void volume(float level){set("volume",Float.toString(Math.max(0,Math.min(1,level))*100));}
 void speed(float speed){set("speed",Float.toString(speed));}
 void seek(int millis){if(handle!=0)nativeSeek(handle,Math.max(0,millis)/1000.0);}
 void set(String key,String value){if(handle!=0)nativeSet(handle,key,value);}
 boolean desiredPlaying(){return playing;}boolean audioEnabled(){return audio;}
 boolean hdr(){return hdr;}
 void close(){synchronized(ACTIVE){if(ACTIVE.get(surface)==this)ACTIVE.remove(surface);}long old=handle;handle=0;if(old!=0)nativeClose(old);if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}descriptor=null;}
 private static native long nativeOpen(Context context,Surface surface,String uri,String ca,String ua,boolean audio,int start,int renderWidth,int renderHeight,boolean hdr,float peak);
 private static native String nativeProperty(long handle,String key);
 private static native void nativeSet(long handle,String key,String value);
 private static native void nativeSeek(long handle,double seconds);
 private static native void nativeClose(long handle);
}
