package local.thor.bilichannels;
import java.util.*;
/** QWERTY geometry shared by drawing, touch and spatial controller navigation. */
public final class SearchKeyboard {
 public static final class Key{public final String id,label;public final float x,y,w,h;public final int row;Key(String i,String l,float x,float y,float w,float h,int r){id=i;label=l;this.x=x;this.y=y;this.w=w;this.h=h;row=r;}}
 public static List<Key> make(float height,boolean symbols,boolean shift,boolean chinese,List<String> candidates,int page){
  List<Key> out=new ArrayList<>();float top=22,candidateH=Math.min(30,height*.10f),toolH=Math.min(30,height*.11f);float keyH=Math.min(64,(height-top-candidateH*2-toolH-10)/4);top+=Math.max(0,(height-top-candidateH*2-toolH-10-keyH*4)/2);
  for(int row=0;row<2;row++)for(int col=0;col<5;col++){int i=row*5+col,at=page*10+i;out.add(new Key("candidate:"+i,at<candidates.size()?candidates.get(at):"",10+col*92,top+row*candidateH,88,candidateH-4,row));}
  top+=candidateH*2+2;
  String first=symbols?(shift?"[]{}<>~=^|":"1234567890"):"qwertyuiop",second=symbols?(shift?"$€£¥+_\\@#":"@#￥%&*()-"):"asdfghjkl",third=symbols?(shift?"\"'：；（）·":"!?/：；、_"):"zxcvbnm";
  addChars(out,first,10,top,460,keyH,2,shift&&!symbols);addChars(out,second,33,top+keyH,414,keyH,3,shift&&!symbols);
  out.add(new Key("shift",symbols?"#+=":shift?"⇧ 锁定":"⇧",10,top+keyH*2,62,keyH-5,4));addChars(out,third,78,top+keyH*2,322,keyH,4,shift&&!symbols);out.add(new Key("delete","⌫",406,top+keyH*2,64,keyH-5,4));
  float y=top+keyH*3;String[] ids={"language","symbols","comma","space","period","submit"};String[] labels={chinese?"中 / En":"En / 中",symbols?"ABC":"123",chinese?"，":",","空格",chinese?"。":".","搜索"};float[] widths={68,59,38,140,38,97};float x=10;
  for(int i=0;i<ids.length;i++){out.add(new Key(ids[i],labels[i],x,y,widths[i]-4,keyH-5,5));x+=widths[i];}
  y+=keyH;String[] tools={"previous","next","left","right","all","paste","clear","system"};String[] labels2={"候选 ‹","候选 ›","光标 ‹","光标 ›","全选","粘贴","清空","系统键盘"};for(int i=0;i<tools.length;i++)out.add(new Key(tools[i],labels2[i],10+i*57.5f,y,54,toolH-3,6));return out;
 }
 static void addChars(List<Key> out,String chars,float x,float y,float width,float h,int row,boolean shift){for(int i=0;i<chars.length();i++){String letter=chars.substring(i,i+1);out.add(new Key("text:"+(shift?letter.toUpperCase(Locale.ROOT):letter),shift?letter.toUpperCase(Locale.ROOT):letter,x+i*width/chars.length(),y,width/chars.length()-4,h-5,row));}}
 public static int hit(List<Key> keys,float x,float y){for(int i=0;i<keys.size();i++){Key k=keys.get(i);if(x>=k.x&&x<=k.x+k.w&&y>=k.y&&y<=k.y+k.h)return i;}return -1;}
 public static int move(List<Key> keys,int index,int dx,int dy){if(keys.isEmpty())return 0;index=Math.max(0,Math.min(index,keys.size()-1));Key from=keys.get(index);int best=index;float score=Float.MAX_VALUE;for(int i=0;i<keys.size();i++){Key k=keys.get(i);if(k.label.isEmpty())continue;if(dy!=0){if((k.row-from.row)*dy<=0)continue;float d=Math.abs(k.row-from.row)*10000+Math.abs(k.x+k.w/2-from.x-from.w/2);if(d<score){score=d;best=i;}}else if(k.row==from.row&&(k.x-from.x)*dx>0){float d=Math.abs(k.x-from.x);if(d<score){score=d;best=i;}}}return best;}
}
