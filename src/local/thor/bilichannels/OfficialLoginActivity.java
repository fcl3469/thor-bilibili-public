package local.thor.bilichannels;
import android.app.*;import android.os.*;import android.content.*;import android.webkit.*;import android.net.Uri;import android.view.*;import android.widget.*;
/** Official login/challenge only. No JS bridges, credential fields or third-party login pages. */
public final class OfficialLoginActivity extends Activity {
 static final String LOGIN_URL="https://passport.bilibili.com/h5-app/passport/login";
 WebView web;boolean verifying;Handler ui=new Handler(Looper.getMainLooper());
 @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout box=new LinearLayout(this);box.setOrientation(1);TextView tip=new TextView(this);tip.setText("B站官方登录 / 安全验证 · 完成后点下方返回");tip.setPadding(16,14,16,14);box.addView(tip);web=new WebView(this);box.addView(web,new LinearLayout.LayoutParams(-1,0,1));Button done=new Button(this);done.setText("完成验证，返回 Thor 频道");done.setOnClickListener(v->finishLogin());box.addView(done);setContentView(box);
  web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);web.getSettings().setUserAgentString(WebSettings.getDefaultUserAgent(this));web.getSettings().setUseWideViewPort(true);web.getSettings().setLoadWithOverviewMode(true);CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
  web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !allowed(r.getUrl());}});
  if(BiliApi.session!=null){String header=BiliApi.session.cookies.header("https://www.bilibili.com");for(String token:header.split(";"))if(token.contains("="))CookieManager.getInstance().setCookie("https://www.bilibili.com",token.trim()+"; Domain=.bilibili.com; Path=/; Secure");}
  String target=getIntent().getStringExtra("url");if(target==null||!allowed(Uri.parse(target)))target=LOGIN_URL;web.loadUrl(target);
 }
 static boolean allowed(Uri u){String h=u.getHost();return "https".equals(u.getScheme())&&h!=null&&(h.equals("bilibili.com")||h.endsWith(".bilibili.com")||h.equals("geetest.com")||h.endsWith(".geetest.com"));}
 void finishLogin(){if(verifying)return;verifying=true;AuthSession session=BiliApi.session;if(session==null){finish();return;}int epoch=session.epoch();for(String host:new String[]{"www.bilibili.com","passport.bilibili.com","account.bilibili.com","m.bilibili.com"})session.cookies.merge("https://"+host,CookieManager.getInstance().getCookie("https://"+host));new Thread(()->{try{org.json.JSONObject p=BiliApi.profile();if(epoch==session.epoch()){session.name=p.optString("uname","已登录");session.mid=p.optLong("mid");session.save();}}catch(Exception ignored){}ui.post(()->{setResult(RESULT_OK);finish();});},"thor-login-complete").start();}
 @Override public void onBackPressed(){finishLogin();}
 @Override protected void onDestroy(){web.stopLoading();web.destroy();CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();super.onDestroy();}
}
