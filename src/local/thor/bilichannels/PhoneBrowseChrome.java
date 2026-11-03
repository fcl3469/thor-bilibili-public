package local.thor.bilichannels;
import android.content.Context;import android.graphics.Color;import android.graphics.drawable.GradientDrawable;import android.util.TypedValue;import android.view.*;import android.widget.*;

/** Phone-only search and navigation. Landscape uses a left rail; portrait keeps the bottom tabs. */
final class PhoneBrowseChrome extends FrameLayout {
 static String tabIcon(int tab){switch(tab){case MainActivity.RECOMMENDED:return "home";case MainActivity.POPULAR:return "hot";case MainActivity.COLUMN:return "film";case MainActivity.DYNAMIC:return "dynamic";case MainActivity.LIBRARY:return "download";case MainActivity.MINE:return "person";default:return "search";}}
 final MainActivity a;final LinearLayout top,bottom,search,rail;final TextView prompt,section;int selected=-1,availableHeight;float unit;boolean builtLandscape;
 PhoneBrowseChrome(MainActivity a,Context c){super(c);this.a=a;
  rail=new LinearLayout(c);rail.setOrientation(LinearLayout.VERTICAL);rail.setBackgroundColor(Color.WHITE);addView(rail,new LayoutParams(1,-1,Gravity.LEFT));
  top=new LinearLayout(c);top.setGravity(Gravity.CENTER_VERTICAL);top.setBackgroundColor(Color.WHITE);addView(top,new LayoutParams(-1,1,Gravity.TOP));
  search=new LinearLayout(c);search.setGravity(Gravity.CENTER_VERTICAL);top.addView(search,new LinearLayout.LayoutParams(-1,-1));search.addView(new AppIcon(c,"search",a.muted));
  prompt=new TextView(c);prompt.setText("搜索视频 / BV 号");prompt.setTextColor(a.muted);prompt.setIncludeFontPadding(false);prompt.setGravity(Gravity.CENTER_VERTICAL);search.addView(prompt,new LinearLayout.LayoutParams(0,-1,1));
  section=new TextView(c);section.setVisibility(GONE);section.setTextColor(a.ink);section.setIncludeFontPadding(false);section.setGravity(Gravity.CENTER_VERTICAL);section.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);top.addView(section,new LinearLayout.LayoutParams(0,-1,1));
  search.setContentDescription("搜索视频");search.setOnClickListener(v->{if(a.homeTab!=MainActivity.SEARCH)a.navigateHome(MainActivity.SEARCH,1,false);if(!a.features.keyboard){a.features.tabRoot=MainActivity.SEARCH;a.features.search();}});
  bottom=new LinearLayout(c);bottom.setBackgroundColor(Color.WHITE);addView(bottom,new LayoutParams(-1,1,Gravity.BOTTOM));
 }
 int px(float n){return Math.round(n*unit);}
 float landscapeUnit(){if(unit>0)return unit;android.util.DisplayMetrics m=a.getResources().getDisplayMetrics();return Math.max(1,Math.min(m.widthPixels,m.heightPixels)/480f);}
 int cutoutLeft(){WindowInsets insets=getRootWindowInsets();DisplayCutout cutout=insets==null?null:insets.getDisplayCutout();return cutout==null?0:Math.max(0,cutout.getSafeInsetLeft());}
 int railWidth(){return a.phoneLandscape?Math.round(88*landscapeUnit())+cutoutLeft():0;}
 float topHeight(){return a.homeTab==MainActivity.LIBRARY?0:(a.phoneLandscape?58:76)*landscapeUnit();}
 float bottomHeight(){return a.phoneLandscape?0:62*landscapeUnit();}
 int railRowHeight(){return Math.max(1,Math.min(px(72),(availableHeight-rail.getPaddingTop()-rail.getPaddingBottom())/Math.max(1,a.tabs.length)));}
 @Override protected void onMeasure(int widthSpec,int heightSpec){
  availableHeight=MeasureSpec.getSize(heightSpec);
  float next=Math.min(MeasureSpec.getSize(widthSpec),MeasureSpec.getSize(heightSpec))/480f;boolean land=a.phoneLandscape;
  if(next>0&&(next!=unit||land!=builtLandscape)){unit=next;selected=-1;builtLandscape=land;}
  int railW=railWidth(),cutout=cutoutLeft();rail.getLayoutParams().width=Math.max(1,railW);rail.getLayoutParams().height=-1;rail.setPadding(cutout+px(8),a.columnBrowsing()?px(12):Math.round(topHeight()+px(8)),px(8),px(8));
  top.getLayoutParams().height=Math.round(topHeight());FrameLayout.LayoutParams tp=(FrameLayout.LayoutParams)top.getLayoutParams();tp.leftMargin=railW;top.setLayoutParams(tp);
  top.setPadding(px(16),px(8),px(16),px(8));
  bottom.getLayoutParams().height=land?1:Math.max(1,Math.round(bottomHeight()));
  search.getLayoutParams().width=land?Math.min(px(360),Math.max(px(180),Math.round((MeasureSpec.getSize(widthSpec)-railW)*.42f))):-1;
  search.setPadding(px(14),0,px(14),0);GradientDrawable bg=new GradientDrawable();bg.setColor(UiStyle.SUBDUED);bg.setCornerRadius(px(UiStyle.RADIUS_PILL));search.setBackground(bg);
  search.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(px(22),px(22)));prompt.setPadding(px(12),0,0,0);prompt.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(16));
  section.setVisibility(land?VISIBLE:GONE);section.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(20));section.setPadding(px(20),0,0,0);section.setText(a.tabs[Math.max(0,Math.min(a.tabs.length-1,a.homeTab))]);
  bind();super.onMeasure(widthSpec,heightSpec);
 }
 void bind(){
  boolean browse=!a.columnBrowsing(),land=a.phoneLandscape;
  top.setVisibility(browse&&a.homeTab!=MainActivity.LIBRARY?VISIBLE:GONE);rail.setVisibility(land?VISIBLE:GONE);bottom.setVisibility(!land?VISIBLE:GONE);
  rail.setPadding(cutoutLeft()+px(8),a.columnBrowsing()?px(12):Math.round(topHeight()+px(8)),px(8),px(8));
  if(land){int rowHeight=railRowHeight();for(int i=0;i<rail.getChildCount();i++){View cell=rail.getChildAt(i);ViewGroup.LayoutParams lp=cell.getLayoutParams();if(lp.height!=rowHeight){lp.height=rowHeight;cell.setLayoutParams(lp);}}}
  if(unit<=0||selected==a.homeTab&&builtLandscape==land)return;selected=a.homeTab;builtLandscape=land;
  bottom.removeAllViews();rail.removeAllViews();section.setText(a.tabs[Math.max(0,Math.min(a.tabs.length-1,a.homeTab))]);
  for(int i=0;i<a.tabs.length;i++){
   final int tab=i;int color=i==a.homeTab?a.accent:a.muted;
   LinearLayout cell=new LinearLayout(getContext());cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER);
   cell.setBackground(UiStyle.ripple(getContext(),land&&i==a.homeTab?UiStyle.ACCENT_SOFT:Color.TRANSPARENT,UiStyle.RADIUS_CONTROL));
   if(land)cell.setPadding(px(6),px(10),px(6),px(10));else cell.setPadding(px(4),px(4),px(4),px(2));
   cell.addView(new AppIcon(getContext(),tabIcon(tab),color),new LinearLayout.LayoutParams(px(land?26:24),px(land?26:24)));
   TextView label=new TextView(getContext());label.setText(a.tabs[i]);label.setTextColor(color);label.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(land?13:12));label.setIncludeFontPadding(false);label.setGravity(Gravity.CENTER);label.setPadding(0,px(5),0,0);cell.addView(label);
   cell.setContentDescription(a.tabs[i]);cell.setOnClickListener(v->{if(tab!=a.homeTab)a.navigateHome(tab,tab<a.homeTab?-1:1,false);else if(tab==MainActivity.COLUMN){if(!a.pgc.back())a.pgc.refresh();}else if(tab==MainActivity.LIBRARY){a.openLocalLibrary();}else if(tab==MainActivity.MINE){a.features.tabRoot=tab;a.features.relations.mine();}else a.refreshCurrentFeed();});
   UiStyle.pressFeedback(cell);
   if(land)rail.addView(cell,new LinearLayout.LayoutParams(-1,railRowHeight()));
   else bottom.addView(cell,new LinearLayout.LayoutParams(0,-1,1));
  }
  if(!land)bottom.setPadding(0,px(7),0,px(7));
 }
}
