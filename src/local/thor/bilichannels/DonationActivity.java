package local.thor.bilichannels;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Optional support, using only the maintainer-supplied personal payment QR images. */
public final class DonationActivity extends Activity {
 private static final int INK=0xff24272c,MUTED=0xff6b7078,ACCENT=0xfffb7299;
 private final ExecutorService writer=Executors.newSingleThreadExecutor();
 private final List<Button> choices=new ArrayList<>();
 private int cents=100;private String channel="";private LinearLayout body;private TextView amount;private boolean saving;
 static void open(Context context){context.startActivity(new Intent(context,DonationActivity.class));}
 private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
 private String label(String provider){return provider.equals("wechat")?"微信":"支付宝";}
 private String asset(String provider){return "support/"+provider+".jpg";}
 @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(0xfff6f7f8);getWindow().setNavigationBarColor(Color.WHITE);if(saved!=null){int previous=saved.getInt("cents",100);if(previous>0&&previous<=999999)cents=previous;String selected=saved.getString("channel","");if(selected.equals("wechat")||selected.equals("alipay"))channel=selected;}render();}
 private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(8),0,dp(8));body.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
 private Button button(String value,Runnable action){Button view=new Button(this);view.setText(value);view.setTextSize(16);view.setAllCaps(false);view.setMinHeight(dp(48));view.setTextColor(INK);view.setOnClickListener(v->action.run());body.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
 private void render(){
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(16),dp(24),dp(24));body.setBackgroundColor(Color.WHITE);scroll.addView(body);setContentView(scroll);choices.clear();
  button("‹ 返回",this::back);text("支持开发",25,INK);text("如果 Thor 频道对你有帮助，可以自愿支持开发。打赏不解锁功能，也不影响正常使用。",15,MUTED);
  amount=text("支持金额  ¥"+DonationAmount.text(cents),22,ACCENT);amount.setContentDescription("支持金额 "+DonationAmount.text(cents)+" 元");
  if(channel.isEmpty()){
   for(int i=0;i<DonationAmount.PRESETS.length;i+=2){LinearLayout row=new LinearLayout(this);body.addView(row,new LinearLayout.LayoutParams(-1,-2));for(int j=i;j<i+2;j++){final int value=DonationAmount.PRESETS[j];Button choice=new Button(this);choice.setText("¥"+(value/100));choice.setTextSize(18);choice.setMinHeight(dp(52));choice.setTag(value);choice.setContentDescription("打赏 "+(value/100)+" 元");choice.setOnClickListener(v->choose(value));row.addView(choice,new LinearLayout.LayoutParams(0,-2,1));choices.add(choice);}}
   button("自定义金额",this::custom);refreshAmount();text("选择收款方式",17,INK);button("微信打赏",()->showQr("wechat"));button("支付宝打赏",()->showQr("alipay"));
   text("这是普通个人收款码。请在支付应用中填写所选金额，并核对收款人；App 不会自动扣款或读取支付结果。",14,MUTED);
  }else{
   text(label(channel)+"收款码",19,INK);text("请在"+label(channel)+"付款时填写 ¥"+DonationAmount.text(cents)+"。可用另一台设备扫码，或保存图片后从扫一扫的相册中选择。",15,MUTED);
   ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setAdjustViewBounds(true);image.setContentDescription(label(channel)+"收款码");body.addView(image,new LinearLayout.LayoutParams(-1,dp(420)));
   try(InputStream stream=getAssets().open(asset(channel))){image.setImageBitmap(BitmapFactory.decodeStream(stream));}catch(IOException error){text("收款码暂时无法显示，请返回后重试",16,INK);}
   button("复制金额 ¥"+DonationAmount.text(cents),()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("支持开发金额",DonationAmount.text(cents)));toast("金额已复制，请在付款时填写");});
   Button save=button("保存收款码",this::saveQr);save.setEnabled(!saving);button("打开"+label(channel),this::openPaymentApp);button("重新选择金额／方式",()->{channel="";render();});
   text("请以微信或支付宝中的实际付款结果为准。返回 App 不会被当作付款成功。",13,MUTED);
  }
 }
 private void refreshAmount(){amount.setText("支持金额  ¥"+DonationAmount.text(cents));amount.setContentDescription("支持金额 "+DonationAmount.text(cents)+" 元");for(Button choice:choices){boolean selected=((Integer)choice.getTag())==cents;choice.setSelected(selected);choice.setTextColor(selected?Color.WHITE:INK);choice.setBackgroundTintList(ColorStateList.valueOf(selected?ACCENT:0xfff2f3f5));}}
 private void choose(int value){cents=value;refreshAmount();}
 private void custom(){EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setSingleLine(true);input.setText(DonationAmount.text(cents));input.setSelectAllOnFocus(true);input.setHint("0.01–9999.99 元");LinearLayout box=new LinearLayout(this);box.setPadding(dp(24),dp(8),dp(24),0);box.addView(input,new LinearLayout.LayoutParams(-1,-2));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("自定义打赏金额").setView(box).setNegativeButton("取消",null).setPositiveButton("确定",null).create();dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{choose(DonationAmount.parse(input.getText().toString()));dialog.dismiss();}catch(IllegalArgumentException error){input.setError(error.getMessage());}});input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});dialog.show();}
 private void showQr(String provider){channel=provider;render();}
 private void saveQr(){
  if(saving)return;final String selected=channel;if(!selected.equals("wechat")&&!selected.equals("alipay"))return;saving=true;render();
  writer.execute(()->{Uri uri=null;boolean success=false;try{
   ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"Thor-Bili-"+selected+"-support-"+System.currentTimeMillis()+".jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Thor-Bili");values.put(MediaStore.Images.Media.IS_PENDING,1);uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("No image destination");
   try(InputStream input=getAssets().open(asset(selected));OutputStream output=getContentResolver().openOutputStream(uri)){if(output==null)throw new IOException("No image stream");byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;)output.write(buffer,0,n);}
   ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);getContentResolver().update(uri,ready,null,null);success=true;
  }catch(Exception error){if(uri!=null)try{getContentResolver().delete(uri,null,null);}catch(Exception ignored){}}
   final boolean saved=success;runOnUiThread(()->{saving=false;if(isFinishing()||isDestroyed())return;toast(saved?"收款码已保存到相册，请在"+label(selected)+"扫一扫中选择相册":"暂时无法保存，请重试或用另一台设备扫码");render();});
  });
 }
 private void openPaymentApp(){String provider=channel;Intent launch=getPackageManager().getLaunchIntentForPackage(provider.equals("wechat")?"com.tencent.mm":"com.eg.android.AlipayGphone");if(launch==null){toast("未找到"+label(provider)+"，可保存收款码后用其他设备扫码");return;}try{startActivity(launch);toast("请在扫一扫中选择已保存的收款码，填写 ¥"+DonationAmount.text(cents));}catch(ActivityNotFoundException error){toast("暂时无法打开"+label(provider));}}
 private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
 private void back(){if(!channel.isEmpty()){channel="";render();}else finish();}
 @Override public void onBackPressed(){back();}
 @Override protected void onSaveInstanceState(Bundle saved){saved.putInt("cents",cents);saved.putString("channel",channel);super.onSaveInstanceState(saved);}
 @Override protected void onDestroy(){writer.shutdown();super.onDestroy();}
 @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getKeyCode()==KeyEvent.KEYCODE_BUTTON_B){if(event.getAction()==KeyEvent.ACTION_UP)back();return true;}if(event.getKeyCode()==KeyEvent.KEYCODE_BUTTON_A){View focused=getCurrentFocus();if(focused!=null&&focused.isEnabled()&&!(focused instanceof EditText)){if(event.getAction()==KeyEvent.ACTION_UP)focused.performClick();return true;}}return super.dispatchKeyEvent(event);}
}
