import local.thor.bilichannels.*;import org.json.*;
public class CommentModelTest {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  JSONObject row=new JSONObject("{\"rpid_str\":\"12345678901234567\",\"like\":123,\"action\":1,\"ctime\":1234,\"rcount\":3,\"member\":{\"mid\":42,\"uname\":\"测试用户\",\"avatar\":\"//img.test/a.webp\"},\"content\":{\"message\":\"第一行\\n第二行[doge]\"},\"reply_control\":{\"location\":\"IP属地：上海\"},\"replies\":[{\"rpid\":2,\"member\":{\"uname\":\"回复者\"},\"content\":{\"message\":\"楼中楼\"}}]}");
  Comment c=Comment.parse(row);check(c.id==12345678901234567L,"64-bit reply identity");check(c.liked&&c.likes==123,"server like state");check(c.avatar.equals("https://img.test/a.webp"),"secure avatar");check(c.message.contains("\n")&&c.message.contains("[doge]"),"preserve body formatting");check(c.children.size()==1&&c.children.get(0).name.equals("回复者"),"reply preview author");check(c.replies==3&&c.location.contains("上海"),"reply count and metadata");
  JSONObject data=new JSONObject().put("page",new JSONObject().put("num",1).put("size",20).put("count",60)).put("upper",new JSONObject().put("top",row)).put("replies",new JSONArray().put(row).put(new JSONObject().put("rpid",9)));
  Comment.Page p=Comment.page(data,1);check(p.rows.size()==2&&p.more&&p.total==60,"deduplicate pinned reply, preserve paging");
  data.getJSONObject("page").put("num",3);p=Comment.page(data,3);check(!p.more,"last page boundary");
  p=Comment.page(new JSONObject(),1);check(p.rows.isEmpty()&&!p.more,"empty comment section");
  c=Comment.parse(new JSONObject().put("rpid",1));check(c.name.equals("用户")&&!c.liked&&c.likes==0,"missing optional fields");
  check(Comment.demo().size()>20,"native demo has enough rows to exercise recycling");
  JSONObject emoji=new JSONObject().put("[吃瓜]",new JSONObject().put("url","//i0.hdslb.com/a.png").put("meta",new JSONObject().put("size",1))).put("[大表情]",new JSONObject().put("url","https://i0.hdslb.com/b.webp").put("meta",new JSONObject().put("size",2))).put("[坏图片]",new JSONObject().put("url","file:///tmp/a")).put("",new JSONObject().put("url","https://i0.hdslb.com/a.png"));
  row.getJSONObject("content").put("emote",emoji);row.put("root_str","12345678901234560").put("parent_str","12345678901234561");c=Comment.parse(row);
  check(c.rootId==12345678901234560L&&c.parentId==12345678901234561L,"64-bit root and parent identity");
  check(c.emotes.size()==2,"ignore malformed and non-HTTPS emote metadata");
  check(c.emotes.get("[吃瓜]").url.startsWith("https://"),"normalize scheme-relative emote image");
  check(c.emotes.get("[吃瓜]").size==1&&c.emotes.get("[大表情]").size==2,"keep ordinary and large emote dimensions");
  check(c.message.contains("[doge]"),"unknown emote retains readable original token");
  row.getJSONArray("replies").getJSONObject(0).getJSONObject("content").put("emote",emoji);c=Comment.parse(row);
  check(c.children.get(0).emotes.size()==2,"child reply preview preserves its own emotes");
  c=Comment.parse(new JSONObject().put("rpid",4).put("content",new JSONObject().put("message","😀👨‍👩‍👧")));
  check(c.rootId==0&&c.parentId==0,"top-level comment has no parent");check(c.message.equals("😀👨‍👩‍👧")&&c.emotes.isEmpty(),"Unicode emoji remains native text");
  System.out.println("PASS: "+checks+" structured comment cases");
 }
}
