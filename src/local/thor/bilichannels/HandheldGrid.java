package local.thor.bilichannels;

import java.util.ArrayList;
import java.util.List;

/** Full-screen cards for direct selection on the handheld; video fits inside each card. */
public final class HandheldGrid {
 public static int columns(int capacity){return capacity>=5?3:2;}
 public static float captionHeight(float width,float height){return Math.min(height*.27f,width*.17f);}
 public static float headerHeight(float width,float height){return Math.max(72,Math.min(height*.128f,width*.072f));}
 public static PreviewLayout.Plan layout(int count,int capacity,float width,float height){
  List<PreviewLayout.Box> boxes=new ArrayList<>();
  if(count<=0||width<=0||height<=0)return new PreviewLayout.Plan(PreviewLayout.Mode.EMPTY,boxes);
  int cols=columns(capacity),rows=(capacity+cols-1)/cols;
  float gap=Math.max(8,Math.min(width,height)*.016f),w=(width-(cols+1)*gap)/cols,h=(height-(rows+1)*gap)/rows;
  for(int i=0;i<Math.min(count,capacity);i++)boxes.add(new PreviewLayout.Box(i,gap+i%cols*(w+gap),gap+i/cols*(h+gap),w,h));
  return new PreviewLayout.Plan(PreviewLayout.Mode.GRID,boxes);
 }
 public static boolean move(BrowseState state,int dx,int dy){
  if(state.count==0)return false;
  int cols=columns(state.visibleRows),oldStart=state.start(),next=Math.max(0,Math.min(state.count-1,state.selected+(dy!=0?dy*cols:dx)));
  if(next==state.selected)return false;
  state.selected=next;
  if(next<oldStart)state.offset=state.clamp(oldStart-cols);
  else if(next>=state.end())state.offset=state.clamp(oldStart+cols);
  // Also supports selections restored from an older list layout.
  if(next<state.start())state.offset=state.clamp(next);
  if(next>=state.end())state.offset=state.clamp(next-state.visibleRows+1);
  return true;
 }
}
