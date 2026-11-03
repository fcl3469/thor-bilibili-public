package local.thor.bilichannels;

import android.net.Uri;import android.os.*;import android.view.*;import android.webkit.*;
import org.json.*;import java.io.*;import java.util.*;

/** Background transport for the native cloud browser. The website is shown only for login. */
class XunleiCloudClient {
 interface Reply{void done(JSONObject data,String error);}
 final MainActivity a;private final Handler ui=new Handler(Looper.getMainLooper());private WebView web;
 private String adapter;private boolean ready,loading,closed;private int sequence,loadEpoch;private String account="";private long readyAt;
 private final ArrayList<Runnable> waiters=new ArrayList<>();private final Map<Integer,Reply> pending=new HashMap<>();
 XunleiCloudClient(MainActivity a){this.a=a;}
 String account(){return account;}
 private void attach(){if(web!=null){if(web.getParent()==null){ViewGroup content=a.findViewById(android.R.id.content);content.addView(web,new ViewGroup.LayoutParams(1,1));}web.onResume();}}
 void suspend(){if(web!=null&&pending.isEmpty())web.onPause();}
 private void create(){
  if(web!=null){attach();return;}
  try(InputStream in=a.getAssets().open("xunlei-cloud.js")){adapter=new String(BiliApi.read(in,64000),"UTF-8");}catch(Exception error){return;}
  web=new WebView(a);web.setAlpha(0);web.setFocusable(false);web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
  WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setMediaPlaybackRequiresUserGesture(true);settings.setUserAgentString(BiliApi.UA);
  CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !XunleiCloudActivity.allowed(request.getUrl());}
   @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){ready=false;}
   @Override public void onPageFinished(WebView view,String url){if(XunleiCloudActivity.trusted(Uri.parse(url))){view.evaluateJavascript(adapter,null);probe(loadEpoch,SystemClock.elapsedRealtime()+20000);}}
   @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())failLoading("云盘连接失败，点刷新重试");}
  });attach();
 }
 private void start(){if(closed)return;create();if(web==null){failLoading("云盘组件加载失败");return;}if(loading)return;loading=true;ready=false;int epoch=++loadEpoch;web.loadUrl(XunleiCloudActivity.HOME);ui.postDelayed(()->{if(!closed&&epoch==loadEpoch&&!ready)failLoading("云盘连接超时，请刷新或重新登录");},25000);}
 private void probe(int epoch,long deadline){
  if(closed||epoch!=loadEpoch||web==null)return;
  if(SystemClock.elapsedRealtime()>deadline){failLoading("云盘连接超时，请重新登录");return;}
  web.evaluateJavascript("Boolean(window.ThorCloud&&window.$nuxt&&window.$nuxt.$store&&window.$nuxt.$store.state.users)",value->{if(closed||epoch!=loadEpoch)return;if("true".equals(value)){ready=true;readyAt=SystemClock.elapsedRealtime();loading=false;ArrayList<Runnable> jobs=new ArrayList<>(waiters);waiters.clear();for(Runnable job:jobs)job.run();}else ui.postDelayed(()->probe(epoch,deadline),250);});
 }
 private void failLoading(String message){loading=false;ready=false;waiters.clear();ArrayList<Reply> callbacks=new ArrayList<>(pending.values());pending.clear();for(Reply reply:callbacks)reply.done(null,message);}
 void reload(){loadEpoch++;ready=loading=false;account="";for(Reply reply:new ArrayList<>(pending.values()))reply.done(null,"云盘已刷新");pending.clear();waiters.clear();if(web!=null){web.stopLoading();web.loadUrl("about:blank");} }
 void identity(Reply reply){
  call("identity",new JSONObject(),(data,error)->{if((error!=null&&error.contains("账号已变化")||error==null&&!data.optBoolean("logged_in"))&&ready&&SystemClock.elapsedRealtime()-readyAt<4000){ui.postDelayed(()->identity(reply),300);return;}if(error!=null){reply.done(null,error);return;}String next=data.optBoolean("logged_in")?XunleiLibrary.accountKey(data.optString("account_id")):"";data.remove("account_id");account=next;try{data.put("account_key",next);}catch(JSONException ignored){}reply.done(data,null);});
 }
 void call(String action,JSONObject args,Reply reply){
  if(closed){reply.done(null,"云盘连接已关闭");return;}attach();final int id=++sequence;pending.put(id,reply);
  Runnable request=()->{if(closed||!pending.containsKey(id))return;web.evaluateJavascript("window.ThorCloud.request("+id+","+JSONObject.quote(action)+","+args.toString()+")",null);poll(id,SystemClock.elapsedRealtime()+25000);};
  if(ready&&web!=null)request.run();else{waiters.add(request);start();}
 }
 private void poll(int id,long deadline){ui.postDelayed(()->{
  if(closed||!pending.containsKey(id)||web==null)return;if(SystemClock.elapsedRealtime()>deadline){finish(id,null,"云盘请求超时，请点刷新重试");return;}
  String url=web.getUrl();if(url==null||!XunleiCloudActivity.trusted(Uri.parse(url))){finish(id,null,"请登录迅雷账号后重试");return;}
  web.evaluateJavascript("window.ThorCloud&&window.ThorCloud.take("+id+")",raw->{if(closed||!pending.containsKey(id))return;try{
   Object value=new JSONTokener(raw==null?"null":raw).nextValue();if(!(value instanceof String)){poll(id,deadline);return;}JSONObject envelope=new JSONObject((String)value);if(envelope.optBoolean("ok"))finish(id,envelope.getJSONObject("data"),null);else finish(id,null,envelope.optString("error","云盘请求失败"));
  }catch(Exception error){finish(id,null,"云盘响应无法读取，请刷新重试");}});
 },200);}
 private void finish(int id,JSONObject data,String error){Reply reply=pending.remove(id);if(reply!=null)reply.done(data,error);}
 void close(){closed=true;loadEpoch++;pending.clear();waiters.clear();ui.removeCallbacksAndMessages(null);if(web!=null){ViewParent p=web.getParent();if(p instanceof ViewGroup)((ViewGroup)p).removeView(web);web.stopLoading();web.destroy();web=null;}CookieManager.getInstance().flush();}
}
