package local.thor.bilichannels;
import java.util.*;import java.io.IOException;import org.json.*;

/** Public creator posts, including text, drawings, video shares and forwarded posts. */
public final class CreatorModels {
 public static final class Post {
  public final String id,text,time;public final List<String> images;public final Video video;
  Post(String id,String text,String time,List<String> images,Video video){this.id=id;this.text=text;this.time=time;this.images=images;this.video=video;}
 }
 public static final class Page {public final List<Post> rows;public final String cursor;public final boolean more;Page(List<Post> rows,String cursor,boolean more){this.rows=rows;this.cursor=cursor;this.more=more;}}
 public static FeedPager.Page videos(JSONObject data,int requested,long mid,String name,String face)throws Exception{
  JSONObject list=data.optJSONObject("list"),page=data.optJSONObject("page");JSONArray array=list==null?null:list.optJSONArray("vlist");
  if(array==null||page==null||page.optInt("pn",requested)!=requested)throw new IOException("UP 主视频页未完整返回，请重试");
  List<Video> rows=ApiModels.videos(array);for(Video v:rows){v.upMid=mid;v.upAvatar=face;}
  return new FeedPager.Page(rows,requested,requested*Math.max(1,page.optInt("ps",30))<page.optInt("count"),"");
 }
 public static Page dynamics(JSONObject data,String previous)throws Exception{
  JSONArray items=data.optJSONArray("items");if(items==null)throw new IOException("UP 主动态未完整返回，请重试");List<Post> rows=new ArrayList<>();Set<String> ids=new HashSet<>();
  for(int i=0;i<items.length();i++){JSONObject row=items.optJSONObject(i);if(row!=null){Post p=post(row,0);if(!p.id.isEmpty()&&ids.add(p.id))rows.add(p);}}
  String cursor=data.optString("offset");boolean more=data.optInt("has_more",data.optBoolean("has_more",false)?1:0)!=0;
  if(more&&(cursor.isEmpty()||cursor.equals(previous)))throw new IOException("UP 主动态续页标记未更新，请重试");return new Page(rows,cursor,more);
 }
 static Post post(JSONObject row,int depth)throws Exception{
  JSONObject modules=row.optJSONObject("modules");if(modules==null)modules=new JSONObject();JSONObject author=modules.optJSONObject("module_author"),d=modules.optJSONObject("module_dynamic");if(d==null)d=new JSONObject();
  JSONObject desc=d.optJSONObject("desc"),major=d.optJSONObject("major");String text=desc==null?"":desc.optString("text");List<String> images=new ArrayList<>();Video video=null;
  if(major!=null){JSONObject archive=major.optJSONObject("archive");if(archive!=null){JSONObject copy=new JSONObject(archive.toString());copy.put("author",author==null?"":author.optString("name"));copy.put("pic",archive.optString("cover"));copy.put("duration",archive.optString("duration_text"));video=ApiModels.video(copy);if(video!=null){video.upMid=author==null?0:author.optLong("mid");video.upAvatar=author==null?"":ApiModels.secure(author.optString("face"));}if(text.isEmpty())text=archive.optString("title");}
   JSONObject draw=major.optJSONObject("draw");JSONArray pictures=draw==null?null:draw.optJSONArray("items");if(pictures!=null)for(int i=0;i<Math.min(18,pictures.length());i++){JSONObject p=pictures.optJSONObject(i);if(p!=null){String url=ApiModels.secure(p.optString("src"));if(url.startsWith("https://"))images.add(url);}}
   if(text.isEmpty())for(String type:new String[]{"article","opus","common","live_rcmd","blocked"}){JSONObject content=major.optJSONObject(type);if(content!=null){text=content.optString("title",content.optString("hint"));JSONObject summary=content.optJSONObject("summary");if(summary!=null)text+="\n"+summary.optString("text");if(!text.isEmpty())break;}}
   JSONObject opus=major.optJSONObject("opus");JSONArray pics=opus==null?null:opus.optJSONArray("pics");if(images.isEmpty()&&pics!=null)for(int i=0;i<Math.min(18,pics.length());i++){String url=ApiModels.secure(pics.getJSONObject(i).optString("url"));if(url.startsWith("https://"))images.add(url);}
  }
  JSONObject orig=row.optJSONObject("orig");if(orig!=null&&depth<2){Post p=post(orig,depth+1);text+=(text.isEmpty()?"":"\n\n")+"转发 · "+p.text;images.addAll(p.images);if(video==null)video=p.video;}
  if(text.isEmpty())text="这条动态的内容暂不支持展示";
  return new Post(row.optString("id_str"),ApiModels.plain(text),author==null?"":author.optString("pub_time"),images,video);
 }
}
