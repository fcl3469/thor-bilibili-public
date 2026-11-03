package local.thor.bilichannels;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.util.*;

/** A full phone profile, or the same content in Thor's lower screen. */
final class RelationsPane extends FrameLayout {
 final MainActivity a;final FeatureUi f;final RelationsUi r;
 final LinearLayout root,header,body,bottom;final ScrollView profile;final LinearLayout profileBody;
 final ListView people;final Rows rows=new Rows();final HashMap<Integer,View> targets=new HashMap<>();
 String bound="";int revision=-1;float unit=1;boolean binding;int renderedFocus=-1;
 ImageView profileFace;TextView followingCount,fansCount;
 RelationsPane(MainActivity a,Context c){super(c);this.a=a;f=a.features;r=f.relations;setBackgroundColor(Color.WHITE);
  root=new LinearLayout(c);root.setOrientation(1);addView(root,new LayoutParams(-1,-1));
  header=column();root.addView(header,new LinearLayout.LayoutParams(-1,-2));
  body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
  profile=new ScrollView(c);profile.setFillViewport(true);profileBody=column();profile.addView(profileBody);body.addView(profile,new LinearLayout.LayoutParams(-1,-1));
  profile.setOnScrollChangeListener((v,x,y,ox,oy)->{if(!binding&&bound.equals("我的")&&bound.equals(f.title))f.menuScroll=y/unit;});
  people=new ListView(c);people.setDivider(null);people.setSelector(new ColorDrawable(0x0cfb7299));people.setAdapter(rows);people.setOnItemClickListener((p,v,index,id)->activate(index));
  people.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){}public void onScroll(AbsListView v,int first,int count,int total){if(binding||!bound.equals(f.title)||bound.equals("我的")||!isShown())return;View top=people.getChildAt(0);f.menuScroll=first*88-(top==null?0:top.getTop()/unit);if(count>0)r.visibleEnd(first+count);}});
  body.addView(people,new LinearLayout.LayoutParams(-1,-1));bottom=new LinearLayout(c);root.addView(bottom,new LinearLayout.LayoutParams(-1,-2));
 }
 int px(float n){return Math.round(n*unit);}
 LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(1);return v;}
 TextView text(String s,float size,int color){TextView t=new TextView(getContext());t.setText(s);t.setTextColor(color);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(size));t.setIncludeFontPadding(false);return t;}
 GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(px(radius));return d;}
 ImageView avatar(float size){ImageView image=new ImageView(getContext());image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(0xfff1f2f3,size));image.setClipToOutline(true);image.setLayoutParams(new LinearLayout.LayoutParams(px(size),px(size)));return image;}
 void image(String url,ImageView image){String thumbnail=url==null?"":url+(url.isEmpty()||url.contains("@")||url.contains("?")?"":"@160w_160h_1c.webp");if(!thumbnail.equals(image.getTag()))a.creators.image(thumbnail,image);}
 void activate(int index){if(index<0||index>=f.actions.size())return;r.controllerFocus=false;f.focus=f.lastMenuFocus=index;f.confirm();}
 void target(View view,int index,String description){targets.put(index,view);view.setContentDescription(description);view.setOnClickListener(v->activate(index));view.setFocusable(true);UiStyle.pressFeedback(view);}
 void line(LinearLayout parent){View v=new View(getContext());v.setBackgroundColor(0xfff1f2f3);parent.addView(v,new LinearLayout.LayoutParams(-1,Math.max(1,px(.6f))));}
 @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getSize(widthSpec);if(width>0&&unit!=width/480f){unit=width/480f;revision=-1;}bind();super.onMeasure(widthSpec,heightSpec);}
 void bind(){if(!r.active())return;boolean changed=!bound.equals(f.title),updated=revision!=r.revision;if(!changed&&!updated){highlight();return;}
  float anchor=f.menuScroll;binding=true;bound=f.title;revision=r.revision;targets.clear();header.removeAllViews();bottom.removeAllViews();
  boolean mine=bound.equals("我的");profile.setVisibility(mine?VISIBLE:GONE);people.setVisibility(mine?GONE:VISIBLE);
  if(mine){buildProfile();if(a.second==null)buildBottom();}else buildListHeader();
  rows.notifyDataSetChanged();binding=false;highlight();
  if(mine){post(()->{if(bound.equals("我的"))profile.scrollTo(0,px(anchor));});}
  else if(changed){int first=Math.max(0,(int)(anchor/88));people.setSelectionFromTop(first,-px(anchor-first*88));}
 }
 void highlight(){if(renderedFocus==f.focus&&!r.controllerFocus)return;renderedFocus=f.focus;for(Map.Entry<Integer,View> e:targets.entrySet())e.getValue().setBackground(r.controllerFocus&&e.getKey()==f.focus?shape(0xffffedf3,10):null);}
 void revealFocus(){if(bound.equals("我的")){View selected=targets.get(f.focus);if(selected!=null){selected.requestFocus();selected.requestRectangleOnScreen(new Rect(0,0,selected.getWidth(),selected.getHeight()),false);}}else{people.smoothScrollToPosition(f.focus);rows.notifyDataSetChanged();}}
 TextView labelButton(String label,int index){TextView t=text(label,16,a.ink);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(px(20),0,px(20),0);t.setMinHeight(px(57));target(t,index,label);return t;}
 void buildProfile(){profileBody.removeAllViews();profileBody.setPadding(px(20),px(12),px(20),px(24));
  LinearLayout toolbar=new LinearLayout(getContext());toolbar.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("我的",23,a.ink);title.setTypeface(null,1);title.setGravity(Gravity.CENTER_VERTICAL);toolbar.addView(title,new LinearLayout.LayoutParams(0,px(48),1));TextView settings=text("设置",15,a.muted);settings.setGravity(Gravity.CENTER);target(settings,11,"设置");toolbar.addView(settings,new LinearLayout.LayoutParams(px(65),px(48)));profileBody.addView(toolbar);
  LinearLayout account=new LinearLayout(getContext());account.setGravity(Gravity.CENTER_VERTICAL);account.setPadding(0,px(20),0,px(22));profileFace=avatar(72);profileFace.setContentDescription("我的头像");image(r.face,profileFace);account.addView(profileFace);
  LinearLayout identity=column();identity.setPadding(px(18),0,px(8),0);TextView name=text(r.owner>0?a.auth.name:"登录 B站",23,r.owner>0?a.accent:a.ink);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);identity.addView(name);TextView detail=text(r.owner>0?"个人空间 · 视频与动态":"登录后查看关注与粉丝",13,a.muted);detail.setPadding(0,px(12),0,0);identity.addView(detail);account.addView(identity,new LinearLayout.LayoutParams(0,-2,1));account.addView(text("›",25,a.muted));target(account,0,r.owner>0?"打开我的空间":"登录 B站");profileBody.addView(account);
  LinearLayout stats=new LinearLayout(getContext());stats.setPadding(0,0,0,px(22));String[] captions={"动态","关注","粉丝"};String[] counts={r.stats==null||r.stats.dynamic<0?"—":String.valueOf(r.stats.dynamic),r.count(false),r.count(true)};int[] actions={3,1,2};
  for(int i=0;i<3;i++){LinearLayout cell=column();cell.setGravity(Gravity.CENTER);TextView number=text(counts[i],23,a.ink);number.setGravity(Gravity.CENTER);number.setSingleLine();number.setEllipsize(TextUtils.TruncateAt.END);cell.addView(number);TextView caption=text(captions[i],14,a.muted);caption.setGravity(Gravity.CENTER);caption.setPadding(0,px(9),0,0);cell.addView(caption);target(cell,actions[i],"我的"+captions[i]+" · "+counts[i]);stats.addView(cell,new LinearLayout.LayoutParams(0,px(66),1));if(i==1)followingCount=number;if(i==2)fansCount=number;}profileBody.addView(stats);
  if(!f.notice.isEmpty()){TextView notice=text(f.notice,12,a.muted);notice.setPadding(0,0,0,px(16));if(r.owner>0)notice.setOnClickListener(v->r.loadStats(true));profileBody.addView(notice);}
  line(profileBody);LinearLayout shortcuts=new LinearLayout(getContext());shortcuts.setPadding(0,px(22),0,px(24));String[] names={"离线缓存","历史记录","我的收藏","稍后再看"};String[] icons={"download","history","star","later"};int[] indices={8,6,4,5};
  for(int i=0;i<4;i++){LinearLayout cell=column();cell.setGravity(Gravity.CENTER);cell.addView(new AppIcon(getContext(),icons[i],0xff36a6d7),new LinearLayout.LayoutParams(px(29),px(29)));TextView label=text(names[i],14,a.ink);label.setGravity(Gravity.CENTER);label.setPadding(0,px(12),0,0);cell.addView(label);target(cell,indices[i],names[i]);shortcuts.addView(cell,new LinearLayout.LayoutParams(0,px(70),1));}profileBody.addView(shortcuts);line(profileBody);
  TextView services=text("我的服务",18,a.ink);services.setTypeface(null,1);services.setPadding(0,px(24),0,px(10));profileBody.addView(services);
  for(String label:new String[]{"关注动态","缓存任务","打开本地视频","弹幕设置","账号与登录设置","刷新关注 / 粉丝数量"}){int index=f.labels.indexOf(label);if(index>=0)profileBody.addView(labelButton(label+"   ›",index));}
 }
 void buildBottom(){bottom.setBackgroundColor(Color.WHITE);bottom.setPadding(0,px(8),0,px(8));for(int i=0;i<a.tabs.length;i++){final int tab=i;LinearLayout cell=column();cell.setGravity(Gravity.CENTER);cell.addView(new AppIcon(getContext(),PhoneBrowseChrome.tabIcon(tab),i==MainActivity.MINE?a.accent:a.muted),new LinearLayout.LayoutParams(px(24),px(24)));TextView label=text(a.tabs[i],12,i==MainActivity.MINE?a.accent:a.muted);label.setGravity(Gravity.CENTER);label.setPadding(0,px(5),0,0);cell.addView(label);cell.setContentDescription(a.tabs[i]);cell.setOnClickListener(v->{if(tab!=MainActivity.MINE)a.navigateHome(tab,tab<MainActivity.MINE?-1:1,false);});bottom.addView(cell,new LinearLayout.LayoutParams(0,px(48),1));}}
 void buildListHeader(){boolean fans=bound.equals("我的粉丝");LinearLayout title=new LinearLayout(getContext());title.setGravity(Gravity.CENTER_VERTICAL);TextView back=text("‹",32,a.ink);back.setGravity(Gravity.CENTER);back.setContentDescription("返回我的");back.setOnClickListener(v->f.back());title.addView(back,new LinearLayout.LayoutParams(px(52),px(56)));title.addView(text("我的好友",21,a.ink),new LinearLayout.LayoutParams(0,-2,1));TextView refresh=text("刷新",14,a.muted);refresh.setGravity(Gravity.CENTER);refresh.setOnClickListener(v->r.load(fans,true));title.addView(refresh,new LinearLayout.LayoutParams(px(65),px(56)));header.addView(title);
  LinearLayout tabs=new LinearLayout(getContext());for(int i=0;i<2;i++){final boolean followers=i==1;LinearLayout cell=column();TextView label=text(i==0?"关注":"粉丝",18,fans==followers?a.accent:a.ink);label.setGravity(Gravity.CENTER);cell.addView(label,new LinearLayout.LayoutParams(-1,px(44)));View underline=new View(getContext());underline.setBackgroundColor(fans==followers?a.accent:Color.WHITE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(px(30),px(3));lp.gravity=Gravity.CENTER;cell.addView(underline,lp);cell.setContentDescription(i==0?"关注列表":"粉丝列表");cell.setOnClickListener(v->r.switchList(followers));tabs.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}header.addView(tabs);line(header);
  RelationModels.Pager model=r.model(fans);TextView total=text(bound+(model.total<0?"":"  "+model.total+" 人"),15,a.ink);total.setPadding(px(20),px(20),px(20),px(18));header.addView(total);
  if(!model.error.isEmpty()||model.page>0&&model.people.isEmpty()){TextView state=text(!model.error.isEmpty()?model.error+"，点击重试":fans?"还没有粉丝":"还没有关注 UP 主",13,a.muted);state.setPadding(px(20),0,px(20),px(14));state.setOnClickListener(v->r.load(fans,false));header.addView(state);}
 }
 final class Rows extends BaseAdapter {
  public int getCount(){return bound.equals("我的")?0:f.labels.size();}public Object getItem(int i){return f.labels.get(i);}public long getItemId(int i){RelationModels.Pager m=r.model(bound.equals("我的粉丝"));return i<m.people.size()?m.people.get(i).mid:-i-1;}
  public View getView(int index,View reuse,ViewGroup parent){PersonRow row=reuse instanceof PersonRow?(PersonRow)reuse:new PersonRow();RelationModels.Pager model=r.model(bound.equals("我的粉丝"));boolean person=index<model.people.size();row.setLayoutParams(new AbsListView.LayoutParams(-1,px(88)));row.setPadding(px(20),px(16),px(20),px(16));row.setBackgroundColor(r.controllerFocus&&f.focus==index?0xffffedf3:Color.WHITE);row.face.setVisibility(person?VISIBLE:GONE);row.meta.setVisibility(person?VISIBLE:GONE);row.badge.setVisibility(person?VISIBLE:GONE);row.face.setLayoutParams(new LinearLayout.LayoutParams(px(56),px(56)));row.content.setPadding(person?px(16):0,0,px(10),0);row.name.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(18));row.meta.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(13));row.meta.setPadding(0,px(10),0,0);row.badge.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(12));row.badge.setPadding(px(10),px(7),px(10),px(7));row.badge.setBackground(shape(0xfff1f2f3,18));
   row.name.setText(f.labels.get(index));if(person){RelationModels.Person p=model.people.get(index);image(p.face,row.face);row.face.setContentDescription(p.name+"的头像");row.meta.setText(p.sign.isEmpty()?"查看主页 · 视频 / 动态":p.sign);row.badge.setText(bound.equals("我的关注")?"已关注":"主页 ›");}else{row.face.setTag(null);row.face.setImageDrawable(null);}return row;}
 }
 final class PersonRow extends LinearLayout {
  final ImageView face;final LinearLayout content;final TextView name,meta,badge;
  PersonRow(){super(RelationsPane.this.getContext());setGravity(Gravity.CENTER_VERTICAL);face=avatar(56);addView(face);content=column();name=text("",18,a.ink);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);content.addView(name);meta=text("",13,a.muted);meta.setSingleLine(true);meta.setEllipsize(TextUtils.TruncateAt.END);content.addView(meta);addView(content,new LinearLayout.LayoutParams(0,-2,1));badge=text("",12,a.muted);addView(badge);setWillNotDraw(false);}
  @Override protected void onDraw(Canvas c){super.onDraw(c);Paint p=new Paint();p.setColor(0xfff1f2f3);c.drawRect(px(92),getHeight()-Math.max(1,px(.5f)),getWidth()-px(20),getHeight(),p);}
 }
}
