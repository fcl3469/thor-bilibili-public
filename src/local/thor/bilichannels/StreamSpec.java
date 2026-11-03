package local.thor.bilichannels;
import java.util.*;
public final class StreamSpec {
 public final List<String> video,audio;public final int quality;public final String label;public final QualityOptions options;public final boolean external;public final String mimeType;
 public StreamSpec(List<String> video,List<String> audio,int quality,String label){this(video,audio,quality,label,null);}
 public StreamSpec(List<String> video,List<String> audio,int quality,String label,QualityOptions options){this(video,audio,quality,label,options,false,"");}
 private StreamSpec(List<String> video,List<String> audio,int quality,String label,QualityOptions options,boolean external,String mimeType){this.options=options;this.video=Collections.unmodifiableList(new ArrayList<>(video));this.audio=Collections.unmodifiableList(new ArrayList<>(audio));this.quality=quality;this.label=label;this.external=external;this.mimeType=mimeType==null?"":mimeType;}
 public static StreamSpec external(String url,String label,String mime){return new StreamSpec(Collections.singletonList(url),Collections.emptyList(),0,label,null,true,mime);}
 public static StreamSpec single(String url){return new StreamSpec(Arrays.asList(url),Collections.emptyList(),16,"360P");}
 public boolean separated(){return !audio.isEmpty();}
}
