package local.thor.bilichannels;

import android.app.AlertDialog;import android.content.*;import android.graphics.Typeface;import android.text.InputFilter;import android.text.InputType;import android.text.TextUtils;import android.view.*;import android.view.inputmethod.*;import android.widget.*;
import java.util.*;

/** Visible native controls for the phone flow; Thor keeps its two-screen controls. */
final class TorrentPhoneUi {
 final TorrentUi owner;final MainActivity a;AlertDialog dialog;EditText editor;TextView status;private String draft="";private int epoch;
 TorrentPhoneUi(TorrentUi owner){this.owner=owner;a=owner.f.a;}
 boolean available(){return a.second==null;}
 int dp(float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
 TextView text(String value,int size){TextView view=new TextView(a);view.setText(value);view.setTextSize(size);view.setTextColor(a.ink);return view;}
 void close(){epoch++;AlertDialog old=dialog;if(editor!=null){draft=editor.getText().toString();a.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(editor.getWindowToken(),0);}dialog=null;editor=null;status=null;if(old!=null)old.dismiss();}
 String clipboard(){try{ClipboardManager manager=a.getSystemService(ClipboardManager.class);ClipData clip=manager==null?null:manager.getPrimaryClip();if(clip!=null)for(int i=0;i<Math.min(4,clip.getItemCount());i++){CharSequence value=clip.getItemAt(i).getText();if(value!=null&&!value.toString().trim().isEmpty())return value.toString();}}catch(SecurityException ignored){}return "";}
 void input(String initial){
  close();if(!available())return;final int token=epoch;
  LinearLayout content=new LinearLayout(a);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(8),dp(22),dp(4));
  TextView help=text("输入或粘贴 magnet 链接，然后选择在线播放或永久缓存。",14);help.setTextColor(a.muted);content.addView(help);
  EditText field=new EditText(a);field.setContentDescription("磁力链接输入框");field.setHint("magnet:?xt=urn:btih:…");field.setTextSize(15);field.setGravity(Gravity.TOP|Gravity.START);field.setMinLines(2);field.setMaxLines(4);field.setHorizontallyScrolling(false);field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32768)});field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_ACTION_DONE);field.setText(initial==null?draft:initial);field.setSelection(field.length());LinearLayout.LayoutParams inputParams=new LinearLayout.LayoutParams(-1,-2);inputParams.topMargin=dp(12);content.addView(field,inputParams);
  LinearLayout tools=new LinearLayout(a);for(String label:new String[]{"粘贴","清空"}){Button button=new Button(a);button.setText(label);button.setAllCaps(false);button.setOnClickListener(v->{if(label.equals("清空")){field.setText("");field.setError(null);}else{String value=clipboard();if(value.isEmpty()){field.setError("剪贴板里没有文字，请手动输入");return;}String magnet=MagnetLink.extract(value);field.setText(magnet==null?value:magnet);field.setSelection(field.length());field.setError(null);}field.requestFocus();});tools.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));}content.addView(tools);
  ScrollView scroll=new ScrollView(a);scroll.setFillViewport(false);scroll.addView(content);
  AlertDialog shown=new AlertDialog.Builder(a).setTitle("输入磁力链接").setView(scroll).setPositiveButton("在线播放",null).setNegativeButton("缓存下载",null).setNeutralButton("取消",null).create();dialog=shown;editor=field;shown.setCanceledOnTouchOutside(false);
  shown.setOnDismissListener(d->{if(dialog==shown){draft=field.getText().toString();dialog=null;editor=null;status=null;epoch++;}});
  shown.setOnShowListener(d->{
   for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE})shown.getButton(which).setOnClickListener(v->{if(token!=epoch)return;String link=MagnetLink.extract(field.getText());if(link==null){field.setError("请输入有效的磁力链接，以 magnet:? 开头");field.requestFocus();return;}draft=link;close();owner.resolve(link,which==AlertDialog.BUTTON_NEGATIVE);});
   field.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick();return true;}return false;});
   Window window=shown.getWindow();if(window!=null){window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);window.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels-dp(32),dp(560)),WindowManager.LayoutParams.WRAP_CONTENT);}field.requestFocus();field.postDelayed(()->{if(dialog==shown&&shown.isShowing())a.getSystemService(InputMethodManager.class).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);},150);
  });shown.show();
 }
 void page(boolean waiting){
  if(!available())return;close();final int token=epoch;FeatureUi f=owner.f;List<String> labels=new ArrayList<>(f.labels);List<Runnable> actions=new ArrayList<>(f.actions);
  Runnable back=()->{f.close();owner.home();};if(!labels.isEmpty()&&labels.get(labels.size()-1).startsWith("返回本地")){back=actions.remove(actions.size()-1);labels.remove(labels.size()-1);}final Runnable returnHome=back;
  AlertDialog.Builder builder=new AlertDialog.Builder(a).setOnCancelListener(d->{if(token==epoch){close();returnHome.run();}});
  if(waiting){
   LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(12),dp(24),dp(16));ProgressBar progress=new ProgressBar(a);body.addView(progress,new LinearLayout.LayoutParams(-1,dp(48)));status=text(f.notice,14);status.setPadding(0,dp(14),0,0);body.addView(status);builder.setTitle(f.title).setView(body).setNegativeButton("暂停并返回",(d,w)->{if(token!=epoch)return;close();if(!actions.isEmpty())actions.get(0).run();else returnHome.run();});
  }else{
   LinearLayout title=new LinearLayout(a);title.setOrientation(LinearLayout.VERTICAL);title.setPadding(dp(24),dp(18),dp(24),dp(10));TextView heading=text(f.title,20);heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setMaxLines(2);heading.setEllipsize(TextUtils.TruncateAt.END);title.addView(heading);status=text(f.notice,13);status.setTextColor(a.muted);status.setPadding(0,dp(9),0,0);status.setMaxLines(4);title.addView(status);builder.setCustomTitle(title);
   if(!labels.isEmpty())builder.setItems(labels.toArray(new String[0]),(d,index)->{if(token!=epoch)return;close();actions.get(index).run();});builder.setNegativeButton("返回本地",(d,w)->{if(token==epoch){close();returnHome.run();}});
  }
  AlertDialog shown=builder.create();dialog=shown;shown.setCanceledOnTouchOutside(false);shown.setOnDismissListener(d->{if(dialog==shown){dialog=null;status=null;epoch++;}});shown.show();if(shown.getWindow()!=null)shown.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
 }
 void notice(String value){if(status!=null)status.setText(value);}
}
