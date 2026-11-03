package local.thor.bilichannels;

import java.io.*;
import java.security.*;
import java.net.SocketTimeoutException;
import java.util.*;

/** A public model is installed only after both its pinned length and SHA256 match. */
public final class ModelDownload {
 public interface Progress {void update(long done,long total,boolean verifying)throws IOException;}
 private final HttpDownload transfer;
 public ModelDownload(){transfer=new HttpDownload();}
 public ModelDownload(HttpDownload.Connection connections){transfer=new HttpDownload(connections);}
 public void install(List<String> urls,File target,long length,String sha,Progress progress)throws IOException{
  if(length<=0||!sha.matches("[a-f0-9]{64}"))throw new IOException("模型清单无效");
  File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("模型目录无法创建");
  if(valid(target,length,sha,progress))return;
  File downloaded=new File(target+".download");
  File partial=new File(downloaded+".part"),metadata=new File(downloaded+".meta");
  if(partial.length()>length){partial.delete();metadata.delete();}
  if(!downloaded.isFile()&&valid(partial,length,sha,progress)){
   if(!partial.renameTo(downloaded))throw new IOException("模型暂存失败");metadata.delete();
  }
  if(!valid(downloaded,length,sha,progress)){
   downloaded.delete();IOException failure=new IOException("模型下载地址不可用");boolean complete=false;
   for(String url:urls){
    try{
     transfer.transfer(Collections.singletonList(url),downloaded,Collections.emptyMap(),(done,total)->{
      progress.update(done,length,false);
      if(done>length||(total>=0&&total!=length))throw new IOException("模型下载长度与清单不符");
     });
     if(!valid(downloaded,length,sha,progress)){
      downloaded.delete();new File(downloaded+".part").delete();new File(downloaded+".meta").delete();
      throw new IOException("模型校验失败，请重试下载");
     }
     complete=true;break;
    }catch(SocketTimeoutException timeout){failure=new IOException("模型连接超时，请联网后继续下载",timeout);}
    catch(InterruptedIOException paused){throw paused;}
    catch(IOException error){failure=error;}
   }
   if(!complete)throw failure;
  }
  progress.update(length,length,true);
  if(!downloaded.setReadOnly()||!downloaded.renameTo(target))throw new IOException("模型保存失败");
 }
 private static boolean valid(File file,long length,String sha,Progress progress)throws IOException{
  if(!file.isFile()||file.length()!=length)return false;
  try{
   MessageDigest digest=MessageDigest.getInstance("SHA-256");
   try(InputStream input=new FileInputStream(file)){
    byte[] bytes=new byte[1048576];for(int n;(n=input.read(bytes))!=-1;){progress.update(length,length,true);digest.update(bytes,0,n);}
   }
   StringBuilder actual=new StringBuilder();for(byte b:digest.digest())actual.append(String.format(Locale.ROOT,"%02x",b&255));
   return actual.toString().equals(sha);
  }catch(NoSuchAlgorithmException error){throw new IOException("本机无法校验模型",error);}
 }
}
