package local.thor.bilichannels;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Read-only recommendations under the intro; never auto-plays a recommendation. */
final class RelatedPane extends LinearLayout {
 interface Source {List<Video> load(Video video)throws Exception;}
 Source source=BiliApi::related;
 final MainActivity a;
 final LinkedHashMap<String,List<Video>> cache=new LinkedHashMap<String,List<Video>>(12,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,List<Video>> entry){return size()>12;}};
 final ArrayList<Video> items=new ArrayList<>();final ArrayList<View> rows=new ArrayList<>();
 Video target;int generation,epoch=-1;boolean busy,loaded;String error="";float unit=1;
 final Runnable request=()->load();
 RelatedPane(MainActivity activity,Context context){super(context);a=activity;setOrientation(VERTICAL);}
 int px(float value){return Math.max(1,Math.round(value*unit));}
 TextView text(String value,float size,int color,boolean bold){TextView view=new TextView(getContext());view.setText(value);view.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(size));view.setTextColor(color);view.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",0));view.setIncludeFontPadding(false);view.setLineSpacing(px(3),1);return view;}
 GradientDrawable rounded(int color,int radius){return UiStyle.shape(getContext(),color,Math.max(UiStyle.RADIUS_CONTROL,radius));}
 void bind(Video video,float scale){boolean resized=unit!=scale;unit=scale;int authEpoch=a.auth.epoch();if(epoch!=authEpoch){epoch=authEpoch;cache.clear();target=null;}if(target!=null&&target.id.equals(video.id)){if(resized)render();return;}removeCallbacks(request);generation++;target=video;items.clear();error="";loaded=false;List<Video> saved=cache.get(video.id);busy=saved==null;if(saved!=null){items.addAll(saved);loaded=true;}render();if(busy)postDelayed(request,500);}
 void reload(){if(target==null)return;removeCallbacks(request);generation++;cache.remove(target.id);items.clear();loaded=false;busy=true;error="";render();post(request);}
 void load(){if(target==null||a.dead)return;final Video video=target;final int token=generation,authEpoch=epoch;final Source service=source;busy=true;a.io.execute(()->{List<Video> result=null;String failure="";try{result=service.load(video);}catch(Exception e){failure=e.getMessage()==null?"暂时无法读取相关推荐":e.getMessage();}final List<Video> found=result;final String message=failure;a.ui.post(()->{if(a.dead||token!=generation||target!=video||authEpoch!=a.auth.epoch())return;busy=false;loaded=found!=null;error=message;items.clear();if(found!=null){HashSet<String> seen=new HashSet<>();for(Video item:found)if(item!=null&&!item.id.equals(video.id)&&seen.add(item.id)){items.add(item);if(items.size()==12)break;}cache.put(video.id,new ArrayList<>(items));}render();});});}
 @Override protected void onDetachedFromWindow(){removeCallbacks(request);generation++;target=null;busy=false;super.onDetachedFromWindow();}
 @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);if(visibility==VISIBLE)post(this::loadVisibleCovers);}
 void loadVisibleCovers(){for(View row:rows)((Thumb)((LinearLayout)row).getChildAt(0)).loadIfVisible();}
 void render(){removeAllViews();rows.clear();if(target==null)return;View divider=new View(getContext());divider.setBackgroundColor(0xfff1f2f3);addView(divider,new LayoutParams(-1,px(1)));
  LinearLayout heading=new LinearLayout(getContext());heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(0,px(20),0,px(14));heading.addView(text(target.demo?"演示推荐":"相关推荐",17,a.ink,true),new LayoutParams(0,-2,1));TextView refresh=text(busy?"加载中":"刷新",12,a.accent,false);refresh.setGravity(Gravity.CENTER);refresh.setPadding(px(16),px(8),0,px(8));refresh.setOnClickListener(v->{if(!busy)reload();});UiStyle.pressFeedback(refresh);heading.addView(refresh);addView(heading);
  if(items.isEmpty()){TextView state=text(busy?"正在读取相关推荐…":!error.isEmpty()?error+" · 点击重试":"暂时没有相关推荐",13,a.muted,false);state.setPadding(0,px(8),0,px(20));if(!error.isEmpty())state.setOnClickListener(v->reload());addView(state);return;}
  final Video origin=target;for(Video video:items){LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(px(6),px(10),px(6),px(10));row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20fb7299),rounded(Color.WHITE,8),null));row.setContentDescription("播放相关推荐："+video.title);row.setOnClickListener(v->{if(a.current()!=null&&a.current().id.equals(origin.id))a.playRelatedVideo(video);});Thumb thumb=new Thumb(video);row.addView(thumb,new LayoutParams(px(140),px(80)));LinearLayout body=new LinearLayout(getContext());body.setOrientation(LinearLayout.VERTICAL);TextView title=text(video.title,15,a.ink,false);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);body.addView(title,new LayoutParams(-1,-2));TextView up=text(video.up,11,0xff9499a0,false);up.setSingleLine(true);up.setEllipsize(TextUtils.TruncateAt.END);up.setPadding(0,px(8),0,px(4));body.addView(up,new LayoutParams(-1,-2));body.addView(text(video.demo?"无声演示视频":video.views+" 播放",11,0xff9499a0,false));LayoutParams bp=new LayoutParams(0,-2,1);bp.leftMargin=px(14);row.addView(body,bp);addView(row,new LayoutParams(-1,-2));rows.add(row);}post(this::loadVisibleCovers);
 }
 final class Thumb extends View {
  final Video video;final Paint paint=new Paint(3);Bitmap bitmap;boolean requested;final Rect visible=new Rect();
  Thumb(Video item){super(RelatedPane.this.getContext());video=item;bitmap=a.covers.get(video.id);setBackground(rounded(0xfff1f2f3,UiStyle.RADIUS_CONTROL));setClipToOutline(true);}
  void loadIfVisible(){if(bitmap!=null||requested||video.cover.isEmpty()||a.dead||!isShown()||!getLocalVisibleRect(visible))return;requested=true;a.io.execute(()->{Bitmap image=null;try{image=BiliApi.cover(video);}catch(Exception ignored){}final Bitmap ready=image;a.ui.post(()->{if(a.dead){if(ready!=null)ready.recycle();return;}if(ready!=null){a.covers.put(video.id,ready);bitmap=ready;invalidate();}});});}
  @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();if(bitmap!=null){float fit=Math.min(w/bitmap.getWidth(),h/bitmap.getHeight()),bw=bitmap.getWidth()*fit,bh=bitmap.getHeight()*fit;c.drawBitmap(bitmap,null,new RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),paint);}else loadIfVisible();
   paint.setTextSize(px(11));paint.setTypeface(Typeface.DEFAULT);String time=MainActivity.clockText(video.duration*1000);float width=paint.measureText(time)+px(8);paint.setColor(0xaa000000);c.drawRoundRect(w-width-px(4),h-px(21),w-px(4),h-px(4),px(3),px(3),paint);paint.setColor(Color.WHITE);c.drawText(time,w-width,h-px(8),paint);
  }
 }
}
