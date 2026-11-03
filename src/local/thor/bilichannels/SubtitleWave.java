package local.thor.bilichannels;
import java.io.*;

/** Checked 16 kHz mono PCM. Digital silence never becomes an invented subtitle. */
final class SubtitleWave {
 static final int MAX_MS=30000;
 final int duration;final boolean signal;final short[] pcm;
 private SubtitleWave(int duration,boolean signal,short[] pcm){this.duration=duration;this.signal=signal;this.pcm=pcm;}
 static SubtitleWave read(File path)throws IOException{
  try(RandomAccessFile file=new RandomAccessFile(path,"r")){
   if(file.length()<44)return new SubtitleWave(0,false,new short[0]);byte[] name=new byte[4];file.readFully(name);
   if(!new String(name,"US-ASCII").equals("RIFF"))throw new IOException("字幕音轨格式不支持");file.seek(8);file.readFully(name);
   if(!new String(name,"US-ASCII").equals("WAVE"))throw new IOException("字幕音轨格式不支持");
   boolean format=false;
   while(file.getFilePointer()+8<=file.length()){
    file.readFully(name);long size=Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));long at=file.getFilePointer();String type=new String(name,"US-ASCII");
    if(size>file.length()-at)throw new IOException("字幕音轨数据不完整");
    if(type.equals("fmt ")){
     if(size<16)throw new IOException("字幕音轨格式无效");int encoding=Short.toUnsignedInt(Short.reverseBytes(file.readShort())),channels=Short.toUnsignedInt(Short.reverseBytes(file.readShort()));int rate=Integer.reverseBytes(file.readInt());file.skipBytes(6);int bits=Short.toUnsignedInt(Short.reverseBytes(file.readShort()));
     if((encoding!=1&&encoding!=65534)||channels!=1||rate!=16000||bits!=16)throw new IOException("字幕音轨需要 16kHz 单声道 PCM");format=true;
    }else if(type.equals("data")){
     if(!format||size>MAX_MS*32||size%2!=0)throw new IOException("字幕音轨区间无效");boolean signal=false;short[] pcm=new short[(int)(size/2)];
     for(int i=0;i<pcm.length;i++){pcm[i]=Short.reverseBytes(file.readShort());if(Math.abs((int)pcm[i])>4)signal=true;}
     return new SubtitleWave((int)(size*1000/32000),signal,pcm);
    }
    file.seek(at+size+(size&1));
   }
   return new SubtitleWave(0,false,new short[0]);
  }
 }
}
