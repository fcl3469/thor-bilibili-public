package local.thor.bilichannels;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Read-only relationship counts and paged people, independent of Android views. */
public final class RelationModels {
 public static final int PAGE_SIZE=50;
 public static final class Stats {
  public final long following,followers,dynamic;
  public Stats(long following,long followers,long dynamic){this.following=following;this.followers=followers;this.dynamic=dynamic;}
 }
 public static Stats stats(JSONObject data)throws Exception{
  if(!data.has("following")||!data.has("follower")||data.optLong("following",-1)<0||data.optLong("follower",-1)<0)throw new IOException("关注和粉丝数量暂未返回，请重试");
  return new Stats(data.getLong("following"),data.getLong("follower"),data.optLong("dynamic_count",data.optLong("dynamic",-1)));
 }
 public static final class Person {
  public final long mid;public final String name,face,sign;
  Person(long mid,String name,String face,String sign){this.mid=mid;this.name=name;this.face=face;this.sign=sign;}
  public Video creator(){Video v=new Video("creator-"+mid,"",name,"",0,0,0,false);v.upMid=mid;v.upAvatar=face;return v;}
 }
 public static final class Page {
  public final List<Person> people;public final int number;public final long total;public final boolean more;
  Page(List<Person> people,int number,long total,boolean more){this.people=people;this.number=number;this.total=total;this.more=more;}
 }
 public static Page page(JSONObject data,int number)throws Exception{
  JSONArray list=data.optJSONArray("list");long total=data.optLong("total",-1);
  if(list==null||total<0||number<1)throw new IOException("名单暂未完整返回，请重试");
  List<Person> people=new ArrayList<>();Set<Long> ids=new HashSet<>();
  for(int i=0;i<list.length();i++){JSONObject row=list.optJSONObject(i);if(row==null)continue;long mid=row.optLong("mid");if(mid<=0||!ids.add(mid))continue;people.add(new Person(mid,row.optString("uname","用户 "+mid),ApiModels.secure(row.optString("face")),ApiModels.plain(row.optString("sign")).replace('\n',' ')));}
  boolean more=(long)number*PAGE_SIZE<total;
  if(people.isEmpty()&&(list.length()>0||more||number==1&&total>0))throw new IOException("名单未返回有效用户，请重试");
  return new Page(people,number,total,more);
 }
 public static final class Pager {
  public final List<Person> people=new ArrayList<>();public int page,generation;public long total=-1;public boolean loading,more=true;public String error="";
  public int begin(){if(loading||!more)return -1;loading=true;error="";return ++generation;}
  public void reset(){generation++;page=0;total=-1;loading=false;more=true;error="";people.clear();}
  public boolean accept(int token,Page next){if(token!=generation)return false;loading=false;if(next.number!=page+1){error="名单页码不一致，请重试";return false;}Set<Long> ids=new HashSet<>();for(Person p:people)ids.add(p.mid);int before=people.size();for(Person p:next.people)if(ids.add(p.mid))people.add(p);if(next.more&&people.size()==before){error="名单没有更新，请重试";return false;}page=next.number;total=next.total;more=next.more;error="";return true;}
  public void fail(int token,String message){if(token==generation){loading=false;error=message;}}
 }
}
