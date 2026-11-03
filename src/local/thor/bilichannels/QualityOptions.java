package local.thor.bilichannels;
import org.json.*;import java.util.*;
/** Server-advertised tiers are distinct from playable tracks and account permission. */
public final class QualityOptions {
 public final int ceiling;public final Set<Integer> advertised=new HashSet<>(),h264=new HashSet<>(),other=new HashSet<>(),loginRequired=new HashSet<>(),vipRequired=new HashSet<>();public final boolean hasCatalogue,audio;
 public QualityOptions(JSONObject d){ceiling=d.optInt("quality",0);JSONArray accept=d.optJSONArray("accept_quality"),formats=d.optJSONArray("support_formats");hasCatalogue=accept!=null&&accept.length()>0||formats!=null&&formats.length()>0;
  if(accept!=null)for(int i=0;i<accept.length();i++)advertised.add(accept.optInt(i));
  if(formats!=null)for(int i=0;i<formats.length();i++){JSONObject f=formats.optJSONObject(i);if(f==null)continue;int q=f.optInt("quality");advertised.add(q);if(f.optBoolean("need_login",false))loginRequired.add(q);if(f.optBoolean("need_vip",false))vipRequired.add(q);}
  JSONObject dash=d.optJSONObject("dash");boolean sound=false;if(dash!=null){JSONArray video=dash.optJSONArray("video"),aud=dash.optJSONArray("audio");if(video!=null)for(int i=0;i<video.length();i++){JSONObject t=video.optJSONObject(i);if(t==null||ApiModels.urls(t).isEmpty())continue;int q=t.optInt("id");other.add(q);if(t.optInt("codecid")==7||t.optString("codecs").startsWith("avc1"))h264.add(q);}if(aud!=null)for(int i=0;i<aud.length();i++){JSONObject t=aud.optJSONObject(i);if(t!=null&&t.optString("codecs").startsWith("mp4a")&&!ApiModels.urls(t).isEmpty())sound=true;}}
  JSONArray direct=d.optJSONArray("durl");if(direct!=null&&direct.length()==1&&direct.optJSONObject(0)!=null&&!ApiModels.urls(direct.optJSONObject(0)).isEmpty()){h264.add(ceiling);other.add(ceiling);sound=true;}
  audio=sound;
 }
 public boolean available(int q){return q<=ceiling&&h264.contains(q)&&audio;}
 public String badge(int q,boolean logged,int vip){if(available(q))return "可选";if(hasCatalogue&&!advertised.contains(q))return "视频未提供";if(!logged&&(q>ceiling||loginRequired.contains(q)))return "需先登录";if(vip==0&&(vipRequired.contains(q)||premium(q)&&q>ceiling))return "会员画质";if(q<=ceiling&&other.contains(q)&&!h264.contains(q))return "格式暂不支持";if(q<=ceiling&&h264.contains(q)&&!audio)return "未取得音轨";return "平台未开放";}
 public int playableCeiling(){int best=0;for(int tier:h264)if(available(tier))best=Math.max(best,tier);return best;}
 public String reason(int q,boolean logged,int vip){String actual=playableCeiling()>0?ApiModels.qualityName(playableCeiling()):"暂无可用档位";switch(badge(q,logged,vip)){
  case "可选":return "平台已返回可播放的音视频";
  case "视频未提供":return "这个视频没有返回 "+ApiModels.qualityName(q)+" 档位，登录也不保证增加画质。";
  case "需先登录":return "当前未登录，本次可播放到 "+actual+"。请先登录再检查；高阶档位还可能需要大会员。";
  case "会员画质":return "当前账号未开通大会员，平台未提供该档。此档通常属于大会员画质，实际以平台授权为准。";
  case "格式暂不支持":return "该档未返回本版支持的 H.264 格式；不是切换成功，也不一定是登录问题。";
  case "未取得音轨":return "该档没有返回可用音轨，暂时无法完整播放，请稍后重试。";
  default:return "平台未向当前会话返回该档，具体原因未说明。可刷新登录状态后重试；本次可播放到 "+actual+"。";
 }}
 static boolean premium(int q){return q==74||q==112||q==116||q>=120;}
}
