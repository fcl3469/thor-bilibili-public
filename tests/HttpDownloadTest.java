import local.thor.bilichannels.*;import java.io.*;import java.net.*;import java.nio.file.*;import java.util.*;
public class HttpDownloadTest {
 static int cases;static void check(boolean v,String message){cases++;if(!v)throw new AssertionError(message);}
 static final byte[] DATA=new byte[1024];static{for(int i=0;i<DATA.length;i++)DATA[i]=(byte)i;}
 static class Reply extends HttpURLConnection {
  final int code;final byte[] payload;final Map<String,String> headers=new HashMap<>();Reply(int code,byte[] payload)throws Exception{super(new URL("https://example.test/video"));this.code=code;this.payload=payload;headers.put("ETag","\"v1\"");}
  public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}public int getResponseCode(){return code;}public long getContentLengthLong(){return Long.parseLong(headers.getOrDefault("Content-Length",String.valueOf(payload.length)));}public String getHeaderField(String key){return headers.get(key);}public InputStream getInputStream(){return new ByteArrayInputStream(payload);}
 }
 static Reply response(int code,byte[] data){try{return new Reply(code,data);}catch(Exception e){throw new RuntimeException(e);}}
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("thor-download-check").toFile(),target=new File(dir,"video.mp4");Map<String,String> headers=Collections.emptyMap();
  new HttpDownload(url->response(200,DATA)).transfer(Arrays.asList("https://example.test/v"),target,headers,(n,total)->{});check(Arrays.equals(Files.readAllBytes(target.toPath()),DATA),"whole transfer bytes");target.delete();
  Reply truncated=response(200,Arrays.copyOf(DATA,300));truncated.headers.put("Content-Length","1024");try{new HttpDownload(url->truncated).transfer(Arrays.asList("https://example.test/v"),target,headers,(n,total)->{});throw new AssertionError("truncated body accepted");}catch(IOException expected){}check(!target.exists()&&new File(target+".part").length()==300,"truncated data is partial only");
  Reply resume=response(206,Arrays.copyOfRange(DATA,300,1024));resume.headers.put("Content-Range","bytes 300-1023/1024");new HttpDownload(url->resume).transfer(Arrays.asList("https://example.test/v"),target,headers,(n,total)->{});check("bytes=300-".equals(resume.getRequestProperty("Range"))&&"\"v1\"".equals(resume.getRequestProperty("If-Range")),"resume validated range");check(Arrays.equals(Files.readAllBytes(target.toPath()),DATA),"resumed bytes match exactly");target.delete();
  Files.write(new File(target+".part").toPath(),Arrays.copyOf(DATA,300));Properties props=new Properties();props.setProperty("validator","\"v1\"");try(OutputStream out=new FileOutputStream(target+".meta")){props.store(out,"");}
  new HttpDownload(url->response(200,DATA)).transfer(Arrays.asList("https://example.test/v"),target,headers,(n,total)->{});check(target.length()==1024&&Arrays.equals(Files.readAllBytes(target.toPath()),DATA),"server ignoring range restarts instead of appending");target.delete();
  try{new HttpDownload(url->response(200,DATA)).transfer(Arrays.asList("https://example.test/v"),target,headers,(n,total)->{if(n>0)throw new InterruptedIOException("paused");});throw new AssertionError("pause ignored");}catch(InterruptedIOException expected){}check(!target.exists()&&new File(target+".part").length()==1024,"paused transfer cannot be marked playable");
  Reply invalid=response(206,DATA);invalid.headers.put("Content-Range","bytes 0-1023/1024");try{new HttpDownload(url->invalid).transfer(Arrays.asList("https://example.test/v"),target,headers,(n,total)->{});throw new AssertionError("wrong range accepted");}catch(IOException expected){}check(!target.exists(),"invalid range rejected");
  for(File file:dir.listFiles())file.delete();dir.delete();System.out.println("PASS: "+cases+" streaming, truncation, range resume, restart and cancellation cases");
 }
}
