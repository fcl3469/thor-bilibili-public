package local.thor.bilichannels;

import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.swig.settings_pack;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicReference;import com.frostwire.jlibtorrent.alerts.*;

/** One temporary torrent. Readers never expose a sparse-file hole as video data. */
public final class TorrentEngine implements AutoCloseable {
 public static final long RESERVE_BYTES=256L*1024*1024;
 private static final long HEAD_BYTES=2L*1024*1024,TAIL_BYTES=256L*1024;
 public static final class Entry {
  public final int index;public final String name,path;public final long size,offset;
  Entry(int index,String path,long size,long offset){this.index=index;this.path=path;this.name=new File(path).getName();this.size=size;this.offset=offset;}
 }
 public static final class Stats {
  public final long downloaded,total;public final int peers,seeds,bytesPerSecond;public final String error;public final boolean complete;
  Stats(long downloaded,long total,int peers,int seeds,int rate,String error){this(downloaded,total,peers,seeds,rate,error,false);}
  Stats(long downloaded,long total,int peers,int seeds,int rate,String error,boolean complete){this.downloaded=downloaded;this.total=total;this.peers=peers;this.seeds=seeds;this.bytesPerSecond=rate;this.error=error;this.complete=complete;}
 }
 private final Object lock=new Object();
 private final File directory,stateDirectory;private final SessionParams parameters;private final boolean retainFiles;private final Object checkpointLock=new Object();
 private SessionManager session;private TorrentHandle handle;private TorrentInfo info;private Entry selected;
 private final List<Entry> videos=new ArrayList<>();
 private volatile boolean cancelled,closed;private boolean started;
 private int firstPiece=-1,lastPiece=-1;private long lastDemand=-1;

