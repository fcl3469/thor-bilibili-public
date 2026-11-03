package local.thor.bilichannels;
import java.io.IOException;import java.util.*;import org.json.*;

/** Official season catalogs and episodes have identities independent of archive BV IDs. */
public final class PgcModels {
 public static final int[] TYPES={1,4,2,3,5,7};
 public static final String[] NAMES={"番剧","国创","电影","纪录片","电视剧","综艺"};
 public static String category(int type){for(int i=0;i<TYPES.length;i++)if(TYPES[i]==type)return NAMES[i];return "影视";}
 public static final class Show {
  public final long id;public final int type;public final String title,cover,badge,update,score,summary;
  public Show(long id,int type,String title,String cover,String badge,String update,String score,String summary){this.id=id;this.type=type;this.title=title;this.cover=cover;this.badge=badge;this.update=update;this.score=score;this.summary=summary;}
 }
 public static final class Page {public final List<Show> items;public final int page;public final boolean more;Page(List<Show> items,int page,boolean more){this.items=items;this.page=page;this.more=more;}}
 public static final class Season {
  public final Show show;public final List<Video> episodes;public final String description;
  Season(Show show,List<Video> episodes,String description){this.show=show;this.episodes=Collections.unmodifiableList(episodes);this.description=description;for(Video v:episodes)v.pgcEpisodes=this.episodes;}
 }
 public static JSONObject checked(JSONObject root)throws Exception{
  int code=root.optInt("code",-1);if(code!=0){String message=root.optString("message",root.optString("msg","请求失败"));if(code==-101)message="请先登录 B站账号";else if(code==-10403||code==10015002)message="当前账号暂不可播放，请检查会员、购片或地区权限";throw new IOException(message+"（"+code+"）");}
  JSONObject result=root.optJSONObject("result");if(result==null)result=root.optJSONObject("data");if(result==null)throw new IOException("平台未返回影视数据，请稍后重试");if(result.has("v_voucher"))throw new IOException("B站要求安全验证，请在账户页打开官方验证后重试");return result;
 }
 static String title(JSONObject j){return ApiModels.plain(j.optString("title"));}
 public static Page page(JSONObject d,int requested,int type)throws Exception{
  int page=d.optInt("num",d.optInt("page",requested));if(page!=requested)throw new IOException("影视页码不一致，请重试");JSONArray rows=d.optJSONArray("list");if(rows==null)rows=d.optJSONArray("result");if(rows==null)throw new IOException("平台未返回影视列表");
  List<Show> items=new ArrayList<>();Set<Long> seen=new HashSet<>();for(int i=0;i<rows.length();i++){JSONObject j=rows.optJSONObject(i);if(j==null)continue;long id=j.optLong("season_id");if(id<=0||!seen.add(id)||title(j).isEmpty())continue;items.add(new Show(id,j.optInt("season_type",type),title(j),ApiModels.secure(j.optString("cover")),j.optString("badge"),j.optString("index_show",j.optString("index_show_text")),j.optString("score"),j.optString("subTitle",j.optString("desc"))));}
  boolean more=d.has("has_next")?flag(d,"has_next"):d.has("numPages")?page<d.optInt("numPages"):d.has("total")?page*Math.max(1,d.optInt("size",24))<d.optInt("total"):rows.length()>0;
  return new Page(items,page,more);
 }
 public static boolean flag(JSONObject j,String key){return j.optBoolean(key,false)||j.optInt(key,0)==1;}
 public static Season season(JSONObject d)throws Exception{
  long sid=d.optLong("season_id");if(sid<=0)throw new IOException("作品标识缺失");JSONObject update=d.optJSONObject("new_ep"),rating=d.optJSONObject("rating");String description=d.optString("evaluate");Show show=new Show(sid,d.optInt("type"),title(d),ApiModels.secure(d.optString("cover")),"",update==null?"":update.optString("desc"),rating==null?"":rating.optString("score"),description);
  List<Video> episodes=new ArrayList<>();Set<Long> seen=new HashSet<>();appendEpisodes(episodes,seen,d.optJSONArray("episodes"),show,description,"");JSONArray sections=d.optJSONArray("section");if(sections!=null)for(int i=0;i<sections.length();i++){JSONObject section=sections.optJSONObject(i);if(section!=null)appendEpisodes(episodes,seen,section.optJSONArray("episodes"),show,description,section.optString("title"));}
  return new Season(show,episodes,description);
 }
 static void appendEpisodes(List<Video> out,Set<Long> seen,JSONArray rows,Show show,String description,String section){if(rows==null)return;for(int i=0;i<rows.length();i++){JSONObject e=rows.optJSONObject(i);if(e==null||e.optBoolean("is_view_hide"))continue;long ep=e.optLong("id",e.optLong("ep_id"));if(ep<=0||!seen.add(ep))continue;String name=e.optString("show_title");if(name.isEmpty()){name=e.optString("title");if(name.matches("[0-9.]+"))name="第 "+name+" 集";if(!e.optString("long_title").isEmpty())name+=" "+e.optString("long_title");}if(!section.isEmpty())name=section+" · "+name;String id=e.optString("bvid");if(!id.matches("BV[0-9A-Za-z]{10}"))id="ep"+ep;
   Video v=new Video(id,show.title+" · "+name,category(show.type),ApiModels.secure(e.optString("cover",show.cover)),e.optLong("aid"),e.optLong("cid"),(int)Math.min(Integer.MAX_VALUE/1000,e.optLong("duration")/1000),false);v.seasonId=show.id;v.episodeId=ep;v.seasonTitle=show.title;v.episodeTitle=name;v.pgcBadge=e.optString("badge");v.description=description;v.partIndex=out.size();JSONObject dim=e.optJSONObject("dimension");if(dim!=null)v.setDimensions(dim.optInt("width"),dim.optInt("height"),dim.optInt("rotate"));v.details=new JSONObject();v.detailsAt=System.currentTimeMillis();out.add(v);
  }}
 public static StreamSpec stream(JSONObject d,int quality)throws Exception{
  if(flag(d,"is_drm"))throw new IOException("此片使用受保护播放，请在 B站官方客户端观看");int code=d.optInt("code",0);if(code!=0)throw new IOException(d.optString("message","当前账号暂不可播放")+"（"+code+"）");StreamSpec spec=ApiModels.stream(d,quality);return flag(d,"is_preview")?new StreamSpec(spec.video,spec.audio,spec.quality,"试看 · "+spec.label,spec.options):spec;
 }
 private PgcModels(){}
}
