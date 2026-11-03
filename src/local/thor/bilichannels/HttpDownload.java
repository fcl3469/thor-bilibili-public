package local.thor.bilichannels;
import java.io.*;import java.net.*;import java.util.*;import java.util.regex.*;
/** App-owned streaming transfer. A partial file never becomes playable before EOF/length validation. */
public final class HttpDownload {
 public interface Progress {void update(long done,long total)throws IOException;}
 public interface Connection {HttpURLConnection open(String url)throws IOException;}
 public static final Connection HTTPS=url->{URL parsed=new URL(url);if(!"https".equals(parsed.getProtocol()))throw new IOException("缓存仅支持 HTTPS");return (HttpURLConnection)parsed.openConnection();};
 final Connection connections;public HttpDownload(){this(HTTPS);}public HttpDownload(Connection connections){this.connections=connections;}
 public void transfer(List<String> urls,File target,Map<String,String> headers,Progress progress)throws IOException{
  IOException failure=new IOException("未取得缓存地址");for(String url:urls)try{download(url,target,headers,progress);return;}catch(InterruptedIOException e){throw e;}catch(IOException e){failure=e;}throw failure;
 }
 public long length(List<String> urls,Map<String,String> headers){for(String source:urls){HttpURLConnection c=null;try{String url=source;for(int n=0;n<6;n++){c=connections.open(url);c.setRequestMethod("HEAD");c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());c.setRequestProperty("Accept-Encoding","identity");int code=c.getResponseCode();if(code>=300&&code<400&&c.getHeaderField("Location")!=null){url=new URL(new URL(url),c.getHeaderField("Location")).toString();c.disconnect();continue;}if(code==200&&c.getContentLengthLong()>0)return c.getContentLengthLong();break;}}catch(IOException ignored){}finally{if(c!=null)c.disconnect();}}return -1;}
 private void download(String source,File target,Map<String,String> headers,Progress progress)throws IOException{
  File partial=new File(target+".part"),metadata=new File(target+".meta");Properties saved=new Properties();if(metadata.isFile())try(InputStream in=new FileInputStream(metadata)){saved.load(in);}catch(IOException ignored){}
  String validator=saved.getProperty("validator","");long offset=partial.isFile()&&!validator.isEmpty()?partial.length():0;
  for(int attempt=0;attempt<2;attempt++){
   progress.update(offset,-1);HttpURLConnection connection=null;
   try{
    String url=source;for(int redirects=0;;redirects++){connection=connections.open(url);connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);for(Map.Entry<String,String> h:headers.entrySet())connection.setRequestProperty(h.getKey(),h.getValue());connection.setRequestProperty("Accept-Encoding","identity");if(offset>0){connection.setRequestProperty("Range","bytes="+offset+"-");connection.setRequestProperty("If-Range",validator);}int code=connection.getResponseCode();if(code>=300&&code<400){String location=connection.getHeaderField("Location");if(location==null||redirects>=5)throw new IOException("缓存重定向失败");url=new URL(new URL(url),location).toString();connection.disconnect();continue;}break;}
    int code=connection.getResponseCode();if(code==416&&offset>0){offset=0;validator="";continue;}if(code!=200&&code!=206)throw new IOException("缓存请求失败 HTTP "+code);
    long response=connection.getContentLengthLong(),total=response,start=0;String etag=connection.getHeaderField("ETag"),modified=connection.getHeaderField("Last-Modified");String newValidator=etag!=null&&!etag.startsWith("W/")?etag:modified==null?"":modified;
    if(code==206){Matcher range=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(String.valueOf(connection.getHeaderField("Content-Range")));if(!range.matches())throw new IOException("缓存续传范围无效");start=Long.parseLong(range.group(1));long end=Long.parseLong(range.group(2));total=Long.parseLong(range.group(3));if(start!=offset||end<start||end>=total||(response>=0&&response!=end-start+1))throw new IOException("缓存续传范围不一致");if(offset>0&&!newValidator.equals(validator)){offset=0;validator="";continue;}}
    if(code==200){offset=0;start=0;}
    saved.setProperty("validator",newValidator);try(OutputStream out=new FileOutputStream(metadata)){saved.store(out,"Thor partial transfer");}
    long done=start,received=0;try(InputStream in=connection.getInputStream();RandomAccessFile out=new RandomAccessFile(partial,"rw")){if(start==0)out.setLength(0);out.seek(start);byte[] buffer=new byte[65536];int size;progress.update(done,total);while((size=in.read(buffer))!=-1){progress.update(done,total);out.write(buffer,0,size);received+=size;done+=size;}out.getFD().sync();}
    if((response>=0&&received!=response)||(total>=0&&done!=total)||done<=0)throw new IOException("缓存数据未完整，保留进度后可重试");progress.update(done,done);
    if(!partial.renameTo(target))throw new IOException("无法保存完整缓存文件");metadata.delete();return;
   }finally{if(connection!=null)connection.disconnect();}
  }throw new IOException("缓存续传校验失败，请重试");
 }
}
