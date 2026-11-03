package local.thor.bilichannels;

import android.app.AlertDialog;import android.content.Intent;import org.json.*;
import java.io.*;import java.lang.ref.WeakReference;import java.util.*;import java.util.concurrent.*;

/** Native cloud browsing, selection, and local resume history owned by one Activity. */
final class XunleiCloud {
 static final class Page {
  final String id,name,space;final ArrayList<XunleiLibrary.Item> items=new ArrayList<>();String next="",anchor="";int first,top,revision;boolean loaded;
  Page(String id,String name,String space){this.id=id;this.name=name;this.space=space;}
 }
 final MainActivity a;XunleiCloudClient client;java.util.function.Consumer<JSONObject> playback;private XunleiLibrary library;
 Page page=new Page("","迅雷云盘","");final ArrayDeque<Page> stack=new ArrayDeque<>();
 private WeakReference<XunleiCloudPane> view=new WeakReference<>(null);private int generation;private boolean busy,closed;String status="正在连接迅雷云盘…",playbackError="";boolean login,playbackPending;
 private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"thor-cloud-history");t.setDaemon(true);return t;});
 XunleiCloud(MainActivity a){this.a=a;client=new XunleiCloudClient(a);playback=a::openXunleiVideo;}
 XunleiLibrary library(){if(library==null)try{library=new XunleiLibrary(new File(a.getFilesDir(),"xunlei-history.json"));}catch(IOException error){a.message("云盘历史读取失败");}return library;}
 String account(){return client.account();}
 List<XunleiLibrary.Record> recent(){XunleiLibrary store=library();return store==null?Collections.emptyList():store.recent(account(),System.currentTimeMillis());}
 void attach(XunleiCloudPane pane){view=new WeakReference<>(pane);if(!page.loaded&&!busy)refresh();else notifyView();}
 void detach(XunleiCloudPane pane){if(view.get()==pane){view.clear();client.suspend();}}
 boolean busy(){return busy;}
 void notifyView(){XunleiCloudPane pane=view.get();if(pane!=null)pane.bind();if(a.gallery!=null&&a.gallery.localPane!=null)a.gallery.localPane.cloudStatusChanged();}
 void refresh(){if(closed)return;rememberScroll();int epoch=++generation;busy=true;login=false;playbackPending=false;playbackError="";status="正在连接迅雷云盘…";notifyView();String previous=account();
  client.identity((identity,error)->{if(closed||epoch!=generation)return;if(error!=null){fail(error);return;}if(!identity.optBoolean("logged_in")){page=new Page("","迅雷云盘","");stack.clear();login=true;fail("登录迅雷账号后查看云盘视频");return;}if(!previous.equals(account())){page=new Page("","迅雷云盘","");stack.clear();}load(false,epoch);});
 }
 private void load(boolean append,int epoch){if(append)rememberScroll();busy=true;status="正在读取视频…";notifyView();Page target=page;String owner=account();try{
  JSONObject args=new JSONObject().put("parent",target.id).put("space",target.space).put("page",append?target.next:"");
  client.call("list",args,(data,error)->{if(closed||epoch!=generation||page!=target||!owner.equals(account()))return;if(error!=null){fail(error);return;}try{
   List<XunleiLibrary.Item> items=XunleiLibrary.mergeCatalog(append?target.items:Collections.emptyList(),data.optJSONArray("files"));
   target.items.clear();target.items.addAll(items);target.first=XunleiLibrary.catalogAnchor(target.items,target.anchor,target.first);target.revision++;target.next=data.optString("next");target.loaded=true;busy=false;status=target.items.isEmpty()?"这个文件夹里没有视频":target.items.size()+" 项 · 最新在前";notifyView();
  }catch(JSONException parse){fail("云盘列表无法读取，请刷新重试");}});
 }catch(JSONException ignored){fail("云盘目录无效");}}
 void more(){if(!busy&&!page.next.isEmpty())load(true,++generation);}
 void open(XunleiLibrary.Item item){if(item.locked){a.message("请在账号页面完成保险箱验证；普通云盘可直接播放");return;}if(item.folder){rememberScroll();stack.push(page);page=new Page(item.id,item.name,item.space);load(false,++generation);}else play(item,"",false);}
 void rememberScroll(){XunleiCloudPane pane=view.get();if(pane!=null)pane.rememberScroll();}
 boolean back(){if(stack.isEmpty())return false;generation++;busy=playbackPending=false;playbackError="";page=stack.pop();status=page.items.size()+" 个文件夹 / 视频";notifyView();return true;}
 void login(){rememberScroll();a.startActivityForResult(new Intent(a,XunleiCloudActivity.class),45);}
 void loggedIn(){generation++;busy=false;page=new Page("","迅雷云盘","");stack.clear();client.reload();refresh();}
 void playRecord(XunleiLibrary.Record record){play(record.item,record.account,false);}
 void chooseQuality(Video video){if(!video.cloud())return;XunleiLibrary.Record record=library()==null?null:library().get(video.cloudAccount,video.cloudFile,video.cloudSpace);if(record==null){a.message("请从云盘列表重新选择视频");return;}play(record.item,record.account,true);}
 void play(XunleiLibrary.Item item,String expected,boolean choose){
  if(closed||a.dead)return;rememberScroll();int epoch=++generation;busy=playbackPending=true;playbackError="";status="正在获取播放地址…";notifyView();
  // A play click supersedes a pending catalog request. Allocate its generation
  // before identity lookup so a late response cannot revive an older click.
  client.identity((identity,error)->{if(closed||a.dead||epoch!=generation)return;if(error!=null){fail(error);return;}if(!identity.optBoolean("logged_in")){login=true;fail("请先登录迅雷账号后重试");return;}String owner=account();if(!expected.isEmpty()&&!owner.equals(expected)){fail("请登录对应迅雷账号后继续这条记录");return;}resolvePlay(item,owner,choose,epoch);});
 }
 private void resolvePlay(XunleiLibrary.Item item,String owner,boolean choose,int epoch){
  try{client.call("play",item.json(),(data,error)->{if(closed||epoch!=generation)return;if(!owner.equals(account())){fail("迅雷账号已变化，请刷新云盘");return;}if(error!=null){fail(error);return;}try{
   JSONArray streams=data.getJSONArray("streams");if(streams.length()==0){fail("这个视频暂时没有可用播放地址");return;}
   busy=playbackPending=false;status=page.items.size()+" 个文件夹 / 视频";notifyView();
   if(choose&&streams.length()>1){String[] labels=new String[streams.length()];for(int i=0;i<labels.length;i++)labels[i]=streams.getJSONObject(i).optString("label","云盘视频");new AlertDialog.Builder(a).setTitle("播放清晰度").setItems(labels,(d,index)->{try{start(item,owner,streams.getJSONObject(index));}catch(JSONException ignored){}}).setNegativeButton("取消",null).show();notifyView();return;}
   int selected=0;XunleiLibrary.Record record=library()==null?null:library().get(owner,item.id,item.space);if(record!=null)for(int i=0;i<streams.length();i++)if(record.quality.equals(streams.getJSONObject(i).optString("label")))selected=i;
   start(item,owner,streams.getJSONObject(selected));
  }catch(JSONException errorValue){fail("视频地址无法读取，请刷新后重试");}});}catch(JSONException ignored){fail("视频信息无效");}
 }
 private void start(XunleiLibrary.Item item,String owner,JSONObject stream)throws JSONException{
  if(!owner.equals(account())){fail("迅雷账号已变化，请重新选择视频");return;}JSONObject media=item.json().put("account_key",owner).put("url",stream.getString("url")).put("label",stream.optString("label")).put("mime",stream.optString("mime",item.mime));
  playback.accept(media);
 }
 void remember(JSONObject media){try{String account=media.optString("account_key");if(account.isEmpty()||library()==null)return;XunleiLibrary.Item item=new XunleiLibrary.Item(media);XunleiLibrary.Record record=library().played(account,item,media.optString("label"),System.currentTimeMillis());int position=(int)Math.min(Integer.MAX_VALUE,record.position);a.getPreferences(0).edit().putInt("position_xunlei-"+record.key+"_0",position).apply();}catch(IOException error){a.message("云盘播放记录暂未保存");}}
 void checkpoint(Video video,int position,int duration){if(!video.cloud()||library()==null||closed)return;long now=System.currentTimeMillis();try{writer.execute(()->{try{library().progress(video.cloudAccount,video.cloudFile,video.cloudSpace,position,duration,now);}catch(IOException ignored){}});}catch(RejectedExecutionException ignored){}}
 private void fail(String error){if(playbackPending)playbackError=error;busy=playbackPending=false;status=error;notifyView();XunleiCloudPane pane=view.get();if(pane==null||!pane.isShown())a.message(error);}
 void close(){closed=true;generation++;view.clear();client.close();writer.shutdown();}
}
