import local.thor.bilichannels.*;import java.util.*;
public class DanmakuTrackTest {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static List<Video.Dan> feed(){List<Video.Dan> out=new ArrayList<>();for(int i=0;i<600;i++)out.add(new Video.Dan(i*.04f,"comment "+i));return out;}
 static DanmakuTrack create(List<Video.Dan> feed,int density,boolean right){DanmakuTrack t=new DanmakuTrack();t.configure(feed,1,800,450,20,density,right);return t;}
 public static void main(String[] args){
  List<Video.Dan> one=Arrays.asList(new Video.Dan(0,"hello"));DanmakuTrack right=create(one,1,true);DanmakuTrack.Flight f=right.update(0,s->100).get(0);
  check(right.x(f,0)==-100,"left to right begins fully outside left edge");check(right.x(f,4)==300,"position follows media time at constant speed");check(right.x(f,8.5f)>700,"flight reaches opposite edge");check(right.update(9.1f,s->100).isEmpty(),"retires after text has fully crossed screen");
  DanmakuTrack left=create(one,1,false);DanmakuTrack.Flight other=left.update(0,s->100).get(0);check(left.x(other,0)==800&&left.x(other,8.5f)==-50,"right to left covers full width too");
  List<Video.Dan> crowded=feed();int[] counts=new int[3];for(int density=0;density<3;density++){DanmakuTrack t=create(crowded,density,true);boolean overlap=false;for(int frame=0;frame<1200;frame++){float now=frame/60f;List<DanmakuTrack.Flight> flights=t.update(now,s->100);checkBound(flights.size()<=t.capacity());for(int i=0;i<flights.size();i++)for(int j=i+1;j<flights.size();j++){DanmakuTrack.Flight a=flights.get(i),b=flights.get(j);if(a.lane==b.lane){float x=t.x(a,now),y=t.x(b,now);if(x<y+b.width&&y<x+a.width)overlap=true;}}if(frame==360)counts[density]=flights.size();}check(!overlap,"stable lanes do not overlap at density "+density);}
  check(counts[0]<counts[1]&&counts[1]<counts[2],"density changes visible population");
  DanmakuTrack t=create(crowded,2,true);List<DanmakuTrack.Flight> moving=t.update(6,s->100);DanmakuTrack.Flight stable=moving.get(0);float x=t.x(stable,6);t.update(6,s->100);check(t.x(stable,6)==x&&moving.contains(stable),"pause keeps flight and its lane");t.update(6.016f,s->100);check(moving.contains(stable)&&stable.lane>=0,"next frame retains existing flight identity");
  float min=999,max=0;for(DanmakuTrack.Flight item:moving){min=Math.min(min,t.y(item));max=Math.max(max,t.y(item));}check(min<80&&max>350,"lanes span full frame height");
  t.update(0,s->100);check(t.update(.1f,s->100).size()<10,"backward seek clears future flights");
  int[] measured={0};for(int i=0;i<60;i++)t.update(.1f+i/60f,s->{measured[0]++;return 100;});check(measured[0]<25,"frame updates measure only newly admitted comments");
  List<Video.Dan> fresh=Arrays.asList(new Video.Dan(1,"new source"));t.configure(fresh,2,800,450,20,1,true);check(t.update(1,s->100).get(0).item.text.equals("new source"),"new video discards old timeline");
  System.out.println("PASS: "+checks+" danmaku movement, density, collision, pause, seek and bounded-work checks");
 }
 static void checkBound(boolean b){if(!b)throw new AssertionError("active capacity exceeded");}
}
