package local.thor.bilichannels;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.text.*;
import java.util.*;
import org.json.*;

/** Initial subtitle preparation may hold the clock; normal playback never waits for a caption. */
final class LiveSubtitles {
 private final MainActivity activity;
 boolean enabled,failed;private boolean binding,bound,ready,starting;private String videoKey="",source="",status="";private int session,job,pending=-1,preparePosition=-1;private long connectedAt;
 private Messenger remote;private ServiceConnection connection;private long clockSentAt;
 private final LinkedHashMap<Integer,List<Cue>> windows=new LinkedHashMap<Integer,List<Cue>>(40,.75f,true){protected boolean removeEldestEntry(Map.Entry<Integer,List<Cue>> entry){return size()>256;}};
 private static final class Cue {int start,end;String ja,zh;}
 private final Messenger incoming=new Messenger(new Handler(Looper.getMainLooper(),this::receive));
 private final Paint background=new Paint(3);private final TextPaint text=new TextPaint(3);
 LiveSubtitles(MainActivity activity){this.activity=activity;}
 static boolean available(MainActivity.Tile tile){return tile!=null&&tile.full&&(tile.video.cloud()||tile.video.local());}
 void toggle(MainActivity.Tile tile){
  if(enabled&&!failed){enabled=false;stop();windows.clear();tile.applyPlayback();redraw();return;}
  if(!available(tile)||tile.mp==null||!tile.ready||tile.openedSpec==null){activity.message("请先播放一个云盘或本地视频");return;}
  String soc=Build.SOC_MODEL.toUpperCase(Locale.ROOT);if(!soc.contains("SM8550")&&!soc.contains("SM8750")){activity.message("本地字幕目前仅在骁龙 8 Gen 2 / 8 Elite 上试用");return;}
  if(!SubtitleModels.available(activity)){SubtitleModelActivity.open(activity);return;}
  stop();enabled=true;failed=false;starting=true;preparePosition=-1;videoKey=tile.video.mediaKey();source="";windows.clear();status="正在准备开播字幕";
  activity.message("正在准备日中直译字幕，准备好后继续播放");tick(tile);tile.applyPlayback();redraw();
 }
 void suspend(){stop();}
 void destroy(){enabled=false;stop();windows.clear();}
 boolean holding(MainActivity.Tile tile){return enabled&&!failed&&starting&&available(tile)&&videoKey.equals(tile.video.mediaKey());}
 void seeked(MainActivity.Tile tile,int position){if(enabled&&!failed&&available(tile)&&videoKey.equals(tile.video.mediaKey())){starting=false;preparePosition=Math.max(0,position);send(SubtitleService.CANCEL,new Bundle());pending=-1;job++;tick(tile);tile.applyPlayback();}}
 int bufferedMillis(MainActivity.Tile tile){if(tile==null||tile.mp==null)return 0;int position=tile.position(),at=position/SubtitleTimeline.WINDOW_MS*SubtitleTimeline.WINDOW_MS;while(windows.containsKey(at))at+=SubtitleTimeline.WINDOW_MS;return Math.max(0,Math.min(at,tile.mp.getDuration())-position);}
 void tick(MainActivity.Tile tile){
  if(!enabled)return;if(!activity.playing||activity.current()==null||!videoKey.equals(activity.current().mediaKey())){enabled=false;stop();windows.clear();return;}
  if(!available(tile)||!videoKey.equals(tile.video.mediaKey()))return;
  if(!activity.active||tile.mp==null||!tile.ready||tile.openedSpec==null||failed)return;
  String next=tile.openedSpec.audio.isEmpty()?tile.openedSpec.video.get(0):tile.openedSpec.audio.get(0);
  if(!next.equals(source)){stop();source=next;if(windows.isEmpty())starting=true;}
  tile.applyPlayback();
  if(!binding&&!bound){connect();return;}
  if(!ready){if(SystemClock.elapsedRealtime()-connectedAt>90000)error("字幕准备超时，点击「字幕重试」");return;}
  int actual=tile.position();long now=SystemClock.elapsedRealtime();if(now-clockSentAt>=200){Bundle clock=new Bundle();clock.putInt("position",actual);send(SubtitleService.CLOCK,clock);clockSentAt=now;}
  if(!starting&&preparePosition>=0&&SubtitleSchedule.seekSettled(preparePosition,actual,SystemClock.uptimeMillis()-tile.lastSeek))preparePosition=-1;
  int position=preparePosition>=0?preparePosition:actual,window=position/SubtitleTimeline.WINDOW_MS*SubtitleTimeline.WINDOW_MS;
  int duration=tile.mp.getDuration();if(duration<=0){status="等待视频时长，以便对齐字幕";return;}
  if(starting&&SubtitleSchedule.startupReady(window,duration,windows::containsKey)){starting=false;preparePosition=-1;tile.applyPlayback();}
  if(SubtitleSchedule.obsolete(pending,window)){send(SubtitleService.CANCEL,new Bundle());pending=-1;job++;}
  if(pending>=0)return;
  int start=SubtitleSchedule.next(window,duration,windows::containsKey,at->!windows.get(at).isEmpty());
  if(start>=0){
   pending=start;job++;Bundle request=new Bundle();request.putInt("start",start);request.putInt("duration",duration);request.putString("source",source);request.putBoolean("cloud",tile.video.cloud());request.putBoolean("startup",starting);send(SubtitleService.DECODE,request);return;
  }
  status="本地日→中 · 已准备后续对白";
 }
 private void connect(){
  binding=true;ready=false;connectedAt=SystemClock.elapsedRealtime();final int owner=++session;
  ServiceConnection next=new ServiceConnection(){
   public void onServiceConnected(ComponentName name,IBinder binder){if(owner!=session)return;binding=false;bound=true;remote=new Messenger(binder);send(SubtitleService.PREPARE,new Bundle());}
   public void onServiceDisconnected(ComponentName name){if(owner==session)error("本地字幕服务已停止，点击「字幕重试」");}
   public void onBindingDied(ComponentName name){if(owner==session)error("本地字幕服务已停止，点击「字幕重试」");}
   public void onNullBinding(ComponentName name){if(owner==session)error("本地字幕服务无法启动");}
  };connection=next;
  try{if(!activity.bindService(new Intent(activity,SubtitleService.class),next,Context.BIND_AUTO_CREATE))error("本地字幕服务无法启动");}
  catch(RuntimeException error){error("本地字幕服务无法启动");}
 }
 private void send(int what,Bundle payload){
  if(remote==null)return;payload.putInt("session",session);payload.putInt("job",job);Message message=Message.obtain(null,what);message.setData(payload);message.replyTo=incoming;
  try{remote.send(message);}catch(RemoteException error){error("本地字幕服务连接失败");}
 }
 private boolean receive(Message message){
  Bundle data=message.getData();if(!enabled||data.getInt("session")!=session)return true;
  int responseJob=data.getInt("job");
  if(message.what==SubtitleService.READY){ready=true;status="正在预取当前音轨";redraw();return true;}
  if(responseJob!=job)return true;
  if(message.what==SubtitleService.STATUS)status=data.getString("status","");
  else if(message.what==SubtitleService.ERROR)error(data.getString("status","字幕生成失败，可点击重试"));
  else if(message.what==SubtitleService.RESULT){
   try{
    int start=data.getInt("start");if(data.getInt("request_start",start)!=pending)return true;JSONArray rows=new JSONArray(data.getString("cues","[]"));ArrayList<Cue> cues=new ArrayList<>();
    for(int i=0;i<Math.min(30,rows.length());i++){
     JSONObject row=rows.getJSONObject(i);Cue cue=new Cue();cue.start=row.getInt("start");cue.end=row.getInt("end");cue.ja=row.getString("ja");cue.zh=row.getString("zh");
     if(cue.start>=start&&cue.end>cue.start&&cue.end<=start+SubtitleTimeline.WINDOW_MS&&cue.ja.length()<=500&&cue.zh.length()<=1000)cues.add(cue);
    }
    boolean obsolete=data.getBoolean("obsolete",false);if(!obsolete)windows.put(start,cues);pending=-1;status=obsolete?"正在准备当前位置字幕":cues.isEmpty()?"当前片段未识别到日语对白":"本地日→中 · 字幕已就绪";
   }catch(JSONException error){error("字幕时间轴读取失败，可点击重试");}
  }
  MainActivity.Tile tile=activity.playerControls.tile();if(tile!=null){tick(tile);tile.applyPlayback();}redraw();return true;
 }
 private void error(String message){failed=true;ready=false;status=message;pending=-1;stop();MainActivity.Tile tile=activity.playerControls.tile();if(tile!=null)tile.applyPlayback();redraw();}
 private void stop(){
  Messenger previous=remote;remote=null;if(previous!=null){Message stop=Message.obtain(null,SubtitleService.STOP);try{previous.send(stop);}catch(RemoteException ignored){}}
  session++;ready=false;pending=-1;clockSentAt=0;
  ServiceConnection old=connection;connection=null;if(old!=null)try{activity.unbindService(old);}catch(IllegalArgumentException ignored){}
  binding=bound=false;
 }
 private void redraw(){MainActivity.Tile tile=activity.playerControls.tile();if(tile!=null)tile.overlay.invalidate();}
 String label(){return failed?"字幕重试":enabled?"字幕 已开启":"日→中字幕";}
 String status(){return status;}
 void draw(Canvas canvas,MainActivity.Tile tile){
  if(!enabled||!tile.full||!videoKey.equals(tile.video.mediaKey()))return;
  float unit=activity.playerControls.unit(tile),width=tile.getWidth(),height=tile.getHeight();boolean controls=activity.playerControls.visible;
  text.setTypeface(Typeface.DEFAULT);text.setTextSize(13*unit);text.setColor(Color.WHITE);text.setShadowLayer(2*unit,0,unit,Color.BLACK);
  int buffered=bufferedMillis(tile);if(controls||holding(tile)||failed){
   String shown=holding(tile)?"开播字幕准备中 · "+buffered/1000+" 秒就绪":failed?status:buffered==0?"字幕准备中 · 画面继续播放":"字幕已开启 · 已准备 "+buffered/1000+" 秒";
   String note=MainActivity.ellipsize(shown,text,13*unit,Math.max(1,width-48*unit-180*unit));float top=controls?104*unit:15*unit;
   background.setColor(0x88000000);canvas.drawRoundRect(20*unit,top-2*unit,28*unit+text.measureText(note),top+22*unit,5*unit,5*unit,background);canvas.drawText(note,24*unit,top+15*unit,text);
  }
  int position=tile.position(),window=position/SubtitleTimeline.WINDOW_MS*SubtitleTimeline.WINDOW_MS;List<Cue> list=windows.get(window);Cue selected=null;
  if(list!=null)for(Cue cue:list)if(SubtitleTimeline.contains(cue.start,cue.end,position)){selected=cue;break;}
  if(selected==null||failed)return;
  int available=Math.max(1,(int)(width-48*unit));text.setTextSize(21*unit);text.setColor(Color.WHITE);
  StaticLayout chinese=StaticLayout.Builder.obtain(selected.zh,0,selected.zh.length(),text,available).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(controls?2:3).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
  float bottom=height-(controls?222*unit:22*unit),japaneseHeight=(controls?18:36)*unit;
  text.setTextSize(13*unit);text.setColor(0xffe5e5e5);
  StaticLayout japanese=StaticLayout.Builder.obtain(selected.ja,0,selected.ja.length(),text,available).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(controls?1:2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
  float boxWidth=Math.min(available,Math.max(lineWidth(chinese),lineWidth(japanese))+16*unit);
  float boxLeft=24*unit+(available-boxWidth)/2f;
  canvas.save();canvas.translate(24*unit,bottom-chinese.getHeight()-japaneseHeight);
  background.setColor(0x4d000000);canvas.drawRoundRect(boxLeft-24*unit,-5*unit,boxLeft-24*unit+boxWidth,chinese.getHeight()+japaneseHeight+5*unit,UiStyle.RADIUS_CONTROL*unit,UiStyle.RADIUS_CONTROL*unit,background);
  chinese.draw(canvas);canvas.translate(0,chinese.getHeight()+4*unit);japanese.draw(canvas);canvas.restore();text.clearShadowLayer();
 }
 private float lineWidth(StaticLayout layout){
  float width=0;for(int i=0;i<layout.getLineCount();i++)width=Math.max(width,layout.getLineRight(i)-layout.getLineLeft(i));
  return width;
 }
}
