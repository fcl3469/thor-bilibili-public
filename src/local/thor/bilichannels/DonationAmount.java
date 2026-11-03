package local.thor.bilichannels;

import java.math.BigDecimal;

/** Integer cents throughout; never round a supporter's chosen amount. */
final class DonationAmount {
 static final int[] PRESETS={100,200,500,1000};
 static int parse(String value){
  String clean=value==null?"":value.trim();
  if(!clean.matches("[0-9]{1,4}(?:\\.[0-9]{1,2})?"))throw new IllegalArgumentException("请输入 0.01–9999.99 元，最多两位小数");
  int cents=new BigDecimal(clean).movePointRight(2).intValueExact();
  if(cents<1||cents>999999)throw new IllegalArgumentException("请输入 0.01–9999.99 元");
  return cents;
 }
 static String text(int cents){if(cents<1||cents>999999)throw new IllegalArgumentException("金额无效");return BigDecimal.valueOf(cents,2).toPlainString();}
}
