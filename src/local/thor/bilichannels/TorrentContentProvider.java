package local.thor.bilichannels;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.*;
import java.io.*;
import java.util.*;

/** Seekable, private content URI: Media3 asks for bytes; the torrent schedules their pieces. */
public final class TorrentContentProvider extends ContentProvider {
 @Override public boolean onCreate(){MagnetClipboardWatcher.install(getContext());return true;}
 private TorrentStore.Record record(Uri uri)throws FileNotFoundException{
  List<String> parts=uri.getPathSegments();
  if(!TorrentRuntime.AUTHORITY.equals(uri.getAuthority())||parts.size()!=2)throw new FileNotFoundException("无效的磁力视频地址");
  try{TorrentStore.Record record=TorrentRuntime.store(getContext()).get(parts.get(0));if(record==null||record.local||!String.valueOf(record.index).equals(parts.get(1)))throw new FileNotFoundException("视频记录不存在");return record;}catch(IOException e){throw new FileNotFoundException(e.getMessage());}
 }
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
  if(!"r".equals(mode))throw new FileNotFoundException("磁力视频仅支持读取");
  TorrentStore.Record record=record(uri);final TorrentRuntime.Task task;
  try{
   TorrentStore store=TorrentRuntime.store(getContext());store.touch(record,System.currentTimeMillis());
   if(store.playable(record))return ParcelFileDescriptor.open(TorrentEngine.safePath(store.payload(record),record.path),ParcelFileDescriptor.MODE_READ_ONLY);
   task=TorrentRuntime.resume(getContext(),record,false);
   long deadline=SystemClock.elapsedRealtime()+42000;
   while(!task.stopped&&task.selected==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
   if(task.stopped||task.selected==null)throw new FileNotFoundException("视频分片恢复失败，请在本地栏目继续任务");task.retain();
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new FileNotFoundException("视频读取已取消");}catch(IOException|RuntimeException e){throw new FileNotFoundException(e.getMessage());}
  HandlerThread thread=new HandlerThread("thor-torrent-reader");thread.start();
  try{
   return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,new ProxyFileDescriptorCallback(){
    @Override public long onGetSize(){return record.size;}
    @Override public int onRead(long offset,int size,byte[] data)throws ErrnoException{
     try{int n=task.engine.read(offset,Math.min(size,data.length),data);return n<0?0:n;}
     catch(IOException|RuntimeException e){throw new ErrnoException("torrentRead",OsConstants.EIO,e);}
    }
    @Override public void onRelease(){task.release();thread.quitSafely();}
   },new Handler(thread.getLooper()));
  }catch(IOException|RuntimeException e){thread.quitSafely();task.release();throw new FileNotFoundException("无法打开磁力视频流："+e.getClass().getSimpleName());}
 }
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
  try{
   TorrentStore.Record record=record(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
   MatrixCursor cursor=new MatrixCursor(columns,1);Object[] row=new Object[columns.length];
   for(int i=0;i<columns.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?record.name:OpenableColumns.SIZE.equals(columns[i])?record.size:null;
   cursor.addRow(row);return cursor;
  }catch(FileNotFoundException e){return null;}
 }
 @Override public String getType(Uri uri){try{String name=record(uri).name.toLowerCase(Locale.ROOT);return name.endsWith(".mkv")?"video/x-matroska":name.endsWith(".webm")?"video/webm":"video/mp4";}catch(FileNotFoundException e){return "application/octet-stream";}}
 @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
 @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException("Read only");}
 @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException("Read only");}
}
