package local.thor.bilichannels;

/** Preserve a short normal lead; use silent scenes to prepare the next dialogue. */
final class SubtitleSchedule {
 static final int AHEAD=2,IDLE_AHEAD=11,BATCH_MS=25000;
 // Explicit seeks cancel their jobs. Advancing playback must let a slow read finish.
 static boolean obsolete(int pending,int current){return pending>=0&&pending>current+IDLE_AHEAD*SubtitleTimeline.WINDOW_MS;}
 static boolean seekSettled(int target,int actual,long elapsed){return Math.abs((long)actual-target)<SubtitleTimeline.WINDOW_MS||elapsed>=3000;}
 static boolean containsAudio(int cachedStart,int cachedDuration,int start,int duration){return duration>0&&start>=cachedStart&&(long)start+duration<=(long)cachedStart+cachedDuration;}
 static int translationWindow(int requested,int current,int audioStart,int audioDuration,int total){
  if(current<=requested)return requested;int start=Math.max(0,current-500),duration=Math.min(9000,total-start);return containsAudio(audioStart,audioDuration,start,duration)?current:-1;
 }
 static int next(int current,int duration,java.util.function.IntPredicate ready,java.util.function.IntPredicate speech){
  int voicedAhead=0;for(int ahead=0;ahead<=IDLE_AHEAD;ahead++){
   int at=current+ahead*SubtitleTimeline.WINDOW_MS;if(at>=duration)return -1;
   if(!ready.test(at))return at;if(ahead>0&&speech.test(at))voicedAhead++;
   if(ahead>=AHEAD&&voicedAhead>=2)return -1;
  }return -1;
 }
 static boolean startupReady(int current,int duration,java.util.function.IntPredicate ready){
  for(int i=0;i<2;i++){int at=current+i*SubtitleTimeline.WINDOW_MS;if(at>=duration)return true;if(!ready.test(at))return false;}return true;
 }
}
