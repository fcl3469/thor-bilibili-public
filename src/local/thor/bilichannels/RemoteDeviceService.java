package local.thor.bilichannels;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.net.Uri;
import android.util.Base64;
import android.view.PixelCopy;
import org.json.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Outbound command channel; only fixed app-local diagnostics and self-update. */
public final class RemoteDeviceService extends Service {
    private static volatile WeakReference<Activity> foreground=new WeakReference<>(null);
    private volatile boolean running;
    private final Handler main=new Handler(Looper.getMainLooper());
    static void foreground(Activity activity){foreground=new WeakReference<>(activity);}
    static void background(Activity activity){if(foreground.get()==activity)foreground=new WeakReference<>(null);}
    static void maybeStart(Context context){
        SharedPreferences p=context.getSharedPreferences("remote_debug_connection",0);
        if(!p.getString("cookie","").isEmpty()&&!p.getString("base","").isEmpty())try{context.startForegroundService(new Intent(context,RemoteDeviceService.class));}catch(RuntimeException ignored){}
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();NotificationManager manager=getSystemService(NotificationManager.class);NotificationChannel ch=new NotificationChannel("thor-remote","电脑调试连接",NotificationManager.IMPORTANCE_LOW);ch.setSound(null,null);manager.createNotificationChannel(ch);Intent open=new Intent(this,RemoteDebugActivity.class);PendingIntent content=PendingIntent.getActivity(this,104,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);startForeground(104,new Notification.Builder(this,"thor-remote").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Thor 电脑调试连接").setContentText("在 Thor 内与电脑 Codex 对话").setContentIntent(content).setOngoing(true).build());String stale=prefs().getString("probe_command","");if(!stale.isEmpty())prefs().edit().putString("completed_command",stale).putString("completed_result","{\"error\":\"自测被进程重启中断，请重试\"}").remove("probe_command").commit();running=true;new Thread(()->{RemoteInstallReceiver.recoverInstalled(this);loop();},"ThorRemoteDevice").start();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public void onDestroy(){running=false;super.onDestroy();}
    private SharedPreferences prefs(){return getSharedPreferences("remote_debug_connection",0);}
    private String id(){return prefs().getString("client_id","");}
    private JSONObject info()throws Exception{
        PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);Activity a=foreground.get();
        return new JSONObject().put("package",getPackageName()).put("version",p.versionName).put("version_code",p.getLongVersionCode()).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE).put("activity",a==null?"background":a.getClass().getSimpleName()).put("transport",prefs().getString("transport","embedded-tsnet"));
    }
    private void loop(){while(running){try{
        ThorLink.status(this);
        String pending=prefs().getString("completed_command","");
        if(!pending.isEmpty()){JSONObject result=new JSONObject(prefs().getString("completed_result","{}"));complete(pending,result);if(pending.equals(prefs().getString("completed_command","")))prefs().edit().remove("completed_command").remove("completed_result").commit();}
        JSONObject response=(JSONObject)ThorLink.api(this,"/api/device/poll",new JSONObject().put("id",id()).put("info",info()));
        if(!response.isNull("command")){JSONObject cmd=response.getJSONObject("command");String command=cmd.getString("id");try{JSONObject result=execute(command,cmd.getString("kind"),cmd.getJSONObject("payload"));if(result!=null)complete(command,result);}catch(Exception e){complete(command,new JSONObject().put("error",e.getMessage()==null?e.toString():e.getMessage()));}}
        Thread.sleep(2000);
    }catch(Exception e){try{Thread.sleep(4000);}catch(InterruptedException ignored){return;}}}}
    private boolean active(String command)throws Exception{return ((JSONObject)ThorLink.api(this,"/api/device/status",new JSONObject().put("id",id()).put("command",command))).optBoolean("active");}
    private void complete(String command,JSONObject result)throws Exception{ThorLink.api(this,"/api/device/complete",new JSONObject().put("id",id()).put("command",command).put("result",result));}
    private JSONObject execute(String command,String kind,JSONObject payload)throws Exception{
        if(kind.equals("device")){JSONObject state=ThorLink.status(this);state.put("authorization_required",!state.optString("auth_url","").isEmpty());state.remove("auth_url");return info().put("connection",state);}
        if(kind.equals("rife24")||kind.equals("rife30")){
            if(!prefs().getString("probe_command","").isEmpty())throw new IOException("上一次自测仍在结束，请稍后再试");
            Activity current=foreground.get();if(current==null)throw new IOException("请先让 Thor 显示在前台，再开始自测");
            prefs().edit().putString("probe_command",command).commit();int sourceFps=kind.equals("rife24")?24:30;
            main.post(()->current.startActivity(new Intent(current,RemotePlaybackProbeActivity.class).putExtra("command",command).putExtra("source_fps",sourceFps)));
            return null;
        }
        if(kind.equals("logs")){
            java.lang.Process process=new ProcessBuilder("logcat","-d","-t","200","--pid="+android.os.Process.myPid(),"-s","ThorRIFE:I","ThorQuality:I","AndroidRuntime:E","*:S").redirectErrorStream(true).start();ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=process.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1&&out.size()<65536)out.write(b,0,n);}process.waitFor(5,TimeUnit.SECONDS);process.destroy();return new JSONObject().put("scope","仅当前 App 可读日志").put("logs",out.toString("UTF-8"));
        }
        if(kind.equals("screenshot"))return screenshot();
        if(kind.equals("smoke")){
            JSONObject result=info();result.put("passed",true).put("checks",new JSONArray().put("应用内指令接收").put("包版本读取").put("主进程存活")).put("scope","基本运行检查；不代表播放、插帧或屏幕帧率验收");return result;
        }
        if(kind.equals("install")){
            if(!payload.getString("url").matches("/api/artifact/[a-f0-9]{32}"))throw new IOException("更新地址无效");
            if(!active(command))return null;
            File apk=new File(getCacheDir(),"thor-remote-update.apk");ThorLink.download(this,payload.getString("url"),apk,payload.getString("sha256"));
            if(!active(command)){apk.delete();return null;}
            PackageManager pm=getPackageManager();PackageInfo candidate=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);PackageInfo installed=pm.getPackageInfo(getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
            if(candidate==null||!getPackageName().equals(candidate.packageName)||candidate.getLongVersionCode()<installed.getLongVersionCode())throw new IOException("更新包身份或版本不匹配");
            if(!Arrays.equals(candidate.signingInfo.getApkContentsSigners(),installed.signingInfo.getApkContentsSigners()))throw new IOException("更新包签名不一致");
            prefs().edit().putString("install_command",command).putString("install_sha256",payload.getString("sha256")).putString("install_file",apk.getAbsolutePath()).putBoolean("install_session_started",false).commit();
            main.post(()->{Activity a=foreground.get();Intent open=new Intent(RemoteDeviceService.this,RemoteDebugActivity.class).putExtra("install_pending",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);if(a!=null)a.startActivity(open);else{NotificationManager manager=getSystemService(NotificationManager.class);PendingIntent pi=PendingIntent.getActivity(RemoteDeviceService.this,105,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);manager.notify(105,new Notification.Builder(RemoteDeviceService.this,"thor-remote").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Thor 新版已准备好").setContentText("点此在 Thor 内确认更新").setContentIntent(pi).setAutoCancel(true).build());}});
            return null;
        }
        throw new IOException("未允许的应用内指令");
    }
    private JSONObject screenshot()throws Exception{
        Activity a=foreground.get();if(a==null)throw new IOException("请先让 Thor 显示在前台，再截图");CountDownLatch done=new CountDownLatch(1);final Bitmap[] image={null};final int[] result={-1};
        main.post(()->{try{int width=a.getWindow().getDecorView().getWidth(),height=a.getWindow().getDecorView().getHeight();Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);PixelCopy.request(a.getWindow(),bitmap,code->{image[0]=bitmap;result[0]=code;done.countDown();},main);}catch(RuntimeException e){done.countDown();}});
        if(!done.await(10,TimeUnit.SECONDS)||result[0]!=PixelCopy.SUCCESS||image[0]==null)throw new IOException("当前 Thor 窗口暂时不能截图");ByteArrayOutputStream out=new ByteArrayOutputStream();image[0].compress(Bitmap.CompressFormat.PNG,100,out);image[0].recycle();return new JSONObject().put("scope","当前 Thor 窗口；独立视频表面可能不包含在内").put("image",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));
    }
    static void continueInstall(Activity activity){
        SharedPreferences p=activity.getSharedPreferences("remote_debug_connection",0);String command=p.getString("install_command","");if(command.isEmpty())return;
        if(p.getBoolean("install_session_started",false))return;
        if(!activity.getPackageManager().canRequestPackageInstalls()){activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));return;}
        p.edit().putBoolean("install_session_started",true).commit();new Thread(()->{boolean submitted=false;try{
            if(!((JSONObject)ThorLink.api(activity,"/api/device/status",new JSONObject().put("id",p.getString("client_id","")).put("command",command))).optBoolean("active")){p.edit().remove("install_command").remove("install_session_started").commit();return;}
            File apk=new File(p.getString("install_file",""));if(!apk.isFile())throw new IOException("更新文件不存在");
            PackageInstaller installer=activity.getPackageManager().getPackageInstaller();PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);params.setAppPackageName(activity.getPackageName());params.setSize(apk.length());if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            String actual=RemoteInstallReceiver.sha(apk);if(!actual.equals(p.getString("install_sha256","")))throw new IOException("待安装文件已变化");
            int sessionId=installer.createSession(params);
            try(PackageInstaller.Session session=installer.openSession(sessionId)){try(InputStream in=new FileInputStream(apk);OutputStream out=session.openWrite("base.apk",0,apk.length())){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);session.fsync(out);}
                if(!((JSONObject)ThorLink.api(activity,"/api/device/status",new JSONObject().put("id",p.getString("client_id","")).put("command",command))).optBoolean("active")){session.abandon();p.edit().remove("install_command").remove("install_session_started").commit();return;}
                Intent done=new Intent(activity,RemoteInstallReceiver.class).setAction("local.thor.bilichannels.REMOTE_INSTALL");PendingIntent callback=PendingIntent.getBroadcast(activity,106,done,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);session.commit(callback.getIntentSender());submitted=true;
            }
        }catch(Exception e){if(submitted){maybeStart(activity);return;}String failure="应用内安装未完成";try{failure=new JSONObject().put("error",failure+"："+e.getClass().getSimpleName()).toString();}catch(JSONException ignored){}p.edit().putString("completed_command",command).putString("completed_result",failure).remove("install_command").remove("install_session_started").commit();maybeStart(activity);}},"ThorSelfUpdate").start();
    }
}
