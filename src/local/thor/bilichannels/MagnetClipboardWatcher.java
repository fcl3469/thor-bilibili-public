package local.thor.bilichannels;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;
/** Clipboard access is restricted to a resumed, focused MainActivity. */
final class MagnetClipboardWatcher implements Application.ActivityLifecycleCallbacks {
 private static MagnetClipboardWatcher instance;
 private final Context app;private final ClipboardManager clipboard;private final Handler ui=new Handler(Looper.getMainLooper());
 private MainActivity activity;private AlertDialog dialog;private View decor;
 private final ClipboardManager.OnPrimaryClipChangedListener changed=()->schedule();
 private final ViewTreeObserver.OnWindowFocusChangeListener focused=hasFocus->{if(hasFocus)schedule();};
 static synchronized void install(Context context){if(instance!=null)return;Context app=context.getApplicationContext();if(app instanceof Application){instance=new MagnetClipboardWatcher(app);((Application)app).registerActivityLifecycleCallbacks(instance);}}
 private MagnetClipboardWatcher(Context app){this.app=app;clipboard=app.getSystemService(ClipboardManager.class);}
 static boolean enabled(Context context){return context.getSharedPreferences("magnet-clipboard",0).getBoolean("enabled",true);}
 static void enabled(Context context,boolean value){context.getSharedPreferences("magnet-clipboard",0).edit().putBoolean("enabled",value).apply();if(instance!=null)instance.schedule();}
 private void schedule(){ui.removeCallbacks(inspect);ui.postDelayed(inspect,400);}
 private final Runnable inspect=this::inspectClipboard;
 private void inspectClipboard(){
  MainActivity a=activity;if(a==null||!a.active||a.dead||!(a.hasWindowFocus()||a.second!=null&&a.second.getWindow()!=null&&a.second.getWindow().getDecorView().hasWindowFocus())||!enabled(app)||dialog!=null&&dialog.isShowing())return;
  if(a.features==null||a.features.keyboard){schedule();return;}
  try{
   if(clipboard==null||!clipboard.hasPrimaryClip())return;ClipData clip=clipboard.getPrimaryClip();if(clip==null)return;
   String magnet=null;for(int i=0;i<Math.min(4,clip.getItemCount());i++){magnet=MagnetLink.extract(clip.getItemAt(i).getText());if(magnet!=null)break;}
   if(magnet==null)return;String fingerprint=MagnetLink.fingerprint(magnet);SharedPreferences preferences=app.getSharedPreferences("magnet-clipboard",0);
   if(fingerprint.equals(preferences.getString("last-offered","")))return;
   final String found=magnet;
   dialog=new AlertDialog.Builder(a).setTitle("识别到磁力链接").setMessage(MagnetLink.title(found)+"\n选择在线播放或永久缓存")
    .setPositiveButton("在线播放",(d,w)->{a.openLocalLibrary();a.features.torrents.resolve(found,false);})
    .setNegativeButton("缓存下载",(d,w)->{a.openLocalLibrary();a.features.torrents.resolve(found,true);})
    .setNeutralButton("忽略",null).create();
   dialog.setOnDismissListener(d->{dialog=null;});dialog.show();preferences.edit().putString("last-offered",fingerprint).apply();
  }catch(SecurityException|IllegalArgumentException ignored){}
 }
 @Override public void onActivityResumed(Activity value){if(!(value instanceof MainActivity))return;detach();activity=(MainActivity)value;decor=activity.getWindow().getDecorView();decor.getViewTreeObserver().addOnWindowFocusChangeListener(focused);if(clipboard!=null)clipboard.addPrimaryClipChangedListener(changed);TorrentRuntime.prune(app);if(activity.getIntent().getBooleanExtra("open_local",false)){activity.getIntent().removeExtra("open_local");activity.ui.post(activity::openLocalLibrary);}schedule();}
 private void detach(){ui.removeCallbacks(inspect);if(clipboard!=null)clipboard.removePrimaryClipChangedListener(changed);if(decor!=null&&decor.getViewTreeObserver().isAlive())decor.getViewTreeObserver().removeOnWindowFocusChangeListener(focused);if(dialog!=null){dialog.dismiss();dialog=null;}activity=null;decor=null;}
 @Override public void onActivityPaused(Activity value){if(value==activity)detach();}
 @Override public void onActivityDestroyed(Activity value){if(value==activity)detach();}
 @Override public void onActivityCreated(Activity a,Bundle b){}
 @Override public void onActivityStarted(Activity a){}
 @Override public void onActivityStopped(Activity a){}
 @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
}
