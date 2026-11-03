package local.thor.bilichannels;
import java.util.*;import java.net.URLEncoder;import java.security.MessageDigest;
public final class Wbi {
 static final int[] TABLE={46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52};
 public static String encode(String v){try{return URLEncoder.encode(v,"UTF-8").replace("+","%20").replace("%7E","~").replace("*","%2A");}catch(Exception e){throw new IllegalArgumentException(e);}}
 public static String query(Map<String,String> params){StringBuilder b=new StringBuilder();for(Map.Entry<String,String> e:new TreeMap<>(params).entrySet()){if(b.length()>0)b.append('&');b.append(encode(e.getKey())).append('=').append(encode(e.getValue()));}return b.toString();}
 public static String sign(Map<String,String> input,String keys,long seconds)throws Exception{if(keys.length()!=64)throw new IllegalArgumentException("Invalid WBI key");StringBuilder mix=new StringBuilder();for(int i=0;i<32;i++)mix.append(keys.charAt(TABLE[i]));Map<String,String> p=new TreeMap<>();for(Map.Entry<String,String> e:input.entrySet())p.put(e.getKey(),e.getValue().replaceAll("[!'()*]",""));p.put("wts",String.valueOf(seconds));String q=query(p);byte[] hash=MessageDigest.getInstance("MD5").digest((q+mix).getBytes("UTF-8"));StringBuilder h=new StringBuilder();for(byte b:hash)h.append(String.format(Locale.ROOT,"%02x",b&255));return q+"&w_rid="+h;}
}
