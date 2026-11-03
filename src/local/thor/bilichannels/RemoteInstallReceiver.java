package local.thor.bilichannels;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.os.*;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;

public final class RemoteInstallReceiver extends BroadcastReceiver {
    static String sha(File file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format("%02x",b&255));return hex.toString();}
    static void recoverInstalled(Context context){
        SharedPreferences p=context.getSharedPreferences("remote_debug_connection",0);String command=p.getString("install_command","");
        if(command.isEmpty()||!p.getBoolean("install_session_started",false))return;
        try{String actual=sha(new File(context.getApplicationInfo().sourceDir));if(actual.equals(p.getString("install_sha256",""))){JSONObject result=new JSONObject().put("installed",true).put("sha256",actual).put("transport","Thor 应用内自更新").put("verified_after_restart",true);p.edit().putString("completed_command",command).putString("completed_result",result.toString()).remove("install_command").remove("install_session_started").commit();}}catch(Exception ignored){}
    }
    @Override public void onReceive(Context context,Intent intent){
        SharedPreferences p=context.getSharedPreferences("remote_debug_connection",0);String command=p.getString("install_command","");
        boolean replaced=Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction());
        final int status=replaced?PackageInstaller.STATUS_SUCCESS:intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(replaced&&command.isEmpty()){RemoteDeviceService.maybeStart(context);return;}
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){Intent confirm=intent.getParcelableExtra(Intent.EXTRA_INTENT);if(confirm!=null){confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);try{context.startActivity(confirm);}catch(RuntimeException e){NotificationManager m=context.getSystemService(NotificationManager.class);PendingIntent pi=PendingIntent.getActivity(context,107,confirm,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);m.notify(107,new Notification.Builder(context,"thor-remote").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("确认 Thor 更新").setContentText("点击完成系统安装确认").setContentIntent(pi).setAutoCancel(true).build());}}return;}
        if(command.isEmpty())return;
        PendingResult pending=goAsync();new Thread(()->{try{
            JSONObject result=new JSONObject();
            if(status==PackageInstaller.STATUS_SUCCESS){String actual=sha(new File(context.getApplicationInfo().sourceDir));if(!actual.equals(p.getString("install_sha256","")))result.put("error","安装后 APK 哈希不一致");else result.put("installed",true).put("sha256",actual).put("transport","Thor 应用内自更新");}
            else result.put("error","系统未完成更新："+status);
            p.edit().putString("completed_command",command).putString("completed_result",result.toString()).remove("install_command").remove("install_session_started").commit();RemoteDeviceService.maybeStart(context);
        }catch(Exception ignored){}finally{pending.finish();}},"ThorUpdateResult").start();
    }
}
