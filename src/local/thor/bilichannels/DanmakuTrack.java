package local.thor.bilichannels;

import java.util.*;

/** Stable lanes, media-time movement, bounded active work, and seek reconstruction. */
public final class DanmakuTrack {
 public interface Measure { float width(String text); }
 public static final class Flight {
  public final Video.Dan item; public final float width; public final int lane;
  Flight(Video.Dan item,float width,int lane){this.item=item;this.width=width;this.lane=lane;}
 }
 private List<Video.Dan> source=Collections.emptyList();
 private long sourceId; private int sourceCount,next,laneCursor,density;
 private float width,height,font,speed,lastTime=Float.NaN,lastLaunch=-1000;
 private boolean rightward; private float[] measured=new float[0]; private Flight[] tails=new Flight[0];
 private final ArrayList<Flight> active=new ArrayList<>();
 public void configure(List<Video.Dan> items,long id,float w,float h,float size,int level,boolean toRight){
  level=Math.max(0,Math.min(2,level));
  if(source!=items||sourceCount!=items.size()||sourceId!=id||width!=w||height!=h||font!=size||density!=level||rightward!=toRight){
   source=items;sourceCount=items.size();sourceId=id;width=w;height=h;font=size;density=level;rightward=toRight;speed=Math.max(1,w/8f);
   measured=new float[items.size()];int available=Math.max(1,(int)((h-2*size)/(size*1.65f)));tails=new Flight[Math.min(available,new int[]{4,8,14}[level])];reset();
  }
 }
 public int lanes(){return tails.length;}
 public int capacity(){return density==0?10:density==1?24:48;}
 public float x(Flight f,float time){float distance=(time-f.item.time)*speed;return rightward?-f.width+distance:width-distance;}
 public float y(Flight f){float top=font*1.5f,bottom=Math.max(top,height-font);return tails.length<=1?top:top+(bottom-top)*f.lane/(tails.length-1);}
 public void reset(){lastTime=Float.NaN;lastLaunch=-1000;next=0;laneCursor=0;active.clear();Arrays.fill(tails,null);}
 private void expire(float time){for(int i=active.size()-1;i>=0;i--){Flight f=active.get(i);if((time-f.item.time)*speed>=width+f.width)active.remove(i);}}
 private int lowerBound(float time){int lo=0,hi=source.size();while(lo<hi){int mid=(lo+hi)>>>1;if(source.get(mid).time<time)lo=mid+1;else hi=mid;}return lo;}
 public List<Flight> update(float time,Measure measure){
  if(width<=0||tails.length==0)return active;
  if(Float.isNaN(lastTime)||time<lastTime-.05f||time-lastTime>1.2f){reset();next=lowerBound(time-48);}
  float interval=density==0?.65f:density==1?.24f:.08f,gap=font*1.2f;
  while(next<source.size()&&source.get(next).time<=time){
   int index=next++;Video.Dan item=source.get(index);float at=item.time;expire(at);
   if(at-lastLaunch<interval||active.size()>=capacity())continue;
   int lane=-1;for(int i=0;i<tails.length;i++){int candidate=(laneCursor+i)%tails.length;Flight previous=tails[candidate];if(previous==null||(rightward?x(previous,at)>=gap:x(previous,at)+previous.width<=width-gap)){lane=candidate;break;}}
   if(lane<0)continue;
   float length=measured[index];if(length<=0)measured[index]=length=Math.max(1,measure.width(item.text));
   Flight f=new Flight(item,length,lane);active.add(f);tails[lane]=f;laneCursor=(lane+1)%tails.length;lastLaunch=at;
  }
  expire(time);lastTime=time;return active;
 }
}
