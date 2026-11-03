import local.thor.bilichannels.*;import org.json.*;import java.util.*;import java.nio.file.*;
import com.google.zxing.*;import com.google.zxing.common.*;
public class AccountAndMediaTest {
 static int cases;static void check(boolean ok,String message){cases++;if(!ok)throw new AssertionError(message);}
 static void rejects(JSONObject response,String reason)throws Exception{try{ApiModels.checked(response);throw new AssertionError(reason);}catch(java.io.IOException expected){cases++;}}
 static JSONObject track(int quality,String codec,String url)throws Exception{return new JSONObject().put("id",quality).put("codecid",codec.equals("avc")?7:12).put("baseUrl",url).put("backupUrl",new JSONArray().put(url+"?backup=1"));}
 public static void main(String[] args)throws Exception{
  SessionCookies jar=new SessionCookies();jar.merge("https://passport.bilibili.com","SESSDATA=example; bili_jct=csrf; Domain=.bilibili.com; unknown=no");
  check(jar.hasLogin(),"valid session missing");check(jar.header("https://api.bilibili.com/x").contains("SESSDATA=example"),"API auth missing");
  for(String url:Arrays.asList("https://example.org/","http://api.bilibili.com/","https://api.bilibili.com.evil.test/","https://api.bilibili.com@evil.test/","https://upos-sz.bilivideo.com/video.mp4"))check(jar.header(url).isEmpty(),"cookie leaked to "+url);
  jar.accept("https://example.org/",Arrays.asList("SESSDATA=bad; Domain=.bilibili.com"));check(jar.get("SESSDATA").equals("example"),"untrusted cookie accepted");
  jar.put("SESSDATA","attack\r\nX-Foo: injection");check(jar.get("SESSDATA").equals("example"),"header injection");
  jar.accept("https://passport.bilibili.com",Arrays.asList("SESSDATA=deleted; Max-Age=0; Domain=.bilibili.com"));check(!jar.hasLogin(),"session deletion ignored");
  check(SessionCookies.trusted("https://account.bilibili.com/h5/account-h5/auth/scan-web?qrcode_key=test"),"new official QR domain rejected");
  Map<String,String> p=new HashMap<>();p.put("foo","114");p.put("bar","514");p.put("zab","1919810");String sig=Wbi.sign(p,"7cd084941338484aae1ad9425b84077c4932caff0ff746eab6f01bf08b70ac45",1702204169L);
  check(sig.equals("bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4"),"WBI known vector: "+sig);
  check(Wbi.query(Collections.singletonMap("keyword","掌机 A&B")).equals("keyword=%E6%8E%8C%E6%9C%BA%20A%26B"),"query encoding");
  rejects(new JSONObject("{\"code\":0,\"data\":{\"v_voucher\":\"challenge\"}}"),"captcha reported as empty results");rejects(new JSONObject("{\"code\":-101,\"message\":\"login\"}"),"expired account accepted");rejects(new JSONObject("{\"code\":-111}"),"CSRF failure accepted");
  JSONObject dash=new JSONObject().put("video",new JSONArray().put(track(120,"hevc","https://cdn.test/hevc")).put(track(80,"avc","https://cdn.test/1080")).put(track(64,"avc","https://cdn.test/720")).put(track(16,"avc","https://cdn.test/360"))).put("audio",new JSONArray().put(new JSONObject().put("codecs","mp4a.40.2").put("bandwidth",192000).put("baseUrl","https://cdn.test/aac")));
  for(int[] q:new int[][]{{80,80},{64,64},{32,16},{120,80},{1,16}}){StreamSpec s=ApiModels.stream(new JSONObject().put("dash",dash),q[0]);check(s.quality==q[1],"wrong permitted quality");check(s.separated()&&s.audio.size()==1&&s.video.size()==2,"tracks/backups missing");}
  check(ApiModels.stream(new JSONObject().put("quality",64).put("dash",dash),120).quality==64,"server quality ceiling ignored");
  check(!new Video("same","","","",0,1,0,false).tileKey().equals(new Video("same","","","",0,2,0,false).tileKey()),"different cached parts share a tile identity");
  try{ApiModels.stream(new JSONObject().put("dash",new JSONObject().put("video",dash.getJSONArray("video"))),80);throw new AssertionError("silent incomplete stream accepted");}catch(java.io.IOException expected){cases++;}
  StreamSpec direct=ApiModels.stream(new JSONObject("{\"quality\":16,\"durl\":[{\"url\":\"http://cdn.test/muxed.mp4\"}]}"),80);check(!direct.separated()&&direct.video.get(0).startsWith("https://"),"progressive fallback");
  JSONArray results=new JSONArray("[{\"bvid\":\"BV1BqhB6nEdN\",\"title\":\"<em class=keyword>掌机</em> &amp; Thor\",\"author\":\"UP\",\"duration\":\"10:23\",\"pic\":\"//img.test/a.jpg\",\"dimension\":{\"width\":1920,\"height\":1080,\"rotate\":90}},{\"bvid\":\"../../escape\"}]");
  List<Video> videos=ApiModels.videos(results);check(videos.size()==1,"invalid video id accepted");check(videos.get(0).title.equals("掌机 & Thor")&&videos.get(0).duration==623&&videos.get(0).isPortrait(),"search metadata parsing");
  try(PinyinInputStream in=new PinyinInputStream(args[0])){PinyinInput input=new PinyinInput(in);for(char c:"zhang".toCharArray())input.type(c);check(input.candidates().contains("掌"),"pinyin lacks 掌");int i=input.candidates().indexOf('掌');input.page=i/10;input.choose(i%10);for(char c:"ji".toCharArray())input.type(c);i=input.candidates().indexOf('机');input.page=i/10;input.choose(i%10);check(input.text.equals("掌机"),"controller Chinese input");input.backspace();check(input.text.equals("掌"),"Chinese deletion");input.type('l');input.type('v');check(input.candidates().contains("绿"),"tone-marked umlaut pinyin");}
  String qr="https://account.bilibili.com/h5/account-h5/auth/scan-web?qrcode_key=host-test-only";BitMatrix m=new MultiFormatWriter().encode(qr,BarcodeFormat.QR_CODE,480,480);int[] pixels=new int[480*480];for(int y=0;y<480;y++)for(int x=0;x<480;x++)pixels[y*480+x]=m.get(x,y)?0xff000000:0xffffffff;String decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(480,480,pixels)))).getText();check(qr.equals(decoded),"QR round trip");
  System.out.println("PASS: "+cases+" account/media checks: origin scope, deletion, WBI, challenge detection, tracks, quality, search, Chinese input, QR round trip");
 }
 static class PinyinInputStream extends java.io.FileInputStream {PinyinInputStream(String p)throws Exception{super(p);}}
}
