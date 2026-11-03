import local.thor.bilichannels.*;
import java.util.*;

public class HandheldGridTest {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] args){
  int cases=0;
  for(int capacity=4;capacity<=6;capacity++)for(int count=1;count<=35;count++){
   BrowseState s=new BrowseState(count);s.setVisibleRows(capacity);s.select(0);
   for(int i=0;i<count;i++){check(s.selected==i,"right traversal skips video");check(s.selected>=s.start()&&s.selected<s.end(),"focus hidden on forward scroll");HandheldGrid.move(s,1,0);}
   for(int i=count-1;i>=0;i--){check(s.selected==i,"left traversal skips video");check(s.selected>=s.start()&&s.selected<s.end(),"focus hidden on reverse scroll");HandheldGrid.move(s,-1,0);}
   for(int i=0;i<count;i++)for(int dy:new int[]{-1,1}){s.select(i);HandheldGrid.move(s,0,dy);check(s.selected>=s.start()&&s.selected<s.end(),"vertical scroll hides selected card");cases++;}
   for(float[] screen:new float[][]{{1920,1080},{1240,1080},{1080,1920}}){
    PreviewLayout.Plan p=HandheldGrid.layout(Math.min(count,capacity),capacity,screen[0],screen[1]);
    check(p.boxes.size()==Math.min(count,capacity),"mixed video must remain selectable");
    double area=0;for(PreviewLayout.Box b:p.boxes){check(b.x>=0&&b.y>=0&&b.x+b.width<=screen[0]&&b.y+b.height<=screen[1],"card out of screen");area+=b.width*b.height;
     for(PreviewLayout.Box other:p.boxes)if(other!=b)check(b.x+b.width<=other.x+.01f||other.x+other.width<=b.x+.01f||b.y+b.height<=other.y+.01f||other.y+other.height<=b.y+.01f,"cards overlap");
     for(float ratio:new float[]{9/16f,16/9f,4/3f}){float h=b.height-HandheldGrid.captionHeight(b.width,b.height);float[] scale=PreviewLayout.videoScale(b.width,h,ratio);check(Math.abs(b.width*scale[0]/(h*scale[1])-ratio)<.001,"fitted video stretched");}
    }if(count>=capacity&&capacity!=5){double coverage=area/(screen[0]*screen[1]);check(coverage>.87&&coverage<.95,"comfortable card spacing without wasting screen");}
   }
  }
  check(HandheldGrid.headerHeight(1920,1080)>.12f*1080,"upper channels have readable reserved space");
  check(ControllerStick.gridCommand(1,.2f)==ControllerStick.RIGHT,"right stick");check(ControllerStick.gridCommand(-1,.2f)==ControllerStick.LEFT,"left stick");check(ControllerStick.gridCommand(.2f,1)==ControllerStick.DOWN,"down stick");check(ControllerStick.gridCommand(.2f,-1)==ControllerStick.UP,"up stick");check(ControllerStick.gridCommand(.2f,.2f)==0,"dead zone");
  BrowseState empty=new BrowseState(0);check(!HandheldGrid.move(empty,1,0),"empty feed navigation");
  System.out.println("PASS: handheld grid reachability, bounds, complete frames and "+cases+" vertical navigation cases");
 }
}
