package local.thor.bilichannels;
import java.util.*;
/** One query owns its pages. Failed or stale responses never advance its cursor. */
public final class SearchPager {
 public final List<Video> items=new ArrayList<>();private final Set<String> ids=new HashSet<>();
 public String query="",error="";public int page,generation,emptyPages,totalPages=-1;public boolean loading,more=true;
 public static final class Page {public final List<Video> items;public final int page,totalPages;public final boolean more,verified;public Page(List<Video> rows,int page,int pages,boolean more){this(rows,page,pages,more,false);}public Page(List<Video> rows,int page,int pages,boolean more,boolean verified){this.items=rows;this.page=page;this.totalPages=pages;this.more=more;this.verified=verified;}}
 public void reset(String q){generation++;query=q;page=emptyPages=0;totalPages=-1;items.clear();ids.clear();error="";loading=false;more=true;}
 public int begin(){if(loading||!more)return -1;loading=true;error="";return generation;}
 public boolean accept(int token,Page result){if(token!=generation||!loading)return false;if(result.page!=page+1){fail(token,"搜索页码不一致，点击重试");return false;}int added=0;for(Video v:result.items)if(ids.add(v.id)){items.add(v);added++;}if(added==0&&!result.items.isEmpty()&&!result.verified){fail(token,"平台返回了重复结果，点击重试");return false;}page=result.page;totalPages=result.totalPages;more=result.more;emptyPages=added==0?emptyPages+1:0;loading=false;if(more&&emptyPages>=3)error="平台暂未返回新结果，继续上滑重试";return true;}
 public void fail(int token,String message){if(token!=generation)return;loading=false;error=message;}
 public boolean nearEnd(int end,int rows){return end>=items.size()-Math.max(12,rows*2);}
 public String label(){return loading?"正在加载更多…":!error.isEmpty()?"加载暂未完成 · 继续上滑重试":more?"继续滑动加载更多":"已显示平台返回的全部结果";}
}
