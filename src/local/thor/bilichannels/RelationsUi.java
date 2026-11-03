package local.thor.bilichannels;

import java.util.*;

/** Personal entry points and account-scoped, read-only people lists. */
final class RelationsUi {
 final FeatureUi f;final MainActivity a;
 RelationModels.Pager following=new RelationModels.Pager(),fans=new RelationModels.Pager();
 RelationModels.Stats stats;long owner,statsAt;int epoch=-1;boolean statsLoading;String statsError="",face="";int revision;boolean controllerFocus;
 RelationsUi(FeatureUi f){this.f=f;a=f.a;}
 boolean sameAccount(){return owner==a.auth.mid&&epoch==a.auth.epoch();}
 void account(){if(sameAccount())return;owner=a.auth.mid;epoch=a.auth.epoch();following=new RelationModels.Pager();fans=new RelationModels.Pager();stats=null;statsAt=0;statsLoading=false;statsError="";face="";revision++;}
 String count(boolean followers){return stats==null?(owner>0?"—":"登录后查看"):String.valueOf(followers?stats.followers:stats.following);}
 boolean active(){return f.title.equals("我的")||f.title.equals("我的关注")||f.title.equals("我的粉丝");}
 void move(int delta){controllerFocus=true;f.focus=Math.max(0,Math.min(f.labels.size()-1,f.focus+delta));f.lastMenuFocus=f.focus;f.redraw();if(a.controls!=null)a.controls.relationsPane.revealFocus();}
 void mine(){account();f.menu("我的");mineRows();loadStats(false);}
 void mineRows(){f.labels.clear();f.actions.clear();f.subtitles.clear();f.disabledRows.clear();
  f.add(owner>0?a.auth.name:"登录 B站",()->{if(owner>0)openSelf(false);else f.account();});f.subtitles.put(0,owner>0?"账号与登录设置":"登录后查看关注、粉丝和收藏");
  f.add("我的关注 · "+count(false),()->open(false));f.subtitles.put(1,"找到关注的 UP 主，查看视频和动态");
  f.add("我的粉丝 · "+count(true),()->open(true));f.subtitles.put(2,"查看关注我的用户");
  f.add("我的动态",()->openSelf(true));f.add("我的收藏夹",()->f.folders(null));f.add("稍后再看",()->f.authenticatedFeed("稍后再看",BiliApi::watchLater));
  f.add("历史记录",()->f.lowerHistory(false));
  f.add("缓存任务",f::cacheTasks);f.add("离线缓存",f::offline);f.add("打开本地视频",a::pickLocalVideo);f.add("弹幕设置",f::danmaku);f.add("设置",f::settings);f.add("支持开发／打赏",()->DonationActivity.open(a));
  if(owner>0)f.add("刷新关注 / 粉丝数量",()->loadStats(true));f.add("账号与登录设置",f::account);f.add("关注动态",f::following);if(a.second!=null)f.add("回到视频简介",()->a.showLowerSection(0));
  revision++;f.notice=statsLoading?"正在读取关注和粉丝数量…":statsError;f.redraw();
 }
 void profileChanged(org.json.JSONObject profile){account();face=ApiModels.secure(profile.optString("face"));profileChanged();}
 void profileChanged(){account();if(f.title.equals("我的")){mineRows();loadStats(false);}}
 void restored(){if(f.title.equals("我的"))profileChanged();else if(f.title.equals("我的关注")||f.title.equals("我的粉丝")){if(sameAccount())render(f.title.equals("我的粉丝"));else mine();}}
 void loadStats(boolean force){account();if(owner<=0||!a.auth.cookies.hasLogin()||statsLoading||!force&&stats!=null&&System.currentTimeMillis()-statsAt<60000)return;
  final long mid=owner;final int requestedEpoch=epoch;statsLoading=true;statsError="";if(f.title.equals("我的"))mineRows();
  a.io.execute(()->{try{RelationModels.Stats got=BiliApi.relationStats(mid);a.ui.post(()->{if(a.dead||!sameAccount()||owner!=mid||epoch!=requestedEpoch)return;stats=got;statsAt=System.currentTimeMillis();statsLoading=false;if(f.title.equals("我的"))mineRows();});}catch(Exception e){a.ui.post(()->{if(a.dead||!sameAccount()||owner!=mid||epoch!=requestedEpoch)return;statsLoading=false;statsError="数量读取失败："+message(e)+"；可点刷新重试";if(f.title.equals("我的"))mineRows();});}});
 }
 String title(boolean followers){return followers?"我的粉丝":"我的关注";}
 RelationModels.Pager model(boolean followers){return followers?fans:following;}
 void open(boolean followers){if(!f.requireLogin())return;account();f.menu(title(followers));render(followers);if(model(followers).page==0)load(followers,false);}
 void switchList(boolean followers){if(f.title.equals(title(followers)))return;f.resetPage();open(followers);}
 void render(boolean followers){if(!f.title.equals(title(followers)))return;RelationModels.Pager model=model(followers);int selected=f.focus;float scroll=f.menuScroll;
  f.labels.clear();f.actions.clear();f.subtitles.clear();f.disabledRows.clear();
  for(RelationModels.Person person:model.people){int index=f.labels.size();f.add(person.name,()->openCreator(person));f.subtitles.put(index,person.sign.isEmpty()?"查看主页 · 视频 / 动态":person.sign);}
  if(model.loading)f.add("正在加载…",()->{});else if(!model.error.isEmpty())f.add("加载失败，点击重试",()->load(followers,false));else if(model.more)f.add("加载更多",()->load(followers,false));
  f.add("刷新名单",()->load(followers,true));f.add("返回",f::back);
  f.notice=!model.error.isEmpty()?model.error:model.loading&&model.people.isEmpty()?"正在读取名单…":model.page==0?"":model.people.isEmpty()?(followers?"还没有粉丝":"还没有关注 UP 主"):"已显示 "+model.people.size()+" / "+model.total+" 人"+(model.more?" · 继续滑动加载更多":"");
  f.focus=Math.min(selected,f.labels.size()-1);f.lastMenuFocus=f.focus;f.menuScroll=scroll;revision++;f.redraw();
 }
 void load(boolean followers,boolean refresh){account();if(owner<=0||!a.auth.cookies.hasLogin())return;RelationModels.Pager model=model(followers);if(refresh){model.reset();f.menuScroll=0;f.focus=0;}int token=model.begin();if(token<0)return;
  final long mid=owner;final int requestedEpoch=epoch,page=model.page+1;render(followers);
  a.io.execute(()->{try{RelationModels.Page result=BiliApi.relations(mid,followers,page);a.ui.post(()->{if(a.dead||!sameAccount()||owner!=mid||epoch!=requestedEpoch||model!=model(followers))return;if(model.accept(token,result))render(followers);else if(token==model.generation)render(followers);});}catch(Exception e){a.ui.post(()->{if(a.dead||!sameAccount()||owner!=mid||epoch!=requestedEpoch||model!=model(followers))return;model.fail(token,message(e));render(followers);});}});
 }
 void visibleEnd(int last){boolean followers=f.title.equals("我的粉丝");if(!followers&&!f.title.equals("我的关注"))return;RelationModels.Pager m=model(followers);if(m.page>0&&m.more&&!m.loading&&m.error.isEmpty()&&last>=m.people.size()-4){int generation=f.generation;a.ui.post(()->{if(generation==f.generation)load(followers,false);});}}
 void openSelf(boolean dynamic){if(!f.requireLogin())return;openCreator(new RelationModels.Person(owner,a.auth.name,face,""));if(dynamic&&a.creators.active())a.creators.switchTab(1);}
 void openCreator(RelationModels.Person person){
  FeatureUi.PageState saved=f.new PageState();ArrayDeque<FeatureUi.PageState> history=new ArrayDeque<>(f.history);int root=f.tabRoot,lower=a.lowerTab,requestedEpoch=a.auth.epoch();boolean lowerRoot=f.lowerRoot;long mid=a.auth.mid;
  f.resetPage();f.history.clear();a.creators.open(person.creator());CreatorUi.Session session=a.creators.current();
  if(session!=null)session.menuReturn=()->{f.resetPage();f.history.clear();f.tabRoot=root;f.lowerRoot=lowerRoot;a.lowerTab=lower;if(a.auth.epoch()==requestedEpoch&&a.auth.mid==mid){saved.restore();f.history.addAll(history);f.redraw();}else mine();};f.redraw();
 }
 static String message(Exception e){return e.getMessage()==null?"网络暂不可用":e.getMessage();}
}
