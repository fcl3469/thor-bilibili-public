package local.thor.bilichannels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Content geometry only. Queue slots always refer to the unchanged lower list. */
public final class PreviewLayout {
    public enum Mode { EMPTY, GRID, MIXED, PORTRAIT_PAIR, PORTRAIT_SINGLE }
    public static final class Box {
        public final int slot;
        public final float x, y, width, height;
        public Box(int slot, float x, float y, float width, float height) {
            this.slot = slot; this.x = x; this.y = y;
            this.width = width; this.height = height;
        }
    }
    public static final class Plan {
        public final Mode mode;
        public final List<Box> boxes;
        Plan(Mode mode, List<Box> boxes) { this.mode = mode; this.boxes = boxes; }
        public boolean shows(int slot) {
            for (Box box : boxes) if (box.slot == slot) return true;
            return false;
        }
    }
    /** Phone content and controls always have equal height; video geometry stays proportional. */
    public static int phonePreviewHeight(List<Video> queue,int selected,int width,int height,boolean playing,Video current){return Math.max(1,height/2);}
    /** Both previews and formal playback retain the complete frame, without distortion. */
    public static float[] videoScale(float width,float height,float ratio){float drawWidth=Math.min(width,height*ratio);return new float[]{drawWidth/width,drawWidth/ratio/height};}
    private static Box fit(int slot, float x, float y, float w, float h, float ratio) {
        float fw = Math.min(w, h * ratio), fh = fw / ratio;
        return new Box(slot, x + (w - fw) / 2, y + (h - fh) / 2, fw, fh);
    }
    public static Plan make(List<Video> queue, int selectedSlot, float width,
                            float height, boolean preferSelectedPortrait) {
        return make(queue, selectedSlot, width, height, preferSelectedPortrait, false, null);
    }
    public static Plan make(List<Video> queue, int selectedSlot, float width,
                            float height, boolean preferSelectedPortrait,
                            boolean handheld, PreviewSeats seats) {
        List<Box> boxes = new ArrayList<>();
        if (queue.isEmpty() || width <= 0 || height <= 0) return new Plan(Mode.EMPTY, boxes);
        int count = Math.min(6, queue.size());
        float gap = Math.max(1, Math.min(width, height) * .006f);
        float usableW = width - 2 * gap, usableH = height - 2 * gap;
        List<Integer> portrait = new ArrayList<>(), landscape = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            (queue.get(i).isPortrait() ? portrait : landscape).add(i);
        }
        boolean mixedGrid = handheld && width > height && portrait.size() == 1 && landscape.size() >= 4;
        if (!mixedGrid && seats != null) seats.clear();
        if (portrait.size() >= 2) {
            List<Integer> show = new ArrayList<>(portrait.subList(0, 2));
            if (preferSelectedPortrait && portrait.contains(selectedSlot) && !show.contains(selectedSlot)) {
                show.set(1, selectedSlot);
                Collections.sort(show);
            }
            float a = queue.get(show.get(0)).aspect(), b = queue.get(show.get(1)).aspect();
            float h = Math.min(usableH, (usableW - gap) / (a + b));
            float startX = (width - h * (a + b) - gap) / 2, y = (height - h) / 2;
            boxes.add(new Box(show.get(0), startX, y, h * a, h));
            boxes.add(new Box(show.get(1), startX + h * a + gap, y, h * b, h));
            return new Plan(Mode.PORTRAIT_PAIR, boxes);
        }
        if (portrait.size() == 1) {
            int pi = portrait.get(0);
            float ar = queue.get(pi).aspect();
            if (landscape.isEmpty()) {
                boxes.add(fit(pi, gap, gap, usableW, usableH, ar));
                return new Plan(Mode.PORTRAIT_SINGLE, boxes);
            }
            if (mixedGrid) {
                int[] cells = (seats == null ? new PreviewSeats() : seats).arrange(queue, selectedSlot);
                // Fixed regions avoid moving retained videos when a new video's aspect differs.
                float rightWidth = Math.min(usableW * .4f, usableH * 9 / 16f);
                float leftWidth = usableW - gap - rightWidth;
                float cellWidth = (leftWidth - gap) / 2;
                float cellHeight = Math.min((usableH - gap) / 2, cellWidth * 9 / 16f);
                float top = (height - 2 * cellHeight - gap) / 2;
                for (int cell = 0; cell < cells.length; cell++) if (cells[cell] >= 0) {
                    int slot = cells[cell];
                    boxes.add(fit(slot, gap + (cell % 2) * (cellWidth + gap),
                            top + (cell / 2) * (cellHeight + gap), cellWidth, cellHeight, queue.get(slot).aspect()));
                }
                boxes.add(fit(pi, gap + leftWidth + gap, gap, rightWidth, usableH, ar));
                return new Plan(Mode.MIXED, boxes);
            }
            float reciprocalSum = 0;
            for (int li : landscape) reciprocalSum += 1 / queue.get(li).aspect();
            float stackGaps = (landscape.size() - 1) * gap;
            // A single left column follows queue order for every mixed window, including five landscapes.
            float h = Math.min(usableH,
                    (usableW - gap + stackGaps / reciprocalSum) / (1 / reciprocalSum + ar));
            float leftWidth = (h - stackGaps) / reciprocalSum;
            float x = (width - leftWidth - gap - h * ar) / 2, top = (height - h) / 2, y = top;
            for (int li : landscape) {
                float lh = leftWidth / queue.get(li).aspect();
                boxes.add(new Box(li, x, y, leftWidth, lh));
                y += lh + gap;
            }
            boxes.add(new Box(pi, x + leftWidth + gap, top, h * ar, h));
            return new Plan(Mode.MIXED, boxes);
        }
        return new Plan(Mode.GRID, horizontal(queue,landscape,gap,gap,usableW,usableH,gap));
    }
    static List<Box> horizontal(List<Video> q,List<Integer> slots,float x,float y,float w,float h,float gap){
        int n=slots.size();List<Box> best=new ArrayList<>();double bestArea=-1;
        int[][] patterns=n==5?new int[][]{{2,3},{2,2,1}}:n==6?new int[][]{{3,3},{2,2,2}}:new int[][]{n<=2?new int[]{n}:n==3?new int[]{2,1}:new int[]{2,2}};
        for(int[] rows:patterns){
            float[] heights=new float[rows.length];int start=0;float total=0;
            for(int r=0;r<rows.length;r++){float sum=0;for(int k=0;k<rows[r];k++)sum+=q.get(slots.get(start+k)).aspect();float rw=w;if(rows[r]==1&&n>2)rw=(w-gap)/2;heights[r]=(rw-(rows[r]-1)*gap)/sum;total+=heights[r];start+=rows[r];}
            float scale=Math.min(1,Math.max(.001f,(h-(rows.length-1)*gap)/total)),top=y+(h-total*scale-(rows.length-1)*gap)/2;List<Box> boxes=new ArrayList<>();start=0;double area=0;
            for(int r=0;r<rows.length;r++){float rh=heights[r]*scale,rw=(rows[r]-1)*gap;for(int k=0;k<rows[r];k++)rw+=rh*q.get(slots.get(start+k)).aspect();float left=x+(w-rw)/2;for(int k=0;k<rows[r];k++){int slot=slots.get(start+k);float width=rh*q.get(slot).aspect();boxes.add(new Box(slot,left,top,width,rh));area+=width*rh;left+=width+gap;}top+=rh+gap;start+=rows[r];}
            if(area>bestArea){bestArea=area;best=boxes;}
        }return best;
    }
}
