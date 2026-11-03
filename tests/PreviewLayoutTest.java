import local.thor.bilichannels.*;
import java.util.*;

public class PreviewLayoutTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static Video v(int slot, boolean portrait) {
        Video v = new Video("item" + slot, "title", "up", "", 0, 0, 10, true);
        v.setDimensions(portrait ? 1080 : 1920, portrait ? 1920 : 1080, 0);
        return v;
    }
    static void geometry(PreviewLayout.Plan p, float w, float h, List<Video> q) {
        Set<Integer> slots = new HashSet<>();
        for (PreviewLayout.Box b : p.boxes) {
            check(slots.add(b.slot), "duplicate preview");
            check(b.width > 0 && b.height > 0, "empty frame");
            check(b.x >= -.01 && b.y >= -.01 && b.x + b.width <= w + .01 && b.y + b.height <= h + .01, "frame out of bounds");
            check(Math.abs(b.width / b.height - q.get(b.slot).aspect()) < .001, "video stretched");
            for (PreviewLayout.Box c : p.boxes) if (b != c) {
                boolean overlaps = Math.min(b.x + b.width, c.x + c.width) - Math.max(b.x, c.x) > .01
                        && Math.min(b.y + b.height, c.y + c.height) - Math.max(b.y, c.y) > .01;
                check(!overlaps, "frames overlap");
            }
        }
    }
    public static void main(String[] args) {
        int cases = 0;
        for (int mask = 0; mask < 16; mask++) {
            List<Video> queue = new ArrayList<>();
            for (int i = 0; i < 4; i++) queue.add(v(i, (mask & (1 << i)) != 0));
            int portraits = Integer.bitCount(mask);
            for (int selected = 0; selected < 4; selected++) for (boolean follow : new boolean[]{false, true}) {
                for (float[] size : new float[][]{{1920,1080},{1080,1180},{1500,900},{360,260},{300,800},{1920,400}}) {
                    PreviewLayout.Plan plan = PreviewLayout.make(queue, selected, size[0], size[1], follow);
                    check(plan.boxes.size() == (portraits >= 2 ? 2 : 4), "wrong count for mask " + mask);
                    check(plan.mode == (portraits >= 2 ? PreviewLayout.Mode.PORTRAIT_PAIR : portraits == 1 ? PreviewLayout.Mode.MIXED : PreviewLayout.Mode.GRID), "wrong mode");
                    if (portraits >= 2) {
                        for (PreviewLayout.Box b : plan.boxes) check(queue.get(b.slot).isPortrait(), "horizontal preview leaks into portrait pair");
                        if (follow && queue.get(selected).isPortrait()) {
                            check(plan.shows(selected), "selected portrait hidden");
                            for (int i=0;i<4;i++) if(i!=selected&&queue.get(i).isPortrait()) {check(plan.shows(i), "did not supplement earliest portrait");break;}
                        }
                        check(plan.boxes.get(0).slot < plan.boxes.get(1).slot, "pair visual order changed");
                        if (!follow) { int seen=0;for(int i=0;i<4;i++)if(queue.get(i).isPortrait()&&seen++<2)check(plan.shows(i),"queue order changed"); }
                    }
                    if (portraits == 1) {
                        PreviewLayout.Box right = null;
                        for (PreviewLayout.Box b : plan.boxes) if (queue.get(b.slot).isPortrait()) right = b;
                        for (PreviewLayout.Box b : plan.boxes) if (b != right) check(b.x + b.width < right.x, "landscape not on left");
                    }
                    geometry(plan, size[0], size[1], queue); cases++;
                }
            }
        }
        for(int count=0;count<4;count++) {
            List<Video> queue=new ArrayList<>();for(int i=0;i<count;i++)queue.add(v(i,i%2==0));
            geometry(PreviewLayout.make(queue,0,1080,1200,true),1080,1200,queue);
        }
        List<Video> wide=new ArrayList<>();for(int i=0;i<4;i++)wide.add(v(i,false));
        PreviewLayout.Plan thor=PreviewLayout.make(wide,0,1920,1080,true);float area=0;for(PreviewLayout.Box b:thor.boxes)area+=b.width*b.height;
        check(area/(1920*1080f)>.96f,"Thor four-landscape preview wastes screen area");
        for(int selected=0;selected<4;selected++)for(boolean playing:new boolean[]{false,true}) {
            int half=PreviewLayout.phonePreviewHeight(wide,selected,1440,3200,playing,wide.get(selected));
            check(half==1600,"phone portrait must keep equal upper and lower areas");
            geometry(PreviewLayout.make(wide,selected,1440,half,true),1440,half,wide);
        }
        for(float ratio:new float[]{.5625f,1,4/3f,16/9f,2.35f}){float[] scale=PreviewLayout.videoScale(900,700,ratio);check(scale[0]<=1.001f&&scale[1]<=1.001f,"frame cropped");check(Math.abs(900*scale[0]/(700*scale[1])-ratio)<.001f,"frame stretched");}
        for(int mask=0;mask<16;mask++){List<Video> queue=new ArrayList<>();for(int i=0;i<4;i++)queue.add(v(i,(mask&(1<<i))!=0));int h=PreviewLayout.phonePreviewHeight(queue,1,1440,3200,false,null);check(h==1600,"aspect mix changed the equal phone split");geometry(PreviewLayout.make(queue,1,1440,h,true),1440,h,queue);}
        int expanded=0;
        for(int count=4;count<=6;count++)for(int mask=0;mask<(1<<count);mask++)for(int selected=0;selected<count;selected++)for(float[] size:new float[][]{{1920,1080},{1440,1544},{960,720},{360,240}}){
            List<Video> queue=new ArrayList<>();for(int i=0;i<count;i++)queue.add(v(i,(mask&(1<<i))!=0));
            PreviewLayout.Plan plan=PreviewLayout.make(queue,selected,size[0],size[1],true);geometry(plan,size[0],size[1],queue);
            check(plan.boxes.size()==(Integer.bitCount(mask)>=2?2:count),"4-6 row preview count");
            if(queue.get(selected).isPortrait())check(plan.shows(selected),"selected portrait from fifth/sixth slot hidden");expanded++;
        }
        int stacks=0;
        for(int count:new int[]{5,6})for(int portrait=0;portrait<count;portrait++)for(float[] size:new float[][]{{1440,1544},{1544,1440},{1920,1080},{960,720}}){
            List<Video> queue=new ArrayList<>();for(int i=0;i<count;i++){Video item=v(i,i==portrait);if(i!=portrait&&i%2==0)item.setDimensions(1440,1080,0);queue.add(item);}
            PreviewLayout.Plan plan=PreviewLayout.make(queue,portrait,size[0],size[1],true);geometry(plan,size[0],size[1],queue);
            PreviewLayout.Box right=null,previous=null;for(PreviewLayout.Box box:plan.boxes)if(box.slot==portrait)right=box;
            for(int slot=0;slot<count;slot++)if(slot!=portrait){PreviewLayout.Box found=null;for(PreviewLayout.Box box:plan.boxes)if(box.slot==slot)found=box;check(found!=null&&found.x+found.width<right.x,"mixed landscape must stay left of portrait");if(previous!=null){check(Math.abs(found.x-previous.x)<.01&&Math.abs(found.width-previous.width)<.01,"mixed landscape must form one equal-width column");check(found.y>previous.y+previous.height,"mixed column must follow queue order vertically");}previous=found;}stacks++;
        }
        // Handheld: one portrait, four stable landscape cells and one standby.
        List<Video> scrolling=new ArrayList<>();for(int i=0;i<8;i++)scrolling.add(v(i,i==2));
        PreviewSeats seats=new PreviewSeats();List<Video> firstWindow=new ArrayList<>(scrolling.subList(0,6));
        PreviewLayout.Plan first=PreviewLayout.make(firstWindow,1,1920,1080,true,true,seats);geometry(first,1920,1080,firstWindow);
        check(first.boxes.size()==5&&first.shows(2)&&!first.shows(5),"handheld has four landscape cells plus portrait and one standby");
        List<Video> nextWindow=new ArrayList<>(scrolling.subList(1,7));PreviewLayout.Plan next=PreviewLayout.make(nextWindow,0,1920,1080,true,true,seats);geometry(next,1920,1080,nextWindow);
        int retained=0;for(PreviewLayout.Box before:first.boxes)for(PreviewLayout.Box after:next.boxes)if(firstWindow.get(before.slot)==nextWindow.get(after.slot)){check(Math.abs(before.x-after.x)<.01&&Math.abs(before.y-after.y)<.01,"scroll moved a retained handheld video");retained++;}check(retained==4&&next.shows(4)&&!next.shows(5),"standby fills departing cell, newest video waits");
        PreviewLayout.Plan selected=PreviewLayout.make(nextWindow,5,1920,1080,true,true,seats);check(selected.shows(5),"selected standby must become visible");int unchanged=0;for(PreviewLayout.Box before:next.boxes)for(PreviewLayout.Box after:selected.boxes)if(before.slot==after.slot){check(Math.abs(before.x-after.x)<.01&&Math.abs(before.y-after.y)<.01,"selecting standby moved another cell");unchanged++;}check(unchanged==4,"standby selection must replace only one landscape");
        for(int pi=0;pi<6;pi++)for(int selectedSlot=0;selectedSlot<6;selectedSlot++)for(float[] size:new float[][]{{1920,1080},{1280,720},{1500,900}}){List<Video> queue=new ArrayList<>();for(int i=0;i<6;i++){Video item=v(i,i==pi);if(i%2==0&&i!=pi)item.setDimensions(1440,1080,0);queue.add(item);}PreviewLayout.Plan plan=PreviewLayout.make(queue,selectedSlot,size[0],size[1],true,true,new PreviewSeats());geometry(plan,size[0],size[1],queue);check(plan.boxes.size()==5&&plan.shows(selectedSlot)&&plan.shows(pi),"handheld selection or portrait hidden");}
        PreviewLayout.make(Collections.singletonList(v(9,false)),0,1920,1080,true,true,seats);PreviewLayout.Plan reset=PreviewLayout.make(firstWindow,1,1920,1080,true,true,seats);check(reset.shows(0)&&!reset.shows(5),"leaving mixed layout clears seats");
        System.out.println("PASS: 108 handheld aspect/selection layouts; stable scroll cells, standby replacement, focus priority and mode reset");
        System.out.println("PASS: "+stacks+" single-column mixed windows with varied landscape ratios and portrait positions");
        System.out.println("PASS: "+expanded+" expanded 4-6 row aspect/focus/layout cases");
        Video rotation=v(0,false);rotation.setDimensions(1920,1080,90);check(rotation.isPortrait(),"90 degree rotation ignored");
        rotation.setDimensions(1920,1080,270);check(rotation.isPortrait(),"270 degree rotation ignored");
        rotation.setDimensions(1080,1080,0);check(!rotation.isPortrait(),"square classified portrait");
        rotation.setDimensions(0,0,0);check(!rotation.isPortrait(),"unknown classified portrait");
        System.out.println("PASS: " + cases + " layouts; all 16 aspect combinations; exact counts, queue slots, focus, bounds, no overlap/stretch; equal phone split, Thor grid occupancy, full-frame transforms, sparse/rotated/unknown inputs");
    }
}
