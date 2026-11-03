package local.thor.bilichannels;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Persistent, touch-accessible loading/error feedback above the video surface. */
final class PlaybackStatusView extends FrameLayout {
 final TextView heading,detail,elapsed;final ProgressBar spinner;final Button retry,back;
 private final LinearLayout card;
 PlaybackStatusView(Context context,Runnable retryAction,Runnable backAction){
  super(context);setVisibility(GONE);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setClickable(true);
  card.setBackground(UiStyle.shape(context,0xf21b1e23,UiStyle.RADIUS_CONTROL));
  LinearLayout title=new LinearLayout(context);title.setGravity(Gravity.CENTER_VERTICAL);spinner=new ProgressBar(context);spinner.setIndeterminateTintList(ColorStateList.valueOf(0xfffb7299));title.addView(spinner,new LinearLayout.LayoutParams(dp(22),dp(22)));
  heading=text(17,Color.WHITE);heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setContentDescription("播放状态标题");LinearLayout.LayoutParams label=new LinearLayout.LayoutParams(0,-2,1);label.setMargins(dp(10),0,0,0);title.addView(heading,label);card.addView(title,new LinearLayout.LayoutParams(-1,dp(32)));
  detail=text(14,0xffd8dce2);detail.setContentDescription("播放状态说明");detail.setMaxLines(3);detail.setPadding(0,dp(8),0,0);card.addView(detail);
  elapsed=text(12,0xffb4bbc4);elapsed.setPadding(0,dp(6),0,0);card.addView(elapsed);
  LinearLayout actions=new LinearLayout(context);retry=button("重试",0xfffb7299);retry.setContentDescription("重试播放");retry.setOnClickListener(v->retryAction.run());back=button("返回",0xff343941);back.setContentDescription("退出当前视频");back.setOnClickListener(v->backAction.run());
  LinearLayout.LayoutParams first=new LinearLayout.LayoutParams(0,dp(44),1);first.setMargins(0,0,dp(8),0);actions.addView(retry,first);actions.addView(back,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams buttons=new LinearLayout.LayoutParams(-1,-2);buttons.setMargins(0,dp(12),0,0);card.addView(actions,buttons);
  addView(card,new FrameLayout.LayoutParams(dp(360),-2,Gravity.CENTER));
 }
 private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
 private TextView text(int size,int color){TextView view=new TextView(getContext());view.setTextSize(size);view.setTextColor(color);view.setIncludeFontPadding(false);return view;}
 private Button button(String label,int color){
  Button button=new Button(getContext());button.setText(label);button.setTextSize(15);button.setTextColor(Color.WHITE);
  button.setAllCaps(false);button.setMinHeight(0);button.setMinimumHeight(0);button.setGravity(Gravity.CENTER);
  button.setIncludeFontPadding(false);button.setPadding(dp(12),0,dp(12),0);
  button.setBackground(UiStyle.shape(getContext(),color,UiStyle.RADIUS_CONTROL));
  UiStyle.pressFeedback(button);return button;
 }
 private void value(TextView view,String text){if(!text.contentEquals(view.getText()))view.setText(text);}
 void state(String title,String message,long waitMillis,boolean loading){
  boolean appeared=getVisibility()!=VISIBLE;
  value(heading,title);value(detail,message);elapsed.setVisibility(loading?VISIBLE:GONE);
  if(loading)value(elapsed,"已等待 "+Math.max(0,waitMillis/1000)+" 秒");
  spinner.setVisibility(loading?VISIBLE:GONE);retry.setVisibility(!loading||waitMillis>=8000?VISIBLE:GONE);
  setVisibility(VISIBLE);
  if(appeared){
   card.setScaleX(.97f);card.setScaleY(.97f);
   card.animate().alpha(1).scaleX(1).scaleY(1)
     .setDuration(UiStyle.scaled(getContext(),UiStyle.ENTER_MS))
     .setInterpolator(UiStyle.STANDARD).start();
  }
 }
 void hide(){
  if(getVisibility()!=VISIBLE)return;
  UiStyle.animateOut(card,this::finishHide);
 }
 private void finishHide(){
  super.setVisibility(GONE);
  card.setAlpha(1);
  card.setScaleX(1);
  card.setScaleY(1);
  card.setTranslationY(0);
 }
 @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getSize(widthSpec);card.getLayoutParams().width=Math.max(dp(160),Math.min(dp(360),width-dp(32)));super.onMeasure(widthSpec,heightSpec);}
}
