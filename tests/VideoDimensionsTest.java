import local.thor.bilichannels.Video;
public final class VideoDimensionsTest {
 static void check(boolean b){if(!b)throw new AssertionError("Video dimension authority");}
 public static void main(String[] args)throws Exception{
  Video v=new Video("test","","","",0,0,0,false);
  v.setDimensions(1080,1920,0);v.setDecoderDimensions(1920,1080);
  check(!v.isPortrait()&&v.decoderSizeKnown);
  v.setMetadataDimensions(1080,1920,0);check(!v.isPortrait());
  v.setDecoderDimensions(0,0);check(v.width==1920&&v.decoderSizeKnown);
  v.setDecoderDimensions(1280,720);check(v.width==1280&&!v.isPortrait());
  // Selecting a different part resets decoder authority and honors rotation.
  v.setDimensions(1920,1080,90);check(v.isPortrait()&&!v.decoderSizeKnown);
  v.setDecoderDimensions(720,1280);v.setMetadataDimensions(1920,1080,0);
  check(v.isPortrait()&&v.width==720&&v.height==1280);
  v.setDimensions(0,0,0);v.setMetadataDimensions(1920,1080,270);
  check(v.isPortrait()&&!v.decoderSizeKnown);
  // Metadata hydration holds v during HTTP I/O; decoder/UI must remain free.
  java.util.concurrent.CountDownLatch held=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),decoded=new java.util.concurrent.CountDownLatch(1);
  Thread network=new Thread(()->{synchronized(v){held.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}}});
  network.start();held.await();Thread decoder=new Thread(()->{v.setDecoderDimensions(1920,1080);decoded.countDown();});decoder.start();
  try{check(decoded.await(1,java.util.concurrent.TimeUnit.SECONDS));}finally{release.countDown();network.join();decoder.join();}
  System.out.println("Video dimension authority: 8 cases passed");
 }
}
