package local.thor.bilichannels;

import java.util.*;
import java.util.regex.*;

/** Offsets stay in the original comment text, including Unicode and line breaks. */
public final class CommentTimestamps {
 private static final Pattern TIME=Pattern.compile("(?<![0-9A-Za-z_:/：.])([0-9]{1,3})[:：]([0-9]{2})(?:[:：]([0-9]{2}))?(?![0-9A-Za-z_:/：.])");
 private static final Pattern URL=Pattern.compile("(?i)(?:https?://|bilibili://|www\\.)\\S+");
 public static final class Stamp {
  public final int start,end,millis;
  Stamp(int start,int end,int millis){this.start=start;this.end=end;this.millis=millis;}
 }
 public static List<Stamp> find(String text){
  List<Stamp> result=new ArrayList<>();if(text==null||text.isEmpty())return result;
  List<int[]> urls=new ArrayList<>();Matcher url=URL.matcher(text);while(url.find())urls.add(new int[]{url.start(),url.end()});
  Matcher match=TIME.matcher(text);while(match.find()){
   int first=Integer.parseInt(match.group(1)),second=Integer.parseInt(match.group(2));String last=match.group(3);
   if(second>=60)continue;long seconds=first*60L+second;
   if(last!=null){int third=Integer.parseInt(last);if(third>=60)continue;seconds=first*3600L+second*60L+third;}
   if(seconds>Integer.MAX_VALUE/1000)continue;
   boolean inUrl=false;for(int[] range:urls)if(match.start()<range[1]&&match.end()>range[0]){inUrl=true;break;}
   if(!inUrl)result.add(new Stamp(match.start(),match.end(),(int)(seconds*1000)));
  }return result;
 }
 private CommentTimestamps(){}
}
