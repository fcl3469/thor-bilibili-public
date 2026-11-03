package local.thor.bilichannels;
import android.net.Uri;
import org.json.*;
import java.util.*;
/** Short-lived signed media URLs are kept in memory, never in local history or logs. */
final class XunleiMedia {
 static boolean validUrl(String value){try{Uri u=Uri.parse(value);String host=u.getHost();return "https".equals(u.getScheme())&&host!=null&&!host.isEmpty()&&u.getUserInfo()==null&&!host.equalsIgnoreCase("localhost")&&!host.equals("::1")&&!host.startsWith("127.")&&!host.startsWith("169.254.");}catch(Exception e){return false;}}
 static Video video(JSONObject media)throws JSONException{
  String url=media.getString("url");if(!validUrl(url))throw new JSONException("Invalid media URL");
  String id=media.optString("id"),name=media.optString("name","云盘视频"),account=media.optString("account_key"),space=media.optString("space");String key=account.isEmpty()?Integer.toHexString(id.hashCode()):XunleiLibrary.key(account,id,space);Video v=new Video("xunlei-"+key,name,"迅雷云盘","",0,0,(int)Math.min(Integer.MAX_VALUE,media.optLong("duration_ms")/1000),false);v.localUri=url;
  if(!account.isEmpty()){v.cloudAccount=account;v.cloudFile=id;v.cloudSpace=space;}
  v.localSpec=StreamSpec.external(url,media.optString("label","云盘视频"),media.optString("mime"));v.description="迅雷云盘视频 · Thor 播放器\n可在播放菜单中开启插帧和选择清晰度。历史记录保存进度，继续播放时自动刷新地址。";return v;
 }
}
