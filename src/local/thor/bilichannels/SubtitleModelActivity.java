package local.thor.bilichannels;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** Explicit model download entry; ordinary playback never starts a model network request. */
public final class SubtitleModelActivity extends Activity {
 private final Handler ui=new Handler(Looper.getMainLooper());private TextView status,amount;private ProgressBar progress;private Button download,pause;private CheckBox mobile;private boolean ready;
 static void open(Context context){context.startActivity(new Intent(context,SubtitleModelActivity.class));}
 private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
 private TextView text(LinearLayout parent,String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(0xff24272c);view.setPadding(0,dp(8),0,dp(8));parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);getWindow().setStatusBarColor(0xfff6f7f8);getWindow().setNavigationBarColor(Color.WHITE);
  ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(20),dp(24),dp(28));scroll.addView(body);setContentView(scroll);
  text(body,"日语转中文字幕",25);
  long total=SubtitleModels.totalBytes(this);text(body,"首次下载约 "+size(total)+"，包含日语识别和日中直译模型。下载完成后可离线使用；不使用字幕时无需下载。",16);
  text(body,"翻译仍在设备上完成，不上传音轨。支持后台下载和中断后继续，下载完成会检查文件是否完整。",14).setTextColor(0xff6b7078);
  status=text(body,"正在检查模型",18);amount=text(body,"",14);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);body.addView(progress,new LinearLayout.LayoutParams(-1,dp(24)));
  mobile=new CheckBox(this);mobile.setText("允许使用移动数据下载（约 "+size(total)+"）");mobile.setTextSize(14);if(saved!=null)mobile.setChecked(saved.getBoolean("mobile"));body.addView(mobile);
  download=new Button(this);download.setText("下载模型");body.addView(download,new LinearLayout.LayoutParams(-1,-2));download.setOnClickListener(v->startDownload());
  pause=new Button(this);pause.setText("暂停下载");body.addView(pause,new LinearLayout.LayoutParams(-1,-2));pause.setOnClickListener(v->{SubtitleModelDownloadService.pause();refresh();});
  Button back=new Button(this);back.setText("返回");body.addView(back,new LinearLayout.LayoutParams(-1,-2));back.setOnClickListener(v->finish());
  text(body,"模型：ReazonSpeech k2 v2、Hy-MT2-1.8B（Apache 2.0）；语音检测：Silero VAD（MIT）。沿用当前模型，下载方式不改变翻译效果。",13).setTextColor(0xff6b7078);
  text(body,"来源：公开模型仓库与下载镜像。下载文件均通过完整性校验，模型版本保持一致。",12).setTextColor(0xff6b7078);
 }
 private static String size(long bytes){return bytes>=1000000000L?String.format(Locale.ROOT,"%.2f GB",bytes/1000000000.0):String.format(Locale.ROOT,"%.1f MB",bytes/1000000.0);}
 private void startDownload(){
  if(mobile.isChecked())new AlertDialog.Builder(this).setTitle("使用移动数据下载？").setMessage("字幕模型约 "+size(SubtitleModels.totalBytes(this))+"，会使用较多流量。").setPositiveButton("下载",(dialog,which)->begin(true)).setNegativeButton("取消",null).show();
  else begin(false);
 }
 private void begin(boolean allowMobile){
  try{SubtitleModelDownloadService.start(this,allowMobile);download.setEnabled(false);ui.postDelayed(this::refresh,250);}
  catch(RuntimeException error){status.setText("暂时无法启动下载，请重新打开本页重试");download.setEnabled(true);}
 }
 private void refresh(){
  ready=SubtitleModels.available(this);SubtitleModelDownloadService.Snapshot s=SubtitleModelDownloadService.state(this);boolean running=s.running();
  status.setText(ready?"模型已就绪，返回视频开启「日→中字幕」":s.detail);
  amount.setText(ready?"已保存 · "+size(s.total):size(s.done)+" / "+size(s.total)+(s.total>0?" · "+Math.min(100,100*s.done/s.total)+"%":""));
  progress.setProgress(ready?1000:s.total>0?(int)Math.min(1000,1000*s.done/s.total):0);
  download.setVisibility(ready?View.GONE:View.VISIBLE);download.setEnabled(!running);download.setText(s.state.equals("idle")?"下载模型":"继续下载");
  pause.setVisibility(running?View.VISIBLE:View.GONE);pause.setEnabled(s.state.equals("downloading"));mobile.setEnabled(!running);mobile.setVisibility(ready?View.GONE:View.VISIBLE);
 }
 private final Runnable ticker=new Runnable(){public void run(){refresh();ui.postDelayed(this,700);}};
 @Override protected void onResume(){super.onResume();ui.post(ticker);}
 @Override protected void onPause(){ui.removeCallbacksAndMessages(null);super.onPause();}
 @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("mobile",mobile.isChecked());super.onSaveInstanceState(state);}
 @Override public boolean dispatchKeyEvent(KeyEvent event){
  if(event.getKeyCode()==KeyEvent.KEYCODE_BUTTON_B){if(event.getAction()==KeyEvent.ACTION_UP)finish();return true;}
  if(event.getKeyCode()==KeyEvent.KEYCODE_BUTTON_A){View focused=getCurrentFocus();if(focused!=null&&focused.isEnabled()){if(event.getAction()==KeyEvent.ACTION_UP)focused.performClick();return true;}}
  return super.dispatchKeyEvent(event);
 }
}
