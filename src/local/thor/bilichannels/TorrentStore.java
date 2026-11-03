package local.thor.bilichannels;
import org.json.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Persistent video records. Recent streams expire; permanent downloads never expire automatically. */
public final class TorrentStore {
 public static final long RECENT_MS=30L*24*60*60*1000;
 public static final class Record {
  public final String id;public final boolean local;public final long created;
  public volatile String magnet="",key="",uri="",name="",path="",state="paused",error="";
  public volatile int index=-1;public volatile long size,downloaded,lastPlayed,updated;public volatile boolean permanent,complete,deleted;
  Record(String id,boolean local,long now){this.id=id;this.local=local;created=lastPlayed=updated=now;}
 }
 private final File root;private final LinkedHashMap<String,Record> records=new LinkedHashMap<>();
 public TorrentStore(File root)throws IOException{
  this.root=root;if(!root.mkdirs()&&!root.isDirectory())throw new IOException("无法打开本地视频目录");
  File[] dirs=root.listFiles();if(dirs!=null)for(File d:dirs){if(!d.isDirectory()||!validId(d.getName()))continue;
   try{JSONObject j=new JSONObject(new String(TorrentFiles.read(new File(d,"record.json"),131072),StandardCharsets.UTF_8));if(!d.getName().equals(j.getString("id")))continue;
    Record r=new Record(d.getName(),j.optBoolean("local"),j.getLong("created"));r.magnet=j.optString("magnet");r.key=j.optString("key");r.uri=j.optString("uri");r.name=j.optString("name");r.path=j.optString("path");r.index=j.optInt("index",-1);r.size=j.optLong("size");r.downloaded=j.optLong("downloaded");r.permanent=j.optBoolean("permanent");r.complete=j.optBoolean("complete");r.lastPlayed=j.optLong("last_played",r.created);r.updated=j.optLong("updated",r.created);r.state=r.complete?"complete":"paused";r.error=j.optString("error");if(!r.local&&!r.path.isEmpty())TorrentEngine.safePath(payload(r),r.path);records.put(r.id,r);
   }catch(Exception ignored){}
  }
 }
 private static boolean validId(String id){return id!=null&&id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
 public File directory(Record r){if(!validId(r.id))throw new IllegalArgumentException("无效的记录");return new File(root,r.id);}
 public File payload(Record r){return new File(directory(r),"data");}
 public synchronized Record get(String id){Record r=records.get(id);return r==null||r.deleted?null:r;}
 public synchronized Record create(String magnet,boolean permanent,long now)throws IOException{
  Record r=new Record(UUID.randomUUID().toString(),false,now);r.magnet=TorrentEngine.validateMagnet(magnet);r.key=MagnetLink.key(magnet);r.name=MagnetLink.title(magnet);r.permanent=permanent;records.put(r.id,r);save(r);return r;
 }
 public synchronized Record find(String key,int index){for(Record r:records.values())if(!r.deleted&&!r.local&&r.index==index&&r.key.equals(key))return r;return null;}
 public synchronized Record rememberLocal(String uri,String name,long now)throws IOException{
  Record found=null;for(Record r:records.values())if(r.local&&r.uri.equals(uri)){found=r;break;}
  if(found==null){found=new Record(UUID.randomUUID().toString(),true,now);found.uri=uri;found.complete=true;found.state="complete";records.put(found.id,found);}
  found.name=name;found.lastPlayed=found.updated=now;save(found);return found;
 }
 public synchronized void select(Record r,TorrentEngine.Entry e,long now)throws IOException{r.index=e.index;r.name=e.name;r.path=e.path;r.size=e.size;r.lastPlayed=r.updated=now;r.state="buffering";r.error="";save(r);}
 public synchronized void touch(Record r,long now)throws IOException{if(r.deleted)return;r.lastPlayed=r.updated=now;save(r);}
 public synchronized void keep(Record r)throws IOException{if(r.deleted)return;r.permanent=true;r.updated=System.currentTimeMillis();save(r);}
 public synchronized void update(Record r,TorrentEngine.Stats stats,String state,String error,boolean flush)throws IOException{
  if(r.deleted)return;r.downloaded=Math.max(0,Math.min(r.size,stats.downloaded));r.complete=stats.complete;r.state=r.complete?"complete":state;r.error=error;
  if(flush||System.currentTimeMillis()-r.updated>=15000){r.updated=System.currentTimeMillis();save(r);}
 }
 public synchronized List<Record> entries(long now){List<Record> result=new ArrayList<>();for(Record r:records.values())if(!r.deleted&&(r.local||r.index>=0)&&(r.permanent||now-r.lastPlayed<RECENT_MS))result.add(r);result.sort((a,b)->Long.compare(b.lastPlayed,a.lastPlayed));return result;}
 public synchronized List<Record> expired(long now,Set<String> inUse){List<Record> result=new ArrayList<>();for(Record r:records.values())if(!r.deleted&&!r.permanent&&!inUse.contains(r.id)&&now-r.lastPlayed>=RECENT_MS)result.add(r);return result;}
 public synchronized boolean playable(Record r){if(r.deleted)return false;if(r.local)return !r.uri.isEmpty();if(!r.complete||r.size<=0)return false;try{File file=TorrentEngine.safePath(payload(r),r.path);return file.isFile()&&file.length()==r.size;}catch(IOException e){return false;}}
 public synchronized void remove(Record r)throws IOException{r.deleted=true;records.remove(r.id);TorrentEngine.deleteDirectory(directory(r));}
 private void save(Record r)throws IOException{
  if(r.deleted)return;try{JSONObject j=new JSONObject().put("id",r.id).put("local",r.local).put("created",r.created).put("magnet",r.magnet).put("key",r.key).put("uri",r.uri).put("name",r.name).put("path",r.path).put("index",r.index).put("size",r.size).put("downloaded",r.downloaded).put("last_played",r.lastPlayed).put("updated",r.updated).put("permanent",r.permanent).put("complete",r.complete).put("state",r.state).put("error",r.error);TorrentFiles.write(new File(directory(r),"record.json"),j.toString().getBytes(StandardCharsets.UTF_8));}catch(JSONException e){throw new IOException(e);}
 }
}
