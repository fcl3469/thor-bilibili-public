package local.thor.bilichannels;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.util.*;
import javax.imageio.ImageIO;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;

public final class DonationTest {
 private static int checks;
 private static void check(boolean value,String detail){checks++;if(!value)throw new AssertionError(detail);}
 public static void main(String[] args)throws Exception{
  int[] values={1,100,200,500,1000,123,999999};
  for(int value:values)check(DonationAmount.parse(DonationAmount.text(value))==value,"amount round trip: "+value);
  for(String value:new String[]{"","0","0.00","-1","0.001","1.234","10000","1e2","NaN","1,00","1.00 extra"}){try{DonationAmount.parse(value);throw new AssertionError("invalid amount accepted");}catch(IllegalArgumentException expected){checks++;}}
  check(Arrays.equals(DonationAmount.PRESETS,new int[]{100,200,500,1000}),"requested preset amounts");
  check(DonationAmount.parse(" 1.20 ")==120,"trimmed decimal amount");
  for(String provider:new String[]{"wechat","alipay"}){
   BufferedImage image=ImageIO.read(new File("assets/support/"+provider+".jpg"));check(image!=null,"provided image readable");int w=image.getWidth(),h=image.getHeight();RGBLuminanceSource source=new RGBLuminanceSource(w,h,image.getRGB(0,0,w,h,null,0,w));Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,true);URI uri=new URI(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)),hints).getText());
   check(provider.equals("wechat")?"wxp".equals(uri.getScheme()):"https".equals(uri.getScheme())&&"qr.alipay.com".equals(uri.getHost()),"provided payment QR belongs to the intended provider");
  }
  System.out.println("PASS: "+checks+" donation amount and offline payment QR checks; no payment app or transfer invoked");
 }
}
