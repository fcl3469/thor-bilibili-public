package local.thor.bilichannels;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** App-local tsnet node: no VpnService, root, separate client or ADB required. */
final class ThorLink {
    private static boolean loaded;
    private static volatile IBinder bridge;
    private static final Object bindLock=new Object();
    private static final String NATIVE_REVISION="tsnet-1.104.1-underlay-r6";
    private static Network boundUnderlay;
    private static String underlayError="";
    /** Called only in :thor_link; native sockets also need the same explicit network mark. */
    private static synchronized long underlay(Context context)throws IOException{
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        Network active=manager.getActiveNetwork(),selected=active;
        NetworkCapabilities capabilities=manager.getNetworkCapabilities(active);
        if(capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
            selected=null;
            for(Network candidate:manager.getAllNetworks()){
                NetworkCapabilities c=manager.getNetworkCapabilities(candidate);
                if(c==null||c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)||!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))continue;
                if(selected==null||c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))selected=candidate;
                if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))break;
            }
        }
        underlayError="";
        if(!Objects.equals(boundUnderlay,selected)){
            if(!manager.bindProcessToNetwork(selected)){
                // A VPN may disallow bypass. Keep the existing LAN/default route usable.
                manager.bindProcessToNetwork(null);boundUnderlay=null;
                underlayError="VPN 尚未允许 Thor 直接连接电脑，请检查 VPN 的应用分流设置";
                return 0;
            }
            boundUnderlay=selected;
        }
        return selected==null?0:selected.getNetworkHandle();
    }
    static final class NeedsAuthorization extends IOException {
        final String url;
        NeedsAuthorization(String url){super("请先授权 Thor 内置连接");this.url=url;}
    }
    private static synchronized void load()throws IOException{
        if(!loaded)try{System.loadLibrary("thor_link");loaded=true;}catch(UnsatisfiedLinkError e){throw new IOException("此版本未包含内置网络库",e);}
    }
    static JSONObject checked(String raw)throws Exception{JSONObject result=new JSONObject(raw);if(result.has("error"))throw new IOException(result.getString("error"));return result;}
    private static JSONObject localStatus(Context context)throws Exception{
        SharedPreferences guard=context.getSharedPreferences("remote_native_guard",0);
        if(!NATIVE_REVISION.equals(guard.getString("revision","")))guard.edit().putString("revision",NATIVE_REVISION).putBoolean("pending",false).commit();
        if(!loaded&&guard.getBoolean("pending",false))throw new IOException("内置网络上次启动异常，已隔离停用，播放器仍可使用");
        long network=underlay(context);
        boolean first=!loaded;if(first)guard.edit().putBoolean("pending",true).commit();
        load();SharedPreferences p=context.getSharedPreferences("remote_debug_connection",0);
        String id=p.getString("client_id","");if(id.isEmpty()){id=UUID.randomUUID().toString().replace("-","");p.edit().putString("client_id",id).commit();}
        JSONArray interfaces=new JSONArray();Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
        while(all!=null&&all.hasMoreElements()){
            NetworkInterface ni=all.nextElement();if(!ni.isUp()||ni.getName().startsWith("tun"))continue;
            JSONArray addresses=new JSONArray();for(InterfaceAddress a:ni.getInterfaceAddresses())addresses.put(a.getAddress().getHostAddress().replaceAll("%.*$","")+"/"+a.getNetworkPrefixLength());
            interfaces.put(new JSONObject().put("Name",ni.getName()).put("Index",ni.getIndex()).put("MTU",ni.getMTU()).put("Loopback",ni.isLoopback()).put("Addresses",addresses));
        }
        checked(nativeStart(new File(context.getFilesDir(),"remote-tsnet").getAbsolutePath(),"thor-"+id.substring(0,10),interfaces.toString(),network));
        JSONObject result=checked(nativeStatus());result.put("underlay_network",network).put("underlay_error",underlayError);if(first)guard.edit().putBoolean("pending",false).commit();return result;
    }
    static JSONObject status(Context context)throws Exception{
        SharedPreferences p=context.getSharedPreferences("remote_debug_connection",0);if(p.getString("client_id","").isEmpty())p.edit().putString("client_id",UUID.randomUUID().toString().replace("-","")).commit();
        return checked(invoke(context,0));
    }
    private static void requireRunning(JSONObject state)throws IOException{
        if("Running".equals(state.optString("state")))return;
        String url=state.optString("auth_url","");
        if(!url.isEmpty())throw new NeedsAuthorization(url);
        if(!state.optString("last_error","").isEmpty())throw new IOException("内置连接暂时无法访问授权服务，正在重试，请检查网络");
        throw new IOException("内置连接正在准备（"+state.optString("state")+"），请稍后重试");
    }
    static JSONObject request(Context context,String base,String path,JSONObject body,String cookie)throws Exception{
        JSONObject state=status(context);String lan=context.getSharedPreferences("remote_debug_connection",0).getString("lan_address","");if(lan.isEmpty())requireRunning(state);
        URI peer=new URI(base);
        JSONObject response;
        try{response=checked(invoke(context,1,peer.getRawAuthority(),body==null?"GET":"POST",base+path,body==null?"":body.toString(),cookie,lan));}
        catch(IOException e){if(!"Running".equals(state.optString("state"))&&!state.optString("auth_url","").isEmpty())throw new NeedsAuthorization(state.getString("auth_url"));throw e;}
        context.getSharedPreferences("remote_debug_connection",0).edit().putString("transport",response.optString("route","embedded-tsnet")).apply();return response;
    }
    static byte[] bytes(JSONObject response)throws JSONException{return Base64.decode(response.getString("body"),Base64.DEFAULT);}
    static Object api(Context context,String path,JSONObject body)throws Exception{
        SharedPreferences p=context.getSharedPreferences("remote_debug_connection",0);String base=p.getString("base",""),cookie=p.getString("cookie","");
        JSONObject response=request(context,base,path,body,cookie);String raw=new String(bytes(response),"UTF-8");Object data=new JSONTokener(raw).nextValue();
        if(response.getInt("status")>=400)throw new IOException(data instanceof JSONObject?((JSONObject)data).optString("error","电脑请求失败"):"电脑请求失败");
        return data;
    }
    static JSONObject download(Context context,String path,File file,String hash)throws Exception{
        SharedPreferences p=context.getSharedPreferences("remote_debug_connection",0);String base=p.getString("base",""),cookie=p.getString("cookie","");
        JSONObject state=status(context);String lan=p.getString("lan_address","");if(lan.isEmpty())requireRunning(state);
        return checked(invoke(context,2,new URI(base).getRawAuthority(),base+path,cookie,file.getAbsolutePath(),hash,lan));
    }
    static String local(Context context,int op,String[] args)throws Exception{
        if(op==0)return localStatus(context).toString();
        load();checked(nativePeer(new JSONObject().put("host",args[0]).put("lan",args[5]).toString()));
        if(op==1)return nativeRequest(args[1],args[2],args[3],args[4]);
        if(op==2)return nativeDownload(args[1],args[2],args[3],args[4]);
        throw new IOException("内置连接操作无效");
    }
    static String readText(File file)throws IOException{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] data=new byte[8192];int n;while((n=in.read(data))!=-1){if(out.size()+n>48*1024*1024)throw new IOException("IPC 响应过大");out.write(data,0,n);}return out.toString("UTF-8");}}
    private static String invoke(Context context,int operation,String...args)throws Exception{
        synchronized(bindLock){
            if(bridge==null||!bridge.isBinderAlive()){
                CountDownLatch ready=new CountDownLatch(1);Context app=context.getApplicationContext();
                ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder service){bridge=service;ready.countDown();}public void onServiceDisconnected(ComponentName name){bridge=null;}public void onBindingDied(ComponentName name){bridge=null;}};
                if(!app.bindService(new Intent(app,ThorNetworkService.class),connection,Context.BIND_AUTO_CREATE|Context.BIND_IMPORTANT)||!ready.await(10,TimeUnit.SECONDS))throw new IOException("内置连接服务暂未就绪");
            }
        }
        File request=new File(context.getCacheDir(),"remote-ipc-request-"+UUID.randomUUID()+".json");JSONObject data=new JSONObject().put("op",operation).put("args",new JSONArray(Arrays.asList(args)));
        try(FileOutputStream out=new FileOutputStream(request)){out.write(data.toString().getBytes("UTF-8"));}
        Parcel input=Parcel.obtain(),reply=Parcel.obtain();
        try{input.writeString(request.getAbsolutePath());IBinder current=bridge;if(current==null)throw new IOException("内置连接已断开");current.transact(1,input,reply,0);reply.readException();File response=new File(reply.readString());if(!response.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath()+File.separator))throw new IOException("连接响应路径无效");try{return readText(response);}finally{response.delete();}}
        catch(android.os.DeadObjectException e){bridge=null;throw new IOException("内置连接进程异常，播放器未受影响",e);}
        finally{request.delete();input.recycle();reply.recycle();}
    }
    private static native String nativeStart(String path,String hostname,String interfaces,long network);
    private static native String nativeStatus();
    private static native String nativePeer(String host);
    private static native String nativeRequest(String method,String address,String body,String cookie);
    private static native String nativeDownload(String address,String cookie,String path,String hash);
}
