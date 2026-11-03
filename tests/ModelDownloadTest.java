import local.thor.bilichannels.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Exercise interrupted and corrupt model delivery without real network or Android storage. */
public class ModelDownloadTest {
 static int cases;static void check(boolean value,String reason){cases++;if(!value)throw new AssertionError(reason);}
 static final byte[] DATA=new byte[150000];static{for(int i=0;i<DATA.length;i++)DATA[i]=(byte)(i*29);}
 static String hash(byte[] data)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
 static class Reply extends HttpURLConnection {
  final int code;final byte[] data;final Map<String,String> headers=new HashMap<>();
  Reply(int code,byte[] data)throws Exception{super(new URL("https://example.test/model"));this.code=code;this.data=data;headers.put("ETag","\"model-v1\"");}
  public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}public int getResponseCode(){return code;}public long getContentLengthLong(){return Long.parseLong(headers.getOrDefault("Content-Length",String.valueOf(data.length)));}public String getHeaderField(String key){return headers.get(key);}public InputStream getInputStream(){return new ByteArrayInputStream(data);}
 }
 static Reply reply(int code,byte[] data){try{return new Reply(code,data);}catch(Exception e){throw new RuntimeException(e);}}
 static final ModelDownload.Progress PROGRESS=(done,total,verifying)->{};
 static final List<String> URLS=Collections.singletonList("https://example.test/model");
 static void remove(File file){file.setWritable(true);if(file.isDirectory())for(File child:file.listFiles())remove(child);file.delete();}
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("thor-model-download-").toFile(),model=new File(dir,"model.gguf");String sha=hash(DATA);
  try{
   new ModelDownload(url->reply(200,DATA)).install(URLS,model,DATA.length,sha,PROGRESS);
   check(Arrays.equals(Files.readAllBytes(model.toPath()),DATA),"verified model installed exactly");
   new ModelDownload(url->{throw new AssertionError("installed model redownloaded");}).install(URLS,model,DATA.length,sha,PROGRESS);check(true,"valid installed model reused without network");remove(model);
   try{new ModelDownload(url->reply(200,DATA)).install(URLS,model,DATA.length,sha,(done,total,checking)->{if(!checking&&done>=65536)throw new InterruptedIOException("pause");});throw new AssertionError("pause ignored");}catch(InterruptedIOException expected){}
   File partial=new File(model+".download.part");long offset=partial.length();check(offset>0&&offset<DATA.length&&!model.exists(),"pause preserves only a partial model");
   Reply resumed=reply(206,Arrays.copyOfRange(DATA,(int)offset,DATA.length));resumed.headers.put("Content-Range","bytes "+offset+"-"+(DATA.length-1)+"/"+DATA.length);
   new ModelDownload(url->resumed).install(URLS,model,DATA.length,sha,PROGRESS);check(("bytes="+offset+"-").equals(resumed.getRequestProperty("Range")),"resume asks for the exact missing bytes");check(Arrays.equals(Files.readAllBytes(model.toPath()),DATA),"resumed content verified before install");remove(model);
   byte[] corrupt=DATA.clone();corrupt[10]^=3;
   try{new ModelDownload(url->reply(200,corrupt)).install(URLS,model,DATA.length,sha,PROGRESS);throw new AssertionError("corrupt model accepted");}catch(IOException expected){}
   check(!model.exists()&&!new File(model+".download").exists(),"same-length hash mismatch never installed or retained as complete");
   int[] requested={0};new ModelDownload(url->reply(200,requested[0]++==0?corrupt:DATA)).install(Arrays.asList("https://one.test/model","https://two.test/model"),model,DATA.length,sha,PROGRESS);
   check(requested[0]==2&&Arrays.equals(Files.readAllBytes(model.toPath()),DATA),"corrupt mirror falls back to a source with the pinned hash");remove(model);
   Reply truncated=reply(200,Arrays.copyOf(DATA,25000));truncated.headers.put("Content-Length",String.valueOf(DATA.length));
   try{new ModelDownload(url->truncated).install(URLS,model,DATA.length,sha,PROGRESS);throw new AssertionError("truncation accepted");}catch(IOException expected){}
   check(!model.exists()&&new File(model+".download.part").length()==25000,"network truncation is resumable and cannot be loaded");remove(new File(model+".download.part"));remove(new File(model+".download.meta"));
   try{new ModelDownload(url->reply(200,new byte[DATA.length+1])).install(URLS,model,DATA.length,sha,PROGRESS);throw new AssertionError("oversize accepted");}catch(IOException expected){}check(!model.exists(),"response length checked before installation");
   Files.write(new File(model+".download").toPath(),DATA);new ModelDownload(url->{throw new AssertionError("fully downloaded stage fetched again");}).install(URLS,model,DATA.length,sha,PROGRESS);check(model.isFile(),"process death after transfer resumes from verified staging");remove(model);
   Files.write(new File(model+".download.part").toPath(),DATA);new ModelDownload(url->{throw new AssertionError("complete partial fetched again");}).install(URLS,model,DATA.length,sha,PROGRESS);check(model.isFile(),"last-byte pause validates the complete partial locally");remove(model);
   int[] attempts={0};new ModelDownload(url->{if(attempts[0]++==0)throw new SocketTimeoutException("timeout");return reply(200,DATA);}).install(Arrays.asList("https://one.test/model","https://two.test/model"),model,DATA.length,sha,PROGRESS);check(attempts[0]==2&&model.isFile(),"connection timeout falls back rather than treating it as user pause");remove(model);
   try{new ModelDownload(url->reply(200,DATA)).install(URLS,model,DATA.length,sha,(done,total,checking)->{if(checking)throw new InterruptedIOException("pause verify");});throw new AssertionError("verification pause ignored");}catch(InterruptedIOException expected){}check(!model.exists(),"pause during checksum cannot install unverified bytes");remove(new File(model+".download"));
   Files.write(model.toPath(),corrupt);new ModelDownload(url->reply(200,DATA)).install(URLS,model,DATA.length,sha,PROGRESS);check(Arrays.equals(Files.readAllBytes(model.toPath()),DATA),"invalid existing file replaced only with verified bytes");
   try{new ModelDownload(url->{throw new AssertionError("bad manifest requested network");}).install(URLS,new File(dir,"other"),DATA.length,"bad",PROGRESS);throw new AssertionError("invalid SHA accepted");}catch(IOException expected){}check(true,"invalid pinned manifest rejected");
  }finally{remove(dir);}
  System.out.println("PASS: "+cases+" pinned model install, reuse, interruption, resume, fallback, corruption and truncation cases");
 }
}
