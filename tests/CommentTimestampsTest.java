import local.thor.bilichannels.CommentTimestamps;import java.util.*;
public class CommentTimestampsTest {
 static int cases;
 static void expect(String text,int... times){cases++;List<CommentTimestamps.Stamp> spans=CommentTimestamps.find(text);if(spans.size()!=times.length)throw new AssertionError(text+" count "+spans.size());for(int i=0;i<times.length;i++){CommentTimestamps.Stamp span=spans.get(i);if(span.millis!=times[i]||span.end<=span.start||!text.substring(span.start,span.end).matches("[0-9:：]+"))throw new AssertionError(text);}}
 public static void main(String[] args){
  expect("01:23",83000);expect("1:02:03",3723000);expect("看这里0:00！",0);expect("（00:03）",3000);expect("12：34",754000);expect("1：02：03",3723000);
  expect("😀开头 0:02\n精彩在 02:19，结尾 123:45",2000,139000,7425000);expect("[吃瓜]0:07[doge]",7000);expect("59:59 99:59 10:00:00",3599000,5999000,36000000);
  expect("1:60 00:99 1:61:03 01:02:70");expect("1:2 1:2:03 1:02:3 12:34:56:78");expect("1000:00 999:59:59");expect("v1:23 A01:23 01:23abc 10:20.5 1.01:23");
  expect("https://example.test/?time=01:23 0:05",5000);expect("https://example.test/1:02:03");expect("bilibili://video/1?t=02:34 www.example.test/?t=12:34");
  expect("2026-10-15 普通评论 123456");expect("");expect(null);expect("00:02、00:02",2000,2000);
  String unicode="😀中文 1:23 后文";CommentTimestamps.Stamp span=CommentTimestamps.find(unicode).get(0);if(!unicode.substring(span.start,span.end).equals("1:23"))throw new AssertionError("UTF-16 offsets");cases++;
  System.out.println("PASS: "+cases+" comment timestamp cases: formats, ranges, Unicode offsets, URL and malformed exclusions");
 }
}
