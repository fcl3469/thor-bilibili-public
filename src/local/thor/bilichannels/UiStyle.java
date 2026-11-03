package local.thor.bilichannels;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Shared visual and motion tokens for the phone and Thor surfaces. */
final class UiStyle {
 static final int INK=0xff18191c;
 static final int BODY=0xff61666d;
 static final int MUTED=0xff9499a0;
 static final int ACCENT=0xfffb7299;
 static final int ACCENT_SOFT=0xffffedf3;
 static final int ACCENT_FILL=0xfffff7fa;
 static final int CANVAS=0xfff6f7f8;
 static final int SURFACE=0xffffffff;
 static final int SUBDUED=0xfff7f8fa;
 static final int DIVIDER=0xffeef0f2;
 static final int RIPPLE=0x24fb7299;

 static final int RADIUS_BADGE=8;
 static final int RADIUS_CONTROL=12;
 static final int RADIUS_CARD=16;
 static final int RADIUS_PILL=999;

 static final int PRESS_IN_MS=90;
 static final int PRESS_OUT_MS=160;
 static final int ENTER_MS=220;
 static final int EXIT_MS=160;
 static final int PAGE_MS=320;

 static final PathInterpolator STANDARD=new PathInterpolator(.22f,.68f,.18f,1);
 private static final Map<View,Boolean> PRESS_BOUND=Collections.synchronizedMap(new WeakHashMap<>());

 private UiStyle(){}

 static int dp(Context context,float value){
  return Math.round(value*context.getResources().getDisplayMetrics().density);
 }

 static int scaled(Context context,int duration){
  if(!ValueAnimator.areAnimatorsEnabled())return 1;
  return Math.max(1,Math.round(duration*ValueAnimator.getDurationScale()));
 }

 static GradientDrawable shape(Context context,int color,int radiusDp){
  GradientDrawable drawable=new GradientDrawable();
  drawable.setColor(color);
  drawable.setCornerRadius(dp(context,radiusDp));
  return drawable;
 }

 static GradientDrawable shape(Context context,int color,int radiusDp,int strokeColor){
  GradientDrawable drawable=shape(context,color,radiusDp);
  if(strokeColor!=Color.TRANSPARENT)drawable.setStroke(Math.max(1,dp(context,.75f)),strokeColor);
  return drawable;
 }

 static RippleDrawable ripple(Context context,int baseColor,int radiusDp){
  return new RippleDrawable(ColorStateList.valueOf(RIPPLE),shape(context,baseColor,radiusDp),null);
 }

 /** Adds tactile scale without consuming the child's normal click handling. */
 static void pressFeedback(View view){
  if(view==null||PRESS_BOUND.put(view,Boolean.TRUE)!=null)return;
  view.setOnTouchListener((target,event)->{
   if(!target.isEnabled())return false;
   int action=event.getActionMasked();
   if(action==MotionEvent.ACTION_DOWN){
    target.animate().cancel();
    target.animate().scaleX(.975f).scaleY(.975f).alpha(.9f)
      .setDuration(scaled(target.getContext(),PRESS_IN_MS)).setInterpolator(STANDARD).start();
   }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
    target.animate().cancel();
    target.animate().scaleX(1).scaleY(1).alpha(1)
      .setDuration(scaled(target.getContext(),PRESS_OUT_MS)).setInterpolator(STANDARD).start();
   }
   return false;
  });
 }

 static void animateIn(View view,float riseDp){
  if(view==null)return;
  Context context=view.getContext();
  view.animate().cancel();
  if(!ValueAnimator.areAnimatorsEnabled()){
   view.setAlpha(1);
   view.setTranslationY(0);
   return;
  }
  float rise=dp(context,riseDp);
  view.setAlpha(0);
  view.setTranslationY(rise);
  view.animate().alpha(1).translationY(0)
    .setDuration(scaled(context,ENTER_MS)).setInterpolator(STANDARD).start();
}

 static void animateOut(View view,Runnable endAction){
  if(view==null){
   if(endAction!=null)endAction.run();
   return;
  }
  view.animate().cancel();
  if(!ValueAnimator.areAnimatorsEnabled()){
   if(endAction!=null)endAction.run();
   return;
  }
  Context context=view.getContext();
  view.animate().alpha(0).translationY(dp(context,6))
    .setDuration(scaled(context,EXIT_MS)).setInterpolator(STANDARD)
    .withEndAction(()->{
     if(endAction!=null)endAction.run();
    }).start();
 }
}
