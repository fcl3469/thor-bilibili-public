package local.thor.bilichannels;
import android.app.*;import android.content.*;import android.os.*;
/** User-started persistent download with a silent notification and an explicit pause action. */
public final class TorrentDownloadService extends Service {
 private final Handler ui=new Handler(Looper.getMainLooper());private PowerManager.WakeLock wake;
 static void start(Context context){context.startForegroundService(new Intent(context,TorrentDownloadService.class));}
 @Override public void onCreate(){super.onCreate();NotificationManager manager=getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel("thor-torrent","磁力缓存",NotificationManager.IMPORTANCE_LOW);channel.setSound(null,null);channel.enableVibration(false);manager.createNotificationChannel(channel);startForeground(364,notification());wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"thor:torrent-cache");wake.acquire(6*60*60*1000L);}
 private Notification notification(){
  TorrentRuntime.Task task=TorrentRuntime.current();Intent intent=new Intent(this,MainActivity.class).putExtra("open_local",true);
  PendingIntent open=PendingIntent.getActivity(this,364,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  PendingIntent pause=PendingIntent.getService(this,365,new Intent(this,TorrentDownloadService.class).setAction("pause"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification.Builder b=new Notification.Builder(this,"thor-torrent").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Thor 频道 · 磁力缓存").setContentText(task==null?"正在读取缓存任务":task.summary()).setContentIntent(open).addAction(new Notification.Action.Builder(null,"暂停",pause).build()).setOnlyAlertOnce(true).setOngoing(true);
  if(task!=null&&task.record.size>0)b.setProgress(100,(int)Math.min(100,task.stats.downloaded*100/task.record.size),false);return b.build();
 }
 private final Runnable update=new Runnable(){public void run(){TorrentRuntime.Task task=TorrentRuntime.current();if(task==null||!task.needsService()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return;}getSystemService(NotificationManager.class).notify(364,notification());ui.postDelayed(this,1500);}};
 @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"pause".equals(intent.getAction())){TorrentRuntime.Task task=TorrentRuntime.current();if(task!=null)task.pause();stopSelf();return START_NOT_STICKY;}ui.removeCallbacks(update);ui.post(update);return START_NOT_STICKY;}
 @Override public void onTimeout(int startId,int type){TorrentRuntime.Task task=TorrentRuntime.current();if(task!=null)task.pause();stopSelf();}
 @Override public void onDestroy(){ui.removeCallbacks(update);TorrentRuntime.Task task=TorrentRuntime.current();if(task!=null&&task.needsService())task.pause();if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
