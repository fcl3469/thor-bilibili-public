package local.thor.bilichannels;
import android.content.*;import android.graphics.Color;import android.graphics.Typeface;import android.graphics.drawable.GradientDrawable;import android.graphics.drawable.StateListDrawable;import android.view.*;import android.widget.*;import java.io.*;import java.text.SimpleDateFormat;import java.util.*;
/** Native local, torrent, cloud, and recent-video pages inside the Local column. */
final class LocalLibraryPane extends LinearLayout {
 final MainActivity a;private final ListView list;private final TextView empty,count,setting;final TextView cloudNotice;private final FrameLayout body;private final LinearLayout actionsRow;private final Button[] actions=new Button[3],filters=new Button[4];XunleiCloudPane cloudPane;
 private List<Entry> rows=new ArrayList<>();private final Rows adapter=new Rows();private int filter,zone=2,actionIndex,selected;private String signature="";private boolean identifying;
 private static final class Entry {final TorrentStore.Record torrent;final XunleiLibrary.Record cloud;Entry(TorrentStore.Record r){torrent=r;cloud=null;}Entry(XunleiLibrary.Record r){cloud=r;torrent=null;}String id(){return cloud==null?torrent.id:cloud.key;}long when(){return cloud==null?torrent.lastPlayed:cloud.played;}}
 LocalLibraryPane(MainActivity activity,Context context){
  super(context);a=activity;setOrientation(VERTICAL);setBackgroundColor(0xfff6f7f8);setPadding(dp(16),dp(8),dp(16),dp(8));
  LinearLayout title=new LinearLayout(context);title.setGravity(Gravity.CENTER_VERTICAL);TextView heading=label("本地",23,a.ink);heading.setTypeface(Typeface.DEFAULT_BOLD);title.addView(heading,new LinearLayout.LayoutParams(0,dp(40),1));count=label("",13,a.muted);title.addView(count);addView(title);
  LinearLayout buttons=new LinearLayout(context);actionsRow=buttons;String[] names={"本机视频","输入磁力","粘贴磁力"},descriptions={"打开本机视频","输入磁力链接","粘贴磁力链接"};Runnable[] run={a::pickLocalVideo,a.features.torrents::input,a.features.torrents::paste};
  for(int i=0;i<3;i++){final int at=i;actions[i]=button(names[i]);actions[i].setContentDescription(descriptions[i]);actions[i].setOnClickListener(v->run[at].run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(i==0?0:4),dp(6),dp(i==2?0:4),dp(12));buttons.addView(actions[i],lp);}addView(buttons);
  LinearLayout tabs=new LinearLayout(context);String[] labels={"本机/磁力","迅雷云盘","最近播放","永久缓存"};for(int i=0;i<labels.length;i++){final int at=i;filters[i]=button(labels[i]);filters[i].setContentDescription(labels[i]);filters[i].setOnClickListener(v->{a.localSection=at;filter=at;a.getPreferences(0).edit().putInt("localSection",at).apply();selected=0;signature="";bind();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1);lp.setMargins(dp(i==0?0:3),0,dp(i==3?0:3),dp(10));tabs.addView(filters[i],lp);}addView(tabs);
  cloudNotice=label("",14,a.muted);cloudNotice.setContentDescription("云盘播放状态");cloudNotice.setPadding(dp(4),dp(2),dp(4),dp(10));cloudNotice.setVisibility(GONE);addView(cloudNotice);
  body=new FrameLayout(context);list=new ListView(context);list.setDivider(new android.graphics.drawable.ColorDrawable(UiStyle.CANVAS));list.setDividerHeight(dp(12));list.setCacheColorHint(Color.TRANSPARENT);list.setSelector(UiStyle.ripple(context,Color.TRANSPARENT,UiStyle.RADIUS_CONTROL));list.setAdapter(adapter);list.setOnItemClickListener((p,v,position,id)->{selected=position;openSelected();});body.addView(list,new FrameLayout.LayoutParams(-1,-1));empty=label("打开本机视频，或粘贴磁力链接开始播放／缓存",15,a.muted);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(20),dp(20),dp(20));body.addView(empty,new FrameLayout.LayoutParams(-1,-1));addView(body,new LinearLayout.LayoutParams(-1,0,1));
  setting=label("",13,a.muted);setting.setMinHeight(dp(44));setting.setGravity(Gravity.CENTER_VERTICAL);setting.setPadding(dp(4),dp(8),dp(4),dp(4));setting.setOnClickListener(v->{if(filter!=1){MagnetClipboardWatcher.enabled(a,!MagnetClipboardWatcher.enabled(a));bind();}});addView(setting);bind();
 }
 private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private TextView label(String value,int size,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
 private GradientDrawable surface(int color){return UiStyle.shape(getContext(),color,UiStyle.RADIUS_CONTROL);}
 private void buttonSurface(Button b,boolean selected){StateListDrawable background=new StateListDrawable();background.addState(new int[]{android.R.attr.state_pressed},surface(0xffffdce8));background.addState(new int[]{android.R.attr.state_focused},surface(0xffffdce8));background.addState(new int[]{},surface(selected?0xffffe8f0:Color.WHITE));b.setBackground(background);}
 private Button button(String value){Button b=new Button(getContext());b.setText(value);b.setTextSize(14);b.setSingleLine(true);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setGravity(Gravity.CENTER);b.setIncludeFontPadding(false);b.setPadding(dp(6),0,dp(6),0);b.setTextColor(a.ink);buttonSurface(b,false);UiStyle.pressFeedback(b);return b;}
 void bind(){
  filter=Math.max(0,Math.min(filters.length-1,a.localSection));
  actionsRow.setVisibility(filter==1?GONE:VISIBLE);if(filter==1&&zone==0)zone=1;
  if(filter==1){if(cloudPane==null){cloudPane=new XunleiCloudPane(a);body.addView(cloudPane,new FrameLayout.LayoutParams(-1,-1));}cloudPane.setVisibility(VISIBLE);list.setVisibility(GONE);empty.setVisibility(GONE);a.cloud.attach(cloudPane);count.setText("");}
  else{
   if(cloudPane!=null){cloudPane.rememberScroll();cloudPane.setVisibility(GONE);a.cloud.detach(cloudPane);}list.setVisibility(VISIBLE);
   try{ArrayList<Entry> next=new ArrayList<>();long now=System.currentTimeMillis();for(TorrentStore.Record r:TorrentRuntime.store(a).entries(now))if(filter!=3||r.permanent)next.add(new Entry(r));if(filter==2)for(XunleiLibrary.Record r:a.cloud.recent())next.add(new Entry(r));next.sort((x,y)->Long.compare(y.when(),x.when()));
    StringBuilder key=new StringBuilder().append(filter);for(Entry e:next)key.append(e.id()).append('/').append(e.when()).append('/').append(e.cloud==null?e.torrent.downloaded/65536:e.cloud.position/1000).append('/').append(e.cloud==null?e.torrent.state:"").append(';');
    if(!key.toString().equals(signature)){String previous=rows.isEmpty()?"":rows.get(Math.min(selected,rows.size()-1)).id();rows=next;signature=key.toString();for(int i=0;i<rows.size();i++)if(rows.get(i).id().equals(previous))selected=i;selected=Math.max(0,Math.min(selected,rows.size()-1));adapter.notifyDataSetChanged();}
    empty.setText(filter==2?"播放过的本机、磁力和云盘视频会显示在这里":filter==3?"暂无永久缓存":"打开本机视频，或粘贴磁力链接开始播放／缓存");empty.setVisibility(rows.isEmpty()?VISIBLE:GONE);count.setText(rows.size()+" 条记录");
   }catch(IOException error){empty.setText("本地列表读取失败");empty.setVisibility(VISIBLE);}
   if(filter==2&&a.cloud.account().isEmpty()&&!identifying){identifying=true;a.cloud.client.identity((data,error)->{if(a.dead)return;if(error==null&&data.optBoolean("logged_in")){signature="";bind();}});}
  }
  for(int i=0;i<filters.length;i++){filters[i].setTextColor(i==filter?a.accent:a.muted);filters[i].setTypeface(i==filter?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);buttonSurface(filters[i],i==filter);}
  setting.setVisibility(filter==1?GONE:VISIBLE);setting.setText("自动识别剪贴板磁力 · "+(MagnetClipboardWatcher.enabled(a)?"已开启":"已关闭"));
  cloudStatusChanged();removeCallbacks(refresh);if(a.active&&a.localBrowsing()&&filter!=1)postDelayed(refresh,1200);
 }
 void cloudStatusChanged(){if(cloudNotice==null)return;String message=a.cloud.playbackPending?"正在获取云盘播放地址…":a.cloud.playbackError.isEmpty()?"":a.cloud.playbackError+" · 点击视频记录重试";cloudNotice.setText(message);cloudNotice.setVisibility(filter==2&&!message.isEmpty()?VISIBLE:GONE);}
 boolean back(){return filter==1&&a.cloud.back();}
 private void openSelected(){if(rows.isEmpty()){if(filter==0)a.features.torrents.input();return;}Entry e=rows.get(Math.min(selected,rows.size()-1));if(e.cloud!=null)a.cloud.playRecord(e.cloud);else a.features.torrents.details(e.torrent.id);}
 boolean key(KeyEvent e){
  int k=e.getKeyCode();boolean yes=k==KeyEvent.KEYCODE_ENTER||k==KeyEvent.KEYCODE_DPAD_CENTER||k==(a.abSwap?KeyEvent.KEYCODE_BUTTON_B:KeyEvent.KEYCODE_BUTTON_A);
  if(!yes&&k!=KeyEvent.KEYCODE_DPAD_UP&&k!=KeyEvent.KEYCODE_DPAD_DOWN&&k!=KeyEvent.KEYCODE_DPAD_LEFT&&k!=KeyEvent.KEYCODE_DPAD_RIGHT)return false;
  if(filter==1&&zone==2&&cloudPane!=null){if(k==KeyEvent.KEYCODE_DPAD_UP&&cloudPane.grid.getSelectedItemPosition()<=0){if(e.getAction()==KeyEvent.ACTION_DOWN){zone=1;filters[filter].requestFocus();}return true;}if(yes&&e.getAction()==KeyEvent.ACTION_DOWN){cloudPane.openSelected();return true;}return cloudPane.key(e);}
  if(e.getAction()!=KeyEvent.ACTION_DOWN)return true;if(yes){if(zone==0)actions[actionIndex].performClick();else if(zone==1){zone=2;if(filter==1&&cloudPane!=null){cloudPane.grid.requestFocusFromTouch();cloudPane.grid.setSelection(Math.max(0,cloudPane.grid.getSelectedItemPosition()));}else list.setSelection(selected);}else openSelected();return true;}
  if(k==KeyEvent.KEYCODE_DPAD_UP){if(zone==2&&selected>0)selected--;else zone=Math.max(filter==1?1:0,zone-1);}else if(k==KeyEvent.KEYCODE_DPAD_DOWN){if(zone<2)zone++;else selected=Math.min(Math.max(0,rows.size()-1),selected+1);}else{int d=k==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;if(zone==0)actionIndex=Math.floorMod(actionIndex+d,actions.length);else{filter=a.localSection=Math.floorMod(filter+d,filters.length);signature="";bind();}}
  if(zone==0)actions[actionIndex].requestFocus();else if(zone==1)filters[filter].requestFocus();else if(filter==1&&cloudPane!=null)cloudPane.grid.requestFocus();else{list.requestFocus();list.setSelection(selected);}return true;
 }
 private final Runnable refresh=new Runnable(){public void run(){if(a.active&&a.localBrowsing())bind();}};
 @Override protected void onAttachedToWindow(){super.onAttachedToWindow();removeCallbacks(refresh);if(filter!=1)postDelayed(refresh,1000);}
 @Override protected void onDetachedFromWindow(){removeCallbacks(refresh);super.onDetachedFromWindow();}
 private final class Rows extends BaseAdapter {
  public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return rows.get(p).id().hashCode();}
  public View getView(int position,View recycled,ViewGroup parent){
   LinearLayout row;TextView name,meta;ProgressBar progress;
   if(recycled==null){row=new LinearLayout(getContext());row.setOrientation(VERTICAL);row.setPadding(dp(16),dp(16),dp(16),dp(16));row.setBackground(surface(Color.WHITE));name=label("",16,a.ink);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setLineSpacing(dp(3),1.08f);row.addView(name);meta=label("",13,a.muted);meta.setLineSpacing(dp(4),1.08f);meta.setPadding(0,dp(10),0,dp(10));row.addView(meta);progress=new ProgressBar(getContext(),null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);row.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));row.setTag(new Object[]{name,meta,progress});}else row=(LinearLayout)recycled;
   Object[] views=(Object[])row.getTag();name=(TextView)views[0];meta=(TextView)views[1];progress=(ProgressBar)views[2];Entry e=rows.get(position);String when=new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(e.when()));
   if(e.cloud!=null){XunleiLibrary.Record r=e.cloud;name.setText(r.item.name);meta.setText("迅雷云盘 · "+when+"\n继续播放 "+MainActivity.clockText((int)r.position)+" / "+MainActivity.clockText((int)r.duration));progress.setVisibility(r.duration>0?VISIBLE:GONE);progress.setProgress(r.duration>0?(int)Math.min(1000,1000.0*r.position/r.duration):0);row.setContentDescription("继续播放云盘视频："+r.item.name);}
   else{TorrentStore.Record r=e.torrent;name.setText(r.name);String state=r.local?"本机文件":r.complete?"已完成 · 可离线播放":r.state.equals("downloading")?"正在缓存":r.state.equals("streaming")?"在线播放中":r.error.isEmpty()?"已暂停 · 可断点继续":"已暂停 · "+r.error;meta.setText((r.permanent?"永久缓存":"最近播放")+" · "+when+"\n"+state+(r.local?"":" · "+TorrentRuntime.human(r.downloaded)+" / "+TorrentRuntime.human(r.size)));progress.setVisibility(r.local?GONE:VISIBLE);progress.setProgress(r.size>0?(int)Math.min(1000,1000.0*r.downloaded/r.size):0);row.setContentDescription(r.name);}
   return row;
  }
 }
}
