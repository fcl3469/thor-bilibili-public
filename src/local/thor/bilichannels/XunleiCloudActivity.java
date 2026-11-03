package local.thor.bilichannels;
import android.app.*;import android.content.*;import android.net.Uri;import android.os.*;import android.view.*;import android.webkit.*;import android.widget.*;import java.io.*;
/** Official login/verification only. Video browsing and playback belong to native Thor views. */
public final class XunleiCloudActivity extends Activity {
 static final String HOME="https://pan.xunlei.com/";WebView web;
 @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);LinearLayout root=new LinearLayout(this);root.setOrientation(1);TextView title=new TextView(this);title.setText("迅雷账号登录 / 官方验证");title.setTextSize(20);title.setPadding(20,18,20,8);root.addView(title);TextView tip=new TextView(this);tip.setText("完成登录或验证后返回，视频在 Thor 的云盘列表中选择。");tip.setPadding(20,4,20,12);root.addView(tip);
  LinearLayout bar=new LinearLayout(this);Button back=new Button(this),reload=new Button(this);back.setText("完成并返回云盘");reload.setText("刷新登录页");bar.addView(back,new LinearLayout.LayoutParams(0,52,2));bar.addView(reload,new LinearLayout.LayoutParams(0,52,1));root.addView(bar);web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);back.setOnClickListener(v->{CookieManager.getInstance().flush();setResult(RESULT_OK);finish();});reload.setOnClickListener(v->web.loadUrl(HOME));
  WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setMediaPlaybackRequiresUserGesture(true);settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);settings.setUserAgentString(BiliApi.UA);CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
  web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !allowed(request.getUrl());}@Override public void onPageFinished(WebView view,String url){if(trusted(Uri.parse(url)))try(InputStream in=getAssets().open("xunlei-cloud.js")){view.evaluateJavascript(new String(BiliApi.read(in,64000),"UTF-8"),null);}catch(Exception ignored){}}});web.loadUrl(HOME);
 }
 static boolean trusted(Uri uri){return "https".equals(uri.getScheme())&&"pan.xunlei.com".equals(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443);}
 static boolean allowed(Uri uri){String host=uri.getHost();return "https".equals(uri.getScheme())&&host!=null&&(host.equals("xunlei.com")||host.endsWith(".xunlei.com")||host.equals("sandai.net")||host.endsWith(".sandai.net")||host.equals("geetest.com")||host.endsWith(".geetest.com"));}
 @Override protected void onPause(){CookieManager.getInstance().flush();if(web!=null)web.onPause();super.onPause();}
 @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
 @Override protected void onDestroy(){if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
}
