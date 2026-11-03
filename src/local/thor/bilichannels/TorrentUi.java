package local.thor.bilichannels;
import android.app.AlertDialog;import android.content.*;import android.os.Build;import java.io.*;

/** Resolver and record details live under the Local column. */
final class TorrentUi {
 final FeatureUi f;final TorrentPhoneUi phone;private boolean switching,handingOff,download;private TorrentRuntime.Task task;
 TorrentUi(FeatureUi features){f=features;phone=new TorrentPhoneUi(this);}
 private void discardTaskHistory(){f.history.removeIf(page->page.name.startsWith("磁力")||page.name.startsWith("本地记录")||page.name.equals("输入磁力链接"));}
 private void menu(String name){switching=true;try{f.menu(name);f.tabRoot=MainActivity.LIBRARY;discardTaskHistory();}finally{switching=false;}}
 void leave(){phone.close();if(!switching&&!handingOff&&task!=null&&!task.handedOff&&(!task.backgroundDownload||task.record.index<0)){task.pause();task=null;}f.a.ui.removeCallbacks(poll);}
 void home(){f.a.openLocalLibrary();}
 void input(){if(phone.available()){phone.input(null);return;}f.showInput("输入磁力链接","",this::offer);f.tabRoot=MainActivity.LIBRARY;discardTaskHistory();f.redraw();}
 void paste(){if(phone.available()){phone.input(phone.clipboard());return;}ClipboardManager cb=f.a.getSystemService(ClipboardManager.class);try{if(cb==null||cb.getPrimaryClip()==null){f.a.message("剪贴板里没有磁力链接");return;}String link=null;for(int i=0;i<Math.min(4,cb.getPrimaryClip().getItemCount());i++){link=MagnetLink.extract(cb.getPrimaryClip().getItemAt(i).getText());if(link!=null)break;}if(link==null){f.a.message("剪贴板里没有有效磁力链接");return;}offer(link);}catch(SecurityException e){f.a.message("请允许应用读取剪贴板，或手动输入链接");}}
 void offer(String value){String link=MagnetLink.extract(value);if(link==null){f.a.message("没有识别到有效磁力链接");return;}new AlertDialog.Builder(f.a).setTitle("磁力链接").setMessage(MagnetLink.title(link)).setPositiveButton("在线播放",(d,w)->resolve(link,false)).setNegativeButton("缓存下载",(d,w)->resolve(link,true)).setNeutralButton("取消",null).show();}
 void resolve(String value,boolean cache){
  try{boolean arm64=false;for(String abi:Build.SUPPORTED_ABIS)if("arm64-v8a".equals(abi))arm64=true;if(!arm64)throw new IOException("磁力功能需要 arm64 设备");
   String link=MagnetLink.extract(value);if(link==null)throw new IOException("磁力链接无效");
   f.a.getSharedPreferences("magnet-clipboard",0).edit().putString("last-offered",MagnetLink.fingerprint(link)).apply();
   if(f.a.playing||f.a.homeTab!=MainActivity.LIBRARY)f.a.openLocalLibrary();
   download=cache;task=TorrentRuntime.start(f.a,link,cache);progress("磁力 · 获取视频列表");
  }catch(IOException|RuntimeException e){f.a.message(e.getMessage()==null?"无法打开磁力链接":e.getMessage());home();}
 }
 private void progress(String title){menu(title);f.add("暂停并保留已下载内容",()->{if(task!=null)task.pause();task=null;f.close();home();});f.notice=task.summary();f.redraw();phone.page(true);f.a.ui.postDelayed(poll,300);}
 void files(){
  if(task==null||task.stopped)return;menu(download?"磁力 · 选择缓存视频":"磁力 · 选择播放视频");
  for(TorrentEngine.Entry entry:task.files){f.add(entry.name+" · "+TorrentRuntime.human(entry.size),()->select(entry));f.subtitles.put(f.labels.size()-1,entry.path);}
  f.add("返回本地",()->{task.pause();task=null;f.close();home();});f.notice=download?"永久保存 · 支持暂停和断点续传":"在线播放也保留已下载文件 · 最近 30 天记录可继续播放或转为永久缓存";f.redraw();phone.page(false);
 }
 private void select(TorrentEngine.Entry entry){
  try{
   TorrentStore library=TorrentRuntime.store(f.a);TorrentStore.Record existing=library.find(task.record.key,entry.index);
   if(existing!=null&&library.playable(existing)){task.pause();task=null;if(download){library.keep(existing);f.close();home();}else playRecord(existing);return;}
   task=TorrentRuntime.select(f.a,task,entry,download);
   if(download){f.close();home();f.a.message("已加入永久缓存，可在列表暂停或继续");}else progress("磁力 · 正在恢复播放");
  }catch(IOException|RuntimeException e){f.a.message(e.getMessage());}
 }
 void details(String id){
  try{TorrentStore library=TorrentRuntime.store(f.a);TorrentStore.Record r=library.get(id);if(r==null){home();return;}task=TorrentRuntime.find(id);menu("本地记录 · "+r.name);
   f.add(library.playable(r)?"播放视频":"继续在线播放",()->playRecord(r));
   if(!r.local){
    if(!r.permanent)f.add("转为永久缓存",()->cache(r));
    else if(!r.complete)f.add(task!=null&&task.needsService()?"暂停缓存下载":"继续缓存下载",()->{TorrentRuntime.Task current=TorrentRuntime.find(id);if(current!=null&&current.needsService()){current.pause();details(id);}else cache(r);});
   }
   f.add(r.local?"移除播放记录":"删除视频与记录",()->confirmRemove(r));f.add("返回本地",()->{f.close();home();});
   f.notice=r.local?"系统文件选择器中的本地视频；删除记录不会删除原文件":(r.permanent?"永久缓存":"最近 30 天记录")+" · "+TorrentRuntime.human(r.downloaded)+" / "+TorrentRuntime.human(r.size)+(r.error.isEmpty()?"":" · "+r.error);f.redraw();phone.page(false);
  }catch(IOException e){f.a.message(e.getMessage());}
 }
 private void cache(TorrentStore.Record r){
  try{TorrentStore s=TorrentRuntime.store(f.a);s.keep(r);if(!s.playable(r))task=TorrentRuntime.resume(f.a,r,true);f.close();home();f.a.message(r.complete?"已永久保存":"正在继续缓存，已下载内容会复用");}catch(IOException|RuntimeException e){f.a.message(e.getMessage());}
 }
 private void playRecord(TorrentStore.Record r){
  try{TorrentStore s=TorrentRuntime.store(f.a);s.touch(r,System.currentTimeMillis());
   if(s.playable(r)){handingOff=true;try{f.a.openLocalVideo(TorrentRuntime.uri(r));}finally{handingOff=false;}return;}
   if(r.local){f.a.message("本地文件不可访问，请重新选择");return;}
   download=false;task=TorrentRuntime.resume(f.a,r,false);progress("磁力 · 正在恢复播放");
  }catch(IOException|RuntimeException e){f.a.message(e.getMessage());}
 }
 private void confirmRemove(TorrentStore.Record r){new AlertDialog.Builder(f.a).setTitle(r.local?"移除记录？":"删除视频与记录？").setMessage(r.local?"原视频文件会保留。":"将删除这条记录和本应用保存的已下载文件。").setPositiveButton("删除",(d,w)->{f.a.io.execute(()->{try{TorrentRuntime.remove(f.a,r);f.a.ui.post(()->{if(!f.a.dead){f.close();home();}});}catch(IOException e){f.a.ui.post(()->f.a.message(e.getMessage()));}});}).setNegativeButton("取消",(d,w)->{if(phone.available())details(r.id);}).setOnCancelListener(d->{if(phone.available())details(r.id);}).show();}
 private final Runnable poll=new Runnable(){public void run(){
  if(f.a.dead||task==null||!f.title.startsWith("磁力"))return;
  if(!task.error.isEmpty()){String error=task.error;menu("磁力 · 任务已暂停");f.add("返回本地列表",()->{f.close();home();});f.notice=error+"；已有内容保留，可从列表继续。";f.redraw();phone.page(false);return;}
  if(task.stopped){f.close();home();return;}
  if(f.title.equals("磁力 · 获取视频列表")&&!task.files.isEmpty()){files();return;}
  if(f.title.equals("磁力 · 正在恢复播放")&&task.prepared){play();return;}
  f.notice=task.summary();f.redraw();phone.notice(f.notice);f.a.ui.postDelayed(this,1000);
 }};
 private void play(){if(task==null||task.stopped||!task.prepared)return;task.handoff();handingOff=true;try{f.a.openLocalVideo(task.uri());}finally{handingOff=false;}f.a.message(task.record.permanent?"永久缓存视频":"在线播放 · 已下载文件和最近 30 天记录会保留");}
}
