package local.thor.bilichannels;
import java.net.*;import java.util.*;
/** Cookie values never leave Bilibili's authentication/API origins. */
public final class SessionCookies {
 private final Map<String,String> values=new LinkedHashMap<>();
 private static final Set<String> NAMES=new HashSet<>(Arrays.asList("SESSDATA","bili_jct","DedeUserID","DedeUserID__ckMd5","sid","buvid3","buvid4","b_nut","buvid_fp","buvid_fp_plain","bili_ticket","bili_ticket_expires","_uuid","b_lsid","CURRENT_FNVAL","CURRENT_QUALITY"));
 public static boolean trusted(String url){try{URI u=URI.create(url);return "https".equals(u.getScheme())&&Arrays.asList("api.bilibili.com","passport.bilibili.com","account.bilibili.com","www.bilibili.com","search.bilibili.com","m.bilibili.com").contains(u.getHost());}catch(Exception e){return false;}}
 public synchronized void put(String name,String value){if(NAMES.contains(name)&&value!=null&&!value.matches("(?s).*[\\r\\n;].*")){if(value.isEmpty())values.remove(name);else values.put(name,value);}}
 public synchronized String get(String name){String v=values.get(name);return v==null?"":v;}
 public synchronized boolean hasLogin(){return !get("SESSDATA").isEmpty()&&!get("bili_jct").isEmpty();}
 public synchronized String header(String url){if(!trusted(url))return "";StringBuilder s=new StringBuilder();for(Map.Entry<String,String> e:values.entrySet()){if(s.length()>0)s.append("; ");s.append(e.getKey()).append('=').append(e.getValue());}return s.toString();}
 public synchronized void merge(String url,String header){if(!trusted(url)||header==null)return;for(String token:header.split(";")){String[] p=token.trim().split("=",2);if(p.length==2)put(p[0],p[1]);}}
 public synchronized void accept(String url,List<String> setCookie){if(!trusted(url)||setCookie==null)return;for(String raw:setCookie){try{for(HttpCookie c:HttpCookie.parse(raw)){String domain=c.getDomain();if(domain!=null&&!domain.equals(".bilibili.com")&&!domain.equals("bilibili.com")&&!domain.equals(URI.create(url).getHost()))continue;put(c.getName(),c.getMaxAge()==0?"":c.getValue());}}catch(IllegalArgumentException ignored){}}}
 public synchronized void clear(){values.clear();}
}
