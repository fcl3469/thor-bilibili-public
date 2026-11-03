package local.thor.bilichannels;
/** Finger deltas are relative to the starting value, never to event frequency. */
public final class PlaybackGesture {
 public static final int NONE=0,BRIGHTNESS=1,VOLUME=2,SEEK=3;
 public static int classify(float startX,float width,float dx,float dy,float slop){if(Math.hypot(dx,dy)<slop)return NONE;if(Math.abs(dy)>Math.abs(dx)*1.15f){if(startX<width/3)return BRIGHTNESS;if(startX>width*2/3)return VOLUME;}if(startX>=0&&startX<=width&&Math.abs(dx)>Math.abs(dy)*1.15f)return SEEK;return NONE;}
 public static float level(float start,float dy,float height){return Math.max(0,Math.min(1,start-dy/Math.max(1,height)*1.35f));}
 public static int seek(int start,float dx,float width,int duration){long span=Math.min(180000,Math.max(10000,duration));return (int)Math.max(0,Math.min(Math.max(0,duration-1),start+Math.round(dx/Math.max(1,width)*span)));}
}
