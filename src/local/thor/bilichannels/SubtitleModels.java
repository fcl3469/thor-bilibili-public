package local.thor.bilichannels;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

/** Model metadata is bundled; verified weights live in the same private paths as older APKs. */
final class SubtitleModels {
 interface Progress {void stage(String value);}
 interface DownloadProgress {void update(String stage,long done,long total)throws IOException;}
 private static JSONObject manifest(Context context)throws Exception{
  try(InputStream input=context.getAssets().open("subtitles/manifest.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;)out.write(buffer,0,n);
   return new JSONObject(new String(out.toByteArray(),"UTF-8"));
  }
 }
 private static File root(Context context){return new File(context.getNoBackupFilesDir(),"thor-subtitles");}
 private static String version(JSONObject asr)throws Exception{
  ArrayList<String> names=names(asr);StringBuilder stamp=new StringBuilder(asr.getString("kind"));
  for(String name:names)stamp.append(asr.getJSONObject("files").getString(name));return stamp.toString();
 }
 private static ArrayList<String> names(JSONObject asr)throws Exception{
  ArrayList<String> names=new ArrayList<>();Iterator<String> keys=asr.getJSONObject("files").keys();
  while(keys.hasNext()){String name=keys.next();if(name.isEmpty()||name.equals(".")||name.equals("..")||!name.equals(new File(name).getName())||name.contains("\\"))throw new IOException("识别模型文件名无效");names.add(name);}
  Collections.sort(names);return names;
 }
 private static boolean asrReady(File directory,JSONObject asr)throws Exception{
  if(!ready(directory,version(asr)))return false;
  for(String name:names(asr))if(!new File(directory,name).isFile()||new File(directory,name).length()!=asr.getJSONObject("file_sizes").getLong(name))return false;
  return true;
 }
 private static boolean directReady(File directory,JSONObject direct)throws Exception{
  File file=new File(directory,"model.gguf");return ready(directory,direct.getString("sha256"))&&file.isFile()&&file.length()==direct.getLong("size_bytes");
 }
 static boolean available(Context context){
  try{JSONObject data=manifest(context);return asrReady(new File(root(context),"reazon"),data.getJSONObject("asr"))&&directReady(new File(root(context),"direct"),data.getJSONObject("direct_translation"));}
  catch(Exception error){return false;}
 }
 static long totalBytes(Context context){
  try{JSONObject data=manifest(context),asr=data.getJSONObject("asr");long total=data.getJSONObject("direct_translation").getLong("size_bytes");for(String name:names(asr))total+=asr.getJSONObject("file_sizes").getLong(name);return total;}
  catch(Exception error){return 0;}
 }
 static synchronized File prepare(Context context,Progress progress)throws Exception{
  JSONObject asr=manifest(context).getJSONObject("asr");File directory=new File(root(context),"reazon");
  if(!asrReady(directory,asr))throw new IOException("请先在「字幕模型」中下载日语识别模型");return directory;
 }
 static synchronized File directModel(Context context,Progress progress)throws Exception{
  JSONObject direct=manifest(context).getJSONObject("direct_translation");File directory=new File(root(context),"direct");
  if(!directReady(directory,direct))throw new IOException("请先在「字幕模型」中下载日中直译模型");return new File(directory,"model.gguf");
 }
 static synchronized void download(Context context,DownloadProgress progress)throws Exception{
  JSONObject data=manifest(context),asr=data.getJSONObject("asr"),direct=data.getJSONObject("direct_translation");
  File directory=new File(root(context),"reazon"),translation=new File(root(context),"direct");
  long total=totalBytes(context),done=0;ModelDownload download=new ModelDownload();
  // Old ready stamps include the exact model hashes; reuse installed models without a transfer.
  boolean recognitionReady=asrReady(directory,asr);
  for(String name:names(asr)){
   long length=asr.getJSONObject("file_sizes").getLong(name);final long base=done;
   if(!recognitionReady){
    ArrayList<String> urls=downloadUrls(asr,name);
    download.install(urls,new File(directory,name),length,asr.getJSONObject("files").getString(name),
     (bytes,size,checking)->progress.update((checking?"正在校验日语识别模型":"正在下载日语识别模型"),base+Math.min(bytes,length),total));
   }
   done+=length;progress.update("日语识别模型已准备",done,total);
  }
  if(!recognitionReady)mark(directory,version(asr));final long base=done;
  if(!directReady(translation,direct))download.install(Arrays.asList(direct.getString("url"),direct.getString("upstream_url")),new File(translation,"model.gguf"),direct.getLong("size_bytes"),direct.getString("sha256"),
   (bytes,size,checking)->progress.update(checking?"正在校验日中直译模型":"正在下载日中直译模型",base+Math.min(bytes,size),total));
  progress.update("正在保存已校验的模型",total,total);mark(translation,direct.getString("sha256"));
 }
 static ArrayList<String> downloadUrls(JSONObject asr,String name)throws Exception{
  ArrayList<String> urls=new ArrayList<>();JSONObject mirrors=asr.optJSONObject("file_mirrors");JSONArray rows=mirrors==null?null:mirrors.optJSONArray(name);
  if(rows!=null)for(int i=0;i<rows.length();i++)urls.add(rows.getString(i));urls.add(asr.getJSONObject("file_urls").getString(name));return urls;
 }
 private static boolean ready(File directory,String sha){
  File stamp=new File(directory,".thor-model-ready");if(!stamp.isFile()||stamp.length()>4096)return false;
  try(InputStream input=new FileInputStream(stamp);ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buffer=new byte[1024];for(int n;(n=input.read(buffer))!=-1;)out.write(buffer,0,n);return new String(out.toByteArray(),"UTF-8").equals(sha);
  }catch(IOException error){return false;}
 }
 private static void mark(File directory,String sha)throws IOException{
  if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("模型目录无法创建");
  File temporary=new File(directory,".thor-model-ready.tmp");
  try(FileOutputStream out=new FileOutputStream(temporary)){out.write(sha.getBytes("UTF-8"));out.getFD().sync();}
  if(!temporary.renameTo(new File(directory,".thor-model-ready")))throw new IOException("模型状态保存失败");
 }
}
