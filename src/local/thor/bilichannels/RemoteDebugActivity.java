package local.thor.bilichannels;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.util.*;
import java.util.concurrent.*;

/** Native chat client. Work and agent lifetime stay on the paired computer. */
public final class RemoteDebugActivity extends Activity {
    private static final int INK=Color.rgb(19,44,40), MUTED=Color.rgb(104,124,118),
            BG=Color.rgb(245,247,243), GREEN=Color.rgb(29,105,83), LINE=Color.rgb(220,229,223);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Map<String,Card> cards=new LinkedHashMap<>();
    private final List<String> sessionIds=new ArrayList<>(), sessionTitles=new ArrayList<>();
    private final List<Button> actions=new ArrayList<>();
    private SharedPreferences prefs;
    private String base="", cookie="", session="", active="", pageContext="";
    private CheckBox attachContext;
    private TextView status, model, error;
    private EditText input;
    private LinearLayout timeline;
    private ScrollView scroll;
    private Spinner sessions;
    private Button send, stop, authorize, update;
    private String authorizationUrl="";
    private boolean visible, busy, fetching, selecting, destroyed;
    private final Runnable poll=new Runnable(){ public void run(){ if(visible){refresh();ui.postDelayed(this,2000);} }};

    static void open(MainActivity a){
        StringBuilder context=new StringBuilder("设备：").append(android.os.Build.MODEL).append(" / Android ").append(android.os.Build.VERSION.RELEASE);
        context.append("\n页面：").append(a.playing?"视频播放":"浏览列表");
        Video video=a.current();
        if(video!=null){context.append("\n视频：").append(video.title).append("\n标识：").append(video.id);context.append("\n片源尺寸：").append(video.width).append("×").append(video.height);}
        context.append("\n插帧：").append(a.memcEnabled?MemcView.label(MemcView.selected(a)):"关闭");
        if(a.memcEnabled&&MemcView.RIFE.equals(MemcView.selected(a))){MainActivity.Tile tile=a.playerControls.tile();context.append(" / ").append(tile!=null&&tile.memcActive()?tile.memcView.statistics():"自动匹配");}
        if(a.features!=null)a.features.close();
        a.startActivity(new Intent(a,RemoteDebugActivity.class).putExtra("page_context",context.toString()));
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("remote_debug_connection",MODE_PRIVATE);
        base=prefs.getString("base","");cookie=prefs.getString("cookie","");session=prefs.getString("session","");
        Window w=getWindow();w.setDecorFitsSystemWindows(false);w.setStatusBarColor(BG);w.setNavigationBarColor(BG);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=column();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);return WindowInsets.CONSUMED;});
        LinearLayout header=row();header.setPadding(dp(18),dp(13),dp(14),dp(10));
        LinearLayout title=column();TextView tag=text("远程开发",11,MUTED);title.addView(tag);TextView name=text("与 Codex 对话",24,INK);name.setTypeface(null,Typeface.BOLD);title.addView(name);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button fresh=button("新对话");fresh.setOnClickListener(v->newSession());header.addView(fresh);
        Button config=button("连接");config.setOnClickListener(v->connectionDialog());header.addView(config);root.addView(header);
        LinearLayout info=row();info.setPadding(dp(18),0,dp(18),dp(5));status=text("正在连接电脑…",12,MUTED);model=text("",11,MUTED);info.addView(status,new LinearLayout.LayoutParams(0,-2,1));info.addView(model);root.addView(info);
        LinearLayout pairing=row();pairing.setPadding(dp(14),0,dp(14),dp(4));authorize=button("授权电脑连接");authorize.setOnClickListener(v->authorizeLink());pairing.addView(authorize);update=button("确认更新");update.setVisibility(View.GONE);update.setOnClickListener(v->RemoteDeviceService.continueInstall(this));pairing.addView(update);root.addView(pairing);
        sessions=new Spinner(this);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(42));sp.setMargins(dp(10),0,dp(10),0);root.addView(sessions,sp);
        sessions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int index,long id){if(!selecting&&index<sessionIds.size()&&!session.equals(sessionIds.get(index))){session=sessionIds.get(index);prefs.edit().putString("session",session).apply();cards.clear();timeline.removeAllViews();refresh();}}});
        HorizontalScrollView tools=new HorizontalScrollView(this);tools.setHorizontalScrollBarEnabled(false);LinearLayout bar=row();bar.setPadding(dp(12),dp(4),dp(12),dp(8));
        String[][] options={{"device","手机状态"},{"build","编译验证"},{"install","安装新版"},{"smoke","启动检查"},{"rife24","24→48 静音自测"},{"rife30","30→60 静音自测"},{"screenshot","截图"},{"logs","运行日志"}};
        for(String[] option:options){Button b=button(option[1]);b.setOnClickListener(v->start(option[0],""));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,dp(38));bp.setMargins(0,0,dp(6),0);bar.addView(b,bp);actions.add(b);}tools.addView(bar);root.addView(tools);
        scroll=new ScrollView(this);scroll.setFillViewport(true);timeline=column();timeline.setPadding(dp(14),dp(4),dp(14),dp(12));scroll.addView(timeline);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        error=text("",12,Color.rgb(161,54,54));error.setPadding(dp(16),0,dp(16),0);error.setVisibility(View.GONE);root.addView(error);
        LinearLayout composer=column();composer.setPadding(dp(14),dp(10),dp(14),dp(10));composer.setBackgroundColor(Color.WHITE);
        input=new EditText(this);input.setTextSize(15);input.setTextColor(INK);input.setHint("告诉 Codex 你想修改或排查什么…");input.setMinLines(2);input.setMaxLines(6);input.setGravity(Gravity.TOP);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setPadding(dp(12),dp(10),dp(12),dp(10));input.setBackground(shape(Color.WHITE,12,LINE));input.setText(prefs.getString("draft",""));composer.addView(input,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout buttons=row();buttons.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);TextView hint=text("任务在电脑运行，可随时切后台",11,MUTED);buttons.addView(hint,new LinearLayout.LayoutParams(0,-2,1));stop=button("停止");stop.setTextColor(Color.rgb(161,54,54));stop.setVisibility(View.GONE);stop.setOnClickListener(v->stopJob());buttons.addView(stop);send=button("发送");send.setBackground(shape(GREEN,12,GREEN));send.setTextColor(Color.WHITE);send.setOnClickListener(v->start("chat",input.getText().toString()));LinearLayout.LayoutParams sb=new LinearLayout.LayoutParams(dp(76),dp(42));sb.setMargins(dp(8),dp(6),0,0);buttons.addView(send,sb);composer.addView(buttons);root.addView(composer);
        attachContext=new CheckBox(this);attachContext.setText("附带当前页面信息");attachContext.setTextSize(12);attachContext.setTextColor(MUTED);attachContext.setChecked(true);composer.addView(attachContext,0);
        pageContext=getIntent().getStringExtra("page_context");if(pageContext==null)pageContext=prefs.getString("page_context","");if(!pageContext.isEmpty())prefs.edit().putString("page_context",pageContext).apply();attachContext.setVisibility(pageContext.isEmpty()?View.GONE:View.VISIBLE);
        setContentView(root);receiveSetup(getIntent());
        if(base.isEmpty()&&getIntent().getStringExtra("key")==null&&getIntent().getBooleanExtra("configured",false)==false)ui.post(this::connectionDialog);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);receiveSetup(intent);}
    private void receiveSetup(Intent intent){String lan=intent.getStringExtra("lan_address");if(lan!=null){intent.removeExtra("lan_address");prefs.edit().putString("lan_address",lan.trim()).apply();String pending=prefs.getString("pending_key","");if(!pending.isEmpty())login(base,pending);}if(intent.getBooleanExtra("install_pending",false)){intent.removeExtra("install_pending");ui.post(()->RemoteDeviceService.continueInstall(this));}String context=intent.getStringExtra("page_context");if(context!=null){pageContext=context;prefs.edit().putString("page_context",context).apply();if(attachContext!=null)attachContext.setVisibility(context.isEmpty()?View.GONE:View.VISIBLE);}String host=intent.getStringExtra("server"),key=intent.getStringExtra("key");if(host!=null&&key!=null){intent.removeExtra("server");intent.removeExtra("key");intent.putExtra("configured",true);login(host,key);}}
    @Override protected void onResume(){super.onResume();visible=true;RemoteDeviceService.foreground(this);RemoteDeviceService.maybeStart(this);if(!prefs.getString("install_command","").isEmpty()){update.setVisibility(View.VISIBLE);if(getPackageManager().canRequestPackageInstalls())RemoteDeviceService.continueInstall(this);}ui.removeCallbacks(poll);ui.post(poll);}
    @Override protected void onPause(){super.onPause();visible=false;RemoteDeviceService.background(this);ui.removeCallbacks(poll);if(input!=null)prefs.edit().putString("draft",input.getText().toString()).apply();}
    @Override protected void onDestroy(){destroyed=true;ui.removeCallbacks(poll);network.shutdown();super.onDestroy();}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));g.setStroke(dp(1),stroke);return g;}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(INK);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(12),dp(5),dp(12),dp(5));b.setBackground(shape(Color.WHITE,10,LINE));return b;}
    private void fail(Exception e){if(destroyed)return;if(e instanceof ThorLink.NeedsAuthorization){authorizationUrl=((ThorLink.NeedsAuthorization)e).url;authorize.setVisibility(View.VISIBLE);}error.setText(e.getMessage()==null?e.toString():e.getMessage());error.setVisibility(View.VISIBLE);}
    private void clearError(){error.setVisibility(View.GONE);}
    private void task(Runnable r){if(!destroyed)network.execute(r);}
    private JSONObject object(String key,Object value)throws JSONException{return new JSONObject().put(key,value);}
    private HttpURLConnection connection(String path)throws IOException{
        URL url=new URL(base+path);if(!"https".equals(url.getProtocol()))throw new IOException("请使用 Tailscale HTTPS 地址");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(25000);c.setRequestProperty("X-Thor-Client","mobile");if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);return c;
    }
    private byte[] read(InputStream in)throws IOException{try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=stream.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private Object api(String path,JSONObject body)throws Exception{
        JSONObject response=ThorLink.request(this,base,path,body,cookie);String raw=new String(ThorLink.bytes(response),"UTF-8");Object value=new JSONTokener(raw).nextValue();int code=response.getInt("status");
        if(code>=400)throw new IOException(value instanceof JSONObject?((JSONObject)value).optString("error","电脑连接失败"):"电脑连接失败");
        String set=response.optString("cookie","");if(!set.isEmpty()){cookie=set.split(";",2)[0];prefs.edit().putString("cookie",cookie).remove("pending_key").apply();RemoteDeviceService.maybeStart(this);}
        ui.post(()->authorize.setVisibility(View.GONE));return value;
    }
    private void authorizeLink(){task(()->{try{
        JSONObject state=ThorLink.status(this);String backend=state.optString("state"),url=state.optString("auth_url","");
        if("Running".equals(backend)){ui.post(()->{authorize.setVisibility(View.GONE);String key=prefs.getString("pending_key","");if(!key.isEmpty())login(base,key);else refresh();});return;}
        if(url.isEmpty())throw new IOException("内置连接状态："+backend+"，请稍后重试");
        URI parsed=new URI(url);if(!"https".equals(parsed.getScheme())||!("login.tailscale.com".equals(parsed.getHost())||"controlplane.tailscale.com".equals(parsed.getHost())))throw new IOException("授权地址无效");
        ui.post(()->showAuthorization(url,backend));
    }catch(Exception e){ui.post(()->fail(e));}});}
    private void showAuthorization(String url,String backend){
        if(destroyed)return;LinearLayout panel=column();TextView loading=text("正在打开官方授权页…",13,MUTED);loading.setPadding(dp(18),dp(10),dp(18),dp(10));panel.addView(loading);
        android.webkit.WebView web=new android.webkit.WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        panel.addView(web,new LinearLayout.LayoutParams(-1,dp(360)));
        web.setWebViewClient(new android.webkit.WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view,android.webkit.WebResourceRequest request){return !"https".equals(request.getUrl().getScheme());}
            @Override public void onPageFinished(android.webkit.WebView view,String address){if(loading.getTag()==null)loading.setText("登录原有账号并确认连接；完成后点“已授权，返回”。");}
            @Override public void onReceivedError(android.webkit.WebView view,android.webkit.WebResourceRequest request,android.webkit.WebResourceError error){if(request.isForMainFrame()){loading.setTag(Boolean.TRUE);loading.setText("授权页加载失败（"+error.getErrorCode()+"）。可点“浏览器授权”，或在电脑完成同一次授权后返回。 ");}}
            @Override public void onReceivedHttpError(android.webkit.WebView view,android.webkit.WebResourceRequest request,android.webkit.WebResourceResponse response){if(request.isForMainFrame()){loading.setTag(Boolean.TRUE);loading.setText("授权服务返回 HTTP "+response.getStatusCode()+"，请稍后重试或使用浏览器授权。");}}
        });
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("授权 Thor 安全连接").setView(panel)
                .setPositiveButton("已授权，返回",(d,w)->authorizeLink())
                .setNeutralButton("浏览器授权",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(android.content.ActivityNotFoundException e){fail(new IOException("手机没有可用浏览器，请在电脑完成授权"));}})
                .setNegativeButton("稍后",null).create();
        dialog.setOnDismissListener(d->{web.stopLoading();web.destroy();});dialog.show();web.loadUrl(url);
    }
    private void connectionDialog(){LinearLayout l=column();l.setPadding(dp(22),dp(4),dp(22),0);EditText host=new EditText(this);host.setText(base);host.setHint("https://电脑名称.ts.net:8443");host.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);host.setTextSize(14);l.addView(host);EditText key=new EditText(this);key.setHint("电脑连接密钥");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setTextSize(14);l.addView(key);EditText lan=new EditText(this);lan.setText(prefs.getString("lan_address",""));lan.setHint("同一 Wi-Fi 备用地址（可选）：192.168.x.x:8767");lan.setTextSize(13);lan.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);l.addView(lan);TextView info=text("连接由 Thor 内置管理。首次授权后，可直接在这里对话、测试和更新。",12,MUTED);info.setPadding(0,dp(12),0,0);l.addView(info);new AlertDialog.Builder(this).setTitle("连接开发电脑").setView(l).setNegativeButton("取消",null).setPositiveButton("连接",(d,w)->{prefs.edit().putString("lan_address",lan.getText().toString().trim()).apply();String entered=key.getText().toString();if(entered.isEmpty()&&host.getText().toString().trim().equals(base)&&!cookie.isEmpty())refresh();else login(host.getText().toString(),entered);}).show();}
    private void login(String host,String key){task(()->{try{URL parsed=new URL(host.trim());if(!"https".equals(parsed.getProtocol())||parsed.getUserInfo()!=null||parsed.getQuery()!=null||parsed.getRef()!=null||!(parsed.getPath().isEmpty()||parsed.getPath().equals("/")))throw new IOException("请输入完整的 HTTPS 电脑地址，不带路径或参数");String normalized=host.trim().replaceAll("/+$","");if(!normalized.equals(base)){session="";prefs.edit().remove("session").apply();}base=normalized;cookie="";prefs.edit().putString("base",base).putString("pending_key",key).remove("cookie").apply();api("/api/login",object("key",key));prefs.edit().putString("base",base).apply();ui.post(()->{clearError();cards.clear();timeline.removeAllViews();sessionIds.clear();sessionTitles.clear();refresh();});}catch(Exception e){ui.post(()->fail(e));}});}
    private void newSession(){if(base.isEmpty()||cookie.isEmpty()){connectionDialog();return;}task(()->{try{String id=((JSONObject)api("/api/sessions",new JSONObject())).getString("id");ui.post(()->{session=id;prefs.edit().putString("session",session).apply();cards.clear();timeline.removeAllViews();refresh();});}catch(Exception e){ui.post(()->fail(e));}});}
    private void start(String kind,String prompt){final String message=kind.equals("chat")&&attachContext.isChecked()&&!pageContext.isEmpty()?prompt+"\n\n[当前 Thor 页面信息]\n"+pageContext:prompt;if(base.isEmpty()||cookie.isEmpty()){connectionDialog();return;}if(busy)return;if(kind.equals("chat")&&prompt.trim().isEmpty())return;busy=true;setBusy(true);task(()->{try{JSONObject request=new JSONObject().put("kind",kind).put("session",session).put("prompt",message);api("/api/jobs",request);ui.post(()->{if(kind.equals("chat")){input.setText("");prefs.edit().remove("draft").apply();}clearError();refresh();scroll.postDelayed(()->scroll.fullScroll(View.FOCUS_DOWN),250);});}catch(Exception e){ui.post(()->{busy=false;setBusy(false);fail(e);});}});}
    private void stopJob(){String job=active;task(()->{try{api("/api/stop",object("job",job));ui.post(this::refresh);}catch(Exception e){ui.post(()->fail(e));}});}
    private void setBusy(boolean b){send.setEnabled(!b);for(Button action:actions)action.setEnabled(!b);stop.setVisibility(b?View.VISIBLE:View.GONE);}
    private void refresh(){if(fetching||base.isEmpty()||cookie.isEmpty()||destroyed)return;fetching=true;final String requested=session;final Map<String,Long> cursors=new HashMap<>();for(Card c:cards.values())cursors.put(c.id,c.after);
        task(()->{try{JSONObject state=(JSONObject)api("/api/state",null);JSONArray ss=state.getJSONArray("sessions");if(ss.length()==0){api("/api/sessions",new JSONObject());ui.post(()->{fetching=false;refresh();});return;}String chosen=requested;boolean found=false;for(int i=0;i<ss.length();i++)if(ss.getJSONObject(i).getString("id").equals(chosen))found=true;if(!found)chosen=ss.getJSONObject(0).getString("id");JSONArray jobs=state.getJSONArray("jobs");Map<String,JSONArray> events=new HashMap<>();for(int i=jobs.length()-1;i>=0;i--){JSONObject j=jobs.getJSONObject(i);if(j.getString("session").equals(chosen)){String id=j.getString("id");events.put(id,(JSONArray)api("/api/events?job="+id+"&after="+cursors.getOrDefault(id,0L),null));}}
            String selected=chosen;ui.post(()->{fetching=false;if(destroyed)return;if(!session.equals(requested)){refresh();return;}try{applyState(state,selected,events);clearError();}catch(Exception e){fail(e);}});
        }catch(Exception e){ui.post(()->{fetching=false;status.setText("连接中断，正在重连…");fail(e);});}});
    }
    private void applyState(JSONObject state,String selected,Map<String,JSONArray> events)throws JSONException{
        boolean changed=!session.equals(selected);session=selected;prefs.edit().putString("session",session).apply();if(changed){cards.clear();timeline.removeAllViews();}
        JSONArray ss=state.getJSONArray("sessions");List<String> ids=new ArrayList<>(),titles=new ArrayList<>();for(int i=0;i<ss.length();i++){JSONObject s=ss.getJSONObject(i);ids.add(s.getString("id"));titles.add(s.getString("title"));}
        selecting=true;if(!ids.equals(sessionIds)||!titles.equals(sessionTitles)){sessionIds.clear();sessionIds.addAll(ids);sessionTitles.clear();sessionTitles.addAll(titles);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,sessionTitles);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);sessions.setAdapter(adapter);}sessions.setSelection(sessionIds.indexOf(session),false);ui.post(()->selecting=false);
        active=state.isNull("active")?"":state.optString("active","");busy=!active.isEmpty();setBusy(busy);status.setText(busy?"电脑正在执行任务":"电脑已连接 · "+("lan-https".equals(prefs.getString("transport",""))?"局域网安全直连":"Thor 内置私网"));model.setText(state.optString("model","Codex"));
        boolean nearBottom=scroll.getChildAt(0).getHeight()-scroll.getScrollY()-scroll.getHeight()<dp(120);JSONArray jobs=state.getJSONArray("jobs");for(int i=jobs.length()-1;i>=0;i--){JSONObject j=jobs.getJSONObject(i);if(!j.getString("session").equals(session))continue;String id=j.getString("id");Card c=cards.get(id);if(c==null){c=new Card(j);cards.put(id,c);timeline.addView(c.root);}c.state.setText(stateName(j.getString("status")));JSONArray es=events.get(id);if(es!=null)for(int k=0;k<es.length();k++)c.event(es.getJSONObject(k));}
        if(nearBottom)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private String stateName(String s){switch(s){case "running":return "运行中";case "done":return "完成";case "failed":return "失败";case "cancelled":return "已停止";case "interrupted":return "服务重启中断";default:return s;}}
    private String jobName(String s){switch(s){case "chat":return "Codex 对话";case "build":return "编译与验证";case "install":return "安装新版";case "smoke":return "启动检查";case "rife24":return "24→48 静音自测";case "rife30":return "30→60 静音自测";case "logs":return "运行日志";case "device":return "手机状态";case "screenshot":return "手机截图";default:return s;}}
    private final class Card{
        final String id;final LinearLayout root;final TextView state,log;long after=0;StringBuilder logs=new StringBuilder();
        Card(JSONObject job)throws JSONException{id=job.getString("id");root=column();root.setPadding(dp(13),dp(12),dp(13),dp(12));root.setBackground(shape(Color.WHITE,15,LINE));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,0,0,dp(12));root.setLayoutParams(rp);LinearLayout heading=row();TextView title=text(jobName(job.getString("kind")),12,MUTED);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));state=text("",12,MUTED);heading.addView(state);root.addView(heading);String p=job.optString("prompt","");if(!p.isEmpty()){TextView t=text(p,15,INK);t.setTextIsSelectable(true);t.setPadding(dp(10),dp(9),dp(10),dp(9));t.setBackground(shape(Color.rgb(238,244,238),10,Color.rgb(238,244,238)));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.setMargins(0,dp(10),0,dp(10));root.addView(t,pp);}TextView toggle=text("命令和输出 ▸",11,MUTED);toggle.setPadding(0,dp(8),0,dp(8));root.addView(toggle);log=text("",10,MUTED);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);log.setVisibility(View.GONE);root.addView(log);toggle.setOnClickListener(v->{boolean show=log.getVisibility()!=View.VISIBLE;log.setVisibility(show?View.VISIBLE:View.GONE);toggle.setText(show?"命令和输出 ▾":"命令和输出 ▸");});}
        void event(JSONObject e)throws JSONException{long eventId=e.getLong("id");if(eventId<=after)return;String kind=e.getString("kind"),value=e.getString("text");if(kind.equals("image")){ImageView image=new ImageView(RemoteDebugActivity.this);image.setAdjustViewBounds(true);image.setMaxHeight(dp(520));root.addView(image,new LinearLayout.LayoutParams(-1,-2));loadImage(value,image);}else if(kind.equals("assistant")||kind.equals("result")||kind.equals("error")||kind.equals("progress")){TextView t=text(value,kind.equals("progress")?11:15,kind.equals("error")?Color.rgb(161,54,54):kind.equals("progress")?MUTED:INK);t.setTextIsSelectable(true);t.setPadding(0,dp(7),0,dp(7));t.setLineSpacing(dp(3),1);root.addView(t);}else{logs.append(value).append('\n');if(logs.length()>100000)logs.delete(0,logs.length()-100000);log.setText(logs.toString());}after=eventId;}
    }
    private void loadImage(String path,ImageView target){if(!path.matches("/api/image/[a-f0-9]{32}"))return;task(()->{try{JSONObject response=ThorLink.request(this,base,path,null,cookie);if(response.getInt("status")!=200)throw new IOException("截图加载失败");byte[] data=ThorLink.bytes(response);BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=2;Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length,opts);ui.post(()->{if(!destroyed)target.setImageBitmap(b);});}catch(Exception e){ui.post(()->fail(e));}});}
}
