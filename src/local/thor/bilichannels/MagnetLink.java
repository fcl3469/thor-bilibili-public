package local.thor.bilichannels;
import java.net.URLDecoder;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;
/** Clipboard text is inspected locally; only a validated magnet is offered. */
public final class MagnetLink {
 private MagnetLink(){}
 public static String extract(CharSequence source){
  if(source==null)return null;String text=source.toString();if(text.length()>131072)text=text.substring(0,131072);
  String lower=text.toLowerCase(Locale.ROOT);int at=0;
  while((at=lower.indexOf("magnet:?",at))>=0){int end=at;while(end<text.length()&&end-at<=32768){char c=text.charAt(end);if(Character.isWhitespace(c)||Character.isISOControl(c)||"<>\"'“”‘’「」『』，。；（）".indexOf(c)>=0)break;end++;}
   String candidate=text.substring(at,end);while(candidate.endsWith(".")||candidate.endsWith(",")||candidate.endsWith(";"))candidate=candidate.substring(0,candidate.length()-1);
   try{return TorrentEngine.validateMagnet(candidate);}catch(IllegalArgumentException ignored){}at+=8;
  }return null;
 }
 public static String key(String magnet){
  String v1=null,v2=null;try{for(String field:magnet.substring(8).split("&")){int at=field.indexOf('=');if(at<0||!decode(field.substring(0,at)).equals("xt"))continue;String value=decode(field.substring(at+1)).toLowerCase(Locale.ROOT);
   if(value.startsWith("urn:btih:")){String hash=value.substring(9);if(hash.matches("[0-9a-f]{40}"))v1=hash;else if(hash.matches("[a-z2-7]{32}")){int buffer=0,bits=0;StringBuilder hex=new StringBuilder();for(char c:hash.toCharArray()){int digit=c>='a'&&c<='z'?c-'a':c-'2'+26;buffer=(buffer<<5)|digit;bits+=5;if(bits>=8){bits-=8;hex.append(String.format(Locale.ROOT,"%02x",(buffer>>bits)&255));}}v1=hex.toString();}}
   if(value.matches("urn:btmh:1220[0-9a-f]{64}"))v2=value.substring(9);
  }}catch(RuntimeException ignored){}
  if(v1!=null)return "btih:"+v1;if(v2!=null)return "btmh:"+v2;throw new IllegalArgumentException("无效的磁力哈希");
 }
 public static String title(String magnet){try{for(String field:magnet.substring(8).split("&"))if(field.startsWith("dn=")){String s=decode(field.substring(3)).replaceAll("[\\p{Cntrl}]", " ").trim();if(!s.isEmpty())return s.length()>90?s.substring(0,90):s;}}catch(RuntimeException ignored){}return "磁力视频资源";}
 public static String fingerprint(String magnet){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(key(magnet).getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte value:bytes)b.append(String.format(Locale.ROOT,"%02x",value&255));return b.toString();}catch(Exception e){throw new IllegalArgumentException("磁力链接无效");}}
 private static String decode(String text){try{return URLDecoder.decode(text,"UTF-8");}catch(Exception e){throw new IllegalArgumentException(e);}}
}
