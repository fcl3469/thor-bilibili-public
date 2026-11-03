package local.thor.bilichannels;
import android.media.Image;
import android.os.Handler;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicBoolean;
/** Android 12 PRIVATE buffers with an explicit native acquire-fence wait. */
final class RifeImageReader implements AutoCloseable {
 private long handle;final Surface surface;private volatile boolean closed;
 private final AtomicBoolean queued=new AtomicBoolean();
 RifeImageReader(Handler worker,Runnable available){
  handle=create(()->{if(!closed&&queued.compareAndSet(false,true))worker.post(()->{queued.set(false);if(!closed)available.run();});});
  try{surface=surface(handle);}catch(RuntimeException e){destroy(handle);handle=0;throw e;}
 }
 Frame acquire(){long[] info=acquire(handle);return info==null?null:new Frame(info);}
 public void close(){if(closed)return;closed=true;destroy(handle);handle=0;surface.release();}
 static final class Frame implements AutoCloseable {
  final Image image;private long nativeImage;final long timestamp;final int width,height;
  Frame(Image image){this.image=image;timestamp=image.getTimestamp();width=image.getCropRect().width();height=image.getCropRect().height();}
  Frame(long[] info){image=null;nativeImage=info[0];timestamp=info[1];width=(int)info[2];height=(int)info[3];}
  void submit(long engine,int space)throws java.io.IOException{if(image!=null)RifeNative.submit(engine,image,space);else RifeNative.submitImage(engine,nativeImage,space);}
  public void close(){if(image!=null)image.close();if(nativeImage!=0){release(nativeImage);nativeImage=0;}}
 }
 private static native long create(Runnable available);
 private static native Surface surface(long reader);
 private static native long[] acquire(long reader);
 private static native void release(long image);
 private static native void destroy(long reader);
}
