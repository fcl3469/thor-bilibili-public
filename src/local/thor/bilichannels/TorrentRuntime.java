package local.thor.bilichannels;

import android.content.Context;import android.net.Uri;import android.os.SystemClock;
import java.io.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicInteger;

/** One active torrent; files and resume checkpoints belong to persistent video records. */
final class TorrentRuntime {
 static final String AUTHORITY="local.thor.bilichannels.torrent";
 private static final ScheduledExecutorService CLOCK=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"thor-torrent-lifetime");t.setDaemon(true);return t;});
 private static final ExecutorService HOUSEKEEPER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"thor-torrent-files");t.setDaemon(true);return t;});
 private static final Set<String> LIVE=ConcurrentHashMap.newKeySet();
 private static Task active;private static TorrentStore store;private static Context app;private static long lastPrune;
 static synchronized TorrentStore store(Context context)throws IOException{
  if(store==null){app=context.getApplicationContext();store=new TorrentStore(new File(app.getFilesDir(),"local-library"));}
  return store;
 }
 static void prune(Context context){
  try{TorrentStore s=store(context);synchronized(TorrentRuntime.class){if(SystemClock.elapsedRealtime()-lastPrune<60000&&lastPrune!=0)return;lastPrune=SystemClock.elapsedRealtime();}
   HOUSEKEEPER.execute(()->{for(TorrentStore.Record r:s.expired(System.currentTimeMillis(),new HashSet<>(LIVE)))try{s.remove(r);}catch(IOException ignored){}});
  }catch(IOException ignored){}
 }
 static synchronized Task current(){return active;}
 static synchronized Task find(String id){return active!=null&&active.id.equals(id)&&!active.stopped?active:null;}
 static synchronized Task start(Context context,String magnet,boolean download)throws IOException{
  TorrentStore s=store(context);TorrentStore.Record r=s.create(magnet,download,System.currentTimeMillis());return launch(context,r,download);
 }
 static synchronized Task resume(Context context,TorrentStore.Record r,boolean download)throws IOException{
  if(r.deleted||r.local)throw new IOException("该记录不能继续磁力下载");
  if(download)store(context).keep(r);
  if(active!=null&&active.id.equals(r.id)&&!active.stopped){if(download){active.backgroundDownload=true;TorrentDownloadService.start(context);}return active;}
  return launch(context,r,download);
 }
 private static Task launch(Context context,TorrentStore.Record r,boolean download)throws IOException{
  Task previous=active;if(previous!=null)previous.pause();
  active=new Task(store(context),r,previous,download);
  if(download&&r.index>=0)try{TorrentDownloadService.start(context);}catch(RuntimeException e){active.fail(new IOException("后台缓存暂时无法启动，请回到应用内重试"));}
  return active;
 }
 static Task select(Context context,Task original,TorrentEngine.Entry entry,boolean download)throws IOException{
  TorrentStore s=store(context);TorrentStore.Record existing=s.find(original.record.key,entry.index);
  if(existing!=null&&!existing.id.equals(original.id)){original.pause();return resume(context,existing,download);}
  s.select(original.record,entry,System.currentTimeMillis());if(download)s.keep(original.record);original.backgroundDownload=download;original.select(entry.index);
  if(download)try{TorrentDownloadService.start(context);}catch(RuntimeException e){original.fail(new IOException("后台缓存暂时无法启动，请回到应用内重试"));}
  return original;
 }
 static Uri uri(TorrentStore.Record r){return r.local?Uri.parse(r.uri):new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(r.id).appendPath(String.valueOf(r.index)).build();}
 static void rememberLocal(Context context,Uri uri,String name){
  try{TorrentStore s=store(context);if(AUTHORITY.equals(uri.getAuthority())){List<String> p=uri.getPathSegments();TorrentStore.Record r=p.isEmpty()?null:s.get(p.get(0));if(r!=null)s.touch(r,System.currentTimeMillis());}
   else s.rememberLocal(uri.toString(),name,System.currentTimeMillis());
  }catch(IOException ignored){}
 }
 static void remove(Context context,TorrentStore.Record r)throws IOException{
  Task t; synchronized(TorrentRuntime.class){t=active!=null&&active.id.equals(r.id)?active:null;if(t!=null)t.pause();}
  if(t!=null)try{if(!t.closed.await(15,TimeUnit.SECONDS))throw new IOException("任务仍在保存进度，请稍后再删除");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("删除已取消");}
  store(context).remove(r);
 }
 static final class Task {
  final String id;final TorrentStore library;final TorrentStore.Record record;final TorrentEngine engine;
  final AtomicInteger readers=new AtomicInteger();final CountDownLatch closed=new CountDownLatch(1);
  final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"thor-torrent-worker");t.setDaemon(true);return t;});
  volatile boolean stopped,handedOff,prepared,opened,backgroundDownload;volatile String phase="正在连接做种者、获取种子信息…",error="";
  volatile List<TorrentEngine.Entry> files=Collections.emptyList();volatile TorrentEngine.Entry selected;
  volatile TorrentEngine.Stats stats=new TorrentEngine.Stats(0,0,0,0,0,"");
  final ScheduledFuture<?> sampling;private volatile long lastCheckpoint;private volatile boolean checkpointQueued;
  Task(TorrentStore library,TorrentStore.Record record,Task previous,boolean download){
   this.library=library;this.record=record;id=record.id;backgroundDownload=download;engine=new TorrentEngine(library.payload(record),null,true,library.directory(record));LIVE.add(id);
   sampling=CLOCK.scheduleWithFixedDelay(this::sample,1,1,TimeUnit.SECONDS);
   worker.execute(()->{try{
    if(previous!=null&&!previous.closed.await(15,TimeUnit.SECONDS))throw new IOException("上一任务仍在保存进度，请稍后重试");
    if(stopped)return;files=engine.open(record.magnet);phase="请选择种子中的视频";
    if(record.index>=0)configure(record.index);
   }catch(IOException|RuntimeException|LinkageError e){fail(e);}catch(InterruptedException e){Thread.currentThread().interrupt();pause();}});
  }
  private void sample(){
   if(stopped)return;
   try{
    stats=engine.stats();if(selected==null)return;
    if(!stats.error.isEmpty()){fail(new IOException(stats.error));return;}
    library.update(record,stats,backgroundDownload?"downloading":"streaming","",stats.complete);
    if(backgroundDownload&&!prepared)prepared=engine.ready();
    if(stats.complete){boolean wasDownload=backgroundDownload;phase="已缓存，可离线播放";backgroundDownload=false;if(wasDownload&&readers.get()==0&&!handedOff)pause();}
    if(!checkpointQueued&&SystemClock.elapsedRealtime()-lastCheckpoint>=30000){checkpointQueued=true;worker.execute(()->{try{if(!stopped)engine.checkpoint();lastCheckpoint=SystemClock.elapsedRealtime();}finally{checkpointQueued=false;}});}
   }catch(IOException|RuntimeException e){if(!stopped)fail(e);}
  }
  void select(int index){if(stopped)return;worker.execute(()->{try{configure(index);}catch(IOException|RuntimeException|LinkageError e){fail(e);}catch(InterruptedException e){Thread.currentThread().interrupt();pause();}});}
  private void configure(int index)throws IOException,InterruptedException{
   if(stopped)return;prepared=false;phase=backgroundDownload?"正在缓存下载…":"正在恢复分片并缓冲视频…";
   selected=engine.select(index,engine.directory().getUsableSpace());
   if(backgroundDownload){prepared=engine.ready();return;}
   long deadline=SystemClock.elapsedRealtime()+90000;
   while(!stopped&&!engine.ready()){if(SystemClock.elapsedRealtime()>deadline)throw new IOException("首段缓冲超时；已下载内容会保留，可稍后继续");Thread.sleep(150);}
   if(!stopped){prepared=true;phase="边下边播";}
  }
  void fail(Throwable e){if(stopped)return;error=e instanceof LinkageError?"磁力组件无法加载；需要 arm64 设备":e.getMessage()==null?"磁力任务失败，可从列表继续":e.getMessage();pause();}
  Uri uri(){return TorrentRuntime.uri(record);}
  void handoff(){handedOff=true;CLOCK.schedule(()->{if(!opened&&!backgroundDownload)pause();},45,TimeUnit.SECONDS);}
  void retain()throws FileNotFoundException{if(stopped||!error.isEmpty())throw new FileNotFoundException("请从本地栏目继续播放");opened=true;readers.incrementAndGet();}
  void release(){readers.updateAndGet(n->Math.max(0,n-1));CLOCK.schedule(()->{if(handedOff&&readers.get()==0&&!backgroundDownload)pause();},5,TimeUnit.SECONDS);}
  boolean needsService(){return !stopped&&backgroundDownload&&record.index>=0&&!record.complete;}
  void pause(){
   synchronized(this){if(stopped)return;stopped=true;phase=error.isEmpty()?"已暂停，保留文件和进度":"任务暂停";engine.cancel();sampling.cancel(false);}
   try{worker.execute(()->{
    try{stats=engine.stats();if(record.index>=0)library.update(record,stats,error.isEmpty()?"paused":"error",error,true);}
    catch(IOException|RuntimeException ignored){}finally{try{engine.close();if(record.index<0)library.remove(record);}catch(IOException|RuntimeException ignored){}finally{LIVE.remove(id);closed.countDown();worker.shutdown();}}
   });}catch(RejectedExecutionException ignored){closed.countDown();LIVE.remove(id);}
  }
  String summary(){TorrentEngine.Stats s=stats;if(!error.isEmpty())return error;return phase+" · "+human(s.bytesPerSecond)+"/s · "+s.peers+" 个连接 · "+human(s.downloaded)+(s.total>0?" / "+human(s.total):"");}
 }
 static String human(long bytes){double n=Math.max(0,bytes);String[] units={"B","KiB","MiB","GiB","TiB"};int i=0;while(n>=1024&&i<units.length-1){n/=1024;i++;}return String.format(Locale.ROOT,i==0?"%.0f %s":"%.1f %s",n,units[i]);}
}
