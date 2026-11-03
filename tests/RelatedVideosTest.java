import local.thor.bilichannels.*;import org.json.*;import java.util.*;
public class RelatedVideosTest {
 static int cases;static void check(boolean ok,String why){cases++;if(!ok)throw new AssertionError(why);}
 static JSONObject row(String id)throws Exception{return new JSONObject().put("bvid",id).put("title","<b>相关推荐</b> &amp; 标题").put("pic","http://img.test/cover.jpg").put("duration",123).put("owner",new JSONObject().put("name","UP 主").put("mid",1234)).put("stat",new JSONObject().put("view",12345));}
 static void rejects(JSONObject data)throws Exception{try{ApiModels.related(data,"BV1GJ411x7h7");throw new AssertionError("must reject malformed/error response");}catch(java.io.IOException expected){cases++;}}
 public static void main(String[] args)throws Exception{
  String current="BV1GJ411x7h7",other="BV1eS4y157Ey";JSONArray rows=new JSONArray().put(row(current)).put(row(other)).put(row(other)).put(row("bad-id")).put(JSONObject.NULL).put(row("BV1Qs411d7pd").put("state",-4));
  List<Video> found=ApiModels.related(new JSONObject().put("code",0).put("data",rows),current);check(found.size()==1&&found.get(0).id.equals(other),"exclude source, duplicates, invalid and unavailable videos");Video v=found.get(0);check(v.title.equals("相关推荐 & 标题"),"plain title");check(v.up.equals("UP 主")&&v.upMid==1234&&v.duration==123,"UP and duration");check(v.cover.startsWith("https://")&&v.views.equals("1.2 万"),"safe cover and play count");
  check(ApiModels.related(new JSONObject().put("code",0).put("data",new JSONArray()),current).isEmpty(),"valid empty recommendations");
  rejects(new JSONObject().put("code",-101).put("message","login"));rejects(new JSONObject().put("code",0));rejects(new JSONObject().put("code",0).put("data",new JSONObject().put("v_voucher","challenge")));
  rows=new JSONArray();for(int i=0;i<20;i++)rows.put(row(String.format(Locale.ROOT,"BV%010d",i)));found=ApiModels.related(new JSONObject().put("code",0).put("data",rows),current);check(found.size()==12&&found.get(11).id.equals("BV0000000011"),"bounded list preserves platform order");
  System.out.println("PASS: "+cases+" related-video parsing/filtering/error cases");
 }
}
