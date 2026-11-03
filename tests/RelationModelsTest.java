import local.thor.bilichannels.*;import org.json.*;
public class RelationModelsTest {
 static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 static JSONObject row(long mid)throws Exception{return new JSONObject().put("mid",mid).put("uname","UP "+mid).put("face","//example.com/avatar").put("sign","一行\n二行");}
 static JSONObject page(long total,long... ids)throws Exception{JSONArray list=new JSONArray();for(long id:ids)list.put(row(id));return new JSONObject().put("total",total).put("list",list);}
 public static void main(String[] args)throws Exception{
  RelationModels.Stats s=RelationModels.stats(new JSONObject().put("following",321).put("follower",0));check(s.following==321&&s.followers==0,"zero fans is valid");
  check(RelationModels.stats(new JSONObject().put("following",1).put("follower",2).put("dynamic_count",13)).dynamic==13&&s.dynamic==-1,"dynamic count is real or unknown");
  boolean bad=false;try{RelationModels.stats(new JSONObject().put("following",4));}catch(Exception e){bad=true;}check(bad,"missing stats never become zero");
  RelationModels.Page first=RelationModels.page(page(51,1,1,2,0),1);check(first.people.size()==2&&first.more,"people deduplicated by mid and next page retained");check(first.people.get(0).face.startsWith("https://")&&!first.people.get(0).sign.contains("\n"),"profile fields normalized");check(first.people.get(0).creator().upMid==1,"profile navigation uses UID without a video ID");
  RelationModels.Pager p=new RelationModels.Pager();int token=p.begin();check(p.begin()==-1,"concurrent requests suppressed");check(p.accept(token,first)&&p.page==1&&p.total==51,"first page accepted");token=p.begin();p.fail(token,"offline");check(p.page==1&&p.people.size()==2&&!p.loading,"failure keeps old list and page");token=p.begin();check(p.accept(token,RelationModels.page(page(51,2,3),2))&&p.people.size()==3&&!p.more,"next page appends unique rows and ends at total");check(p.begin()==-1,"terminal page does not request again");
  p.reset();token=p.begin();p.reset();check(!p.accept(token,first)&&p.people.isEmpty(),"late response after reset ignored");token=p.begin();check(!p.accept(token,RelationModels.page(page(51,3),2))&&p.page==0,"wrong page rejected");
  bad=false;try{RelationModels.page(new JSONObject().put("total",3),1);}catch(Exception e){bad=true;}check(bad,"missing list is an error");bad=false;try{RelationModels.page(page(51),1);}catch(Exception e){bad=true;}check(bad,"empty partial page is not mistaken for no follows");
  RelationModels.Page empty=RelationModels.page(page(0),1);check(empty.people.isEmpty()&&!empty.more,"legitimate empty list supported");
  System.out.println("PASS: "+checks+" relation counts and pagination cases");
 }
}
