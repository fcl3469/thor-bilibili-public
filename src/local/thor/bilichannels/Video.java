package local.thor.bilichannels;
import java.util.*;
public final class Video {
 private static final java.util.concurrent.atomic.AtomicLong NEXT=new java.util.concurrent.atomic.AtomicLong();private final long instance=NEXT.incrementAndGet();public String tileKey(){return id+":"+instance;}
 public final String id,title,up,cover; public long aid,cid,upMid; public String upAvatar=""; public final int duration; public final boolean demo;
 public String description="",views="",stream="",error="",comments="正在读取评论…",localUri=""; public long streamAt;
 public String cloudAccount="",cloudFile="",cloudSpace="";public boolean cloud(){return !cloudFile.isEmpty();}
 public StreamSpec localSpec;public boolean local(){return !localUri.isEmpty();}
 public long seasonId,episodeId;public String seasonTitle="",episodeTitle="",pgcBadge="";public int pgcAuthEpoch=-1;public List<Video> pgcEpisodes=Collections.emptyList();public boolean pgc(){return episodeId>0;}
 public String qualityNote="";public boolean announceQuality;public StreamSpec previewSpec,fullSpec;public long previewSpecAt,fullSpecAt;public int fullQuality;public boolean fullResolving,liked,likeBusy,coinBusy;public int coinsGiven,likeRevision,likeEpoch=-1;public boolean likeLoading;public final List<Part> parts=new ArrayList<>();public int partIndex;
 public static final class Part{public final long cid;public final String title;public final int width,height,rotation;public Part(long cid,String title,int width,int height,int rotation){this.cid=cid;this.title=title;this.width=width;this.height=height;this.rotation=rotation;}}
 public String mediaKey(){return (pgc()?"ep"+episodeId:id)+"_"+cid;}
 public org.json.JSONObject details;public long detailsAt;
 public int width,height,demoAsset; public boolean sizeLoading,sizeRequested,decoderSizeKnown;
 // BiliApi.hydrate holds the Video monitor across network I/O. Size callbacks
 // run on the UI thread, so serialize only dimensions with a separate lock.
 private final Object dimensionLock=new Object();
 public void setDimensions(int w,int h,int rotation){synchronized(dimensionLock){decoderSizeKnown=false;if(w<=0||h<=0){width=height=0;return;}int r=((rotation%360)+360)%360;if(r==90||r==270){width=h;height=w;}else{width=w;height=h;}}}
 // Decoder dimensions describe the selected stream; late catalog/API metadata
 // must not turn a landscape stream back into a portrait. setDimensions resets
 // this authority when explicitly changing to another part.
 public void setMetadataDimensions(int w,int h,int rotation){synchronized(dimensionLock){if(!decoderSizeKnown&&w>0&&h>0)setDimensions(w,h,rotation);}}
 public void setDecoderDimensions(int w,int h){synchronized(dimensionLock){if(w>0&&h>0){setDimensions(w,h,0);decoderSizeKnown=true;}}}
 public boolean isPortrait(){return width>0&&height>width;}
 public float aspect(){return width>0&&height>0?(float)width/height:16/9f;}
 public boolean resolving,commentsLoading,commentsLoaded,danLoading,danLoaded;
 public final List<Dan> dan=new ArrayList<>();
 public Video(String id,String title,String up,String cover,long aid,long cid,int duration,boolean demo){this.id=id;this.title=title;this.up=up;this.cover=cover;this.aid=aid;this.cid=cid;this.duration=duration;this.demo=demo;}
 public static final class Dan {public final float time;public final String text; public Dan(float t,String s){time=t;text=s;}}
 public static List<Video> demoList(){String[] titles={"AYN Thor 双屏体验：这才是掌机的打开方式","Switch 2 怪物猎人荒野掌机实测","ROG Ally SteamOS 完整体体验","骁龙 8 Elite PC 模拟器到底有多强","带着 3DS 去旅行：老游戏的新假期","Wii 菜单为什么让人这么放松","雨天的小房间，和一台复古掌机","我的掌机收藏：一屏装下快乐","森林里的游戏音乐会","口袋里的海边：像素世界漫游","周末做一个属于自己的游戏角","从 NDS 到今天：双屏的想象力"};List<Video> list=new ArrayList<>();for(int i=0;i<titles.length;i++){Video v=new Video("demo"+i,titles[i],"离线演示","",0,0,10,true);boolean portrait=i>=4&&i<=7;v.demoAsset=portrait?2+i%2:i%2;v.setDimensions(portrait?360:640,portrait?640:360,0);v.description="内置原创测试动画，用来检查横竖混排、10 秒循环、双屏滚动和手柄操作。这里不是 B站真实内容。";v.comments="演示评论\n\n上屏看内容，下屏选标题。\n\n仍可见的画面接着播放。\n\n肩键切换评论和简介，菜单键打开缓存。";v.dan.add(new Dan(0,"双屏频道，开始播放"));v.dan.add(new Dan(3,"这个滚动很舒服"));v.dan.add(new Dan(6,"上屏内容 · 下屏操作"));list.add(v);}return list;}
}
