package local.thor.bilichannels;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class SubtitleTimelineTest {
 static int checks;
 static void check(boolean okay,String label){if(!okay)throw new AssertionError(label);checks++;}
 static JSONObject sample(String[] tokens,double[] times)throws Exception{return new JSONObject().put("lang","<|ja|>").put("event","<|Speech|>").put("tokens",new JSONArray(tokens)).put("timestamps",new JSONArray(times));}
 static File wave(int channels,int rate,int bits,short[] pcm,boolean extra)throws Exception{
  int junk=extra?10:0;ByteBuffer buffer=ByteBuffer.allocate(44+junk+pcm.length*2).order(ByteOrder.LITTLE_ENDIAN);
  buffer.put("RIFF".getBytes("US-ASCII")).putInt(buffer.capacity()-8).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16).putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*bits/8).putShort((short)(channels*bits/8)).putShort((short)bits);
  if(extra)buffer.put("JUNK".getBytes("US-ASCII")).putInt(1).put((byte)42).put((byte)0);
  buffer.put("data".getBytes("US-ASCII")).putInt(pcm.length*2);for(short value:pcm)buffer.putShort(value);
  Path file=Files.createTempFile("thor-subtitle-wave-",".wav");Files.write(file,buffer.array());file.toFile().deleteOnExit();return file.toFile();
 }
 public static void main(String[] args)throws Exception{
  List<SubtitleTimeline.Part> parts=SubtitleTimeline.parts(sample(new String[]{"は","い","。"},new double[]{1,1.2,9.96}),0,2200,0);
  check(parts.size()==1,"short speech returns one cue");check(parts.get(0).end<=2200,"fixed-model padding cannot extend real audio");check(parts.get(0).japanese.equals("はい。"),"terminal punctuation retained without a fake timestamp");
  check(!SubtitleTimeline.contains(1000,2000,999)&&SubtitleTimeline.contains(1000,2000,1000)&&!SubtitleTimeline.contains(1000,2000,2000),"cue boundaries clear on seek and at end");
  JSONObject music=sample(new String[]{"はい"},new double[]{1});music.put("event","<|Music|>");check(SubtitleTimeline.parts(music,0,8000,0).isEmpty(),"music is not translated as dialogue");
  JSONObject mixed=sample(new String[]{"それ","は","違う","。"},new double[]{1,1.4,1.8,9.96});mixed.put("event","<|BGM|>");
  parts=SubtitleTimeline.parts(mixed,1015500,9000,1016000);check(parts.size()==1&&parts.get(0).japanese.equals("それは違う。"),"BGM with timed Japanese dialogue must not disappear");
  check(parts.get(0).start>=1016000&&parts.get(0).end<=1024000,"mixed background cue remains within its core window");
  JSONObject background=sample(new String[]{"<|BGM|>","♪"},new double[]{1,2});background.put("event","<|BGM|>");check(SubtitleTimeline.parts(background,0,9000,0).isEmpty(),"background tags and music symbols do not become dialogue");
  JSONObject empty=sample(new String[]{"。","…"},new double[]{1,2});empty.put("event","<|BGM|>");check(SubtitleTimeline.parts(empty,0,9000,0).isEmpty(),"punctuation alone cannot become BGM dialogue");
  JSONObject wrongTime=sample(new String[]{"はい"},new double[]{1});wrongTime.put("event","<|BGM|>").put("timestamps",new JSONArray().put(JSONObject.NULL));check(SubtitleTimeline.parts(wrongTime,0,9000,0).isEmpty(),"BGM cannot bypass valid speech timing");
  JSONObject foreign=sample(new String[]{"Hello"},new double[]{1});foreign.put("lang","<|en|>");check(SubtitleTimeline.parts(foreign,0,8000,0).isEmpty(),"non-Japanese result rejected");
  JSONObject batch=sample(new String[]{"今日","は","晴れ","です","明日","も","雨"},new double[]{6.7,6.9,7.6,8.4,8.7,9.2,15});
  parts=SubtitleTimeline.parts(batch,0,25000,0);check(parts.size()==1&&parts.get(0).japanese.equals("今日は晴れです"),"long ASR context cannot translate later dialogue ahead of its core window");
  parts=SubtitleTimeline.parts(batch,0,25000,8000);check(parts.size()>0&&parts.get(0).start>=8000&&parts.get(parts.size()-1).end<=16000,"one recognized audio batch yields separately aligned later windows");
  JSONObject noise=sample(new String[]{"うん"},new double[]{1});noise.put("speech_regions",new JSONArray());check(SubtitleTimeline.parts(noise,0,8000,0).isEmpty(),"noise with a spurious short recognition cannot become a caption without voice activity");
  noise.put("speech_regions",new JSONArray().put(new JSONArray().put(17000).put(18000)));check(SubtitleTimeline.parts(noise,0,25000,0).isEmpty(),"speech elsewhere in a batch cannot validate an unrelated silent window");
  noise.put("speech_regions",new JSONArray().put(new JSONArray().put(900).put(1800)));check(SubtitleTimeline.parts(noise,0,8000,0).size()==1,"short real replies survive voice gating");
  parts=SubtitleTimeline.parts(sample(new String[]{"前","今","。"},new double[]{.2,1.0,1.2}),7500,9000,8000);check(parts.size()==1&&parts.get(0).start==8500&&parts.get(0).japanese.equals("前今。"),"overlap retains sentence context without displaying the previous window twice");
  check(SubtitleTimeline.parts(sample(new String[]{"前","。"},new double[]{.1,.2}),7500,9000,8000).isEmpty(),"context-only sentence does not create a duplicate cue");
  String longSentence="もし明日の朝雨が降っていたら駅まで迎えに行くからそこで待っていてください。";
  String[] characters=new String[longSentence.length()];double[] spokenTimes=new double[characters.length];for(int i=0;i<characters.length;i++){characters[i]=longSentence.substring(i,i+1);spokenTimes[i]=.5+i*.1;}
  parts=SubtitleTimeline.parts(sample(characters,spokenTimes),0,8000,0);check(parts.size()==1&&parts.get(0).japanese.equals(longSentence),"long spoken sentence is not cut into 28-character fragments");
  check(!SubtitleSchedule.obsolete(16000,0)&&!SubtitleSchedule.obsolete(8000,0),"both prefetched windows survive ordinary UI ticks");
  check(!SubtitleSchedule.obsolete(0,8000)&&!SubtitleSchedule.obsolete(0,32000)&&SubtitleSchedule.obsolete(96000,0)&&!SubtitleSchedule.obsolete(64000,0),"forward playback lets slow reads finish; stale future work is rejected");
  check(SubtitleSchedule.containsAudio(7500,25000,23500,9000)&&!SubtitleSchedule.containsAudio(7500,25000,31500,9000),"one audio batch covers three windows and refills before it runs out");
  JSONObject bad=sample(new String[]{"a","b"},new double[]{1});check(SubtitleTimeline.parts(bad,0,8000,0).isEmpty(),"missing timestamps cannot invent cue timing");
  parts=SubtitleTimeline.parts(sample(new String[]{"はい。","いいえ。"},new double[]{1,1.3}),0,8000,0);check(parts.size()==2&&parts.get(0).end<=parts.get(1).start,"consecutive captions do not overlap");
  short[] silent=new short[16000];SubtitleWave zero=SubtitleWave.read(wave(1,16000,16,silent,false));check(zero.duration==1000&&!zero.signal,"digital silence never produces speech");
  silent[20]=Short.MIN_VALUE;SubtitleWave signal=SubtitleWave.read(wave(1,16000,16,silent,true));check(signal.duration==1000&&signal.signal,"nonstandard RIFF chunks and signed PCM supported");check(signal.pcm.length==16000&&signal.pcm[20]==Short.MIN_VALUE,"PCM samples retained for header-independent native recognition");
  byte[] ordinary=Files.readAllBytes(wave(1,16000,16,silent,false).toPath());ByteBuffer extended=ByteBuffer.allocate(ordinary.length+24).order(ByteOrder.LITTLE_ENDIAN);extended.put(ordinary,0,36);extended.putInt(4,extended.capacity()-8).putInt(16,40).putShort(20,(short)65534);extended.position(36);extended.putShort((short)22).putShort((short)16).putInt(4).put(new byte[]{1,0,0,0,0,0,16,0,(byte)128,0,0,(byte)170,0,56,(byte)155,113});extended.put(ordinary,36,ordinary.length-36);Path extensible=Files.createTempFile("thor-subtitle-extensible-",".wav");Files.write(extensible,extended.array());extensible.toFile().deleteOnExit();SubtitleWave mpv=SubtitleWave.read(extensible.toFile());check(mpv.pcm.length==16000&&mpv.pcm[20]==Short.MIN_VALUE&&mpv.duration==1000,"MPV WAVE_FORMAT_EXTENSIBLE becomes normalized native PCM");
  boolean rejected=false;try{SubtitleWave.read(wave(2,16000,16,silent,false));}catch(IOException expected){rejected=true;}check(rejected,"multichannel PCM must not silently misalign the timeline");
  File truncated=wave(1,16000,16,silent,false);try(RandomAccessFile file=new RandomAccessFile(truncated,"rw")){file.setLength(file.length()-3);}rejected=false;try{SubtitleWave.read(truncated);}catch(IOException expected){rejected=true;}check(rejected,"truncated PCM is rejected");
  System.out.println("Subtitle timeline/audio: "+checks+" checks passed");
 }
}
