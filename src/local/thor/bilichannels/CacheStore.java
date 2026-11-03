package local.thor.bilichannels;
import android.content.*;import android.net.*;import android.os.*;
import org.json.*;import java.io.*;import java.util.*;
/** App-owned persistent queue; the foreground service only keeps our worker alive. */
public final class CacheStore {
 private static CacheStore instance;public static synchronized CacheStore get(Context c){if(instance==null)instance=new CacheStore(c);return instance;}
 final Context context;final SharedPreferences prefs;final File directory;final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
 public static final class Job {public final Video video;public final String key;public volatile String state="queued",error="",label="",track="";public volatile long done,total=-1,videoBytes,audioBytes;public volatile int quality;public volatile boolean audio,removed;volatile long savedAt;long videoTotal=-1,audioTotal=-1;StreamSpec source;
  Job(Video source){Video copy=new Video(source.id,source.title,source.up,source.cover,source.aid,source.cid,source.duration,false);copy.partIndex=source.partIndex;copy.description=source.description;copy.views=source.views;copy.setDimensions(source.width,source.height,0);this.video=copy;key=copy.mediaKey();}
  public boolean active(){return state.equals("queued")||state.equals("downloading")||state.equals("waiting");}
  public float progress(){return state.equals("complete")?1:total<=0?-1:Math.min(.995f,done/(float)total);}
 }
 private CacheStore(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("offline_v4",0);File root=context.getExternalFilesDir(null);directory=new File(root==null?context.getFilesDir():root,"offline-v4");directory.mkdirs();
  for(String key:prefs.getAll().keySet())try{JSONObject data=new JSONObject(prefs.getString(key,"{}"));List<Video> rows=BiliApi.decodeCatalog(data.getString("catalog"));if(rows.isEmpty())continue;Job j=new Job(rows.get(0));if(!key.equals(j.key)||!safe(j))continue;j.state=data.optString("state","paused");if(j.active())j.state="paused";j.error=data.optString("error");j.label=data.optString("label");j.quality=data.optInt("quality",16);j.audio=data.optBoolean("audio");j.done=data.optLong("done");j.total=data.optLong("total",-1);j.videoBytes=data.optLong("videoBytes");j.audioBytes=data.optLong("audioBytes");jobs.put(key,j);}catch(Exception ignored){}
 }
 private static boolean safe(Job j){return j.video.id.matches("BV[0-9A-Za-z]{10}")&&j.video.cid>0&&j.key.matches("[A-Za-z0-9_-]+")&&j.key.equals(j.video.mediaKey());}
 File file(Job j,boolean audio){if(!safe(j))throw new IllegalArgumentException("缓存标识无效");return new File(directory,j.key+(audio?"-audio.m4a":"-video.mp4"));}
 public synchronized Job job(Video v){return v==null?null:jobs.get(v.mediaKey());}
 public synchronized List<Job> entries(){return new ArrayList<>(jobs.values());}
 public synchronized Job next(){for(Job j:jobs.values())if(j.active())return j;return null;}
 public boolean complete(Job j){return j!=null&&j.state.equals("complete")&&j.videoBytes>0&&file(j,false).length()==j.videoBytes&&(!j.audio||j.audioBytes>0&&file(j,true).length()==j.audioBytes);}
 public StreamSpec local(Video v){Job j=job(v);if(!complete(j))return null;return new StreamSpec(Arrays.asList(file(j,false).toURI().toString()),j.audio?Arrays.asList(file(j,true).toURI().toString()):Collections.emptyList(),j.quality,j.label+" · 离线");}
 public String status(Video v){Job j=job(v);if(j==null)return "尚未缓存 · 仅 Wi-Fi";if(complete(j))return "已缓存 · "+j.label;if(j.state.equals("complete"))return "缓存文件不完整，请重新下载";if(j.state.equals("error"))return "失败 · "+j.error;if(j.state.equals("paused"))return "已暂停 · "+bytes(j.done);if(j.state.equals("waiting"))return "等待 Wi-Fi";if(j.state.equals("queued"))return "排队中 · "+j.label;return "缓存中 "+(j.progress()<0?bytes(j.done):Math.round(j.progress()*100)+"%")+" · "+j.track;}
 static String bytes(long n){return String.format(Locale.ROOT,n>=1024*1024?"%.1f MB":"%.0f KB",n>=1024*1024?n/1048576.0:n/1024.0);}
 public synchronized void start(Video v,StreamSpec spec)throws Exception{Job old=job(v);if(running!=null&&running.removed&&running.key.equals(v.mediaKey()))throw new IOException("正在取消旧任务，请稍后重试");if(old!=null&&(old.active()||complete(old)))return;Job j=old==null?new Job(v):old;if(!safe(j))throw new IOException("视频标识无效");if(directory.getUsableSpace()<50L*1024*1024)throw new IOException("存储空间不足");if(old!=null&&j.quality!=spec.quality)eraseFiles(j);j.source=spec;j.quality=spec.quality;j.label=spec.label;j.audio=spec.separated();j.state="queued";j.error="";jobs.put(j.key,j);save(j);launch();}
 public synchronized void resume(Video v){Job j=job(v);if(j==null||j.active()||complete(j))return;j.state="queued";j.error="";save(j);launch();}
 public synchronized void pause(Video v){Job j=job(v);if(j!=null&&j.active()){j.state="paused";save(j);}}
 public synchronized void pauseAll(){for(Job j:jobs.values())if(j.active()){j.state="paused";save(j);}}
 private void launch(){try{context.startForegroundService(new Intent(context,CacheService.class));}catch(RuntimeException e){for(Job j:jobs.values())if(j.state.equals("queued")){j.state="paused";j.error="请保持应用前台后继续";save(j);}}}
 synchronized void save(Job j){if(j.removed||jobs.get(j.key)!=j)return;try{JSONObject data=new JSONObject().put("catalog",BiliApi.encodeCatalog(Arrays.asList(j.video))).put("state",j.state).put("error",j.error).put("quality",j.quality).put("label",j.label).put("audio",j.audio).put("done",j.done).put("total",j.total).put("videoBytes",j.videoBytes).put("audioBytes",j.audioBytes);prefs.edit().putString(j.key,data.toString()).commit();j.savedAt=SystemClock.uptimeMillis();}catch(Exception e){j.state="error";j.error="无法保存缓存记录";}}
 public List<Video> list(){List<Video> rows=new ArrayList<>();for(Job j:entries())rows.add(j.video);return rows;}
 private void eraseFiles(Job j){for(boolean audio:new boolean[]{false,true}){File f=file(j,audio);f.delete();new File(f+".part").delete();new File(f+".meta").delete();}j.videoBytes=j.audioBytes=j.done=0;j.total=-1;}
 public synchronized void remove(Video v){Job j=jobs.remove(v.mediaKey());if(j==null)return;j.removed=true;j.state="removed";prefs.edit().remove(j.key).commit();/* worker cleanup follows cancellation if it was in a read */if(j!=running)eraseFiles(j);}
 volatile Job running;
 public boolean wifi(){ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);NetworkCapabilities n=cm.getNetworkCapabilities(cm.getActiveNetwork());return n!=null&&(n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||n.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));}
 private void permitted(Job j)throws IOException{if(j.removed||!j.active()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("缓存已暂停");if(!wifi()){j.state="waiting";throw new InterruptedIOException("等待 Wi-Fi");}if(directory.getUsableSpace()<8L*1024*1024)throw new IOException("存储空间不足");}
 void run(Job j){running=j;try{permitted(j);j.state="downloading";save(j);StreamSpec spec=j.source;if(spec==null)spec=BiliApi.stream(j.video,j.quality,false);j.source=null;if(j.quality!=spec.quality){eraseFiles(j);j.quality=spec.quality;}j.label=spec.label;j.audio=spec.separated();HttpDownload probe=new HttpDownload();j.videoTotal=j.videoBytes>0?j.videoBytes:probe.length(spec.video,BiliApi.headers());j.audioTotal=j.audio?(j.audioBytes>0?j.audioBytes:probe.length(spec.audio,BiliApi.headers())):0;permitted(j);
   transfer(j,false,spec.video);if(j.audio)transfer(j,true,spec.audio);permitted(j);j.state="complete";j.done=j.total=j.videoBytes+j.audioBytes;j.error="";save(j);
  }catch(InterruptedIOException e){if(j.active()&&!j.state.equals("waiting"))j.state="paused";save(j);}catch(Exception e){if(j.active()){j.state="error";j.error=e.getMessage()==null?"下载失败，可重试":e.getMessage();save(j);}}
  finally{if(j.removed)eraseFiles(j);running=null;}}
 private void transfer(Job j,boolean audio,List<String> urls)throws IOException{File target=file(j,audio);long recorded=audio?j.audioBytes:j.videoBytes;if(recorded>0&&target.length()==recorded)return;j.track=audio?"音频":"视频";long prior=audio?j.videoBytes:0;
  new HttpDownload().transfer(urls,target,BiliApi.headers(),(done,total)->{permitted(j);j.done=prior+done;if(audio)j.audioTotal=total;else j.videoTotal=total;j.total=j.videoTotal>0&&(!j.audio||j.audioTotal>0)?j.videoTotal+Math.max(0,j.audioTotal):-1;if(SystemClock.uptimeMillis()-j.savedAt>1000)save(j);});if(audio)j.audioBytes=target.length();else j.videoBytes=target.length();save(j);
 }
}
