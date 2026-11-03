package local.thor.bilichannels;
import org.json.*;

/** Uses the same authenticated, origin-scoped transport as ordinary videos. */
final class PgcApi {
 static JSONObject data(String path)throws Exception{return PgcModels.checked(BiliApi.json("https://api.bilibili.com"+path));}
 static PgcModels.Page catalog(int type,int page)throws Exception{return PgcModels.page(data("/pgc/season/index/result?season_type="+type+"&type=1&order=3&sort=0&page="+page+"&pagesize=24"),page,type);}
 static PgcModels.Page search(int type,String query,int page)throws Exception{BiliApi.bootstrap();String kind=type==1||type==4?"media_bangumi":"media_ft";java.util.Map<String,String> p=BiliApi.params("search_type",kind,"keyword",query,"page",Integer.toString(page),"page_size","24","platform","h5","web_location","1280306");return PgcModels.page(data("/x/web-interface/wbi/search/type?"+Wbi.sign(p,BiliApi.wbiKeys,System.currentTimeMillis()/1000)),page,type);}
 static PgcModels.Season season(long sid)throws Exception{return PgcModels.season(data("/pgc/view/web/season?season_id="+sid));}
 static JSONObject playData(Video v,int quality)throws Exception{return data("/pgc/player/web/playurl?ep_id="+v.episodeId+"&cid="+v.cid+"&qn="+(quality==0?127:quality)+"&fnval=4048&fourk=1");}
 static StreamSpec stream(Video v,int quality)throws Exception{return PgcModels.stream(playData(v,quality),quality);}
}
