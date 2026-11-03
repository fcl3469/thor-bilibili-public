package local.thor.bilichannels;
public final class RifeDevicePolicyTest {
 private static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  check(RifeDevicePolicy.lowPowerMtk("MT6785",null));check(RifeDevicePolicy.lowPowerMtk("MT6785V/CC","unknown"));
  check(RifeDevicePolicy.lowPowerMtk("unknown","mt6785"));check(RifeDevicePolicy.lowPowerMtk("Helio G90T",""));
  for(String other:new String[]{"SM8550","SM8750","QCS8550","MT6789","MT67850",null})check(!RifeDevicePolicy.lowPowerMtk(other,"unknown"));
  check(RifeDevicePolicy.estimateLongEdge(128)==128);check(RifeDevicePolicy.estimateLongEdge(256)==256);
  for(int v:new int[]{-1,0,192,720,1080,Integer.MAX_VALUE})check(RifeDevicePolicy.estimateLongEdge(v)==192);
  for(float fps:new float[]{23.976f,24f,25f}){check(RifeDevicePolicy.stages(fps)==4);check(RifeDevicePolicy.estimateHeight(fps)==540);}
  for(float fps:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY,23,26,29.97f,30,60}){check(RifeDevicePolicy.stages(fps)==2);check(RifeDevicePolicy.estimateHeight(fps)==720);}
  check(RifeDevicePolicy.outputRate(24)==48);check(RifeDevicePolicy.outputRate(30)==60);check(RifeDevicePolicy.outputRate(25)==50);check(RifeDevicePolicy.outputRate(0)==60);
  RifeFrameBudget smooth=new RifeFrameBudget();for(int i=0;i<120;i++)check(!smooth.record(25000,40000,i));
  RifeFrameBudget slow=new RifeFrameBudget();for(int i=0;i<8;i++)slow.record(45000,40000,0);check(slow.fast);check(slow.record(10000,40000,100));slow.reset();check(!slow.fast);
  RifeFrameBudget drops=new RifeFrameBudget();for(int i=0;i<120&&!drops.fast;i++)drops.record(20000,40000,i*3/4);check(drops.fast);
  System.out.println("G90 family policy, non-MTK isolation and bounded estimation profiles PASS");
 }
}
