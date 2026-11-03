package local.thor.bilichannels;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Regressions for overlapping I/O, bounded reuse, and cancellation during a read. */
public final class SubtitlePrefetchTest {
 static int checks;static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
 static SubtitleAudioPrefetch.Chunk chunk(String source,int window,int total){int start=Math.max(0,window-500);short[] pcm=new short[Math.min(25000,total-start)*16];Arrays.fill(pcm,(short)(window/1000));return new SubtitleAudioPrefetch.Chunk(source,window,start,pcm);}
 public static void main(String[] args)throws Exception{
  CountDownLatch aheadStarted=new CountDownLatch(1),releaseAhead=new CountDownLatch(1);AtomicInteger reads=new AtomicInteger();
  try(SubtitleAudioPrefetch cache=new SubtitleAudioPrefetch((source,window,total,cancelled)->{reads.incrementAndGet();if(window==24000){aheadStarted.countDown();if(!releaseAhead.await(3,TimeUnit.SECONDS))throw new AssertionError("Test did not release next audio batch");}return chunk(source,window,total);})){
   SubtitleAudioPrefetch.Chunk first=cache.acquire("a",0,72000);
   check(aheadStarted.await(1,TimeUnit.SECONDS),"next network read starts before any next model request");
   check(cache.acquire("a",8000,72000)==first&&cache.acquire("a",16000,72000)==first,"model can consume current audio while next read is blocked");
   check(reads.get()==2,"three windows share one batch without duplicate network work");
   releaseAhead.countDown();SubtitleAudioPrefetch.Chunk next=cache.acquire("a",24000,72000);
   check(next.window==24000&&next.start==23500&&next.pcm[0]==24,"prefetched batch retains correct absolute audio time");
   check(cache.acquire("a",32000,72000)==next,"next batch is reused for its following windows");
  }finally{releaseAhead.countDown();}
  CountDownLatch shortAhead=new CountDownLatch(1),releaseShort=new CountDownLatch(1);
  try(SubtitleAudioPrefetch shortStart=new SubtitleAudioPrefetch((source,window,total,cancelled)->{
   if(window==48000)return new SubtitleAudioPrefetch.Chunk(source,window,45000,new short[19500*16]);
   if(window==64000){shortAhead.countDown();if(!releaseShort.await(3,TimeUnit.SECONDS))throw new AssertionError("short prefetch not released");}return chunk(source,window,total);
  })){
   SubtitleAudioPrefetch.Chunk initial=shortStart.acquire("seek",48000,200000);
   check(shortAhead.await(1,TimeUnit.SECONDS),"short seek audio prefetches from its actual coverage instead of skipping a window");
   check(shortStart.acquire("seek",56000,200000)==initial,"short first read still covers both first windows");
   releaseShort.countDown();check(shortStart.acquire("seek",64000,200000).window==64000,"next full batch follows the shorter seek batch without a gap");
  }finally{releaseShort.countDown();}
  CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),callerDone=new CountDownLatch(1);AtomicBoolean wasCancelled=new AtomicBoolean();
  SubtitleAudioPrefetch cache=new SubtitleAudioPrefetch((source,window,total,cancelled)->{if(source.equals("old")){blocked.countDown();release.await(2,TimeUnit.SECONDS);if(cancelled.getAsBoolean())throw new CancellationException();}return chunk(source,window,total);});
  Thread caller=new Thread(()->{try{cache.acquire("old",0,72000);}catch(CancellationException expected){wasCancelled.set(true);}catch(Exception error){throw new RuntimeException(error);}finally{callerDone.countDown();}});caller.start();
  try{
   check(blocked.await(1,TimeUnit.SECONDS),"old read actually entered the loader");cache.cancel();
   check(callerDone.await(1,TimeUnit.SECONDS)&&wasCancelled.get(),"seek cancels the waiting decoder without waiting for network completion");
   release.countDown();check(cache.acquire("new",48000,72000).source.equals("new"),"stale audio cannot populate the new source cache");
  }finally{release.countDown();cache.close();caller.join(1000);}
  Map<Integer,Boolean> ready=new HashMap<>();ready.put(0,false);ready.put(8000,false);ready.put(16000,false);
  check(SubtitleSchedule.next(0,120000,ready::containsKey,at->ready.get(at))==24000,"silent scenes advance preparation beyond the usual short lead");
  ready.put(24000,true);ready.put(32000,true);
  check(SubtitleSchedule.next(0,120000,ready::containsKey,at->ready.get(at))==-1,"idle work stops after two upcoming dialogue windows are prepared");
  ready.clear();for(int at=0;at<=88000;at+=8000)ready.put(at,false);
  check(SubtitleSchedule.next(0,1000000,ready::containsKey,at->false)==-1,"a long silent scene cannot trigger a whole-movie download");
  check(SubtitleSchedule.startupReady(0,120000,ready::containsKey),"processed silence counts as ready startup audio");
  ready.clear();ready.put(0,true);check(!SubtitleSchedule.startupReady(0,120000,ready::containsKey),"a single ready window cannot release normal startup too early");
  try(SubtitleAudioPrefetch shortAudio=new SubtitleAudioPrefetch((source,window,total,cancelled)->new SubtitleAudioPrefetch.Chunk(source,0,0,new short[6960*16-1]))){
   check(shortAudio.acquire("rounded",0,6960).pcm.length==6960*16-1,"fractional-ms resampler tail remains valid without inventing PCM samples");
  }
  check(SubtitleSchedule.seekSettled(120000,122400,200),"keyframe alignment cannot pin subtitle scheduling to an old seek target");
  check(!SubtitleSchedule.seekSettled(120000,0,200),"old decoder snapshot cannot supersede a just-issued seek");
  check(SubtitleSchedule.seekSettled(120000,0,3000),"an unacknowledged seek cannot pin the scheduler forever");
  check(SubtitleSchedule.translationWindow(0,8000,0,17000,120000)==8000,"a slow read prioritizes the current covered caption window");
  check(SubtitleSchedule.translationWindow(0,16000,0,17000,120000)==-1,"expired uncovered work is skipped instead of wasting translation time");
  check(SubtitleSchedule.translationWindow(16000,8000,0,25000,120000)==16000,"prefetch work is never redirected backwards into the current window");
  check(SubtitleSchedule.translationWindow(0,8000,0,12000,10800)==8000,"retargeting respects a short final audio window");
  System.out.println("Subtitle background prefetch: "+checks+" checks passed");
 }
}
