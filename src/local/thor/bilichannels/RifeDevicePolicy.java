package local.thor.bilichannels;
import java.util.Locale;
/** Pure policy; actual APU availability is established by native NNAPI probing. */
final class RifeDevicePolicy {
 static boolean lowPowerMtk(String soc,String hardware){return g90(soc)||g90(hardware);}
 private static boolean g90(String name){
  if(name==null)return false;String n=name.trim().toLowerCase(Locale.ROOT);
  return n.equals("mt6785")||n.startsWith("mt6785/")||n.startsWith("mt6785v")||n.equals("helio g90t")||n.equals("helio g90");
 }
 // Film and 25fps can try the quality path; runtime budget decides whether to keep it.
 // Unknown and 29.97/30fps begin on the fast path.
 static int stages(float fps){return fps>=23.5f&&fps<=25.5f?4:2;}
 static int estimateHeight(float fps){return stages(fps)==4?540:720;}
 static float outputRate(float fps){return fps>0&&fps<40?fps*2:60;}
 static int estimateLongEdge(int saved){return saved==128||saved==256?saved:192;}
}
