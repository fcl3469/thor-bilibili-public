package local.thor.bilichannels;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Two bounded PCM batches: model work never waits to start the next network read. */
final class SubtitleAudioPrefetch implements AutoCloseable {
 interface Loader {Chunk load(String source,int window,int duration,BooleanSupplier cancelled)throws Exception;}
 static final class Chunk {
  final String source;final int window,start;final short[] pcm;final long fetchMillis;
  Chunk(String source,int window,int start,short[] pcm){this(source,window,start,pcm,0);}
  Chunk(String source,int window,int start,short[] pcm,long fetchMillis){this.source=source;this.window=window;this.start=start;this.pcm=pcm;this.fetchMillis=fetchMillis;}
  // Resampling can finish a few samples before an integer-ms endpoint. Keep the
  // actual PCM length for recognition; do not mistake rounding for a cache miss.
  boolean contains(String uri,int at,int duration){return pcm.length>0&&source.equals(uri)&&SubtitleSchedule.containsAudio(start,(pcm.length+15)/16+1,at,duration);}
 }
 private final class Job {
  final String source;final int window,total;volatile boolean cancelled;final FutureTask<Chunk> future;
  Job(String source,int window,int total){this.source=source;this.window=window;this.total=total;future=new FutureTask<>(()->{if(cancelled)throw new CancellationException();Chunk result=loader.load(source,window,total,()->cancelled||closed);if(cancelled||closed)throw new CancellationException();return result;});}
  void cancel(){cancelled=true;future.cancel(false);}
 }
 private final Loader loader;private final ExecutorService io=Executors.newSingleThreadExecutor(r->new Thread(r,"thor-subtitle-audio"));
 private Chunk cached;private Job next;private volatile boolean closed;private int generation;
 SubtitleAudioPrefetch(Loader loader){this.loader=loader;}
 Chunk acquire(String source,int window,int total)throws Exception{
  int start=Math.max(0,window-500),duration=Math.min(9000,total-start),epoch;Job required;Chunk hit;
  synchronized(this){
   if(closed)throw new CancellationException();epoch=generation;hit=cached;
   if(hit!=null&&hit.contains(source,start,duration)){prefetch(hit,total);return hit;}
   if(next==null||next.cancelled||!next.source.equals(source)||window<next.window||window>=next.window+24000){if(next!=null)next.cancel();next=submit(source,window,total);}
   required=next;
  }
  Chunk result;try{result=required.future.get(75,TimeUnit.SECONDS);}catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("音轨预取失败",cause);}
  synchronized(this){
   if(closed||epoch!=generation||required.cancelled)throw new CancellationException();
   if(!result.contains(source,start,duration))throw new IOException("预取音轨没有覆盖当前字幕区间");cached=result;if(next==required)next=null;prefetch(result,total);return result;
  }
 }
 private Job submit(String source,int window,int total){Job job=new Job(source,window,total);io.execute(job.future);return job;}
 private void prefetch(Chunk chunk,int total){
  int window=chunk.window;while(window<total){int start=Math.max(0,window-500),duration=Math.min(9000,total-start);if(!chunk.contains(chunk.source,start,duration))break;window+=SubtitleTimeline.WINDOW_MS;}
  if(window==chunk.window)window+=SubtitleTimeline.WINDOW_MS;if(next==null&&window<total)next=submit(chunk.source,window,total);
 }
 synchronized void cancel(){generation++;cached=null;if(next!=null)next.cancel();next=null;}
 @Override public synchronized void close(){closed=true;cancel();io.shutdownNow();}
}
