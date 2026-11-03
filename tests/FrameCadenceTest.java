package local.thor.bilichannels;
public final class FrameCadenceTest {
 static void check(boolean value){if(!value)throw new AssertionError("Timestamp compatibility");}
 public static void main(String[] args){
  for(float fps:new float[]{23.976f,24,25,29.97f,30}){FrameCadence c=new FrameCadence();for(int i=0;i<300;i++){long pts=Math.round(i*1000000.0/fps);check(c.map(pts,fps)==pts);}check(!c.active());}
  FrameCadence broken=new FrameCadence();long previous=-1;int corrected=0;for(int i=0;i<600;i++){long pts=(i/4)*133333L+new long[]{0,62,33333,100000}[i%4];long value=broken.map(pts,29.196926f);check(Math.abs(value-pts)<=38367);if(broken.active()&&previous>=0){check(value>previous);corrected++;}previous=value;}check(broken.active()&&corrected>400);check(broken.fps()>29&&broken.fps()<31);
  broken.reset();check(!broken.active());check(broken.map(123456789,30)==123456789);
  FrameCadence vfr=new FrameCadence();long pts=0;for(int i=0;i<300;i++){pts+=i%3==0?66667:33333;check(vfr.map(pts,30)==pts);}check(!vfr.active());
  FrameCadence pairsWithoutCatchup=new FrameCadence();pts=0;for(int i=0;i<300;i++){pts+=i%4==0?62:33333;check(pairsWithoutCatchup.map(pts,30)==pts);}check(!pairsWithoutCatchup.active());
  FrameCadence backward=new FrameCadence();int repaired=0;long prior=0;boolean active=false;for(int i=0;i<600;i++){long p=(i/4)*133333L+new long[]{0,66667,33333,100000}[i%4];long v=backward.map(p,30);check(Math.abs(v-p)<=38367);if(active&&backward.active())check(v>prior);if(backward.active())repaired++;active=backward.active();prior=v;}check(repaired>400);
  FrameCadence interrupted=new FrameCadence();int sustained=0;long end=0;for(int i=0;i<900;i++){if(i>100&&i%120==0)continue;long p=(i/4)*133333L+new long[]{0,62,33333,100000}[i%4];long v=interrupted.map(p,30);check(Math.abs(v-p)<=38367);if(interrupted.active())sustained++;end=p;}check(sustained>700);
  for(int i=0;i<150;i++){end+=i%3==0?66667:33333;long v=interrupted.map(end,30);check(Math.abs(v-end)<=38367);}check(!interrupted.active());
  System.out.println("Timestamp compatibility: normal CFR/VFR, balanced defect, bounded shift and reset PASS");
 }
}
