package local.thor.bilichannels;

import java.util.*;
import org.json.*;

/** Media-time cues; padded recognizer time must never escape the real audio window. */
final class SubtitleTimeline {
 static final int WINDOW_MS=8000;
 static final class Part {
  final int start,end;final String japanese;
  Part(int start,int end,String japanese){this.start=start;this.end=end;this.japanese=japanese;}
 }
 static List<Part> parts(JSONObject asr,int audioStart,int actualDuration,int windowStart)throws JSONException{
  ArrayList<Part> out=new ArrayList<>();
  if(!asr.optString("lang").contains("ja")||actualDuration<=0)return out;
  String event=asr.optString("event");boolean background=event.contains("<|BGM|>");
  // SenseVoice's clip-wide BGM label also occurs with clearly transcribed dialogue.
  // Require timed Japanese words below instead of dropping speech mixed with music.
  if(!event.contains("Speech")&&!background)return out;
  JSONArray tokens=asr.optJSONArray("tokens"),times=asr.optJSONArray("timestamps");if(tokens==null||times==null||tokens.length()!=times.length())return out;
  int audioEnd=audioStart+actualDuration,limit=Math.min(audioEnd,windowStart+WINDOW_MS),first=-1,last=-1,previous=-1;StringBuilder text=new StringBuilder();
  int contextStart=Math.max(audioStart,windowStart-500),contextEnd=Math.min(audioEnd,windowStart+WINDOW_MS+500);
  JSONArray regions=asr.optJSONArray("speech_regions");if(regions!=null&&!hasSpeech(regions,contextStart-audioStart,contextEnd-audioStart))return out;
  for(int i=0;i<tokens.length();i++){
   String token=tokens.optString(i).replace('▁',' ').replaceAll("<\\|[^|]*\\|>","");double seconds=times.optDouble(i,Double.NaN);
   if(!Double.isFinite(seconds)||seconds<0||token.trim().isEmpty())continue;
   boolean punctuation=token.matches("[。！？!?、,.…]+") ;
   int at=audioStart+(int)Math.round(seconds*1000);
   // A fixed-shape model may put terminal punctuation at 9.96 seconds after short audio.
   if(at<contextStart)continue;
   if(at>=contextEnd){if(contextEnd==audioEnd&&at>=audioEnd&&punctuation&&text.length()>0)text.append(token);continue;}
   if(previous>=0&&at-previous>=1000&&text.length()>0){if(first>=0)append(out,first,last,limit,text.toString());text.setLength(0);first=last=-1;}
   previous=at;
   // Keep overlap as translation context, but assign display time only to this core window.
   if(at>=windowStart&&at<limit&&!punctuation){if(first<0)first=at;last=at;}
   text.append(token);
   if(token.matches(".*[。！？!?]$")||text.length()>=96){if(first>=0)append(out,first,last,limit,text.toString());text.setLength(0);first=last=-1;}
  }
  if(first>=0)append(out,first,last,limit,text.toString());
  if(background)out.removeIf(part->!part.japanese.matches("(?s).*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}].*"));
  for(int i=0;i+1<out.size();i++){Part item=out.get(i),next=out.get(i+1);if(item.end>next.start)out.set(i,new Part(item.start,next.start,item.japanese));}
  return out;
 }
 private static boolean hasSpeech(JSONArray regions,int start,int end){
  for(int i=0;i<regions.length();i++){JSONArray region=regions.optJSONArray(i);if(region==null)continue;int from=region.optInt(0,-1),to=region.optInt(1,-1);if(from>=0&&to>from&&from<end&&to>start)return true;}return false;
 }
 private static void append(List<Part> out,int first,int last,int limit,String text){int end=Math.min(limit,Math.max(first+1200,last+900));if(end>first&&!text.trim().isEmpty())out.add(new Part(first,end,text.trim()));}
 static boolean contains(int start,int end,int position){return position>=start&&position<end;}
}