 public TorrentEngine(File directory){this(directory,null);}
 public TorrentEngine(File directory,SessionParams parameters){this(directory,parameters,false,null);}
 public TorrentEngine(File directory,SessionParams parameters,boolean retainFiles,File stateDirectory){this.directory=directory;this.parameters=parameters;this.retainFiles=retainFiles;this.stateDirectory=stateDirectory;}
 public File directory(){return directory;}
 public static String validateMagnet(String value){
  if(value==null)throw new IllegalArgumentException("请输入磁力链接");
  String text=value.trim();
  if(text.length()>32768||!text.regionMatches(true,0,"magnet:?",0,8))throw new IllegalArgumentException("请输入包含 BT 信息哈希的 magnet:? 链接");
  for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i)))throw new IllegalArgumentException("磁力链接含有无效字符");
  boolean valid=false;
  try{for(String field:text.substring(8).split("&")){int equals=field.indexOf('=');if(equals<0)continue;String key=java.net.URLDecoder.decode(field.substring(0,equals),"UTF-8"),valuePart=java.net.URLDecoder.decode(field.substring(equals+1),"UTF-8");if(key.equals("xt")&&(valuePart.matches("(?i)urn:btih:([0-9a-f]{40}|[a-z2-7]{32})")||valuePart.matches("(?i)urn:btmh:1220[0-9a-f]{64}")))valid=true;}}
  catch(IllegalArgumentException|UnsupportedEncodingException e){throw new IllegalArgumentException("磁力链接编码无效");}
  if(!valid)throw new IllegalArgumentException("磁力链接缺少有效的 BT 信息哈希");
  return "magnet:?"+text.substring(8);
 }
 private void check()throws IOException{if(cancelled||closed)throw new InterruptedIOException("磁力任务已停止");}
 public void cancel(){cancelled=true;}
 private static SessionParams defaults(){
  SessionParams p=new SessionParams();p.setPosixDiskIO();SettingsPack s=p.getSettings();
  s.connectionsLimit(64).activeDownloads(1).uploadRateLimit(128*1024).listenInterfaces("0.0.0.0:0,[::]:0");
  s.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(),false);
  s.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(),false);
  p.setSettings(s);return p;
 }
 public List<Entry> open(String magnet)throws IOException{
  magnet=validateMagnet(magnet);
  synchronized(lock){
   check();if(started)throw new IOException("任务已经启动");started=true;
   if(!directory.mkdirs()&&!directory.isDirectory())throw new IOException("无法创建临时缓存目录");
   session=new SessionManager();session.start(parameters==null?defaults():parameters);
   // Fetch metadata without downloading any payload before the user chooses a file.
   File metadata=stateDirectory==null?null:new File(stateDirectory,"metadata.torrent"),resume=stateDirectory==null?null:new File(stateDirectory,"resume.dat");
   if(metadata!=null&&metadata.isFile())session.download(new TorrentInfo(TorrentFiles.read(metadata,2*1024*1024)),directory,resume!=null&&resume.length()<=8*1024*1024?resume:null,null,AddTorrentParams.parseMagnetUri(magnet).peers(),TorrentFlags.UPLOAD_MODE.or_(TorrentFlags.STOP_WHEN_READY));
   else session.download(magnet,directory,TorrentFlags.UPLOAD_MODE.or_(TorrentFlags.STOP_WHEN_READY));
  }
  long limit=System.nanoTime()+90_000_000_000L;
  while(System.nanoTime()<limit){
   synchronized(lock){
    check();TorrentHandle[] all=session.getTorrentHandles();
    if(all!=null&&all.length>0){
     if(handle==null){handle=all[0];handle.unsetFlags(TorrentFlags.AUTO_MANAGED);handle.resume();}
     TorrentInfo candidate=handle.torrentFile();
     TorrentStatus.State state=handle.status(true).state();
     if(candidate!=null&&candidate.isValid()&&state!=TorrentStatus.State.CHECKING_FILES&&state!=TorrentStatus.State.CHECKING_RESUME_DATA){
      info=candidate;FileStorage files=info.files();
      if(files.numFiles()>10000||info.numPieces()>2_000_000||info.pieceLength()<=0)throw new IOException("种子文件列表或分片数量过大");
      for(int i=0;i<files.numFiles();i++){
       String path=files.filePath(i);safePath(directory,path);
       if(files.fileFlags(i).and_(FileStorage.FLAG_SYMLINK).nonZero())throw new IOException("不支持包含符号链接的种子");
       if(!files.padFileAt(i)&&files.fileSize(i)>0&&isVideo(path))videos.add(new Entry(i,path,files.fileSize(i),files.fileOffset(i)));
      }
      if(videos.isEmpty())throw new IOException("种子内没有可直接播放的视频；压缩包需要先下载解压");
      if(stateDirectory!=null)TorrentFiles.write(new File(stateDirectory,"metadata.torrent"),info.bencode());
      return Collections.unmodifiableList(new ArrayList<>(videos));
     }
     ErrorCode error=handle.status().errorCode();if(error!=null&&error.isError())throw new IOException("种子连接失败："+error.message());
    }
   }
   sleep();
  }
  throw new IOException("获取种子信息超时；可能暂无可连接的做种者，可稍后重试");
 }
 public static File safePath(File root,String path)throws IOException{
  if(path==null||path.isEmpty()||new File(path).isAbsolute()||path.indexOf('\\')>=0)throw new IOException("种子文件路径无效");
  File file=new File(root,path).getCanonicalFile();String prefix=root.getCanonicalPath()+File.separator;
  if(!file.getPath().startsWith(prefix))throw new IOException("种子文件路径超出缓存目录");
  return file;
 }
 public static boolean isVideo(String path){
  int at=path.lastIndexOf('.');String extension=at<0?"":path.substring(at+1).toLowerCase(Locale.ROOT);
  return Arrays.asList("mp4","mkv","webm","mov","m4v","ts","m2ts","avi","mpg","mpeg","ogv").contains(extension);
 }
 public Entry select(int index,long usableBytes)throws IOException{
  synchronized(lock){
   check();if(info==null)throw new IOException("种子信息尚未就绪");
   Entry found=null;for(Entry e:videos)if(e.index==index)found=e;
   if(found==null)throw new IOException("视频文件不存在");
   long extra=Math.min(64L*1024*1024,2L*info.pieceLength());
   long[] progress=handle.fileProgress(TorrentHandle.PIECE_GRANULARITY);long existing=progress!=null&&index<progress.length?progress[index]:0;
   if(found.size-Math.max(0,Math.min(found.size,existing))>usableBytes-RESERVE_BYTES-extra)throw new IOException("剩余空间不足；需容纳尚未下载的部分，并保留 256 MiB 空间");
   selected=found;
   Priority[] priorities=Priority.array(Priority.IGNORE,info.numFiles());priorities[index]=Priority.NORMAL;
   handle.prioritizeFiles(priorities);
   handle.unsetFlags(TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.STOP_WHEN_READY));handle.resume();
   firstPiece=(int)(found.offset/info.pieceLength());lastPiece=(int)((found.offset+found.size-1)/info.pieceLength());
   request(0,Math.min(found.size,HEAD_BYTES));
   request(Math.max(0,found.size-TAIL_BYTES),Math.min(found.size,TAIL_BYTES));
   return found;
  }
 }
 public Entry selected(){synchronized(lock){return selected;}}
 public boolean ready()throws IOException{
  synchronized(lock){check();if(selected==null)return false;return available(0,Math.min(selected.size,HEAD_BYTES))&&available(Math.max(0,selected.size-TAIL_BYTES),Math.min(selected.size,TAIL_BYTES));}
 }
 private void request(long offset,long count){
  if(selected==null||count<=0)return;
  int from=(int)((selected.offset+offset)/info.pieceLength()),to=(int)((selected.offset+offset+count-1)/info.pieceLength());
  for(int p=from;p<=to;p++)if(!handle.havePiece(p))handle.setPieceDeadline(p,(p-from)*80);
 }
 private boolean available(long offset,long count){
  if(count<=0)return true;int from=(int)((selected.offset+offset)/info.pieceLength()),to=(int)((selected.offset+offset+count-1)/info.pieceLength());
  for(int p=from;p<=to;p++)if(!handle.havePiece(p))return false;
  return true;
 }
 private void demand(long offset,int count){
  // Seeking replaces old deadlines instead of keeping an obsolete playback position urgent.
  if(lastDemand<0||offset<lastDemand||offset-lastDemand>8L*1024*1024){handle.clearPieceDeadlines();}
  request(offset,Math.min(selected.size-offset,Math.max(count,2L*1024*1024)));lastDemand=offset;
 }
 public int read(long offset,int length,byte[] target)throws IOException{
  if(offset<0||length<0||length>target.length)throw new IOException("读取范围无效");
  final int count;final File file;
  synchronized(lock){check();if(selected==null)throw new IOException("尚未选择视频");if(offset>=selected.size)return -1;count=(int)Math.min(length,selected.size-offset);if(count==0)return 0;demand(offset,count);file=safePath(directory,selected.path);}
  long limit=System.nanoTime()+40_000_000_000L;
  while(true){
   synchronized(lock){
    check();ErrorCode error=handle.status().errorCode();if(error!=null&&error.isError())throw new IOException("磁力下载失败："+error.message());
    if(available(offset,count)){
     try(RandomAccessFile input=new RandomAccessFile(file,"r")){input.seek(offset);input.readFully(target,0,count);return count;}
    }
   }
   if(System.nanoTime()>limit)throw new IOException("等待视频分片超时；请检查网络或做种者数量");
   sleep();
  }
 }
 public Stats stats(){
  synchronized(lock){
   if(closed||session==null||handle==null||!handle.isValid())return new Stats(0,selected==null?0:selected.size,0,0,0,"");
   TorrentStatus s=handle.status();long done=s.totalWantedDone();
   if(selected!=null){long[] progress=handle.fileProgress(TorrentHandle.PIECE_GRANULARITY);if(progress!=null&&selected.index<progress.length)done=progress[selected.index];}
   ErrorCode error=s.errorCode();return new Stats(done,selected==null?0:selected.size,s.numPeers(),s.numSeeds(),s.downloadRate(),error!=null&&error.isError()?error.message():"",selected!=null&&s.isFinished()&&done>=selected.size);
  }
 }
 private static void sleep()throws InterruptedIOException{try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("磁力任务已取消");}}
 public boolean checkpoint(){
  if(stateDirectory==null)return false;
  synchronized(checkpointLock){
   final SessionManager owner;final TorrentHandle current;synchronized(lock){if(closed||session==null||handle==null||info==null)return false;owner=session;current=handle;}
   CountDownLatch ready=new CountDownLatch(1);AtomicReference<byte[]> bytes=new AtomicReference<>();
   AlertListener listener=new AlertListener(){public int[] types(){return new int[]{AlertType.SAVE_RESUME_DATA.swig(),AlertType.SAVE_RESUME_DATA_FAILED.swig()};}public void alert(Alert<?> alert){try{if(alert instanceof SaveResumeDataAlert)bytes.set(AddTorrentParams.writeResumeData(((SaveResumeDataAlert)alert).params()).bencode());}finally{ready.countDown();}}};
   owner.addListener(listener);try{current.saveResumeData(TorrentHandle.FLUSH_DISK_CACHE.or_(TorrentHandle.SAVE_INFO_DICT));if(!ready.await(8,TimeUnit.SECONDS)||bytes.get()==null)return false;TorrentFiles.write(new File(stateDirectory,"resume.dat"),bytes.get());return true;}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}catch(IOException|RuntimeException e){return false;}finally{owner.removeListener(listener);}
  }
 }
 @Override public void close(){
  cancel();synchronized(checkpointLock){checkpoint();synchronized(lock){if(closed)return;closed=true;try{if(session!=null)session.stop();}finally{session=null;handle=null;info=null;}}}
  if(!retainFiles)try{deleteDirectory(directory);}catch(IOException ignored){}
 }
 public static void deleteDirectory(File root)throws IOException{
  if(!root.exists())return;
  Files.walkFileTree(root.toPath(),new SimpleFileVisitor<Path>(){
   @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attributes)throws IOException{Files.delete(path);return FileVisitResult.CONTINUE;}
   @Override public FileVisitResult postVisitDirectory(Path path,IOException error)throws IOException{if(error!=null)throw error;Files.delete(path);return FileVisitResult.CONTINUE;}
  });
 }
}
