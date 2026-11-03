package local.thor.bilichannels;
import java.util.*;

/** A continuous masonry canvas. Slots are absolute feed indices, never page-relative. */
public final class PhonePreviewLayout {
 public final List<PreviewLayout.Box> boxes=new ArrayList<>();
 public static float unit(float width,float viewportHeight){return Math.min(width,viewportHeight)/480f;}
 public static float captionHeight(float unit){return 70*unit;}
 public final float height;private final PreviewLayout.Box[] bySlot;
 /** Only new arrivals are arranged; existing tiles never shuffle during a swipe or append. */
 public static final class Order {
  final List<Video> source;final List<Video> seen=new ArrayList<>();
  public final List<Integer> slots=new ArrayList<>();
  private final List<Integer> pending=new ArrayList<>();
  private final Map<Integer,Float> aspects=new HashMap<>();
  public Order(List<Video> source){this.source=source;sync();}
  public float aspect(int slot){return aspects.get(slot);}
  public void sync(){
   if(seen.size()>source.size()||!seen.isEmpty()&&(seen.get(0)!=source.get(0)||seen.get(seen.size()-1)!=source.get(seen.size()-1))){seen.clear();slots.clear();pending.clear();aspects.clear();}
   for(int i=seen.size();i<source.size();i++){seen.add(source.get(i));pending.add(i);}
   List<Integer> ready=new ArrayList<>();
   for(Iterator<Integer> it=pending.iterator();it.hasNext();){int slot=it.next();Video video=source.get(slot);if(video.width>0&&video.height>0){ready.add(slot);aspects.put(slot,video.aspect());it.remove();}}
   // Unknown sizes never enter as guessed landscape cards. Late metadata adds
   // new cards after the existing order; decoder callbacks cannot resize it.
   for(int start=0;start<ready.size();start+=24){int end=Math.min(ready.size(),start+24);List<Integer> portrait=new ArrayList<>(),landscape=new ArrayList<>();
    for(int i=start;i<end;i++){int slot=ready.get(i);(aspect(slot)<1?portrait:landscape).add(slot);}
    Random random=new Random(source.get(ready.get(start)).id.hashCode());int pi=0,li=0,next=2+random.nextInt(3),position=0;
    while(pi<portrait.size()||li<landscape.size()){if(pi<portrait.size()&&(li==landscape.size()||position>=next)){slots.add(portrait.get(pi++));next=position+4+random.nextInt(3);}else slots.add(landscape.get(li++));position++;}
   }
  }
 }

 public PhonePreviewLayout(List<Video> videos,float width,float viewportHeight){this(videos,width,viewportHeight,new Order(videos));}
 public PhonePreviewLayout(List<Video> videos,float width,float viewportHeight,Order order){
  bySlot=new PreviewLayout.Box[videos.size()];
  if(width<=0||viewportHeight<=0){height=0;return;}
  int columns=width>viewportHeight?5:2;
  float unit=unit(width,viewportHeight),gap=14*unit,cell=(width-(columns+1)*gap)/columns;
  float[] bottoms=new float[columns];Arrays.fill(bottoms,gap);
  for(int i:order.slots){
   int column=0;for(int c=1;c<columns;c++)if(bottoms[c]<bottoms[column])column=c;
   float y=bottoms[column],h=cell/order.aspect(i)+captionHeight(unit);
   PreviewLayout.Box box=new PreviewLayout.Box(i,gap+column*(cell+gap),y,cell,h);boxes.add(box);bySlot[i]=box;
   bottoms[column]=y+h+gap;
  }
  float end=0;for(float b:bottoms)end=Math.max(end,b);height=boxes.isEmpty()?0:end;
 }
 public PreviewLayout.Box box(int slot){return slot>=0&&slot<bySlot.length?bySlot[slot]:null;}
 public float clamp(float scroll,float viewportHeight){return Math.max(0,Math.min(scroll,Math.max(0,height-viewportHeight)));}
 /** Keep a small decoding runway outside the viewport, biased toward the swipe. */
 public List<PreviewLayout.Box> nearby(float scroll,float viewportHeight,float top,boolean forward,int limit){
  List<PreviewLayout.Box> candidates=new ArrayList<>();
  for(PreviewLayout.Box b:boxes)if((b.y+b.height<=scroll||b.y>=scroll+viewportHeight)&&b.y+b.height>scroll-viewportHeight&&b.y<scroll+viewportHeight*3)candidates.add(b);
  candidates.sort(Comparator.comparingDouble(b->{boolean below=b.y>=scroll+viewportHeight;float distance=below?b.y-scroll-viewportHeight:scroll-b.y-b.height;return distance+(below==forward?0:viewportHeight*.65f);}));
  List<PreviewLayout.Box> result=new ArrayList<>();for(int i=0;i<Math.min(limit,candidates.size());i++){PreviewLayout.Box b=candidates.get(i);result.add(new PreviewLayout.Box(b.slot,b.x,top+b.y-scroll,b.width,b.height));}return result;
 }
 public PreviewLayout.Plan visible(float scroll,float viewportHeight,float top){
  List<PreviewLayout.Box> visible=new ArrayList<>();
  for(PreviewLayout.Box b:boxes)if(b.y+b.height>scroll&&b.y<scroll+viewportHeight)visible.add(new PreviewLayout.Box(b.slot,b.x,top+b.y-scroll,b.width,b.height));
  return new PreviewLayout.Plan(visible.isEmpty()?PreviewLayout.Mode.EMPTY:PreviewLayout.Mode.GRID,visible);
 }
}
