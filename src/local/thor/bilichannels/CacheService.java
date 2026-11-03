package local.thor.bilichannels;
import android.app.*;import android.content.*;import android.os.*;
public final class CacheService extends Service {
 CacheStore store;final Handler ui=new Handler(Looper.getMainLooper());Thread worker;volatile boolean running;PowerManager.WakeLock wake;
 @Override public void onCreate(){super.onCreate();store=CacheStore.get(this);NotificationManager n=getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel("thor-cache","视频缓存",NotificationManager.IMPORTANCE_LOW);channel.setSound(null,null);channel.enableVibration(false);n.createNotificationChannel(channel);startForeground(360,notification());wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"thor:cache");wake.acquire(60*60*1000L);}
 Notification notification(){CacheStore.Job job=store.next();PendingIntent open=PendingIntent.getActivity(this,360,new Intent(this,MainActivity.class).putExtra("open_cache",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"thor-cache").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Thor 频道 · 缓存").setContentText(job==null?"缓存任务已结束":store.status(job.video)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).build();}
 final Runnable update=new Runnable(){public void run(){if(!running)return;getSystemService(NotificationManager.class).notify(360,notification());ui.postDelayed(this,1500);}};
 @Override public int onStartCommand(Intent intent,int flags,int id){if(!running){running=true;worker=new Thread(()->{try{while(running){CacheStore.Job job=store.next();if(job==null)break;if(!store.wifi()){job.state="waiting";store.save(job);Thread.sleep(1500);continue;}store.run(job);}}catch(InterruptedException ignored){}finally{ui.post(()->{running=false;if(store.next()!=null){onStartCommand(null,0,0);return;}stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}},"thor-cache-transfer");worker.start();ui.post(update);}return START_NOT_STICKY;}
 @Override public void onTimeout(int startId,int type){store.pauseAll();stopSelf();}
 @Override public void onDestroy(){running=false;ui.removeCallbacks(update);store.pauseAll();if(worker!=null)worker.interrupt();if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
