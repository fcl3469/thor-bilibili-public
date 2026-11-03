import local.thor.bilichannels.*;import org.json.*;
public class QualityOptionsTest {
 static int count;static void check(boolean b,String why){count++;if(!b)throw new AssertionError(why);}
 static JSONObject video(int q,int codec)throws Exception{return new JSONObject().put("id",q).put("codecid",codec).put("baseUrl","https://cdn.test/"+q);}
 public static void main(String[] args)throws Exception{
  JSONObject response=new JSONObject().put("quality",64).put("accept_quality",new JSONArray("[116,80,64,32,16]"));
  JSONObject dash=new JSONObject().put("video",new JSONArray().put(video(80,7)).put(video(64,7)).put(video(32,7)).put(video(16,7))).put("audio",new JSONArray().put(new JSONObject().put("codecs","mp4a.40.2").put("baseUrl","https://cdn.test/audio")));response.put("dash",dash);
  QualityOptions guest=new QualityOptions(response);
  check(guest.available(16)&&guest.available(32)&&guest.available(64),"guest returned tiers must be usable");
  check(!guest.available(80),"track presence must not override server permission ceiling");
  check(guest.badge(80,false,-1).equals("需先登录"),"guest restriction explanation");
  check(guest.badge(120,false,-1).equals("视频未提供"),"missing 4K must not promise login upgrade");
  check(guest.badge(116,true,0).equals("会员画质"),"known nonmember explanation");
  check(guest.badge(80,true,1).equals("平台未开放"),"unknown restriction must not invent membership cause");
  check(guest.reason(80,true,1).contains("具体原因未说明"),"honest unknown reason");
  response.put("quality",120);response.put("accept_quality",new JSONArray("[120,80,64,32,16]"));dash.getJSONArray("video").put(video(120,12));QualityOptions vip=new QualityOptions(response);
  check(vip.available(80),"authorized full-HD available");check(vip.badge(120,true,1).equals("格式暂不支持"),"HEVC-only reason");
  dash.remove("audio");QualityOptions silent=new QualityOptions(response);check(!silent.available(64)&&silent.badge(64,true,1).equals("未取得音轨"),"missing audio reason");
  QualityOptions direct=new QualityOptions(new JSONObject("{\"quality\":32,\"durl\":[{\"url\":\"https://cdn.test/direct.mp4\"}]}"));check(direct.available(32),"progressive combined stream");
  check(!direct.available(80),"direct stream is a fixed tier");
  System.out.println("PASS: "+count+" quality availability/reason checks");
 }
}
