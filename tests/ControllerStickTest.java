import local.thor.bilichannels.ControllerStick;
public class ControllerStickTest {
 static void check(boolean b){if(!b)throw new AssertionError("stick contract");}
 public static void main(String[] args){
  check(ControllerStick.command(.3f,-.3f,true)==0);
  check(ControllerStick.command(Float.NaN,1,true)==0);
  check(ControllerStick.command(1,Float.POSITIVE_INFINITY,true)==0);
  check(ControllerStick.command(.6f,-.9f,true)==ControllerStick.VOLUME_UP);
  check(ControllerStick.command(.4f,1,true)==ControllerStick.VOLUME_DOWN);
  check(ControllerStick.command(-1,.7f,true)==ControllerStick.SEEK_BACK);
  check(ControllerStick.command(1,-.7f,true)==ControllerStick.SEEK_FORWARD);
  check(ControllerStick.command(1,0,false)==0);
  check(ControllerStick.command(.2f,-1,false)==ControllerStick.UP);
  check(ControllerStick.command(.2f,1,false)==ControllerStick.DOWN);
  check(ControllerStick.seek(10,-5000,100000)==0);
  check(ControllerStick.seek(999,5000,1000)==999);
  check(ControllerStick.seek(Integer.MAX_VALUE,5000,Integer.MAX_VALUE)==Integer.MAX_VALUE-1);
  check(ControllerStick.seek(0,5000,0)==0);
  System.out.println("PASS: 14 dominant-axis, deadzone, navigation and seek-boundary cases");
 }
}
