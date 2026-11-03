package local.thor.bilichannels;
import java.util.*;
/** Stable list identity keeps scroll anchors intact while confirmed pages append. */
public final class FeedPager {
 public final List<Video> items=new ArrayList<>();private final Set<String> ids=new HashSet<>();
 public int page,generation,emptyPages;public boolean loading,more=true,refreshing;public String cursor="",error="";
 public static final class Page {public final List<Video> items;public final int page;public final boolean more;public final String cursor;public Page(List<Video> items,int page,boolean more,String cursor){this.items=items;this.page=page;this.more=more;this.cursor=cursor==null?"":cursor;}}
 public void seed(List<Video> rows){items.clear();ids.clear();for(Video v:rows)if(ids.add(v.id))items.add(v);}
 public void clear(){generation++;page=emptyPages=0;loading=refreshing=false;more=true;cursor=error="";seed(Collections.emptyList());}
 public int begin(boolean refresh){if(loading&&!refresh)return -1;refresh=refresh||refreshing;if(refresh){generation++;loading=false;}if(loading||!refresh&&!more)return -1;loading=true;refreshing=refresh;error="";return generation;}
 public boolean accept(int token,Page result){if(token!=generation||!loading)return false;int expected=refreshing?1:page+1;if(result.page!=expected){fail(token,"分页响应不一致");return false;}if(refreshing||page==0){seed(Collections.emptyList());emptyPages=0;}int added=0;for(Video v:result.items)if(ids.add(v.id)){items.add(v);added++;}page=result.page;cursor=result.cursor;more=result.more;emptyPages=added==0?emptyPages+1:0;loading=refreshing=false;if(more&&emptyPages>=3)error="平台暂未返回新视频，继续上滑重试";return true;}
 public void fail(int token,String message){if(token!=generation)return;loading=false;error=message;}
 public boolean nearEnd(int end,int rows){return end>=items.size()-Math.max(12,rows*2);}
 public String label(){return loading?"正在加载更多…":!error.isEmpty()?"加载暂未完成 · 继续上滑重试":more?"继续上滑加载更多":"已浏览完当前列表";}
}
