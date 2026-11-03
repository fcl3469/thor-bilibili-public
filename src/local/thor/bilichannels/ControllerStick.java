package local.thor.bilichannels;
/** Dominant-axis mapping keeps small stick noise and diagonal movement from issuing two actions. */
public final class ControllerStick {
 public static final int NONE=0,UP=1,DOWN=2,VOLUME_UP=3,VOLUME_DOWN=4,SEEK_BACK=5,SEEK_FORWARD=6;
 public static final int LEFT=7,RIGHT=8;
 public static int gridCommand(float x,float y){if(!Float.isFinite(x)||!Float.isFinite(y)||Math.max(Math.abs(x),Math.abs(y))<.55f)return NONE;return Math.abs(x)>Math.abs(y)?x<0?LEFT:RIGHT:y<0?UP:DOWN;}
 public static int command(float x,float y,boolean playback){if(Float.isNaN(x)||Float.isNaN(y)||Float.isInfinite(x)||Float.isInfinite(y))return NONE;if(Math.max(Math.abs(x),Math.abs(y))<.55f)return NONE;if(playback){if(Math.abs(x)>Math.abs(y))return x<0?SEEK_BACK:SEEK_FORWARD;return y<0?VOLUME_UP:VOLUME_DOWN;}return Math.abs(y)>=Math.abs(x)?y<0?UP:DOWN:NONE;}
 public static int delay(int command){return command==SEEK_BACK||command==SEEK_FORWARD?100:command==VOLUME_UP||command==VOLUME_DOWN?180:150;}
 public static int seek(int position,int delta,int duration){return (int)Math.max(0,Math.min(Math.max(0,duration-1),(long)position+delta));}
}
