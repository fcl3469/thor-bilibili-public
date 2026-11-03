import local.thor.bilichannels.*;import org.json.*;import java.util.*;
public class MobileDiscoveryTest {
 static int cases;static void check(boolean ok,String message){cases++;if(!ok)throw new AssertionError(message);}
 static void rejects(JSONObject data,int page,String why)throws Exception{try{ApiModels.searchVideos(data,page);throw new AssertionError(why);}catch(java.io.IOException expected){cases++;}}
 public static void main(String[] args)throws Exception{
  JSONObject row=new JSONObject().put("type","video").put("bvid","BV1BqhB6nEdN").put("title","<em class=keyword>掌机</em> &amp; 评测").put("duration","10:23").put("author","UP").put("pic","//img.test/a.jpg");
  JSONArray rows=new JSONArray().put(row).put(row).put(new JSONObject().put("type","live").put("bvid","BV1S8hq6KEcm")).put(JSONObject.NULL).put(new JSONObject().put("bvid","../invalid"));
  JSONObject data=new JSONObject().put("page",1).put("result",new JSONArray().put(new JSONObject().put("result_type","bili_user").put("data",new JSONArray().put(row))).put(new JSONObject().put("result_type","video").put("data",rows)));
  List<Video> list=ApiModels.searchVideos(data,1);check(list.size()==1,"mixes non-video groups, live entries, invalid ids or duplicates");check(list.get(0).title.equals("掌机 & 评测"),"keyword highlights should be plain text");check(list.get(0).duration==623&&list.get(0).cover.startsWith("https://"),"mobile metadata");
  rejects(data,2,"must not silently repeat first page");rejects(new JSONObject().put("v_voucher","verification"),1,"captcha must be explicit");rejects(new JSONObject(),1,"missing search result must be explicit");
  check(ApiModels.searchVideos(new JSONObject().put("page",1).put("result",new JSONArray()),1).isEmpty(),"valid empty keyword results");
  data.put("page",2);check(ApiModels.searchVideos(data,2).size()==1,"confirmed page 2");
  SessionCookies jar=new SessionCookies();jar.merge("https://m.bilibili.com/","SESSDATA=mobile-example; bili_jct=csrf-example");check(jar.hasLogin()&&jar.header("https://api.bilibili.com/x").contains("mobile-example"),"official mobile session must return to native API");check(jar.header("https://m.bilibili.com.evil.test/").isEmpty()&&jar.header("https://upos.bilivideo.com/").isEmpty(),"mobile cookies remain origin scoped");
  JSONObject feed=new JSONObject().put("items",new JSONArray()
   .put(new JSONObject().put("goto","av").put("player_args",new JSONObject().put("aid",123)).put("idx",90))
   .put(new JSONObject().put("goto","av").put("param","123").put("idx",89))
   .put(new JSONObject().put("goto","live").put("param","456").put("idx",88))
   .put(new JSONObject().put("goto","av").put("param","789").put("ad_info",new JSONObject()).put("idx",87))
   .put(new JSONObject().put("goto","av").put("param","321").put("idx",86)));
  check(ApiModels.mobileRecommendationIds(feed).equals(Arrays.asList(123L,321L)),"mobile feed keeps real video IDs, deduplicates and excludes live/ads");
  check(ApiModels.mobileRecommendationCursor(feed).equals("86"),"mobile continuation uses server index, not guessed page offsets");
  boolean missingCursor=false;try{ApiModels.mobileRecommendationCursor(new JSONObject().put("items",new JSONArray()));}catch(java.io.IOException expected){missingCursor=true;}check(missingCursor,"missing mobile cursor cannot silently repeat page one");
  System.out.println("PASS: "+cases+" mobile discovery cases: grouped keyword results, page identity, challenge, metadata and mobile cookie scope");
 }
}
