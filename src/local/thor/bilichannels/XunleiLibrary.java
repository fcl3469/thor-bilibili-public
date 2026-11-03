package local.thor.bilichannels;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local metadata and resume points. No signed URLs, cookies, or account passwords. */
public final class XunleiLibrary {
 public static final long RECENT_MS=30L*24*60*60*1000;
 public static final class Item {
  public final String id,name,space,parent,mime,thumbnail,category;public final boolean folder,locked;public final long size,duration,modified,created;
  public Item(JSONObject value){id=value.optString("id");name=value.optString("name","未命名");space=value.optString("space");parent=value.optString("parent");mime=value.optString("mime");thumbnail=value.optString("thumbnail");category=value.optString("category");folder=value.optBoolean("folder");locked=value.optBoolean("locked")||space.contains("SAFE");size=Math.max(0,value.optLong("size"));duration=Math.max(0,value.optLong("duration_ms"));modified=Math.max(0,value.optLong("modified_ms"));created=Math.max(0,value.optLong("created_ms"));}
  public String catalogKey(){return space+":"+id;}
  public long date(){return modified>0?modified:created;}
  public boolean video(){return !folder&&(category.equals("VIDEO")||mime.startsWith("video/")||name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|mov|avi|webm|m4v|ts|m2ts|rmvb|flv|wmv)$"));}
  public JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("name",name).put("space",space).put("parent",parent).put("mime",mime).put("category",category).put("folder",folder).put("locked",locked).put("size",size).put("duration_ms",duration).put("modified_ms",modified).put("created_ms",created);}
 }
 public static List<Item> mergeCatalog(List<Item> previous,JSONArray page)throws JSONException{
  LinkedHashMap<String,Item> items=new LinkedHashMap<>();for(Item item:previous)items.put(item.catalogKey(),item);
  if(page!=null)for(int i=0;i<page.length();i++){Item item=new Item(page.getJSONObject(i));if(item.folder||item.video())items.put(item.catalogKey(),item);}
  ArrayList<Item> result=new ArrayList<>(items.values());result.sort((a,b)->Long.compare(b.date(),a.date()));return result;
 }
 public static int catalogAnchor(List<Item> items,String key,int fallback){for(int i=0;i<items.size();i++)if(items.get(i).catalogKey().equals(key))return i;return Math.max(0,Math.min(fallback,items.size()-1));}
 public static final class Record {
  public final String key,account;public volatile Item item;public volatile long position,duration,played;public volatile String quality="";
  Record(String account,Item item){this.account=account;this.item=item;key=key(account,item.id,item.space);}
  JSONObject json()throws JSONException{return new JSONObject().put("account",account).put("item",item.json()).put("position_ms",position).put("duration_ms",duration).put("last_played",played).put("quality",quality);}
 }
 private final File file;private final LinkedHashMap<String,Record> rows=new LinkedHashMap<>();
 public XunleiLibrary(File file)throws IOException{
  this.file=file;if(!file.exists())return;
  try{JSONArray data=new JSONArray(new String(TorrentFiles.read(file,2*1024*1024),StandardCharsets.UTF_8));for(int i=0;i<data.length();i++){JSONObject value=data.getJSONObject(i);String account=value.optString("account");Item item=new Item(value.getJSONObject("item"));if(account.isEmpty()||item.id.isEmpty()||item.folder)continue;Record r=new Record(account,item);r.position=Math.max(0,value.optLong("position_ms"));r.duration=Math.max(0,value.optLong("duration_ms"));r.played=value.optLong("last_played");r.quality=value.optString("quality");rows.put(r.key,r);}}catch(JSONException error){throw new IOException("云盘播放记录读取失败",error);}
 }
 public static String accountKey(String uid){return uid==null||uid.isEmpty()||uid.equals("0")?"":hash("xunlei-account:"+uid);}
 public static String key(String account,String id,String space){return hash(account+"\n"+space+"\n"+id);}
 private static String hash(String value){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception error){throw new IllegalStateException(error);}}
 public synchronized Record get(String account,String id,String space){return rows.get(key(account,id,space));}
 public synchronized Record played(String account,Item item,String quality,long now)throws IOException{
  if(account.isEmpty()||item.id.isEmpty()||item.folder)throw new IOException("云盘视频身份无效");String key=key(account,item.id,item.space);Record r=rows.get(key);if(r==null){r=new Record(account,item);rows.put(key,r);}r.item=item;r.played=now;r.quality=quality==null?"":quality;if(item.duration>0)r.duration=item.duration;save(now);return r;
 }
 public synchronized void progress(String account,String id,String space,long position,long duration,long now)throws IOException{Record r=get(account,id,space);if(r==null)return;r.position=Math.max(0,position);if(duration>0)r.duration=duration;r.played=now;save(now);}
 public synchronized List<Record> recent(String account,long now){ArrayList<Record> out=new ArrayList<>();if(account.isEmpty())return out;for(Record r:rows.values())if(r.account.equals(account)&&now-r.played<RECENT_MS)out.add(r);out.sort((a,b)->Long.compare(b.played,a.played));return out;}
 public synchronized void remove(String key,long now)throws IOException{rows.remove(key);save(now);}
 private void save(long now)throws IOException{Iterator<Record> iterator=rows.values().iterator();while(iterator.hasNext())if(now-iterator.next().played>=RECENT_MS)iterator.remove();try{JSONArray data=new JSONArray();for(Record r:rows.values())data.put(r.json());TorrentFiles.write(file,data.toString().getBytes(StandardCharsets.UTF_8));}catch(JSONException error){throw new IOException(error);}}
}
