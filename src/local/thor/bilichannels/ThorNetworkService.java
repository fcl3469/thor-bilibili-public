package local.thor.bilichannels;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.UUID;

/** Isolate the Go runtime from video playback and the main application process. */
public final class ThorNetworkService extends Service {
    private final Binder binder=new Binder(){
        @Override protected boolean onTransact(int code,Parcel input,Parcel output,int flags)throws android.os.RemoteException{
            if(code!=1)return super.onTransact(code,input,output,flags);
            String filename=input.readString();JSONObject result;
            try{
                File file=new File(filename);String parent=getCacheDir().getCanonicalPath()+File.separator;if(!file.getCanonicalPath().startsWith(parent))throw new IOException("IPC 路径无效");
                String raw=ThorLink.readText(file);file.delete();JSONObject request=new JSONObject(raw);JSONArray values=request.getJSONArray("args");String[] args=new String[values.length()];for(int i=0;i<args.length;i++)args[i]=values.getString(i);
                result=new JSONObject(ThorLink.local(ThorNetworkService.this,request.getInt("op"),args));
            }catch(Exception e){result=new JSONObject();try{result.put("error",e.getMessage()==null?e.toString():e.getMessage());}catch(JSONException ignored){}}
            try{File response=new File(getCacheDir(),"remote-ipc-response-"+UUID.randomUUID()+".json");try(FileOutputStream out=new FileOutputStream(response)){out.write(result.toString().getBytes("UTF-8"));}output.writeNoException();output.writeString(response.getAbsolutePath());}catch(IOException e){throw new android.os.RemoteException("内置连接响应失败");}
            return true;
        }
    };
    @Override public IBinder onBind(Intent intent){return binder;}
}
