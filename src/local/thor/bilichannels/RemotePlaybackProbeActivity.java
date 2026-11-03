package local.thor.bilichannels;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Ten-second, video-only local RIFE exercise; no instrumentation or shell access. */
public final class RemotePlaybackProbeActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private String command;
    private int fps,stages,estimate;
    private MemcView view;
    private PlayerEngine player;
    private TextView label;
    private long deadline,warmAt,sampleAt;
    private long[] before;
    private boolean done;
    @Override public void onCreate(Bundle state){super.onCreate(state);command=getIntent().getStringExtra("command");fps=getIntent().getIntExtra("source_fps",24);if(command==null||!command.matches("[a-f0-9]{32}")||(fps!=24&&fps!=30)){finish();return;}stages=fps==24?4:2;estimate=fps==24?540:720;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);label=new TextView(this);label.setTextColor(Color.WHITE);label.setTextSize(16);label.setPadding(24,24,24,24);label.setText("正在准备静音插帧自测…");
        Context profile=new Profile(this,stages,estimate);view=new MemcView(profile,new MemcView.Listener(){
            public void inputReady(Surface surface){if(done)return;player=new PlayerEngine(profile,surface,StreamSpec.single("asset:///remote-rife-"+fps+"-silent.mp4"),false,new PlayerEngine.Events(){
                public void prepared(){if(player!=null){player.audio(false);player.setVolume(0,0);player.start();}}
                public void size(int w,int h){view.videoSize(w,h);view.sourceFrameRate(fps);}
                public void frame(){} public void buffering(boolean value){}public void ended(){if(!done)failed("样片提前结束");}public void error(String reason){failed(reason);}
            });}
            public void inputLost(){if(player!=null){player.release();player=null;}}
            public void failed(String reason){RemotePlaybackProbeActivity.this.failed(reason);}
        });
        FrameLayout videoArea=new FrameLayout(this);videoArea.addView(view,new FrameLayout.LayoutParams(-1,-1));root.addView(videoArea,new FrameLayout.LayoutParams(-1,-1));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{int width=r-l,height=b-t;if(width<=0||height<=0)return;int vw=width,vh=width*9/16;if(vh>height){vh=height;vw=height*16/9;}FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)videoArea.getLayoutParams();if(params.width!=vw||params.height!=vh){params.width=vw;params.height=vh;params.gravity=Gravity.CENTER;videoArea.setLayoutParams(params);}});
        root.addView(label,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));Button cancel=new Button(this);cancel.setText("停止自测");cancel.setOnClickListener(v->failed("用户停止了自测"));root.addView(cancel,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));setContentView(root);deadline=SystemClock.elapsedRealtime()+45000;ui.post(tick);
    }
    @Override protected void onResume(){super.onResume();RemoteDeviceService.foreground(this);}
    @Override protected void onPause(){RemoteDeviceService.background(this);if(!done)finishResult(error("用户离开了自测界面"),false);super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);if(player!=null){player.release();player=null;}super.onDestroy();}
    private void failed(String reason){if(!done)finishResult(error(reason),true);}
    private JSONObject error(String reason){JSONObject r=new JSONObject();try{r.put("passed",false).put("error",reason).put("pipeline",view==null?"尚未创建":view.statistics()).put("scope","应用内静音插帧通路检查").put("screen_present_fps_verified",false);}catch(JSONException ignored){}return r;}
    private final Runnable tick=new Runnable(){public void run(){if(done)return;long now=SystemClock.elapsedRealtime();try{
        if(now>deadline){failed("插帧初始化或采样超时");return;}
        long[] current=view.counters();
        if(current[1]>=3&&warmAt==0)warmAt=now;
        if(warmAt>0&&now-warmAt>=2000&&sampleAt==0){sampleAt=now;before=current;}
        label.setText("静音自测 · "+fps+" → "+(fps*2)+" · "+stages+"阶段 / "+estimate+"p\n"+(sampleAt==0?"正在预热…":"采样 "+Math.min(10,(now-sampleAt)/1000)+" / 10 秒")+"\n"+view.statistics());
        if(sampleAt>0&&now-sampleAt>=10000){double seconds=(now-sampleAt)/1000.0,input=(current[0]-before[0])/seconds,intermediate=(current[1]-before[1])/seconds,submitted=(current[1]+current[2]-before[1]-before[2])/seconds;boolean correctProfile=view.statistics().contains(stages+"阶段")&&view.statistics().contains("估计 "+(estimate*16/9)+"×"+estimate);boolean passed=correctProfile&&input>=fps*.9&&input<=fps*1.1&&intermediate>=fps*.9&&submitted>=fps*2*.92;
            JSONObject result=new JSONObject().put("passed",passed).put("scope","本机解码与 RIFE 提交通路；不是独立屏幕呈现帧率验收").put("source_fps",fps).put("requested_stages",stages).put("requested_estimate",estimate).put("sample_seconds",seconds).put("input_fps",input).put("intermediate_fps",intermediate).put("submitted_fps",submitted).put("pipeline",view.statistics()).put("screen_present_fps_verified",false).put("audible_playback",false).put("system_volume_written",false).put("account_writes",false);
            if(!passed)result.put("error","应用内插帧提交未达到目标，请查看统计");finishResult(result,true);return;
        }
        ui.postDelayed(this,250);
    }catch(Exception e){failed(e.toString());}}};
    private void finishResult(JSONObject result,boolean returnToChat){if(done)return;done=true;ui.removeCallbacksAndMessages(null);if(player!=null){player.release();player=null;}SharedPreferences p=getSharedPreferences("remote_debug_connection",0);p.edit().putString("completed_command",command).putString("completed_result",result.toString()).remove("probe_command").commit();RemoteDeviceService.maybeStart(this);if(returnToChat)startActivity(new Intent(this,RemoteDebugActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
    private static final class Profile extends android.content.ContextWrapper {
        final SharedPreferences settings;
        Profile(Context base,int stages,int estimate){super(base);SharedPreferences original=base.getSharedPreferences(MainActivity.class.getSimpleName(),0);Map<String,Object> values=new HashMap<>();values.put("memcBackend",MemcView.RIFE);values.put("rifeStages",stages);values.put("rifeEstimateHeight",estimate);values.put("rifeFullEstimateHeight",estimate);settings=new ReadOnlyPreferences(original,values);}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return MainActivity.class.getSimpleName().equals(name)?settings:super.getSharedPreferences(name,mode);}
    }
    private static final class ReadOnlyPreferences implements SharedPreferences {
        final SharedPreferences base;final Map<String,Object> values;ReadOnlyPreferences(SharedPreferences base,Map<String,Object> values){this.base=base;this.values=values;}
        public Map<String,?> getAll(){Map<String,Object> result=new HashMap<>(base.getAll());result.putAll(values);return result;}
        public String getString(String key,String def){return values.containsKey(key)?(String)values.get(key):base.getString(key,def);}
        public Set<String> getStringSet(String key,Set<String> def){return base.getStringSet(key,def);}
        public int getInt(String key,int def){return values.containsKey(key)?((Number)values.get(key)).intValue():base.getInt(key,def);}
        public long getLong(String key,long def){return values.containsKey(key)?((Number)values.get(key)).longValue():base.getLong(key,def);}
        public float getFloat(String key,float def){return values.containsKey(key)?((Number)values.get(key)).floatValue():base.getFloat(key,def);}
        public boolean getBoolean(String key,boolean def){return values.containsKey(key)?(Boolean)values.get(key):base.getBoolean(key,def);}
        public boolean contains(String key){return values.containsKey(key)||base.contains(key);}
        public Editor edit(){throw new UnsupportedOperationException("自测配置只在本次会话生效");}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
    }
}
