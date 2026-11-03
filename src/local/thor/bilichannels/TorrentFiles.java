package local.thor.bilichannels;
import java.io.*;import java.nio.file.*;
/** Atomic app-private state; a killed process leaves either the old or new checkpoint. */
public final class TorrentFiles {
 private TorrentFiles(){}
 public static byte[] read(File file,int maximum)throws IOException{long n=file.length();if(n<0||n>maximum)throw new IOException("磁力状态文件过大");return Files.readAllBytes(file.toPath());}
 public static void write(File file,byte[] bytes)throws IOException{
  File parent=file.getParentFile();if(!parent.mkdirs()&&!parent.isDirectory())throw new IOException("无法创建磁力存储目录");
  File temp=File.createTempFile(".state-",".tmp",parent);
  try{try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
   try{Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
  }finally{temp.delete();}
 }
}
