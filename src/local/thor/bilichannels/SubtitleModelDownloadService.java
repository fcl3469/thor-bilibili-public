package local.thor.bilichannels;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;

/** User-started, silent foreground transfer. Process death leaves resumable private partials. */
public final class SubtitleModelDownloadService extends Service {
 static final class Snapshot {
  final String state,detail;final long done,total;
  Snapshot(String state,String detail,long done,long total){this.state=state;this.detail=detail;this.done=done;this.total=total;}
  boolean running(){return state.equals("downloading")||state.equals("pausing");}
 }
 private static volatile Snapshot latest;
 private static volatile SubtitleModelDownloadService active;
 private volatile boolean paused;private Thread worker;private PowerManager.WakeLock wake;private boolean mobile;
 private final Handler ui=new Handler(Looper.getMainLooper());private long publishedAt;
 private static SharedPreferences prefs(Context context){return context.getSharedPreferences("subtitle-model-download",Context.MODE_PRIVATE);}
 static synchronized Snapshot state(Context context){
  if(latest==null){SharedPreferences p=prefs(context);String state=p.getString("state","idle"),detail=p.getString("detail","首次使用时下载，之后可离线使用");
   if(state.equals("downloading")||state.equals("pausing")){state="paused";detail="上次下载已中断，点击继续下载";}
   latest=new Snapshot(state,detail,p.getLong("done",0),SubtitleModels.totalBytes(context));}
  return latest;
 }
 static void start(Context context,boolean mobile){context.startForegroundService(new Intent(context,SubtitleModelDownloadService.class).putExtra("mobile",mobile));}
 static void pause(){SubtitleModelDownloadService service=active;if(service!=null){service.paused=true;Snapshot s=latest;service.publish("pausing","正在暂停，已下载内容会保留",s==null?0:s.done,s==null?0:s.total);if(service.worker!=null)service.worker.interrupt();}}
 @Override public void onCreate(){
  super.onCreate();active=this;
  NotificationChannel channel=new NotificationChannel("thor-subtitle-models","字幕模型下载",NotificationManager.IMPORTANCE_LOW);channel.setSound(null,null);channel.enableVibration(false);getSystemService(NotificationManager.class).createNotificationChannel(channel);
 }
 @Override public int onStartCommand(Intent intent,int flags,int id){
  startForeground(376,notification());if(worker!=null)return START_NOT_STICKY;
  paused=false;mobile=intent!=null&&intent.getBooleanExtra("mobile",false);
  Snapshot previous=state(this);publish("downloading","正在检查已下载模型",previous.done,SubtitleModels.totalBytes(this));
  wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"thor:subtitle-models");wake.acquire(60*60*1000L);
  worker=new Thread(()->{
   android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
   try{
    SubtitleModels.download(this,(stage,done,total)->{
     if(paused||Thread.currentThread().isInterrupted())throw new InterruptedIOException("已暂停，可继续下载");
     NetworkCapabilities network=getSystemService(ConnectivityManager.class).getNetworkCapabilities(getSystemService(ConnectivityManager.class).getActiveNetwork());
     if(network==null||!network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))throw new InterruptedIOException("网络已断开，联网后可继续下载");
     if(!mobile&&!network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))throw new InterruptedIOException("已暂停，连接 Wi-Fi 后可继续，或允许使用流量");
     // Leave headroom for playback and normal app writes; partial files remain resumable.
     if(!stage.contains("校验")&&getNoBackupFilesDir().getUsableSpace()<64L*1024*1024)throw new IOException("可用空间不足，请清理空间后继续下载");
     long now=SystemClock.elapsedRealtime();if(now-publishedAt>=500){publishedAt=now;publish("downloading",stage,done,total);}
    });
    if(paused||Thread.currentThread().isInterrupted())throw new InterruptedIOException("已暂停，可继续下载");
    publish("ready","模型已就绪，返回视频开启「日→中字幕」",SubtitleModels.totalBytes(this),SubtitleModels.totalBytes(this));
   }catch(InterruptedIOException error){Snapshot s=state(this);publish("paused",error.getMessage(),s.done,s.total);}
   catch(Exception error){Snapshot s=state(this);publish("error",error.getMessage()==null?"下载失败，点击继续下载":error.getMessage(),s.done,s.total);}
   finally{ui.post(()->{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}
  },"thor-subtitle-download");worker.start();ui.post(notifyProgress);return START_NOT_STICKY;
 }
 private void publish(String state,String detail,long done,long total){
  if(active!=this)return;
  latest=new Snapshot(state,detail,done,total);prefs(this).edit().putString("state",state).putString("detail",detail).putLong("done",done).apply();
 }
 private Notification notification(){
  Snapshot state=state(this);PendingIntent open=PendingIntent.getActivity(this,376,new Intent(this,SubtitleModelActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  return new Notification.Builder(this,"thor-subtitle-models").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Thor · 字幕模型").setContentText(state.detail).setContentIntent(open).setOnlyAlertOnce(true).setDefaults(0).setOngoing(true).setProgress(1000,state.total>0?(int)(1000*state.done/state.total):0,state.total<=0).build();
 }
 private final Runnable notifyProgress=new Runnable(){public void run(){if(active!=SubtitleModelDownloadService.this)return;getSystemService(NotificationManager.class).notify(376,notification());ui.postDelayed(this,1500);}};
 @Override public void onDestroy(){paused=true;if(worker!=null)worker.interrupt();ui.removeCallbacks(notifyProgress);if(active==this){Snapshot s=state(this);if(s.running())publish("paused","下载已暂停，可点击继续下载",s.done,s.total);active=null;}if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
