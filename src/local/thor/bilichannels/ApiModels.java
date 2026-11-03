package local.thor.bilichannels;
import org.json.*;import java.util.*;import java.io.IOException;
public final class ApiModels {
 public static JSONObject checked(JSONObject j)throws Exception{int code=j.optInt("code",-1);if(code!=0)throw new IOException(code==-101?"登录已失效，请重新登录":"B站返回 "+code+"："+j.optString("message","请求失败"));JSONObject d=j.optJSONObject("data");if(d==null)d=new JSONObject();if(d.has("v_voucher"))throw new IOException("B站要求安全验证，请在账户页打开官方验证后重试");return d;}
 public static String plain(String s){return s.replaceAll("<[^>]*>","").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&nbsp;"," ");}
 static String secure(String s){if(s.startsWith("//"))s="https:"+s;if(s.startsWith("http://"))s="https://"+s.substring(7);return s;}
 public static List<String> urls(JSONObject o){List<String> urls=new ArrayList<>();String first=secure(o.optString("baseUrl",o.optString("base_url",o.optString("url"))));if(first.startsWith("https://"))urls.add(first);JSONArray backup=o.optJSONArray("backupUrl");if(backup==null)backup=o.optJSONArray("backup_url");if(backup!=null)for(int i=0;i<backup.length();i++){String s=secure(backup.optString(i));if(s.startsWith("https://")&&!urls.contains(s))urls.add(s);}return urls;}
 public static StreamSpec stream(JSONObject d,int desired)throws Exception{
  if(desired==0)desired=127;desired=Math.min(desired,d.optInt("quality",desired));
  JSONArray direct=d.optJSONArray("durl");if(direct!=null&&direct.length()==1){List<String> u=urls(direct.getJSONObject(0));if(!u.isEmpty())return new StreamSpec(u,Collections.emptyList(),d.optInt("quality",16),qualityName(d.optInt("quality",16)),new QualityOptions(d));}
  JSONObject dash=d.optJSONObject("dash");if(dash==null)throw new IOException("该视频暂未返回可播放格式");JSONArray tracks=dash.optJSONArray("video");JSONObject best=null;int bestScore=Integer.MIN_VALUE;
  if(tracks!=null)for(int i=0;i<tracks.length();i++){JSONObject v=tracks.getJSONObject(i);int q=v.optInt("id"),codec=v.optInt("codecid");if(d.has("quality")&&q>d.optInt("quality"))continue;if(codec!=7&&codec!=0)continue;int score=q<=desired?q:-(q+1000);if(score>bestScore&&!urls(v).isEmpty()){best=v;bestScore=score;}}
  if(best==null)throw new IOException("没有可用的 H.264 视频轨道");JSONArray aud=dash.optJSONArray("audio");JSONObject audio=null;
  if(aud!=null)for(int i=0;i<aud.length();i++){JSONObject a=aud.getJSONObject(i);if(a.optString("codecs").startsWith("mp4a")&&(audio==null||a.optInt("bandwidth")>audio.optInt("bandwidth"))&&!urls(a).isEmpty())audio=a;}
  if(audio==null)throw new IOException("未取得可用音轨，未开始静音播放");int q=best.optInt("id");
  android.util.Log.i("ThorQuality","requested="+desired+" actual="+q+" codec="+best.optInt("codecid")+" codecs="+best.optString("codecs")+" bandwidth="+best.optInt("bandwidth"));
  return new StreamSpec(urls(best),urls(audio),q,qualityName(q),new QualityOptions(d));
 }
 public static String qualityName(int q){switch(q){case 0:return "自动最高画质";case 127:return "8K";case 126:return "杜比视界";case 125:return "HDR";case 16:return "360P";case 32:return "480P";case 64:return "720P";case 74:return "720P60";case 80:return "1080P";case 112:return "1080P+";case 116:return "1080P60";case 120:return "4K";default:return "画质 "+q;}}
 public static Video video(JSONObject j){String id=j.optString("bvid",j.optString("id"));if(!id.matches("BV[0-9A-Za-z]{10}"))return null;JSONObject owner=j.optJSONObject("owner");if(owner==null)owner=j.optJSONObject("upper");String up=owner==null?j.optString("author",j.optString("up")):owner.optString("name");int duration=j.optInt("duration");String time=j.optString("duration",j.optString("length"));if(time.contains(":")){duration=0;for(String part:time.split(":"))try{duration=duration*60+Integer.parseInt(part.trim());}catch(Exception ignored){}}
  Video v=new Video(id,plain(j.optString("title")),up,secure(j.optString("pic",j.optString("cover"))),j.optLong("aid",j.optLong("id")),j.optLong("cid"),duration,false);v.upMid=owner==null?j.optLong("mid",j.optLong("author_mid")):owner.optLong("mid");v.upAvatar=secure(owner==null?j.optString("upic",j.optString("author_face")):owner.optString("face"));v.description=j.optString("desc",j.optString("description"));JSONObject dim=j.optJSONObject("dimension");if(dim!=null)v.setDimensions(dim.optInt("width"),dim.optInt("height"),dim.optInt("rotate"));JSONObject stat=j.optJSONObject("stat");long views=stat==null?j.optLong("play"):stat.optLong("view");v.views=views>=10000?String.format(Locale.ROOT,"%.1f 万",views/10000.0):String.valueOf(views);return v;
 }
 public static List<Long> mobileRecommendationIds(JSONObject data)throws IOException{
  JSONArray rows=data.optJSONArray("items");if(rows==null)throw new IOException("未取得手机推荐列表");List<Long> ids=new ArrayList<>();
  for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null||!"av".equals(row.optString("goto"))||row.has("ad_info"))continue;JSONObject player=row.optJSONObject("player_args");long aid=player==null?row.optLong("param"):player.optLong("aid");if(aid>0&&!ids.contains(aid))ids.add(aid);}
  return ids;
 }
 public static String mobileRecommendationCursor(JSONObject data)throws IOException{
  JSONArray rows=data.optJSONArray("items");if(rows!=null)for(int i=rows.length()-1;i>=0;i--){JSONObject row=rows.optJSONObject(i);long idx=row==null?0:row.optLong("idx");if(idx>0)return Long.toString(idx);}throw new IOException("手机推荐未返回续页标记");
 }
 public static FeedPager.Page feedPage(JSONObject data,int page,boolean recommended)throws Exception{JSONArray rows=data.optJSONArray(recommended?"item":"list");if(rows==null)throw new IOException("未取得视频列表");boolean more=data.has("no_more")?!data.optBoolean("no_more",false):recommended||rows.length()>0;return new FeedPager.Page(videos(rows),page,more,"");}
 public static FeedPager.Page followingPage(JSONObject data,int page,String previous)throws Exception{JSONArray rows=data.optJSONArray("items");if(rows==null)throw new IOException("未取得动态列表");JSONArray out=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i),modules=row==null?null:row.optJSONObject("modules");if(modules==null)continue;JSONObject dynamic=modules.optJSONObject("module_dynamic"),major=dynamic==null?null:dynamic.optJSONObject("major"),archive=major==null?null:major.optJSONObject("archive");if(archive==null)continue;JSONObject author=modules.optJSONObject("module_author");archive.put("author",author==null?"":author.optString("name"));archive.put("pic",archive.optString("cover"));archive.put("duration",archive.optString("duration_text"));out.put(archive);}String cursor=data.optString("offset");boolean more=data.optInt("has_more",data.optBoolean("has_more",false)?1:0)!=0;if(more&&(cursor.isEmpty()||cursor.equals(previous)))throw new IOException("动态续页标记未更新，继续上滑重试");return new FeedPager.Page(videos(out),page,more,cursor);}
 public static SearchPager.Page searchPage(JSONObject data,int requestedPage)throws Exception{
  if(data.has("v_voucher"))throw new IOException("B站要求安全验证，请在账户页打开官方验证后重试");
  if(data.has("page")&&data.optInt("page")!=requestedPage)throw new IOException("平台返回的搜索页码不一致，请重试");
  JSONArray result=data.optJSONArray("result");if(result==null)throw new IOException("平台未返回关键词搜索结果，请稍后重试");
  JSONArray rows=new JSONArray();JSONObject pageInfo=data;boolean grouped=false;
  for(int i=0;i<result.length();i++){JSONObject row=result.optJSONObject(i);if(row==null)continue;
   if(row.has("result_type")){grouped=true;if(!"video".equals(row.optString("result_type")))continue;JSONArray group=row.optJSONArray("data");if(group!=null)for(int n=0;n<group.length();n++){JSONObject item=group.optJSONObject(n);if(item!=null&&(!item.has("type")||"video".equals(item.optString("type"))))rows.put(item);}}
   else if(!row.has("type")||"video".equals(row.optString("type")))rows.put(row);
  }
  if(grouped){JSONObject info=data.optJSONObject("pageinfo");pageInfo=info==null?new JSONObject():info.optJSONObject("video");if(pageInfo==null)pageInfo=new JSONObject();}
  int pages=pageInfo.optInt("numPages",pageInfo.optInt("totalPages",-1));List<Video> videos=videos(rows);
  // A short page can contain advertisements or filtered entries; only server EOF/empty data ends pagination.
  boolean more=pages>=0?requestedPage<pages:result.length()>0;
  return new SearchPager.Page(videos,requestedPage,pages,more,data.has("page"));
 }
 public static List<Video> searchVideos(JSONObject data,int page)throws Exception{return searchPage(data,page).items;}
 public static List<Video> related(JSONObject response,String current)throws Exception{checked(response);JSONArray rows=response.optJSONArray("data");if(rows==null)throw new IOException("平台未返回相关推荐，请稍后重试");JSONArray available=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optInt("state",0)>=0&&!row.optString("bvid").equals(current))available.put(row);}List<Video> list=videos(available);return new ArrayList<>(list.subList(0,Math.min(12,list.size())));}
 public static List<Video> videos(JSONArray a){List<Video> out=new ArrayList<>();Set<String> ids=new HashSet<>();if(a!=null)for(int i=0;i<a.length();i++){JSONObject j=a.optJSONObject(i);if(j==null)continue;Video v=video(j);if(v!=null&&ids.add(v.id))out.add(v);}return out;}
}
