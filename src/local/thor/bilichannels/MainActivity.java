package local.thor.bilichannels;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.media.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.*;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.animation.ValueAnimator;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements DisplayManager.DisplayListener {
 AuthSession auth;FeatureUi features;XunleiCloud cloud;int localSection;final CreatorUi creators=new CreatorUi(this);final ShortcutBindings triggerEdges=new ShortcutBindings();final String[] shortcuts={"danmaku","mute"};boolean appMuted,memcEnabled;CacheStore cache;int quality=0;float playbackSpeed=1f;
 final Handler ui=new Handler(Looper.getMainLooper());
 final ExecutorService io=Executors.newFixedThreadPool(4),mediaIo=Executors.newFixedThreadPool(3),playbackIo=Executors.newSingleThreadExecutor(),sizeIo=Executors.newFixedThreadPool(3);
 final BrowseState browse=new BrowseState(0);
 final LiveSubtitles subtitles=new LiveSubtitles(this);
 final PlaybackControls playerControls=new PlaybackControls();final PgcUi pgc=new PgcUi(this);
 final InputComposer composer=new InputComposer(this);
 final LinkedHashMap<String,Bitmap> covers=new LinkedHashMap<String,Bitmap>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Bitmap> e){return size()>18;}};
 final Set<String> coverLoading=new HashSet<>();
 static final int RECOMMENDED=0,POPULAR=1,COLUMN=2,DYNAMIC=3,LIBRARY=4,MINE=5,DEMO=6,LOCAL=7,SEARCH=8;
 final FeedPager[] feedPages={new FeedPager(),new FeedPager(),null,new FeedPager(),null};List<Video> videos=new ArrayList<>(),popular=feedPages[POPULAR].items,recommended=feedPages[RECOMMENDED].items;final boolean[] feedLoading=new boolean[5];long lastMoreRetry;int contentGeneration;int homeTab=RECOMMENDED;boolean switchingHome;final HomePage[] homePages=new HomePage[9];
 PhonePreviewLayout.Order phoneFeedOrder;float phoneFeedScroll;LinearLayout singleLayout;PhoneBrowseChrome phoneChrome;boolean phoneLandscape,phoneFullscreen,phonePictureFill=false;float phonePictureZoom,phonePictureOffsetX,phonePictureOffsetY;int phoneFullscreenReturnOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;ValueAnimator phoneResize;int phoneHeightTarget;Presentation second; Gallery gallery; Controls controls; DisplayManager displays;
 boolean active,dead,playing,paused,loading,swapped,abSwap,allPreviews=true,showDan=true,danRightward=true;int danDensity=1;
 int channel=0,detailTab=0,detailFocus=0,settingFocus=0,scroll=0,motionMs=380,loadGeneration=0;
 boolean leftTrigger,rightTrigger;long lastMove,lastSeekCommand;int stickHeld;String displaySignature="";String status="正在加载推荐…",displayInfo="",diagnosticInfo="";
 final int selectionFill=0xffffedf3,selectionColor=0xfffb7299;
 final int ink=0xff18191c,muted=0xff9499a0,accent=0xfffb7299,paper=0xfff6f7f8;
 final String[] tabs={"推荐","热门","专栏","动态","本地","我的"},detailTabs={"评论","简介"};
 final String[] upperTabs={"推荐","热门","专栏","动态","本地"},lowerTabs={"视频简介","历史记录","我的"},lowerPlaybackTabs={"评论","视频简介","历史记录","我的"};
 int lowerTab;LowerPlaybackReturn lowerPlaybackReturn;HomePage localReturn;final ArrayDeque<RelatedPlaybackReturn> relatedReturns=new ArrayDeque<>();
 final int[] colors={0xff8dadbf,0xff768a7f,0xff888eb8,0xffdbad7d,0xff85acb2,0xff98b9ce};
 AudioManager audio;boolean audioHeld;
 final AudioManager.OnAudioFocusChangeListener audioListener=change->{if(change<=0)ui.post(()->{paused=true;if(gallery!=null)gallery.syncPlayers();});};
 final Runnable ticker=new Runnable(){public void run(){if(!active||dead)return;playerControls.tick();if(gallery!=null)gallery.tick();subtitles.tick(playerControls.tile());resizePhonePreview(true);ui.postDelayed(this,250);}};
 final Runnable stickRepeat=new Runnable(){public void run(){if(!active||stickHeld==0)return;performStick(stickHeld,false);ui.postDelayed(this,ControllerStick.delay(stickHeld));}};

 @Override public void onCreate(Bundle b){super.onCreate(b);auth=new AuthSession(this);BiliApi.session=auth;cache=CacheStore.get(this);features=new FeatureUi(this);cloud=new XunleiCloud(this);io.execute(()->{try{PinyinInput warm=new PinyinInput(getAssets().open("pinyin.tsv"));warm.indexedPhrases(getAssets().open("phrases.bin"));}catch(Exception ignored){}});io.execute(()->{auth.load();features.restoreAccount();});displays=getSystemService(DisplayManager.class);audio=getSystemService(AudioManager.class);SharedPreferences p=getPreferences(0);localSection=Math.max(0,Math.min(3,p.getInt("localSection",0)));if(!p.getBoolean("defaults37",false))p.edit().putInt("visibleRows",6).putInt("quality",0).putBoolean("defaults37",true).apply();quality=0;appMuted=p.getBoolean("appMuted",false);memcEnabled=p.getBoolean("memcEnabled",false);for(int i=0;i<2;i++)shortcuts[i]=ShortcutBindings.valid(p.getString("shortcut"+i,shortcuts[i]),i);swapped=p.getBoolean("swap",false);abSwap=p.getBoolean("ab",false);allPreviews=p.getBoolean("four",true);showDan=p.getBoolean("dan",true);danDensity=Math.max(0,Math.min(2,p.getInt("danDensity",1)));danRightward=p.getBoolean("danRightward",true);motionMs=p.getInt("motion",380);browse.setVisibleRows(p.getInt("visibleRows",6));displays.registerDisplayListener(this,ui);channel=b==null?RECOMMENDED:b.getInt("channel",RECOMMENDED);if(channel!=RECOMMENDED&&channel!=POPULAR)channel=RECOMMENDED;homeTab=channel;
  for(int tab=0;tab<2;tab++){try(InputStream cached=new FileInputStream(new File(getFilesDir(),tab==RECOMMENDED?"recommended.json":"catalog.json"))){List<Video> items=BiliApi.decodeCatalog(new String(BiliApi.read(cached,2000000),"UTF-8"));feedPages[tab].seed(items);}catch(Exception ignored){}}videos=channel==RECOMMENDED?recommended:popular;browse.reset(videos.size());immersive(getWindow());log("START "+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE);loadFeed(channel,false);if(getIntent().getBooleanExtra("open_cache",false))ui.post(()->features.cacheTasks());}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("open_cache",false)&&features!=null)ui.post(()->features.cacheTasks());}
 @Override public void onResume(){super.onResume();RemoteDeviceService.foreground(this);RemoteDeviceService.maybeStart(this);active=true;if(features!=null)features.resume();if(playing&&!paused)requestAudio();attachScreens();ui.removeCallbacks(ticker);ui.post(ticker);}
 @Override public void onPause(){subtitles.suspend();RemoteDeviceService.background(this);if(gallery!=null&&playing)gallery.savePosition();active=false;triggerEdges.reset();creators.saveAnchor();stopStick(false);stopPhoneResize();if(controls!=null){controls.stopScroll();controls.finishNavigation();controls.details.pause();}playerControls.hide();if(features!=null)features.pause();composer.hideIme();ui.removeCallbacks(ticker);ui.removeCallbacks(stickRepeat);stickHeld=0;if(gallery!=null)gallery.releaseAll();if(second!=null){second.dismiss();second=null;}abandonAudio();super.onPause();}
 @Override public void onDestroy(){subtitles.destroy();dead=true;if(features!=null)features.destroy();if(cloud!=null)cloud.close();displays.unregisterDisplayListener(this);io.shutdownNow();mediaIo.shutdownNow();playbackIo.shutdownNow();sizeIo.shutdownNow();ui.removeCallbacksAndMessages(null);if(gallery!=null)gallery.releaseAll();if(second!=null)second.dismiss();super.onDestroy();}
 @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("channel",channel);b.putInt("selected",browse.selected);}
 @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(active){ui.removeCallbacks(rebuildScreens);displaySignature=screenSignature();if(second==null&&singleLayout!=null)layoutPhone();else attachScreens();}}
 @Override public void onBackPressed(){back();}
 @Override public void onDisplayAdded(int id){refreshDisplays();}
 @Override public void onDisplayRemoved(int id){refreshDisplays();}
 @Override public void onDisplayChanged(int id){if(active&&!screenSignature().equals(displaySignature))refreshDisplays();}
 String screenSignature(){StringBuilder b=new StringBuilder();for(Display d:displays.getDisplays()){android.util.DisplayMetrics m=new android.util.DisplayMetrics();d.getRealMetrics(m);b.append(d.getDisplayId()).append(':').append(d.getState()).append(':').append(m.widthPixels).append('x').append(m.heightPixels).append(';');}return b.toString();}
 void refreshDisplays(){ui.removeCallbacks(rebuildScreens);ui.postDelayed(rebuildScreens,250);}
 final Runnable rebuildScreens=()->{if(active&&!screenSignature().equals(displaySignature))attachScreens();};
 void immersive(Window w){if(w==null)return;w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);w.setNavigationBarColor(paper);w.setStatusBarColor(paper);boolean phone=second==null;boolean videoFullscreen=phone&&phoneFullscreen;int flags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR|View.SYSTEM_UI_FLAG_LAYOUT_STABLE;if(videoFullscreen)flags|=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;w.getDecorView().setSystemUiVisibility(flags);}
 void attachScreens(){if(!active||dead)return;creators.saveAnchor();displaySignature=screenSignature();singleLayout=null;phoneChrome=null;if(gallery!=null){if(playing)gallery.savePosition();gallery.releaseAll();}if(second!=null){second.dismiss();second=null;}int host=getDisplay()==null?0:getDisplay().getDisplayId();Display other=null;StringBuilder d=new StringBuilder();for(Display screen:displays.getDisplays()){android.util.DisplayMetrics m=new android.util.DisplayMetrics();screen.getRealMetrics(m);d.append(screen.getDisplayId()).append(':').append(screen.getName()).append(' ').append(m.widthPixels).append('x').append(m.heightPixels).append(" flags=").append(screen.getFlags()).append('\n');if(screen.getDisplayId()!=host&&(screen.getFlags()&Display.FLAG_PRIVATE)==0&&screen.getState()!=Display.STATE_OFF){if(other==null||screen.getDisplayId()==0||screen.getName().equals("Screen-2"))other=screen;}}
 diagnosticInfo="host="+host+"\n"+d;boolean mainTop=(host==Display.DEFAULT_DISPLAY)^swapped;
 if(other!=null){try{second=new Presentation(this,other){@Override public boolean dispatchKeyEvent(KeyEvent e){return handleKey(e)||super.dispatchKeyEvent(e);}@Override public boolean dispatchGenericMotionEvent(MotionEvent e){return handleMotion(e)||super.dispatchGenericMotionEvent(e);}};second.setCancelable(false);second.show();immersive(second.getWindow());second.getWindow().setLayout(-1,-1);gallery=new Gallery(mainTop?this:second.getContext());controls=new Controls(mainTop?second.getContext():this);setContentView(mainTop?gallery:controls);second.setContentView(mainTop?controls:gallery);displayInfo="双屏已连接";}catch(RuntimeException e){log("DISPLAY "+e.getClass().getSimpleName()+" "+e.getMessage());if(second!=null)second.dismiss();second=null;}}
 if(second==null){gallery=new Gallery(this);controls=new Controls(this);singleLayout=new LinearLayout(this);singleLayout.setBackgroundColor(paper);singleLayout.addView(gallery);singleLayout.addView(controls);singleLayout.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)layoutPhone();});FrameLayout phoneRoot=new FrameLayout(this);phoneRoot.addView(singleLayout,new FrameLayout.LayoutParams(-1,-1));phoneChrome=new PhoneBrowseChrome(this,this);phoneRoot.addView(phoneChrome,new FrameLayout.LayoutParams(-1,-1));setContentView(phoneRoot);layoutPhone();displayInfo="手机模式";}
 immersive(getWindow());log("DISPLAY "+displayInfo+" "+diagnosticInfo.replace('\n',' '));gallery.post(()->{if(gallery!=null)gallery.render(false);composer.sync();});}
 void stopPhoneResize(){if(phoneResize!=null){phoneResize.cancel();phoneResize=null;}phoneHeightTarget=gallery==null?0:gallery.getHeight();}
 int phonePreviewHeight(){return singleLayout==null?0:singleLayout.getHeight()/2;}
 boolean phoneFullscreenSupported(){return second==null&&!Build.MODEL.toLowerCase(Locale.ROOT).contains("thor");}
 boolean phoneVideoFullscreen(){return phoneFullscreenSupported()&&playing&&phoneFullscreen&&!features.active()&&!creators.active();}
 boolean localBrowsing(){return channel==LIBRARY&&!playing&&((!features.active()||features.lowerRoot)||features.lowerRoot)&&!creators.active();}
 boolean columnBrowsing(){return channel==COLUMN&&videos.isEmpty()&&!playing&&!features.active()&&!creators.active();}
 boolean phoneBrowsePage(){return second==null&&!playing&&!features.active()&&!creators.active();}
 boolean phonePersonalPage(){return second==null&&!playing&&(features.active()&&(features.tabRoot==MINE||features.tabRoot==LIBRARY)||creators.active()&&creators.current().menuReturn!=null);}
 void layoutPhone(){if(singleLayout==null)return;stopPhoneResize();phoneLandscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;boolean fullscreen=phoneVideoFullscreen(),personal=phonePersonalPage(),split=phoneLandscape&&!phoneBrowsePage();gallery.setVisibility(personal?View.GONE:View.VISIBLE);singleLayout.setOrientation(phoneLandscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(phoneLandscape?0:-1,phoneLandscape?-1:0,split?6:1);if(phoneLandscape&&(phoneBrowsePage()||columnBrowsing())&&phoneChrome!=null)gp.leftMargin=phoneChrome.railWidth();gallery.setLayoutParams(gp);controls.setVisibility(fullscreen||phoneBrowsePage()?View.GONE:View.VISIBLE);if(phoneChrome!=null){phoneChrome.setVisibility(phoneBrowsePage()?View.VISIBLE:View.GONE);phoneChrome.bind();}controls.setLayoutParams(new LinearLayout.LayoutParams(phoneLandscape?0:-1,phoneLandscape?-1:0,split?4:1));singleLayout.requestLayout();gallery.syncPlayers();}
 void syncPhoneViewport(){if(phoneChrome!=null){phoneChrome.setVisibility(phoneBrowsePage()?View.VISIBLE:View.GONE);phoneChrome.bind();}if(phoneFullscreen){if(!playing||!phoneFullscreenSupported())setPhoneFullscreen(false);else orientPhoneFullscreen();}if(singleLayout!=null&&controls!=null&&(controls.getVisibility()!=((phoneVideoFullscreen()||phoneBrowsePage())?View.GONE:View.VISIBLE)||gallery.getVisibility()!=(phonePersonalPage()?View.GONE:View.VISIBLE)))layoutPhone();}
 void orientPhoneFullscreen(){Video v=current();if(!phoneFullscreen||v==null)return;Tile t=playerControls.tile();int w=t==null||t.mp==null?0:t.mp.getVideoWidth(),h=t==null||t.mp==null?0:t.mp.getVideoHeight();boolean portrait=w>0&&h>0?h>w:v.isPortrait();int wanted=portrait?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;if(getRequestedOrientation()!=wanted)setRequestedOrientation(wanted);}
 void setPhoneFullscreen(boolean enabled){if(phoneFullscreen==enabled||enabled&&(!phoneFullscreenSupported()||!playing))return;if(enabled){phoneFullscreenReturnOrientation=getRequestedOrientation();phonePictureFill=false;phonePictureZoom=phonePictureOffsetX=phonePictureOffsetY=0;phoneFullscreen=true;orientPhoneFullscreen();}else{phoneFullscreen=false;phonePictureZoom=phonePictureOffsetX=phonePictureOffsetY=0;setRequestedOrientation(phoneFullscreenReturnOrientation);}immersive(getWindow());layoutPhone();applyPhonePictureSize();}
 void togglePhoneFullscreen(){if(!phoneFullscreenSupported()||!playing)return;setPhoneFullscreen(!phoneFullscreen);playerControls.show();}
 float phonePictureScale(Tile tile){if(!phoneVideoFullscreen())return 1;if(phonePictureZoom>0)return phonePictureZoom;if(!phonePictureFill)return 1;float w=tile.getWidth(),h=tile.getHeight(),aspect=tile.video.aspect();return w>0&&h>0?Math.max(w/Math.min(w,h*aspect),h/Math.min(h,w/aspect)):1;}
 String phonePictureLabel(){return phonePictureZoom>0?String.format(Locale.ROOT,"缩放 %.2f×",phonePictureZoom):phonePictureFill?"填充":"适应";}
 void applyPhonePictureSize(){if(gallery!=null)for(Tile t:gallery.tiles.values())t.fitVideo();playerControls.redraw();}
 void setPhonePictureFill(boolean fill){if(!phoneFullscreenSupported())return;phonePictureFill=fill;phonePictureZoom=phonePictureOffsetX=phonePictureOffsetY=0;applyPhonePictureSize();}
 void setPhonePictureZoom(float zoom,float focusX,float focusY){Tile t=playerControls.tile();if(t==null||!phoneVideoFullscreen())return;float previous=phonePictureScale(t);phonePictureZoom=Math.max(1,Math.min(4,zoom));float factor=phonePictureZoom/previous,x=focusX-t.getWidth()/2f,y=focusY-t.getHeight()/2f;phonePictureOffsetX=x+(phonePictureOffsetX-x)*factor;phonePictureOffsetY=y+(phonePictureOffsetY-y)*factor;panPhonePicture(0,0);}
 void panPhonePicture(float dx,float dy){Tile t=playerControls.tile();if(t==null||!phoneVideoFullscreen())return;View picture=t.memcActive()?t.memcView:t.hdrView;float zoom=phonePictureScale(t),width=picture.getWidth()>0?picture.getWidth():t.getWidth(),height=picture.getHeight()>0?picture.getHeight():t.getHeight();float maxX=Math.max(0,(width*zoom-t.getWidth())/2f),maxY=Math.max(0,(height*zoom-t.getHeight())/2f);phonePictureOffsetX=Math.max(-maxX,Math.min(maxX,phonePictureOffsetX+dx));phonePictureOffsetY=Math.max(-maxY,Math.min(maxY,phonePictureOffsetY+dy));applyPhonePictureSize();}
 void resizePhonePreview(boolean animate){syncPhoneViewport();}
 void setVisibleRows(int rows){if(controls!=null)controls.stopScroll();browse.setVisibleRows(rows);getPreferences(0).edit().putInt("visibleRows",browse.visibleRows).apply();render(true);}
 void openSettings(){playerControls.hide();features.settings();}
 void openDemo(){phoneFeedScroll=0;relatedReturns.clear();creators.dismiss();features.close();if(playing)gallery.savePosition();playing=false;paused=false;abandonAudio();channel=DEMO;homeTab=DEMO;contentGeneration++;videos=Video.demoList();browse.reset(videos.size());status="离线布局测试";render(true);}
 void pickLocalVideo(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");startActivityForResult(i,43);}
 void openLocalVideo(Uri uri){
  String name="本地视频";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){String found=c.getString(0);if(found!=null&&!found.trim().isEmpty())name=found;}}catch(Exception ignored){}
  TorrentRuntime.rememberLocal(this,uri,name);
  if(channel!=LOCAL)localReturn=new HomePage();
  relatedReturns.clear();lowerPlaybackReturn=null;creators.dismiss();features.close();if(playing&&gallery!=null)gallery.savePosition();playing=false;paused=false;abandonAudio();
  Video v=new Video("local-"+Integer.toHexString(uri.toString().hashCode()),name,"本地文件","",0,0,0,false);v.localUri=uri.toString();v.description="由 Android 文件选择器打开。播放信息可在菜单中查看分辨率、HDR 编码和当前屏幕能力。";
  channel=LOCAL;homeTab=LOCAL;contentGeneration++;videos=new ArrayList<>(Collections.singletonList(v));browse.reset(1);status="本地视频";render(false);play();
 }
 void openXunleiCloud(){localSection=1;openLocalLibrary();if(gallery!=null&&gallery.localPane!=null)gallery.localPane.bind();}
 void openRecentLibrary(){localSection=2;openLocalLibrary();if(gallery!=null&&gallery.localPane!=null)gallery.localPane.bind();}
 void openXunleiVideo(org.json.JSONObject media){
  final Video video;try{video=XunleiMedia.video(media);}catch(org.json.JSONException error){message("云盘播放地址无效，请重新选择");return;}cloud.remember(media);if(features.title.equals("历史记录")){playLowerVideo(video);return;}
  if(channel!=LOCAL)localReturn=new HomePage();relatedReturns.clear();lowerPlaybackReturn=null;creators.dismiss();features.close();if(playing&&gallery!=null)gallery.savePosition();playing=false;paused=false;abandonAudio();
  channel=LOCAL;homeTab=LOCAL;contentGeneration++;videos=new ArrayList<>(Collections.singletonList(video));browse.reset(1);status="迅雷云盘";render(false);play();
 }
 Video current(){return videos.isEmpty()?null:videos.get(Math.min(browse.selected,videos.size()-1));}
 void loadPopular(boolean retry){loadFeed(POPULAR,retry);}
 void refreshCurrentFeed(){if(localBrowsing()){if(gallery!=null)gallery.render(false);return;}if(columnBrowsing()){pgc.refresh();return;}phoneFeedScroll=0;if(gallery!=null)gallery.stopFeedScroll();if(channel==DYNAMIC){loadFeed(DYNAMIC,true);return;}if(channel==SEARCH&&!features.lastQuery.isEmpty()){features.searchPage(1);return;}if(channel!=RECOMMENDED&&channel!=POPULAR){channel=RECOMMENDED;homeTab=channel;contentGeneration++;videos=recommended;browse.reset(videos.size());render(false);}loadFeed(channel,true);}
 FeedPager activeFeed(){return channel>=0&&channel<feedPages.length&&feedPages[channel]!=null&&videos==feedPages[channel].items?feedPages[channel]:null;}
 void clearFeeds(){for(int i=0;i<feedPages.length;i++)if(feedPages[i]!=null){feedPages[i].clear();feedLoading[i]=false;homePages[i]=null;}}
 void openFollowing(){phoneFeedScroll=0;creators.dismiss();features.close();playing=false;paused=false;abandonAudio();channel=homeTab=DYNAMIC;videos=feedPages[DYNAMIC].items;browse.reset(videos.size());status="关注动态";render(false);if(feedPages[DYNAMIC].page==0)loadFeed(DYNAMIC,false);}
 int feedVisibleEnd(){if(phoneBrowsePage()&&gallery!=null){int end=0;for(PreviewLayout.Box b:gallery.plan.boxes)end=Math.max(end,b.slot+1);return end;}return browse.end();}
 void maybeMoreVideos(boolean retry){if(playing||features.active())return;boolean mayRetry=retry&&SystemClock.uptimeMillis()-lastMoreRetry>1500;if(mayRetry)lastMoreRetry=SystemClock.uptimeMillis();FeedPager model=activeFeed();if(model!=null&&(model.more||model.refreshing)&&!model.loading&&(model.error.isEmpty()||mayRetry)&&model.nearEnd(feedVisibleEnd(),browse.visibleRows))loadFeed(channel,false);else if(channel==SEARCH)features.maybeMoreSearch(mayRetry);}
 String moreVideosLabel(){FeedPager model=activeFeed();if(model!=null&&model.nearEnd(feedVisibleEnd(),browse.visibleRows))return model.label();if(channel==SEARCH&&videos==features.searchResults.items&&features.searchResults.nearEnd(feedVisibleEnd(),browse.visibleRows))return features.searchResults.label();return "";}
 boolean moreVideosFailed(){FeedPager model=activeFeed();return model!=null?!model.error.isEmpty():channel==SEARCH&&videos==features.searchResults.items&&!features.searchResults.error.isEmpty();}
 void loadFeed(int tab,boolean refresh){if(tab<0||tab>=feedPages.length||feedPages[tab]==null)return;if(tab==DYNAMIC&&auth.mid==0)return;FeedPager model=feedPages[tab];int token=model.begin(refresh);if(token<0)return;feedLoading[tab]=true;int epoch=auth.epoch(),next=model.refreshing?1:model.page+1;String cursor=model.refreshing?"":model.cursor;boolean first=next==1,phone=phoneFullscreenSupported();String name=tab==DYNAMIC?"关注动态":tabs[tab];if(channel==tab&&videos==model.items){loading=first;status=first?"正在读取"+name+"…":name;if(controls!=null)controls.invalidate();}
  io.execute(()->{try{FeedPager.Page page=tab==DYNAMIC?BiliApi.followingPage(next,cursor):phone&&tab==RECOMMENDED?loadPhoneRecommendations(next,cursor):BiliApi.feedPage(tab==RECOMMENDED,next);if(phone)preparePhoneFeed(page.items);ui.post(()->{if(dead||token!=model.generation||epoch!=auth.epoch())return;feedLoading[tab]=false;if(!model.accept(token,page))return;if(tab!=DYNAMIC&&first){List<Video> copy=new ArrayList<>(model.items);io.execute(()->{try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),tab==RECOMMENDED?"recommended.json":"catalog.json"))){out.write(BiliApi.encodeCatalog(copy).getBytes("UTF-8"));}catch(Exception ignored){}});}if(channel==tab&&videos==model.items){loading=false;status=name;browse.count=model.items.size();if(first){phoneFeedScroll=0;browse.reset(model.items.size());render(false);}else if(phoneBrowsePage())gallery.render(false);else if(controls!=null)controls.invalidate();if(controls!=null)controls.finishRefresh();maybeMoreVideos(false);}log("FEED "+name+" page="+model.page+" count="+model.items.size());});
  }catch(Exception e){ui.post(()->{if(dead||token!=model.generation||epoch!=auth.epoch())return;model.fail(token,e.getMessage()==null?"网络暂不可用":e.getMessage());feedLoading[tab]=false;if(channel==tab&&videos==model.items){loading=false;status=model.error;if(controls!=null){controls.finishRefresh();controls.invalidate();}}log("FEED_FAIL "+name+" "+e.getClass().getSimpleName());});}});
 }

 void render(boolean animate){if(controls!=null)controls.followSelection(animate);if(gallery!=null)gallery.render(animate);if(controls!=null)controls.invalidate();}
 boolean gridBrowsing(){return second!=null&&channel!=COLUMN&&channel!=LIBRARY&&!playing&&!features.active()&&!creators.active();}
 boolean lowerDockVisible(){return second!=null&&!creators.active()&&!features.keyboard&&!features.qrMode&&(!features.active()||features.lowerRoot);}
 int upperTabCount(){return second!=null?upperTabs.length:tabs.length;}
 int lowerPage(){return playing?(lowerTab==0?detailTab:lowerTab+1):lowerTab;}
 void selectLowerPage(int page){int section=playing?Math.max(0,page-1):page;showLowerSection(Math.max(0,Math.min(2,section)));if(playing&&page<=1){controls.details.selectPage(page,true);controls.invalidate();}}
 void switchLowerPage(int delta){selectLowerPage(Math.floorMod(lowerPage()+delta,playing?lowerPlaybackTabs.length:lowerTabs.length));}
 void showLowerSection(int section){if(second==null)return;stopStick(false);playerControls.hide();features.close();lowerTab=section;features.lowerRoot=section>0;if(section==1)features.lowerHistory(false);else if(section==2)features.lowerMine();else controls.syncDetails();controls.invalidate();}
 final class LowerPlaybackReturn {
  final HomePage origin=new HomePage();final boolean wasPlaying=playing,wasPaused=paused;final int section=lowerTab,detail=detailTab;final FeatureUi.PageState menu=features.new PageState();final ArrayDeque<FeatureUi.PageState> history=new ArrayDeque<>(features.history);
  void restore(){features.close();origin.restore();playing=wasPlaying;paused=wasPaused;detailTab=detail;lowerTab=section;features.lowerRoot=true;menu.restore();features.history.addAll(history);if(playing&&!paused)requestAudio();else abandonAudio();render(false);features.redraw();}
 }
 void playLowerVideo(Video video){if(video==null)return;if(lowerPlaybackReturn==null)lowerPlaybackReturn=new LowerPlaybackReturn();if(playing)gallery.savePosition();features.close();playing=false;paused=false;abandonAudio();videos=new ArrayList<>(Collections.singletonList(video));browse.reset(1);play();}
 final class RelatedPlaybackReturn {
  final HomePage origin=new HomePage();final boolean wasPlaying=playing,wasPaused=paused;final int home=homeTab,detail=detailTab,introY=controls.details.intro.getScrollY(),position=playerControls.tile()==null?0:playerControls.tile().position();final LowerPlaybackReturn parentLowerReturn=lowerPlaybackReturn;
  void restore(){features.close();origin.restore();homeTab=home;playing=wasPlaying;paused=wasPaused;detailTab=detail;lowerTab=0;playerControls.hide();if(playing&&!paused)requestAudio();else abandonAudio();render(false);controls.details.selectPage(detail,false);if(playing){Tile tile=playerControls.tile();if(tile!=null){tile.resumeAt=position;if(tile.ready)tile.seekSaved();}}Video restored=current();controls.details.intro.post(()->{if(current()==restored&&lowerTab==0)controls.details.intro.scrollTo(0,introY);});}
 }
 void playRelatedVideo(Video video){if(video==null||current()==null||video.id.equals(current().id))return;if(relatedReturns.size()>=20)relatedReturns.removeFirst();relatedReturns.addLast(new RelatedPlaybackReturn());if(playing)gallery.savePosition();features.close();playerControls.hide();playing=false;paused=false;abandonAudio();videos=new ArrayList<>(Collections.singletonList(video));browse.reset(1);play();}
 void moveGrid(int dx,int dy){if(HandheldGrid.move(browse,dx,dy))render(true);maybeMoreVideos(dx>0||dy>0);}
 void move(int delta){if(columnBrowsing()){pgc.move(delta);return;}if(creators.active()&&!features.active()){creators.move(delta);return;}if(features!=null&&features.active()){features.move(delta);return;}if(playing){controls.details.moveFocus(delta);return;}if(browse.move(delta))render(true);maybeMoreVideos(delta>0);}
 final class HomePage {
  final List<Video> items=videos;final int source=channel,selection=browse.selected;final float offset=browse.offset,feedScroll=phoneFeedScroll;final PhonePreviewLayout.Order feedOrder=phoneFeedOrder;final String message=status,label=features.feed;final boolean pending=features.busy&&!features.lowerRoot;final FeatureUi.PageState menu=features.active()&&!features.lowerRoot?features.new PageState():null;final ArrayDeque<FeatureUi.PageState> history=new ArrayDeque<>(features.history);final int root=features.tabRoot;
  void restore(){phoneFeedOrder=feedOrder;phoneFeedScroll=feedScroll;videos=items;channel=source;status=message;features.feed=label;browse.reset(items.size());browse.selected=Math.max(0,Math.min(selection,items.size()-1));browse.offset=browse.clamp(offset);if(menu!=null){menu.restore();features.history.addAll(history);features.tabRoot=root;}}
 }
 int navigationTab(){return homeTab>=0&&homeTab<upperTabCount()?homeTab:RECOMMENDED;}
 void saveHomePage(){if(homeTab>=0&&homeTab<homePages.length)homePages[homeTab]=new HomePage();}
 void resetFeaturePage(){features.history.clear();features.tabRoot=-1;features.lowerRoot=false;lowerTab=0;features.resetPage();}
 void restoreHomePage(int target){
  resetFeaturePage();homeTab=target;HomePage saved=homePages[target];if(saved!=null)saved.restore();
  if(target==LIBRARY){channel=LIBRARY;videos=new ArrayList<>();browse.reset(0);status="本地视频 · 磁力播放 · 永久缓存";TorrentRuntime.prune(this);}
  else if(target==COLUMN){channel=COLUMN;videos=new ArrayList<>();browse.reset(0);status="番剧、电影、纪录片";pgc.enter();}
  else if(target==RECOMMENDED||target==POPULAR||target==DYNAMIC&&auth.mid>0){channel=target;List<Video> cached=feedPages[target].items;if(saved==null||videos!=cached){phoneFeedScroll=0;videos=cached;browse.reset(videos.size());}loading=feedLoading[target];status=loading?"正在读取"+tabs[target]+"…":tabs[target]+" · 10 秒静音预览";if(videos.isEmpty())loadFeed(target,false);else ui.post(()->maybeMoreVideos(false));}
  else if(saved==null||saved.pending){features.tabRoot=target;if(target==SEARCH){if(saved!=null&&!features.lastQuery.isEmpty())features.searchPage(features.searchPage);else features.search();}else if(target==DYNAMIC)features.following();else if(target==MINE)features.relations.mine();else features.home();}
  contentGeneration++;render(false);
 }
 void switchTab(int delta){if(playing&&second!=null){if(features.keyboard||features.qrMode)return;stopStick(false);if(features.active()&&!features.lowerRoot)features.close();playerControls.cycle(delta);return;}if(creators.active()&&!features.active()){if(second!=null&&!playing)creators.dismiss();else{creators.switchTab(delta);return;}}
  playerControls.hide();stopStick(false);if(controls!=null){controls.stopScroll();controls.resetRefresh();}
  if(playing){if(features.active())features.close();controls.details.selectPage(Math.floorMod(detailTab+delta,detailTabs.length),true);controls.syncDetails();controls.invalidate();return;}
  int from=navigationTab(),target=Math.floorMod(from+delta,upperTabCount());if(target==from&&homeTab==DEMO){navigateHome(target,1,false);return;}if(target==from){if(target==SEARCH&&!features.active()){features.tabRoot=SEARCH;features.search();}return;}navigateHome(target,delta<0?-1:1,false);
 }
 void navigateHome(int target,int direction,boolean manual){if(columnBrowsing()){pgc.saveAnchor();pgc.generation++;pgc.busy=false;}if(gallery!=null)gallery.stopFeedScroll();int from=navigationTab();saveHomePage();if(controls!=null){if(second==null)controls.beginChannelNavigation(direction,from,target,manual);else controls.beginNavigation(direction);}switchingHome=true;try{restoreHomePage(target);}finally{switchingHome=false;}}
 void returnFromRootTab(){int target=channel<tabs.length?channel:RECOMMENDED;if(target==homeTab){features.close();return;}navigateHome(target,-1,false);}
 void cancelHomeDrag(int origin){saveHomePage();switchingHome=true;try{restoreHomePage(origin);}finally{switchingHome=false;}}

 void openLocalLibrary(){if(playing&&gallery!=null)gallery.savePosition();if(phoneFullscreen)setPhoneFullscreen(false);playing=false;paused=false;playerControls.hide();features.close();creators.dismiss();abandonAudio();if(gallery!=null)gallery.releaseAll();navigateHome(LIBRARY,1,false);}
 void openColumn(){if(playing)gallery.savePosition();if(phoneFullscreen)setPhoneFullscreen(false);playing=false;paused=false;playerControls.hide();features.close();creators.dismiss();abandonAudio();gallery.releaseAll();navigateHome(COLUMN,1,false);}
 void playPgcEpisode(Video video){if(video==null||!video.pgc())return;if(playing)gallery.savePosition();gallery.releaseAll();features.close();creators.dismiss();relatedReturns.clear();lowerPlaybackReturn=null;playing=false;paused=false;channel=homeTab=COLUMN;videos=new ArrayList<>(Collections.singletonList(video));browse.reset(1);play();}
 void returnToPgc(){if(playing)gallery.savePosition();playing=false;paused=false;playerControls.hide();abandonAudio();gallery.releaseAll();channel=homeTab=COLUMN;videos=new ArrayList<>();browse.reset(0);render(false);pgc.enter();}
 void play(){if(columnBrowsing()){pgc.confirm();return;}if(!playing)setPhoneFullscreen(false);if(creators.active()&&!features.active()){creators.confirm();return;}if(features!=null&&features.active()){features.confirm();return;}Video v=current();if(v==null){refreshCurrentFeed();return;}if(playing){playerControls.confirm();return;}if(controls!=null)controls.stopScroll();gallery.stopFeedScroll();quality=0;playing=true;paused=false;playerControls.hide();detailTab=detailFocus=scroll=0;requestAudio();controls.syncDetails();controls.details.selectPage(0,false);gallery.render(true);loadComments();log("PLAY "+v.id);}
 void back(){stopStick(false);if(phoneVideoFullscreen()){setPhoneFullscreen(false);playerControls.show();return;}if(playing&&playerControls.visible&&(features==null||!features.active())){playerControls.hide();return;}if(features!=null&&features.active()){features.back();return;}if(creators.active()){creators.back();return;}if(playing&&detailTab==0&&controls.details.backThread())return;if(playing&&!relatedReturns.isEmpty()&&lowerPlaybackReturn==relatedReturns.peekLast().parentLowerReturn){gallery.savePosition();relatedReturns.removeLast().restore();return;}if(creators.returnToList())return;if(playing&&lowerPlaybackReturn!=null){gallery.savePosition();LowerPlaybackReturn saved=lowerPlaybackReturn;lowerPlaybackReturn=null;saved.restore();return;}if(playing&&channel==LOCAL){gallery.savePosition();playing=false;paused=false;abandonAudio();HomePage saved=localReturn;localReturn=null;if(saved!=null){saved.restore();homeTab=saved.source;}else{channel=RECOMMENDED;homeTab=channel;videos=recommended;browse.reset(videos.size());}render(false);return;}if(playing&&current()!=null&&current().pgc()){returnToPgc();return;}if(localBrowsing()){if(gallery.localPane!=null&&gallery.localPane.back())return;navigateHome(RECOMMENDED,-1,false);return;}if(columnBrowsing()){if(!pgc.back())navigateHome(RECOMMENDED,-1,false);return;}if(playing){gallery.savePosition();playing=false;paused=false;abandonAudio();render(true);return;}if(channel==DEMO||channel==LOCAL){channel=RECOMMENDED;homeTab=channel;videos=recommended;browse.reset(videos.size());render(false);return;}new AlertDialog.Builder(this).setMessage("退出 Thor 频道？").setPositiveButton("退出",(a,b)->finish()).setNegativeButton("继续浏览",null).show();}
 void togglePause(){if(!playing||features.keyboard)return;playerControls.show();Video v=current();if(v!=null&&!v.error.isEmpty()){Tile t=gallery.tiles.get(v.tileKey());if(t!=null)t.retryPlayback();return;}paused=!paused;if(!paused)requestAudio();gallery.syncPlayers();}
 void requestAudio(){if(!audioHeld){audioHeld=audio.requestAudioFocus(audioListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;if(!audioHeld){paused=true;message("暂未获得音频焦点，按 X 重试播放");}}}
 void abandonAudio(){if(audioHeld){audio.abandonAudioFocus(audioListener);audioHeld=false;}}
 void loadComments(){if(controls!=null&&playing)controls.syncDetails();}
 boolean seekCommentTimestamp(Video target,String mediaKey,int millis){
  Tile tile=playerControls.tile();if(millis<0||!playing||target==null||current()!=target||!target.mediaKey().equals(mediaKey)||tile==null||!tile.full||tile.video!=target)return false;
  long duration=tile.ready&&tile.mp!=null?tile.mp.getDuration():target.duration*1000L;
  if(duration>0&&millis>=duration){message("这个时间点超过了当前视频时长");return false;}
  stopStick(false);playerControls.cancelTouch();tile.completed=false;tile.resumeAt=millis;
  if(tile.ready&&tile.mp!=null){tile.resumeAt=-1;tile.mp.seekTo(millis,MediaPlayer.SEEK_CLOSEST);tile.lastSeek=SystemClock.uptimeMillis();subtitles.seeked(tile,millis);if(tile.pendingPlayer!=null){tile.pendingFrame=false;tile.pendingPlayer.seekTo(millis,MediaPlayer.SEEK_CLOSEST);}tile.applyPlayback();}else tile.scheduleDecode();
  playerControls.show();return true;
 }
 void ensureMedia(Tile tile){ensureMedia(tile,tile.full);}
 void ensureMedia(Tile tile,boolean full){
  Video v=tile.video;if(v.pgc()){if(!full)return;if(v.pgcAuthEpoch!=auth.epoch()){v.fullSpec=null;v.fullSpecAt=0;}}if(full&&!tile.full&&(v.demo||v.local()||!v.id.startsWith("BV")))return;if(v.demo){tile.openSpec(StreamSpec.single("asset:///demo-"+v.demoAsset+".mp4"));return;}
  if(v.local()){tile.openSpec(v.localSpec!=null?v.localSpec:new StreamSpec(Collections.singletonList(v.localUri),Collections.emptyList(),0,"本地视频"));return;}
  StreamSpec local=cache.local(v);if(local!=null){if(tile.full==full)tile.openSpec(local);return;}
  StreamSpec spec=full?v.fullSpec:v.previewSpec;long at=full?v.fullSpecAt:v.previewSpecAt;
  if(spec!=null&&System.currentTimeMillis()-at<15*60*1000&&(!full||v.fullQuality==(playing?quality:0))){if(tile.full==full)tile.openSpec(spec);return;}
  if(full?v.fullResolving:v.resolving)return;if(full)v.fullResolving=true;else v.resolving=true;int q=full?(playing?quality:0):16,epoch=auth.epoch();long cid=v.cid;
  (full&&second==null?playbackIo:mediaIo).execute(()->{if(tile.retired){ui.post(()->{if(full)v.fullResolving=false;else v.resolving=false;Tile live=gallery==null?null:gallery.tiles.get(v.tileKey());if(live!=null)live.scheduleDecode();});return;}StreamSpec found=null;Exception error=null;try{found=BiliApi.stream(v,q,!full);}catch(Exception e){error=e;}final StreamSpec result=found;final Exception failure=error;ui.post(()->finishMediaRequest(v,full,q,epoch,cid,result,failure));});
 }
 void finishMediaRequest(Video v,boolean full,int q,int epoch,long cid,StreamSpec found,Exception error){
  if(full)v.fullResolving=false;else v.resolving=false;if(dead)return;Tile live=gallery==null?null:gallery.tiles.get(v.tileKey());
  if(epoch!=auth.epoch()||(cid!=0&&cid!=v.cid)||(full&&live!=null&&live.full&&q!=quality)){if(live!=null)live.scheduleDecode();return;}
  if(error!=null&&full&&live!=null&&live.lowResStartup){live.upgradeFailed(error.getClass().getSimpleName());return;}
  if(error!=null){v.error=error.getMessage()==null?"播放暂不可用":error.getMessage();log("STREAM_FAIL "+v.id+" "+error.getClass().getSimpleName());return;}
  long now=System.currentTimeMillis();if(full){v.fullSpec=found;if(v.pgc())v.pgcAuthEpoch=epoch;v.fullSpecAt=now;v.fullQuality=q;if(q>0&&found.quality!=q){v.qualityNote="实际播放 "+found.label+"。"+(found.options==null?"平台未返回请求的画质。":found.options.reason(q,auth.mid>0,auth.vipStatus));}else v.qualityNote="";}else{v.previewSpec=found;v.previewSpecAt=now;}v.error="";if(live!=null&&live.full==full&&live.shouldDecode()){live.openSpec(found);if(live.selected||live.full)ensureDan(v);}
 }
 FeedPager.Page loadPhoneRecommendations(int page,String cursor)throws Exception{
  if(!"web".equals(cursor))try{
   org.json.JSONObject data=BiliApi.phoneRecommendations(page,cursor);String next=ApiModels.mobileRecommendationCursor(data);
   if(next.equals(cursor))throw new IOException("手机推荐续页未更新，继续上滑重试");
   List<Callable<Video>> jobs=new ArrayList<>();for(long aid:ApiModels.mobileRecommendationIds(data))jobs.add(()->BiliApi.recommendationByAid(aid));
   List<Video> rows=new ArrayList<>();for(Future<Video> future:sizeIo.invokeAll(jobs,8,TimeUnit.SECONDS))try{Video v=future.get();if(v!=null&&v.width>0&&v.height>0)rows.add(v);}catch(ExecutionException|CancellationException ignored){}
   if(rows.isEmpty())throw new IOException("手机推荐暂未就绪");return new FeedPager.Page(rows,page,true,next);
  }catch(InterruptedException e){throw e;}catch(Exception e){if(page>1)throw e;log("PHONE_FEED_FALLBACK "+e.getClass().getSimpleName());}
  // Keep pagination on one source after a first-page fallback.
  FeedPager.Page fallback=BiliApi.feedPage(true,page);return new FeedPager.Page(fallback.items,page,fallback.more,"web");
 }
 // Recommendation rows omit dimensions. Resolve each new batch before its stable
 // order is created; a slow/failed lookup stays off-canvas until the background retry completes.
 void preparePhoneFeed(List<Video> items)throws InterruptedException{
  List<Video> pending=new ArrayList<>();List<Callable<int[]>> jobs=new ArrayList<>();
  for(Video v:items)if(v.width<=0||v.height<=0){pending.add(v);jobs.add(()->{
   // Work on an isolated object: timed-out requests must never mutate a published list.
   Video probe=new Video(v.id,"","","",v.aid,v.cid,v.duration,false);return BiliApi.dimensions(probe);
  });}
  if(jobs.isEmpty())return;List<Future<int[]>> results=sizeIo.invokeAll(jobs,5,TimeUnit.SECONDS);
  for(int i=0;i<results.size();i++)try{int[] d=results.get(i).get();pending.get(i).setMetadataDimensions(d[0],d[1],d[2]);}catch(ExecutionException|CancellationException ignored){}
 }
 void ensureDimensions(Video v){if(v.demo||v.local()||v.width>0&&v.height>0||v.sizeRequested||v.sizeLoading)return;v.sizeRequested=true;v.sizeLoading=true;long sizeCid=v.cid;io.execute(()->{int[] dim;try{Video probe=new Video(v.id,"","","",v.aid,sizeCid,v.duration,false);dim=BiliApi.dimensions(probe);}catch(Exception e){dim=new int[]{0,0,0};}final int[] result=dim;ui.post(()->{if(dead)return;v.sizeLoading=false;if(result[0]<=0||result[1]<=0||sizeCid!=v.cid)return;v.setMetadataDimensions(result[0],result[1],result[2]);scheduleAspectLayout();});});}
 final Runnable aspectLayout=()->{if(active&&!playing)render(true);};
 void scheduleAspectLayout(){ui.removeCallbacks(aspectLayout);ui.postDelayed(aspectLayout,90);}
 void ensureDan(Video v){if(v.demo||v.local()||v.cid==0||v.danLoaded||v.danLoading||!showDan)return;v.danLoading=true;long danCid=v.cid;io.execute(()->{List<Video.Dan> result;try{result=BiliApi.danmaku(v);}catch(Exception e){result=new ArrayList<>();log("DAN_FAIL "+v.id);}final List<Video.Dan> loaded=result;ui.post(()->{if(dead)return;v.danLoading=false;if(danCid!=v.cid){ensureDan(v);return;}v.dan.clear();v.dan.addAll(loaded);v.danLoaded=true;});});}
 void ensureCover(Video v){if(v.demo||v.cover.isEmpty()||covers.containsKey(v.id)||!coverLoading.add(v.id))return;io.execute(()->{Bitmap bitmap=null;try{bitmap=BiliApi.cover(v);}catch(Exception ignored){}final Bitmap ready=bitmap;ui.post(()->{coverLoading.remove(v.id);if(dead)return;if(ready!=null)covers.put(v.id,ready);if(gallery!=null){gallery.invalidate();for(Tile tile:gallery.tiles.values())if(tile.video.id.equals(v.id))tile.overlay.invalidate();}});});}
 void message(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
 void settingAction(){SharedPreferences.Editor e=getPreferences(0).edit();switch(settingFocus){case 0:swapped=!swapped;e.putBoolean("swap",swapped);e.apply();attachScreens();return;case 1:abSwap=!abSwap;e.putBoolean("ab",abSwap);break;case 2:allPreviews=!allPreviews;e.putBoolean("four",allPreviews);if(gallery!=null)gallery.syncPlayers();break;case 3:motionMs=motionMs==380?520:motionMs==520?260:380;e.putInt("motion",motionMs);break;case 4:features.danmaku();return;case 5:features.close();refreshCurrentFeed();return;case 6:Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"thor-bili-diagnostics.txt");startActivityForResult(save,41);return;case 7:finish();return;}e.apply();render(false);}
 String[] settingLabels(){return new String[]{"上下屏互换   "+(swapped?"已互换":"默认"),"面键确认 / 返回   "+(abSwap?"B / A":"A / B"),"预览解码   "+(allPreviews?"显示的预览同时播放":"仅选中格"),"滚动时长   "+motionMs+" ms","弹幕   "+(showDan?"开启":"关闭"),"刷新当前频道","导出诊断信息","退出应用"};}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==45){if(cloud!=null)cloud.loggedIn();if(gallery!=null&&gallery.localPane!=null)gallery.localPane.bind();return;}if(request==44&&result==RESULT_OK&&data!=null){String stream=data.getStringExtra("xunlei_stream");if(stream!=null)try{openXunleiVideo(new org.json.JSONObject(stream));}catch(org.json.JSONException ignored){message("云盘地址读取失败，请重新选择");}return;}if(request==43&&result==RESULT_OK&&data!=null&&data.getData()!=null){try{getContentResolver().takePersistableUriPermission(data.getData(),data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}openLocalVideo(data.getData());return;}if(request==42&&result==RESULT_OK){io.execute(features::restoreAccount);if(channel==SEARCH&&!features.lastQuery.isEmpty())features.searchPage(features.searchPage);return;}if(request==41&&result==RESULT_OK&&data!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(("Thor Bili 0.3.15-preview\n"+Build.MODEL+" Android "+Build.VERSION.RELEASE+"\n"+diagnosticInfo+"\n"+readLog()).getBytes("UTF-8"));message("诊断已保存");}catch(Exception e){message("诊断导出失败");}}}
 synchronized void log(String s){try{File f=new File(getFilesDir(),"diagnostics.log");if(f.length()>250000)new FileOutputStream(f).close();try(FileWriter w=new FileWriter(f,true)){w.write(new Date()+" "+s+"\n");}}catch(Exception ignored){}}
 String readLog(){try(InputStream input=new FileInputStream(new File(getFilesDir(),"diagnostics.log"))){return new String(BiliApi.read(input,300000),"UTF-8");}catch(Exception e){return "暂无日志";}}
 @Override public boolean dispatchKeyEvent(KeyEvent e){return handleKey(e)||super.dispatchKeyEvent(e);}
 boolean handleKey(KeyEvent e){int k=e.getKeyCode();if(localBrowsing()&&!features.active()&&gallery!=null&&gallery.localPane!=null&&gallery.localPane.key(e))return true;if(columnBrowsing()&&pgc.key(e))return true;if(playing&&!playerControls.visible&&!features.active()&&controls.details.coinKey(e))return true;if(k==KeyEvent.KEYCODE_BUTTON_L2||k==KeyEvent.KEYCODE_BUTTON_R2){int side=k==KeyEvent.KEYCODE_BUTTON_L2?0:1;if(triggerEdges.edge(side,false,e.getAction()==KeyEvent.ACTION_DOWN))dispatchTrigger(side);return true;}if(k==KeyEvent.KEYCODE_MENU||k==KeyEvent.KEYCODE_BUTTON_MODE){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){playerControls.hide();if(features.title.equals("更多"))features.close();else features.home();}return true;}if(k==KeyEvent.KEYCODE_BUTTON_L1||k==KeyEvent.KEYCODE_BUTTON_R1){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)switchTab(k==KeyEvent.KEYCODE_BUTTON_L1?-1:1);return true;}if(playing&&second!=null&&playerControls.visible&&(!features.active()||features.lowerRoot)){int yes=abSwap?KeyEvent.KEYCODE_BUTTON_B:KeyEvent.KEYCODE_BUTTON_A,no=abSwap?KeyEvent.KEYCODE_BUTTON_A:KeyEvent.KEYCODE_BUTTON_B;if(k==yes||k==KeyEvent.KEYCODE_ENTER||k==KeyEvent.KEYCODE_DPAD_CENTER||k==no||k==KeyEvent.KEYCODE_BACK||k==KeyEvent.KEYCODE_ESCAPE){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){if(k==no||k==KeyEvent.KEYCODE_BACK||k==KeyEvent.KEYCODE_ESCAPE)playerControls.hide();else playerControls.confirm();}return true;}if(e.getAction()==KeyEvent.ACTION_DOWN&&playerControls.key(k))return true;}if(features!=null&&features.active()){if(features.keyboard&&features.systemInput&&!(k>=KeyEvent.KEYCODE_BUTTON_A&&k<=KeyEvent.KEYCODE_BUTTON_MODE)&&k!=KeyEvent.KEYCODE_BACK&&k!=KeyEvent.KEYCODE_ESCAPE)return false;return features.key(e);}boolean known=k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_DPAD_DOWN||k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT||k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER||k==KeyEvent.KEYCODE_BACK||k>=KeyEvent.KEYCODE_BUTTON_A&&k<=KeyEvent.KEYCODE_BUTTON_MODE||k==KeyEvent.KEYCODE_ESCAPE;if(!known)return false;if(e.getAction()!=KeyEvent.ACTION_DOWN)return true;if(e.getRepeatCount()==0)log("KEY code="+k+" device="+e.getDeviceId());if(playing&&!creators.active()&&playerControls.key(k))return true;if(gridBrowsing()&&(k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_DPAD_DOWN||k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT)){if(e.getRepeatCount()>0&&SystemClock.uptimeMillis()-lastMove<145)return true;lastMove=SystemClock.uptimeMillis();moveGrid(k==KeyEvent.KEYCODE_DPAD_LEFT?-1:k==KeyEvent.KEYCODE_DPAD_RIGHT?1:0,k==KeyEvent.KEYCODE_DPAD_UP?-1:k==KeyEvent.KEYCODE_DPAD_DOWN?1:0);return true;}if((k==KeyEvent.KEYCODE_DPAD_DOWN||k==KeyEvent.KEYCODE_DPAD_UP)){if(e.getRepeatCount()>0&&SystemClock.uptimeMillis()-lastMove<145)return true;lastMove=SystemClock.uptimeMillis();move(k==KeyEvent.KEYCODE_DPAD_DOWN?1:-1);return true;}if(e.getRepeatCount()>0)return true;int yes=abSwap?KeyEvent.KEYCODE_BUTTON_B:KeyEvent.KEYCODE_BUTTON_A,no=abSwap?KeyEvent.KEYCODE_BUTTON_A:KeyEvent.KEYCODE_BUTTON_B;if(k==yes||k==KeyEvent.KEYCODE_ENTER||k==KeyEvent.KEYCODE_DPAD_CENTER){if(creators.active())creators.confirm();else if(playing)playerControls.confirm();else play();}else if(k==no||k==KeyEvent.KEYCODE_BACK||k==KeyEvent.KEYCODE_ESCAPE)back();else if(k==KeyEvent.KEYCODE_BUTTON_L1||k==KeyEvent.KEYCODE_DPAD_LEFT)switchTab(-1);else if(k==KeyEvent.KEYCODE_BUTTON_R1||k==KeyEvent.KEYCODE_DPAD_RIGHT)switchTab(1);else if(k==KeyEvent.KEYCODE_BUTTON_X)togglePause();else if(k==KeyEvent.KEYCODE_BUTTON_Y){if(playing)features.writeDanmaku(true);}else if(k==KeyEvent.KEYCODE_BUTTON_L2||k==KeyEvent.KEYCODE_BUTTON_R2){if(playing&&gallery!=null)seekCommand(k==KeyEvent.KEYCODE_BUTTON_R2?1:-1);}else if(k==KeyEvent.KEYCODE_BUTTON_SELECT){if(!playing)refreshCurrentFeed();}return true;}
 @Override public boolean dispatchGenericMotionEvent(MotionEvent e){return handleMotion(e)||super.dispatchGenericMotionEvent(e);}
 void setShortcut(int side,String id){shortcuts[side]=ShortcutBindings.valid(id,side);getPreferences(0).edit().putString("shortcut"+side,shortcuts[side]).apply();}
 void dispatchTrigger(int side){if(features.active()&&features.keyboard){if(!features.systemInput&&features.input!=null){features.input.next(side==0?-1:1);features.redraw();}return;}if(second!=null&&!features.qrMode){if(creators.active()){playerControls.hide();creators.switchTab(side==0?-1:1);}else switchLowerPage(side==0?-1:1);return;}if(second==null&&playing&&!features.active()&&!creators.active()){stopStick(false);playerControls.cycle(side==0?-1:1);}}
 void runShortcut(int side){String id=shortcuts[side];if(id.equals("danmaku")){showDan=!showDan;features.saveDan();message(showDan?"弹幕已开启":"弹幕已关闭");}else if(id.equals("mute")){appMuted=!appMuted;getPreferences(0).edit().putBoolean("appMuted",appMuted).apply();if(gallery!=null)gallery.syncPlayers();message(appMuted?"已静音":"声音已恢复");}else if(id.equals("pause")){if(playing)togglePause();}else if(id.equals("back10"))seekCommand(-1);else if(id.equals("forward10"))seekCommand(1);}
 void seekCommand(int direction){long now=SystemClock.uptimeMillis();if(!playing||gallery==null||now-lastSeekCommand<180)return;lastSeekCommand=now;gallery.seek(direction*10000);playerControls.focus=1;playerControls.show();}
 void stopStick(boolean commit){ui.removeCallbacks(stickRepeat);stickHeld=0;playerControls.finishStickSeek(commit);}
 void performStick(int command,boolean first){if(gridBrowsing()){moveGrid(command==ControllerStick.LEFT?-1:command==ControllerStick.RIGHT?1:0,command==ControllerStick.UP?-1:command==ControllerStick.DOWN?1:0);return;}if(command==ControllerStick.UP||command==ControllerStick.DOWN){move(command==ControllerStick.UP?-1:1);return;}if(!playing||features.active())return;if(command==ControllerStick.VOLUME_UP||command==ControllerStick.VOLUME_DOWN){audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,command==ControllerStick.VOLUME_UP?AudioManager.ADJUST_RAISE:AudioManager.ADJUST_LOWER,0);playerControls.showVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC),audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));}else playerControls.moveStickSeek(command==ControllerStick.SEEK_FORWARD?1:-1,first);}
 boolean handleMotion(MotionEvent e){
  if((e.getSource()&InputDevice.SOURCE_CLASS_JOYSTICK)==0)return false;
  boolean lt=Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER),e.getAxisValue(MotionEvent.AXIS_BRAKE))>.6f,rt=Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER),e.getAxisValue(MotionEvent.AXIS_GAS))>.6f;if(triggerEdges.edge(0,true,lt))dispatchTrigger(0);if(triggerEdges.edge(1,true,rt))dispatchTrigger(1);leftTrigger=lt;rightTrigger=rt;
  float x=e.getAxisValue(MotionEvent.AXIS_X),y=e.getAxisValue(MotionEvent.AXIS_Y),rx=e.getAxisValue(MotionEvent.AXIS_Z),ry=e.getAxisValue(MotionEvent.AXIS_RZ);if(Math.max(Math.abs(rx),Math.abs(ry))>Math.max(Math.abs(x),Math.abs(y))){x=rx;y=ry;}
  int command=gridBrowsing()?ControllerStick.gridCommand(x,y):ControllerStick.command(x,y,playing&&!features.active()&&!creators.active());float hat=e.getAxisValue(MotionEvent.AXIS_HAT_Y),hatX=e.getAxisValue(MotionEvent.AXIS_HAT_X);if(gridBrowsing()&&Math.max(Math.abs(hat),Math.abs(hatX))>.55f)command=ControllerStick.gridCommand(hatX,hat);else if(Math.abs(hat)>.55f)command=hat<0?ControllerStick.UP:ControllerStick.DOWN;
  if(command!=stickHeld){stopStick(true);stickHeld=command;if(command!=0){performStick(command,true);ui.postDelayed(stickRepeat,command==ControllerStick.SEEK_BACK||command==ControllerStick.SEEK_FORWARD?220:350);}}return true;
 }


 void cacheVideo(Video v){features.cacheCurrent(v);}
 void showDownloads(Video v){message(cache.status(v));}
 String downloadText(Video v){return cache.status(v);}

 final class Gallery extends FrameLayout {
  PgcUi.Pane pgcPane;LocalLibraryPane localPane;final LinkedHashMap<String,Tile> tiles=new LinkedHashMap<>();int width,height;boolean wasPlaying,pendingBrowseAnimation,feedForward=true;
  final LinkedHashMap<String,Bitmap> previewFrames=new LinkedHashMap<String,Bitmap>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Bitmap> e){return size()>12;}};PlaybackReveal reveal;
  Gallery(Context c){super(c);setWillNotDraw(false);setBackgroundColor(0xffedf4f5);setClipChildren(true);setClipToPadding(true);}
  @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(oldw>0&&oldh>0&&(w!=oldw||h!=oldh)&&second!=null)cancelReveal();width=w;height=h;post(()->{if(this==gallery)render(false);});}
  PreviewLayout.Plan plan=new PreviewLayout.Plan(PreviewLayout.Mode.EMPTY,new ArrayList<>());
  final PreviewSeats mixedSeats=new PreviewSeats();
  List<Video> window(){return phoneBrowsePage()?videos:new ArrayList<>(videos.subList(browse.start(),browse.end()));}
  boolean previewVisible(int absoluteIndex){return plan.shows(phoneBrowsePage()?absoluteIndex:absoluteIndex-browse.start());}
  float headerHeight(){return second!=null&&!playing?HandheldGrid.headerHeight(width,height):0;}
  void drawHeader(Canvas c){float h=headerHeight();if(h==0||features.qrMode)return;Paint p=new Paint(3);p.setColor(Color.WHITE);c.drawRect(0,0,width,h,p);float cell=width/(float)upperTabs.length;p.setTextAlign(Paint.Align.CENTER);for(int i=0;i<upperTabs.length;i++){boolean on=navigationTab()==i;p.setTextSize(h*(on?.36f:.33f));p.setTypeface(on?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);p.setColor(on?accent:ink);c.drawText(upperTabs[i],(i+.5f)*cell,h*.51f,p);if(on){p.setColor(accent);float x=(i+.5f)*cell;c.drawRoundRect(x-h*.25f,h*.64f,x+h*.25f,h*.69f,h*.025f,h*.025f,p);}}ChannelStyle.keyBadge(c,p,"L1",h*.4f,h*.38f,h*.23f);ChannelStyle.keyBadge(c,p,"R1",width-h*.4f,h*.38f,h*.23f);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT);p.setTextSize(h*.16f);p.setColor(muted);c.drawText("摇杆选片  ·  A 播放     /     L2  R2 切下屏",width/2f,h*.92f,p);p.setColor(0xffeef0f2);c.drawRect(0,h-1,width,h,p);}
  float headerDownX,headerDownY,feedLastY,menuTouchX,menuTouchY;boolean headerTouch,phoneDrag,menuTouchDragged;
  final OverScroller feedFlinger=new OverScroller(getContext());VelocityTracker feedVelocity;
  PhonePreviewLayout feedLayout;List<Video> layoutItems;long layoutSignature;float feedViewport;
  final Runnable feedMore=()->{if(this==gallery&&phoneBrowsePage())maybeMoreVideos(false);};
  void stopFeedScroll(){feedFlinger.forceFinished(true);phoneDrag=false;if(feedVelocity!=null){feedVelocity.recycle();feedVelocity=null;}}
  void scrollFeed(float y){if(feedLayout==null)return;float next=feedLayout.clamp(y,feedViewport);if(next==phoneFeedScroll)return;feedForward=next>phoneFeedScroll;phoneFeedScroll=next;render(false);}
  PreviewLayout.Plan phonePlan(float header,float footer){
   feedViewport=Math.max(1,height-header-footer);
   if(phoneFeedOrder==null||phoneFeedOrder.source!=videos)phoneFeedOrder=new PhonePreviewLayout.Order(videos);else phoneFeedOrder.sync();long signature=width*31L+Float.floatToIntBits(feedViewport);for(Video v:videos)if(v.width<=0||v.height<=0)ensureDimensions(v);for(int slot:phoneFeedOrder.slots)signature=(signature*31+slot)*31+Float.floatToIntBits(phoneFeedOrder.aspect(slot));
   if(feedLayout==null||layoutItems!=videos||layoutSignature!=signature){
    int anchor=-1;float anchorY=0;if(layoutItems==videos&&feedLayout!=null&&phoneFeedScroll>0){for(PreviewLayout.Box b:feedLayout.boxes)if(b.y+b.height>phoneFeedScroll){anchor=b.slot;anchorY=b.y-phoneFeedScroll;break;}}
    feedLayout=new PhonePreviewLayout(videos,width,feedViewport,phoneFeedOrder);layoutItems=videos;layoutSignature=signature;
    if(feedLayout.box(anchor)!=null)phoneFeedScroll=feedLayout.box(anchor).y-anchorY;
   }
   phoneFeedScroll=feedLayout.clamp(phoneFeedScroll,feedViewport);return feedLayout.visible(phoneFeedScroll,feedViewport,header);
  }
  @Override public boolean onInterceptTouchEvent(MotionEvent e){if(features!=null&&features.active()){int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){menuTouchX=e.getX();menuTouchY=e.getY();menuTouchDragged=false;}else if(action==MotionEvent.ACTION_MOVE&&Math.hypot(e.getX()-menuTouchX,e.getY()-menuTouchY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()*2)menuTouchDragged=true;return true;}if(localBrowsing())return super.onInterceptTouchEvent(e);if(columnBrowsing())return false;
   int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){headerDownX=e.getX();headerDownY=feedLastY=e.getY();headerTouch=headerHeight()>0&&e.getY()<headerHeight()&&!features.qrMode;boolean moving=!feedFlinger.isFinished();stopFeedScroll();if(phoneBrowsePage()){feedVelocity=VelocityTracker.obtain();feedVelocity.addMovement(e);phoneDrag=moving;}}
   if(phoneBrowsePage()&&action==MotionEvent.ACTION_MOVE){if(feedVelocity!=null)feedVelocity.addMovement(e);float dy=e.getY()-headerDownY;if(!phoneDrag&&Math.abs(dy)>ViewConfiguration.get(getContext()).getScaledTouchSlop()&&Math.abs(dy)>Math.abs(e.getX()-headerDownX)){phoneDrag=true;float distance=feedLastY-e.getY();feedLastY=e.getY();post(()->{if(phoneBrowsePage())scrollFeed(phoneFeedScroll+distance);});}}
   if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_UP){if(!phoneDrag&&feedVelocity!=null){feedVelocity.recycle();feedVelocity=null;}}
   return phoneDrag||headerTouch||super.onInterceptTouchEvent(e);
  }
  @Override public boolean onTouchEvent(MotionEvent e){
   int action=e.getActionMasked();
   if(features!=null&&features.active()){if(action==MotionEvent.ACTION_MOVE&&Math.hypot(e.getX()-menuTouchX,e.getY()-menuTouchY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()*2)menuTouchDragged=true;if(action==MotionEvent.ACTION_UP&&!menuTouchDragged){features.close();playerControls.focus=0;playerControls.show();}if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)menuTouchDragged=false;return true;}
   if(phoneBrowsePage()){
    if(action==MotionEvent.ACTION_DOWN){feedLastY=e.getY();if(feedVelocity==null)feedVelocity=VelocityTracker.obtain();feedVelocity.addMovement(e);return true;}
    if(feedVelocity!=null)feedVelocity.addMovement(e);
    if(action==MotionEvent.ACTION_MOVE){if(Math.abs(e.getY()-headerDownY)>ViewConfiguration.get(getContext()).getScaledTouchSlop())phoneDrag=true;scrollFeed(phoneFeedScroll+feedLastY-e.getY());feedLastY=e.getY();return true;}
    if(action==MotionEvent.ACTION_UP){if(phoneDrag&&feedVelocity!=null&&feedLayout!=null){feedVelocity.computeCurrentVelocity(1000,ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity());int velocity=Math.round(-feedVelocity.getYVelocity());if(Math.abs(velocity)>ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity()){feedFlinger.fling(0,Math.round(phoneFeedScroll),0,velocity,0,0,0,Math.round(Math.max(0,feedLayout.height-feedViewport)));postInvalidateOnAnimation();}}phoneDrag=false;if(feedVelocity!=null){feedVelocity.recycle();feedVelocity=null;}maybeMoreVideos(true);return true;}
    if(action==MotionEvent.ACTION_CANCEL){stopFeedScroll();return true;}return true;
   }
   if(action==MotionEvent.ACTION_DOWN){headerTouch=headerHeight()>0&&e.getY()<headerHeight()&&!features.qrMode;headerDownX=e.getX();headerDownY=e.getY();return headerTouch;}
   if(!headerTouch)return super.onTouchEvent(e);if(action==MotionEvent.ACTION_UP){headerTouch=false;if(Math.hypot(e.getX()-headerDownX,e.getY()-headerDownY)<30){int target=Math.max(0,Math.min(upperTabs.length-1,(int)(e.getX()/Math.max(1,width)*upperTabs.length)));switchTab(target-navigationTab());}}else if(action==MotionEvent.ACTION_CANCEL)headerTouch=false;return true;
  }
  @Override public void computeScroll(){if(!phoneBrowsePage()){feedFlinger.forceFinished(true);return;}if(feedFlinger.computeScrollOffset()){scrollFeed(feedFlinger.getCurrY());postInvalidateOnAnimation();}}
  void render(boolean animated){
   if(width==0||height==0||!active)return;
   resizePhonePreview(animated);
   if(localBrowsing()){cancelReveal();if(!tiles.isEmpty())releaseAll();if(pgcPane!=null)pgcPane.setVisibility(GONE);plan=new PreviewLayout.Plan(PreviewLayout.Mode.EMPTY,new ArrayList<>());wasPlaying=false;if(localPane==null){localPane=new LocalLibraryPane(MainActivity.this,getContext());addView(localPane);}FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-1);lp.topMargin=second!=null?Math.round(headerHeight()):phoneChrome==null?0:Math.round(phoneChrome.topHeight());lp.bottomMargin=second==null&&phoneChrome!=null?Math.round(phoneChrome.bottomHeight()):0;localPane.setLayoutParams(lp);localPane.setVisibility(VISIBLE);localPane.bind();return;}if(localPane!=null)localPane.setVisibility(GONE);
   if(columnBrowsing()){cancelReveal();if(!tiles.isEmpty())releaseAll();plan=new PreviewLayout.Plan(PreviewLayout.Mode.EMPTY,new ArrayList<>());wasPlaying=false;if(pgcPane==null){pgcPane=pgc.new Pane(getContext());addView(pgcPane);}FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,-1);cp.topMargin=second==null?0:Math.round(headerHeight());cp.bottomMargin=second==null&&phoneChrome!=null?Math.round(phoneChrome.bottomHeight()):0;pgcPane.setLayoutParams(cp);pgcPane.setVisibility(VISIBLE);pgc.pane=pgcPane;pgcPane.bind();return;}if(pgcPane!=null)pgcPane.setVisibility(GONE);
   // Returning from the half-height player must use the final full-page viewport.
   // Laying out the feed in the old player bounds temporarily changes columns and anchors.
   int expectedBrowseWidth=singleLayout==null?width:singleLayout.getWidth()-(phoneLandscape&&phoneChrome!=null?phoneChrome.railWidth():0);
   if(phoneBrowsePage()&&singleLayout!=null&&(width!=expectedBrowseWidth||height!=singleLayout.getHeight())){pendingBrowseAnimation|=animated;return;}
   animated|=pendingBrowseAnimation;pendingBrowseAnimation=false;
   boolean modeChange=playing!=wasPlaying;Bitmap carriedImage=null;RectF carriedRect=null;
   if(modeChange){if(reveal!=null&&current()!=null&&reveal.video==current()){carriedImage=reveal.image;reveal.image=null;carriedRect=reveal.rect();}cancelReveal();for(int i=getChildCount()-1;i>=0;i--){View child=getChildAt(i);if(child instanceof Tile&&((Tile)child).retired){((Tile)child).cancelMotion();removeView(child);}}}else if(reveal!=null&&!reveal.entering&&(phoneDrag||!feedFlinger.isFinished()))cancelReveal();
   List<Video> window=window();resizePhonePreview(animated);
   boolean phoneFeed=phoneBrowsePage();if(!phoneFeed)for(Video v:window)ensureDimensions(v);
   float header=second!=null?HandheldGrid.headerHeight(width,height):phoneBrowsePage()&&phoneChrome!=null?phoneChrome.topHeight():0,footer=phoneBrowsePage()&&phoneChrome!=null?phoneChrome.bottomHeight():0;
   plan=second!=null?HandheldGrid.layout(window.size(),browse.visibleRows,width,height-header):phoneFeed?phonePlan(header,footer):PreviewLayout.make(window,browse.selected-browse.start(),width,height,true,false,mixedSeats);
   if(header>0&&!phoneFeed){List<PreviewLayout.Box> shifted=new ArrayList<>();for(PreviewLayout.Box b:plan.boxes)shifted.add(new PreviewLayout.Box(b.slot,b.x,b.y+header,b.width,b.height));plan=new PreviewLayout.Plan(plan.mode,shifted);}
   if(modeChange&&animated&&current()!=null){Tile source=tiles.get(current().tileKey());PreviewLayout.Box destination=null;for(PreviewLayout.Box box:plan.boxes)if(box.slot==(phoneFeed?browse.selected:browse.selected-browse.start()))destination=box;reveal=new PlaybackReveal(getContext(),source,current(),width,height,playing,destination,carriedImage,carriedRect);carriedImage=null;}if(carriedImage!=null)carriedImage.recycle();
   if(reveal!=null&&second==null){PreviewLayout.Box destination=null;if(!playing)for(PreviewLayout.Box b:plan.boxes)if(b.slot==browse.selected)destination=b;reveal.retarget(width,height,destination);}
   List<PreviewLayout.Box> boxes=new ArrayList<>(plan.boxes);
   if(phoneFeed&&allPreviews)boxes.addAll(feedLayout.nearby(phoneFeedScroll,feedViewport,header,feedForward,4));
   if(playing&&current()!=null){boxes.clear();boxes.add(new PreviewLayout.Box(browse.selected-browse.start(),0,0,width,height));}
   Set<String> desired=new HashSet<>();for(PreviewLayout.Box box:boxes)desired.add(window.get(box.slot).tileKey());
   for(String id:new ArrayList<>(tiles.keySet()))if(!desired.contains(id)){
    Tile t=tiles.remove(id);t.retired=true;t.cancelMotion();t.release();
    if(animated&&!modeChange)t.animate().alpha(0).translationY(t.getTranslationY()-12).setDuration(Math.min(200,motionMs)).withEndAction(()->removeView(t)).start();else removeView(t);
   }
   for(PreviewLayout.Box box:boxes){
    Video v=window.get(box.slot);ensureDimensions(v);Tile t=tiles.get(v.tileKey());boolean fresh=t==null;
    if(fresh){t=new Tile(getContext(),v);tiles.put(v.tileKey(),t);addView(t,new FrameLayout.LayoutParams(1,1));}
    boolean wasWarming=t.warming;t.warming=phoneFeed&&!plan.shows(box.slot);t.setImportantForAccessibility(t.warming?View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS:View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    if(fresh&&t.warming)t.prepareOffscreenSurface();
    t.slot=box.slot;t.selected=(phoneFeed?box.slot:browse.start()+box.slot)==browse.selected;
    boolean fullTransition=playing!=wasPlaying||fresh&&playing;t.full=playing;t.setClipToOutline(!playing&&!phoneFeed);t.texture.setClipToOutline(phoneFeed);t.texture.invalidateOutline();((GradientDrawable)t.getBackground()).setCornerRadius(playing||phoneFeed?0:18);((GradientDrawable)t.getBackground()).setColor(playing?Color.BLACK:phoneFeed?Color.WHITE:second!=null?0xffe9ecef:colors[Math.abs(v.id.hashCode())%colors.length]);
    boolean sizeChanged=t.getLayoutParams().width!=Math.round(box.width)||t.getLayoutParams().height!=Math.round(box.height);
    t.moveTo(box.x,box.y,Math.max(1,Math.round(box.width)),Math.max(1,Math.round(box.height)),animated&&!modeChange&&!phoneFeed,fresh);
    if(sizeChanged||modeChange||!phoneFeed)t.fitVideo();t.overlay.invalidate();if(fullTransition){if(playing)t.enterFull();else t.leaveFull();}
    if(t.selected&&!t.warming)ensureDan(v);if(!t.warming)ensureCover(v);t.scheduleDecode();if(wasWarming!=t.warming)t.applyPlayback();
   }
   if(reveal!=null&&reveal.getParent()==null){addView(reveal,new FrameLayout.LayoutParams(-1,-1));reveal.start();}if(reveal!=null){if(!reveal.entering){Tile hidden=tiles.get(reveal.video.tileKey());if(hidden!=null)hidden.setAlpha(0);}reveal.bringToFront();}
   setBackgroundColor(playing?Color.BLACK:paper);wasPlaying=playing;if(phoneFeed){ui.removeCallbacks(feedMore);ui.post(feedMore);}composer.sync();if(tiles.isEmpty())setContentDescription(status);invalidate();
  }
  @Override protected void onDraw(Canvas c){super.onDraw(c);if(!playing)ChannelStyle.backdrop(c,getWidth(),getHeight(),false);}
  @Override protected void dispatchDraw(Canvas c){int clip=c.save();if(phoneBrowsePage()&&!columnBrowsing()&&phoneChrome!=null&&reveal==null)c.clipRect(0,phoneChrome.topHeight(),width,height-phoneChrome.bottomHeight());super.dispatchDraw(c);c.restoreToCount(clip);if(features!=null&&features.qrMode){features.drawContent(c,getWidth(),getHeight());return;}if(tiles.isEmpty()&&!columnBrowsing()&&!localBrowsing()){Paint p=new Paint(3);p.setColor(muted);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(Math.min(width/25f,32));c.drawText(phoneBrowsePage()&&!videos.isEmpty()?(loading||videos.stream().anyMatch(v->v.sizeLoading)?"正在准备预览…":"预览暂未就绪，点击底部栏目重试"):status,width/2f,height/2f,p);}drawHeader(c);}
  void tick(){for(Tile t:tiles.values())t.tick();if(reveal!=null)reveal.checkReady();}
  void syncPlayers(){for(Tile t:tiles.values()){if(t.shouldDecode())t.scheduleDecode();else t.release();t.applyPlayback();}}
  void savePosition(){Tile t=current()==null?null:tiles.get(current().tileKey());if(t!=null&&t.ready&&t.frameShown&&t.mp!=null&&!t.video.demo){int pos=t.position();getPreferences(0).edit().putInt("position_"+t.video.mediaKey(),pos).apply();if(cloud!=null)cloud.checkpoint(t.video,pos,t.mp.getDuration());}}
  void seek(int delta){Tile t=current()==null?null:tiles.get(current().tileKey());if(t!=null&&t.ready&&t.mp!=null){try{int at=Math.max(0,Math.min(t.mp.getDuration()-1,t.position()+delta));t.mp.seekTo(at,MediaPlayer.SEEK_CLOSEST);t.lastSeek=SystemClock.uptimeMillis();subtitles.seeked(t,at);}catch(Exception ignored){}}}
  void cancelReveal(){if(reveal!=null){PlaybackReveal old=reveal;reveal=null;if(!old.entering){Tile tile=tiles.get(old.video.tileKey());if(tile!=null)tile.setAlpha(1);}old.dispose();removeView(old);}}
  void releaseAll(){pendingBrowseAnimation=false;stopFeedScroll();ui.removeCallbacks(feedMore);cancelReveal();for(Tile t:tiles.values()){t.retired=true;t.cancelMotion();t.release();}for(int i=getChildCount()-1;i>=0;i--){View v=getChildAt(i);if(v instanceof Tile)((Tile)v).release();}tiles.clear();removeAllViews();pgcPane=null;localPane=null;}
 }

 final class PlaybackReveal extends View {
  final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);final RectF from,to;final Video video;final boolean entering,hasDestination;Bitmap image;ValueAnimator motion;float progress;boolean complete,disposed;long started;
  PlaybackReveal(Context c,Tile tile,Video video,int w,int h){this(c,tile,video,w,h,true,null,null,null);}
  PlaybackReveal(Context c,Tile tile,Video video,int w,int h,boolean entering,PreviewLayout.Box destination,Bitmap carriedImage,RectF carriedRect){super(c);this.video=video;this.entering=entering;hasDestination=entering||destination!=null;setBackgroundColor(second!=null&&entering?Color.BLACK:Color.TRANSPARENT);setClickable(false);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
   float aspect=video.aspect(),tw=Math.min(w,h*aspect),th=tw/aspect;RectF fullRect=new RectF((w-tw)/2,(h-th)/2,(w+tw)/2,(h+th)/2);
   if(carriedImage!=null&&carriedRect!=null){image=carriedImage;from=new RectF(carriedRect);}else if(tile!=null&&tile.getWidth()>0&&tile.getHeight()>0){image=tile.captureFrame();float ph=tile.pictureHeight(),fw=Math.min(tile.getWidth(),ph*aspect),fh=fw/aspect;float x=tile.getX()+(tile.getWidth()-fw)/2,y=tile.getY()+(ph-fh)/2;from=new RectF(x,y,x+fw,y+fh);}else{from=new RectF(fullRect);if(entering){float inset=from.width()*.025f;from.inset(inset,inset/Math.max(.1f,aspect));}}
   if(image==null){Bitmap cover=covers.get(video.id);if(cover!=null&&!cover.isRecycled())image=cover.copy(Bitmap.Config.ARGB_8888,false);}
   if(entering)to=fullRect;else if(destination!=null){float ph=destination.height-(second!=null?HandheldGrid.captionHeight(destination.width,destination.height):0),fw=Math.min(destination.width,ph*aspect),fh=fw/aspect,x=destination.x+(destination.width-fw)/2,y=destination.y+(ph-fh)/2;to=new RectF(x,y,x+fw,y+fh);}else{to=new RectF(from);to.inset(to.width()*.035f,to.height()*.035f);}
  }
  void retarget(int w,int h,PreviewLayout.Box destination){float aspect=video.aspect();if(entering){float fw=Math.min(w,h*aspect),fh=fw/aspect;to.set((w-fw)/2,(h-fh)/2,(w+fw)/2,(h+fh)/2);}else if(destination!=null){float fw=Math.min(destination.width,destination.height*aspect),fh=fw/aspect,x=destination.x+(destination.width-fw)/2,y=destination.y+(destination.height-fh)/2;to.set(x,y,x+fw,y+fh);}}
  float pageAngle(){float turn=progress<.45f?-88*progress/.45f:88*(1-(progress-.45f)/.55f);return entering?turn:-turn;}
  RectF rect(){float f=progress;return new RectF(from.left+(to.left-from.left)*f,from.top+(to.top-from.top)*f,from.right+(to.right-from.right)*f,from.bottom+(to.bottom-from.bottom)*f);}
  void start(){started=SystemClock.uptimeMillis();motion=ValueAnimator.ofFloat(0,1);motion.setDuration(second==null?(entering?440:400):(entering?280:300));motion.setInterpolator(second==null?new PathInterpolator(.3f,0,.2f,1):new PathInterpolator(.2f,.75f,.2f,1));motion.addUpdateListener(v->{progress=(float)v.getAnimatedValue();postInvalidateOnAnimation();});motion.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator v){if(disposed)return;complete=true;progress=1;invalidate();checkReady();}});motion.start();}
  void checkReady(){if(disposed||!complete||gallery==null||gallery.reveal!=this)return;Tile live=gallery.tiles.get(video.tileKey());if(!hasDestination||live==null||!video.error.isEmpty()){gallery.cancelReveal();return;}if(live.full==entering&&live.frameShown&&live.ready){postOnAnimation(()->{if(!disposed&&gallery!=null&&gallery.reveal==this)gallery.cancelReveal();});}}
  void dispose(){disposed=true;if(motion!=null){motion.cancel();motion=null;}if(image!=null){image.recycle();image=null;}}
  @Override protected void onDraw(Canvas c){super.onDraw(c);if(disposed)return;if(second==null||!entering)c.drawColor(Color.argb(Math.round(255*(entering?progress:1-progress)),0,0,0));RectF rect=rect();int pose=c.save();if(second==null&&progress<1){Camera camera=new Camera();Matrix matrix=new Matrix();camera.save();camera.setLocation(0,0,-Math.max(getWidth(),getHeight())/24f);camera.rotateY(pageAngle());camera.getMatrix(matrix);camera.restore();matrix.preTranslate(-rect.left,-rect.centerY());matrix.postTranslate(rect.left,rect.centerY());c.concat(matrix);}if(image!=null){float scale=Math.min(rect.width()/image.getWidth(),rect.height()/image.getHeight()),w=image.getWidth()*scale,h=image.getHeight()*scale;RectF fitted=new RectF(rect.centerX()-w/2,rect.centerY()-h/2,rect.centerX()+w/2,rect.centerY()+h/2);paint.setColor(Color.WHITE);paint.setAlpha(!entering&&!hasDestination?Math.round(255*(1-progress)):255);c.drawBitmap(image,null,fitted,paint);}c.restoreToCount(pose);if(complete&&hasDestination&&SystemClock.uptimeMillis()-started>550){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setColor(0xfffb7299);float x=rect.centerX(),y=rect.bottom-Math.min(26,rect.height()*.12f);c.drawArc(x-10,y-10,x+10,y+10,SystemClock.uptimeMillis()%900*.4f,250,false,paint);paint.setStyle(Paint.Style.FILL);postInvalidateOnAnimation();}checkReady();}
 }

 final class Tile extends FrameLayout implements TextureView.SurfaceTextureListener,SurfaceHolder.Callback {
  final Video video;final TextureView texture;final SurfaceView hdrView;final MemcView memcView;final Overlay overlay;final PlaybackStatusView playbackStatus;long statusStartedAt,bufferingStartedAt;boolean refreshingCloud,warmupRewound;long rifeFirstBaseline,warmupRewindAt;int warmupPosition;Surface surface,fullSurface;PlayerEngine mp,pendingPlayer;StreamSpec pendingSpec;boolean lowResStartup,upgradeFailed,pendingReady,pendingFrame,upgradeHolding;int upgradeAlignments;int upgradeGeneration;long startupAt,upgradeHoldAt,pendingOutputAt;StreamSpec openedSpec;Bitmap heldFrame;boolean frameShown,decodedFrame,warming,buffering,memcBypassed;String memcReason="";int resumeAt=-1;ValueAnimator boundsAnimator;
  float startTouchX,startTouchY;volatile boolean retired;boolean ready,full,selected,completed,loadingMedia;int slot;long lastSeek,prepareStarted,lastSaved;String opened="";
  boolean decodeScheduled,reloadPending;long decodeQueuedAt,decodeStartedAt,firstFrameAt;
  final Runnable decode=()->{decodeScheduled=false;decodeStartedAt=SystemClock.uptimeMillis();if(shouldDecode()&&(mp==null||reloadPending||lowResStartup&&!upgradeFailed&&pendingPlayer==null)&&!loadingMedia)ensureMedia(this);};
  Tile(Context c,Video v){super(c);video=v;setContentDescription(v.title+"，"+v.up+"，点击播放");setClipChildren(true);GradientDrawable bg=new GradientDrawable();bg.setColor(colors[Math.abs(v.id.hashCode())%colors.length]);bg.setCornerRadius(14);setBackground(bg);setClipToOutline(true);texture=new TextureView(c);texture.setOpaque(false);texture.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),10*phoneCardUnit());}});texture.setSurfaceTextureListener(this);addView(texture,new LayoutParams(-1,-1));hdrView=new SurfaceView(c);hdrView.getHolder().addCallback(this);hdrView.setVisibility(GONE);addView(hdrView,new LayoutParams(-1,-1,Gravity.CENTER));memcView=new MemcView(c,new MemcView.Listener(){public void inputReady(Surface input){if(full&&memcActive())scheduleDecode();}public void inputLost(){if(memcActive())release();}public void failed(String reason){if(!retired&&memcActive()){log("MEMC_FAIL "+reason);memcReason=reason;memcBypassed=true;switchMemc(false);message("插帧处理失败，已恢复普通播放");}}});memcView.setVisibility(GONE);addView(memcView,new LayoutParams(-1,-1,Gravity.CENTER));overlay=new Overlay(c);addView(overlay,new LayoutParams(-1,-1));playbackStatus=new PlaybackStatusView(c,this::retryPlayback,()->{playerControls.hide();if(phoneVideoFullscreen())setPhoneFullscreen(false);features.close();back();});addView(playbackStatus,new LayoutParams(-1,-1));setOnClickListener(w->{if(playing){playerControls.toggle();return;}int i=videos.indexOf(video);if(i>=0){if(phoneBrowsePage()){browse.select(i);play();}else if(browse.selected==i)play();else{browse.select(i);render(true);}}});}
  void pressTile(boolean pressed,float x,float y){if(second!=null||full)return;animate().cancel();setCameraDistance(Math.max(getWidth(),getHeight())*12);float rx=pressed?(y/Math.max(1,getHeight())-.5f)*-5:0,ry=pressed?(x/Math.max(1,getWidth())-.5f)*5:0;animate().scaleX(pressed?.965f:1).scaleY(pressed?.965f:1).rotationX(rx).rotationY(ry).setDuration(pressed?90:170).setInterpolator(new PathInterpolator(.2f,.7f,.2f,1)).start();}
  @Override public boolean onTouchEvent(MotionEvent e){if(full)return playerControls.touch(this,e);int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){startTouchX=e.getX();startTouchY=e.getY();if(second==null)ensureMedia(this,true);pressTile(true,startTouchX,startTouchY);return true;}if(action==MotionEvent.ACTION_UP){pressTile(false,0,0);if(Math.hypot(e.getX()-startTouchX,e.getY()-startTouchY)<ViewConfiguration.get(getContext()).getScaledTouchSlop()*2)performClick();return true;}if(action==MotionEvent.ACTION_CANCEL)pressTile(false,0,0);return true;}
  @Override public boolean performClick(){super.performClick();return true;}
  void retryPlayback(){
   if(retired||!full)return;
   if(video.cloud()){
    XunleiLibrary.Record record=cloud.library()==null?null:cloud.library().get(video.cloudAccount,video.cloudFile,video.cloudSpace);
    if(record==null){video.error="云盘记录无法读取，请返回云盘重新选择";updatePlaybackStatus();return;}
    release();refreshingCloud=true;video.error="";statusStartedAt=SystemClock.uptimeMillis();cloud.playRecord(record);updatePlaybackStatus();return;
   }
   int saved=ready&&mp!=null?position():Math.max(0,getPreferences(0).getInt("position_"+video.mediaKey(),0));video.error="";video.stream="";video.fullSpec=null;video.previewSpec=null;statusStartedAt=SystemClock.uptimeMillis();resumeAt=saved;release();paused=false;requestAudio();scheduleDecode();updatePlaybackStatus();
  }
  void updatePlaybackStatus(){
   if(!full||retired||features!=null&&features.active()&&!features.lowerRoot){playbackStatus.hide();return;}
   long now=SystemClock.uptimeMillis();boolean cloudPending=video.cloud()&&cloud.playbackPending;
   String failure=!video.error.isEmpty()?video.error:video.cloud()?cloud.playbackError:"";
   if(!failure.isEmpty()&&!cloudPending){playbackStatus.state("暂时无法播放",failure+"。加载已停止，请重试或返回。",0,false);return;}
   if(cloudPending||!ready||!frameShown){if(statusStartedAt==0)statusStartedAt=now;String title=cloudPending?"正在刷新播放地址":!ready?"正在加载视频":"正在准备插帧画面";String message=now-statusStartedAt>=15000?"加载较慢，可继续等待，也可以点击重试。":cloudPending?"正在重新获取原文件的可用地址。":!ready?"正在连接原片并读取媒体信息，请稍候。":"正在准备首幅画面，播放进度会暂时保持。";playbackStatus.state(title,message,now-statusStartedAt,true);return;}
   if(memcActive()&&memcView.adjusting()&&!paused&&!completed){if(statusStartedAt==0)statusStartedAt=now;playbackStatus.state("正在自动调整插帧","正在选择更适合当前设备的处理方式，请稍候。",now-statusStartedAt,true);return;}statusStartedAt=0;if(buffering&&!paused){if(bufferingStartedAt==0)bufferingStartedAt=now;if(now-bufferingStartedAt>=1200){playbackStatus.state("正在缓冲",now-bufferingStartedAt>=15000?"网络缓冲较慢，可继续等待或重试连接。":"正在获取后续画面，连接恢复后会继续播放。",now-bufferingStartedAt,true);return;}}else bufferingStartedAt=0;
   playbackStatus.hide();
  }
  boolean shouldDecode(){return active&&!retired&&!refreshingCloud&&!phonePersonalPage()&&(features==null||!features.qrMode)&&(full||!video.local()&&(allPreviews||selected))&&video.error.isEmpty();}
  void scheduleDecode(){if(!shouldDecode()){release();return;}if((mp==null||reloadPending||lowResStartup&&!upgradeFailed&&pendingPlayer==null)&&!loadingMedia&&!decodeScheduled){decodeScheduled=true;decodeQueuedAt=SystemClock.uptimeMillis();ui.postDelayed(decode,full||selected||second==null?0:80);}}
  void cancelMotion(){animate().cancel();setScaleX(1);setScaleY(1);setRotationX(0);setRotationY(0);if(boundsAnimator!=null){boundsAnimator.cancel();boundsAnimator=null;}}
  void moveTo(float x,float y,int w,int h,boolean animated,boolean fresh){cancelMotion();float ox=getX(),oy=getY(),oa=getAlpha();int ow=getLayoutParams().width,oh=getLayoutParams().height;if(!animated||fresh){setAlpha(1);getLayoutParams().width=w;getLayoutParams().height=h;setX(x);setY(y);if(ow!=w||oh!=h)requestLayout();if(fresh&&animated){setAlpha(0);setY(y+20);animate().alpha(1).y(y).setDuration(motionMs).setInterpolator(new PathInterpolator(.22f,.68f,.18f,1)).start();}return;}
   boundsAnimator=ValueAnimator.ofFloat(0,1);boundsAnimator.setDuration(motionMs);boundsAnimator.setInterpolator(new PathInterpolator(.22f,.68f,.18f,1));boundsAnimator.addUpdateListener(a->{float f=(float)a.getAnimatedValue();setAlpha(oa+(1-oa)*f);setX(ox+(x-ox)*f);setY(oy+(y-oy)*f);if(ow!=w||oh!=h){getLayoutParams().width=Math.round(ow+(w-ow)*f);getLayoutParams().height=Math.round(oh+(h-oh)*f);requestLayout();}});boundsAnimator.start();}
  // Supply a detached consumer before an offscreen TextureView is drawn. The
  // same buffer queue and player become visible later; no source/surface switch.
  void prepareOffscreenSurface(){if(surface!=null||texture.isAvailable())return;SurfaceTexture pending=new SurfaceTexture(false);pending.setDefaultBufferSize(Math.max(1,Math.round(360*video.aspect())),360);texture.setSurfaceTexture(pending);surface=new Surface(pending);}
  @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){surface=new Surface(s);if(!full)scheduleDecode();}
  @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){fitVideo();}
  @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){if(!full)release();Surface old=surface;surface=null;PlayerEngine.releaseSurface(old,s);return false;}
  @Override public void onSurfaceTextureUpdated(SurfaceTexture s){if(!full&&ready&&!frameShown){showPreviewFrame();ui.post(()->{if(!retired&&!full&&gallery!=null&&phoneBrowsePage()){Bitmap frame=captureFrame(360);if(frame!=null)gallery.previewFrames.put(video.tileKey(),frame);}});}}
  @Override public void surfaceCreated(SurfaceHolder holder){fullSurface=holder.getSurface();if(full&&!memcActive())scheduleDecode();}
  @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){if(mp!=null)mp.outputSize(width,height);fitVideo();}
  @Override public void surfaceDestroyed(SurfaceHolder holder){if(full&&!memcActive())release();fullSurface=null;}
  boolean memcActive(){return full&&memcEnabled&&!memcBypassed;}
  Surface outputSurface(){return full?(memcActive()?memcView.inputSurface():fullSurface):surface;}
  void switchMemc(boolean on){if(!full)return;resumeAt=ready?position():Math.max(0,resumeAt);if(lowResStartup)retainFrame();release();texture.setVisibility(INVISIBLE);memcView.selectBackend(MemcView.selected(MainActivity.this));if(on){memcBypassed=false;memcReason="";}hdrView.setVisibility(memcActive()?GONE:VISIBLE);memcView.setVisibility(memcActive()?VISIBLE:GONE);scheduleDecode();}
  void openSpec(StreamSpec spec){if(lowResStartup){prepareUpgrade(spec);return;}if(!shouldDecode()||outputSurface()==null||mp!=null&&!reloadPending)return;loadingMedia=true;openedSpec=spec;frameShown=decodedFrame=false;warmupRewound=false;warmupRewindAt=0;rifeFirstBaseline=memcView.counters()[2];prepareStarted=SystemClock.uptimeMillis();try{
   reloadPending=false;if(mp!=null){mp.audio(full);mp.speed(full?playbackSpeed:1f);mp.replaceSource(spec);return;}
   mp=new PlayerEngine(getContext(),outputSurface(),spec,full,new PlayerEngine.Events(){
    public void prepared(){playerPrepared();}
    public void size(int w,int h){playerSize(w,h);}
    public void frame(){playerFrame();}
    public void buffering(boolean value){buffering=value;}
    public void ended(){playerEnded();}
    public void error(String error){playerError(error);}
   });
  }catch(Exception e){video.error="播放失败，X 重试";log("MEDIA_OPEN "+e.getClass().getSimpleName());release();}}
    void playerPrepared(){if(retired||mp==null)return;if(memcActive()&&(mp.isHdr()&&!mp.supportsHdrInterpolation()||mp.getFrameRate()>=45)){memcBypassed=true;memcReason=mp.isHdr()?"HDR 保持原生播放":"高帧率视频保持原生播放";switchMemc(false);message(memcReason);return;}ready=true;loadingMedia=false;completed=false;video.error="";rememberDimensions(mp.getVideoWidth(),mp.getVideoHeight());if(memcActive()){memcView.videoSize(mp.getVideoWidth(),mp.getVideoHeight());memcView.sourceFrameRate(mp.getFrameRate());}fitVideo();if(full)seekSaved();applyPlayback();if(controls!=null){controls.syncDetails();controls.invalidate();}}
    void playerSize(int w,int h){if(retired)return;rememberDimensions(w,h);if(memcActive()){memcView.videoSize(w,h);memcView.sourceFrameRate(mp==null?0:mp.getFrameRate());}fitVideo();}
    boolean waitingForRifeFrame(){return memcActive()&&MemcView.RIFE.equals(memcView.backend())&&(memcView.counters()[2]<=rifeFirstBaseline||warmupRewound&&(SystemClock.uptimeMillis()-warmupRewindAt<150||mp!=null&&Math.abs(position()-warmupPosition)>1500));}
    void playerFrame(){if(retired||mp==null)return;decodedFrame=true;if(!full){if(warming)applyPlayback();return;}if(waitingForRifeFrame())return;if(memcActive()&&MemcView.RIFE.equals(memcView.backend())&&!mp.canPrepareWhilePaused()&&!warmupRewound){warmupRewound=true;warmupRewindAt=SystemClock.uptimeMillis();rifeFirstBaseline=memcView.counters()[2];mp.seekTo(warmupPosition,MediaPlayer.SEEK_CLOSEST);return;}frameShown=true;firstFrameAt=SystemClock.uptimeMillis();if(full&&video.announceQuality){video.announceQuality=false;}if(heldFrame!=null){heldFrame.recycle();heldFrame=null;}overlay.invalidate();applyPlayback();if(gallery!=null&&gallery.reveal!=null)gallery.reveal.checkReady();}
    void playerEnded(){if(mp==null||retired)return;if(full){completed=true;paused=true;playerControls.show();}else{mp.seekTo(0,0);mp.start();}}
    void playerError(String error){if(retired)return;video.error=error.contains("UNSUPPORTED_VIDEO")?"暂不支持这个视频的编码":error.contains("IO_")||error.contains("connect")?"原片连接失败":error.contains("DECOD")?"视频解码失败":"播放器无法打开原片";log("MEDIA_ERROR "+video.id+" "+error);release();updatePlaybackStatus();}

  void showPreviewFrame(){frameShown=true;firstFrameAt=SystemClock.uptimeMillis();if(heldFrame!=null){heldFrame.recycle();heldFrame=null;}overlay.invalidate();if(gallery!=null&&gallery.reveal!=null)gallery.reveal.checkReady();}
  Bitmap previewFallback(){if(heldFrame!=null)return heldFrame;Bitmap frame=gallery==null?null:gallery.previewFrames.get(video.tileKey());if(!full&&phoneBrowsePage()&&frame!=null)return frame;Bitmap cover=covers.get(video.id);if(!full&&phoneBrowsePage()&&cover!=null&&Math.abs(cover.getWidth()/(float)cover.getHeight()/video.aspect()-1)>.12f)return null;return cover;}
  // TextureView bitmap copies the buffer, not the view's letterbox transform. Request content aspect directly.
  Bitmap captureFrame(){return captureFrame(720);}
  Bitmap captureFrame(int edge){if(!texture.isAvailable()||!frameShown)return null;try{float aspect=video.aspect();int width=Math.max(1,Math.round(Math.min(texture.getWidth(),Math.min(edge,edge*aspect)))),height=Math.max(1,Math.round(width/aspect));return texture.getBitmap(width,height);}catch(RuntimeException e){return null;}}

  void retainFrame(){Bitmap next=captureFrame();if(next!=null){if(heldFrame!=null)heldFrame.recycle();heldFrame=next;}}
  void reloadSource(int position){if(lowResStartup){resumeAt=position;release();texture.setVisibility(INVISIBLE);scheduleDecode();return;}resumeAt=position;prepareStarted=SystemClock.uptimeMillis();if(gallery==null||gallery.reveal==null)retainFrame();if(mp!=null){mp.pause();ready=false;frameShown=false;loadingMedia=false;reloadPending=true;}else release();scheduleDecode();overlay.invalidate();}
  void rememberDimensions(int w,int h){if(w<=0||h<=0)return;boolean old=video.isPortrait();float ratio=video.aspect();video.setDecoderDimensions(w,h);if(full&&phoneFullscreen)orientPhoneFullscreen();if(old!=video.isPortrait()||Math.abs(ratio-video.aspect())>.02f)scheduleAspectLayout();}
  float phoneCardUnit(){return gallery==null?1:PhonePreviewLayout.unit(gallery.getWidth(),gallery.feedViewport);}
  float captionHeight(int w,int h){return full?0:second!=null?HandheldGrid.captionHeight(w,h):phoneBrowsePage()?PhonePreviewLayout.captionHeight(phoneCardUnit()):0;}
  float pictureHeight(){return Math.max(1,getHeight()-captionHeight(getWidth(),getHeight()));}
  @Override protected void onMeasure(int widthSpec,int heightSpec){int w=MeasureSpec.getSize(widthSpec),h=MeasureSpec.getSize(heightSpec);texture.getLayoutParams().height=!full?Math.max(1,Math.round(h-captionHeight(w,h))):-1;
   // A preview TextureView can report its resize while this Tile is still in
   // layout. Size playback children from the NEW measure specs, never the old
   // getWidth/getHeight observed by asynchronous texture/surface callbacks.
   if(full&&w>0&&h>0){float aspect=video.aspect();int fw=Math.max(1,Math.min(w,Math.round(h*aspect))),fh=Math.max(1,Math.min(h,Math.round(w/aspect)));for(View view:new View[]{hdrView,memcView}){LayoutParams lp=(LayoutParams)view.getLayoutParams();lp.width=fw;lp.height=fh;lp.gravity=Gravity.CENTER;}}
   super.onMeasure(widthSpec,heightSpec);
  }
  @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);if(full)transformPlayback();}
  void transformPlayback(){float zoom=phonePictureScale(this);for(View view:new View[]{hdrView,memcView}){float w=view.getWidth(),h=view.getHeight(),x=0,y=0;if(phoneVideoFullscreen()&&phonePictureZoom>0){float maxX=Math.max(0,(w*zoom-getWidth())/2f),maxY=Math.max(0,(h*zoom-getHeight())/2f);x=Math.max(-maxX,Math.min(maxX,phonePictureOffsetX));y=Math.max(-maxY,Math.min(maxY,phonePictureOffsetY));}view.setPivotX(w/2);view.setPivotY(h/2);view.setScaleX(zoom);view.setScaleY(zoom);view.setTranslationX(x);view.setTranslationY(y);}}
  @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);post(this::fitVideo);}
  void fitVideo(){if(mp==null||!ready)return;try{int vw=mp.getVideoWidth(),vh=mp.getVideoHeight();if(vw==0||vh==0)return;if(full){requestLayout();transformPlayback();if(!lowResStartup)return;}if(texture.getWidth()==0)return;float w=texture.getWidth(),h=texture.getHeight();float[] scale=PreviewLayout.videoScale(w,h,video.aspect());Matrix matrix=new Matrix();matrix.setScale(scale[0],scale[1],w/2,h/2);texture.setTransform(matrix);}catch(Exception ignored){}}
  void enterFull(){video.error="";
   if(second==null&&ready&&frameShown&&mp!=null&&!video.local()){
    lowResStartup=true;upgradeFailed=upgradeHolding=false;upgradeAlignments=0;upgradeHoldAt=pendingOutputAt=0;startupAt=SystemClock.uptimeMillis();pendingReady=false;texture.setVisibility(VISIBLE);texture.bringToFront();overlay.bringToFront();hdrView.setVisibility(memcActive()?GONE:VISIBLE);memcView.setVisibility(memcActive()?VISIBLE:GONE);seekSaved();applyPlayback();fitVideo();scheduleDecode();return;
   }
   retainFrame();release();texture.setVisibility(INVISIBLE);hdrView.setVisibility(memcActive()?GONE:VISIBLE);memcView.setVisibility(memcActive()?VISIBLE:GONE);scheduleDecode();
  }
  void upgradeFailed(String reason){upgradeGeneration++;if(pendingPlayer!=null){pendingPlayer.release();pendingPlayer=null;}pendingSpec=null;pendingFrame=pendingReady=upgradeHolding=false;loadingMedia=false;upgradeFailed=true;applyPlayback();video.qualityNote="暂时保持 "+(openedSpec==null?"当前画质":openedSpec.label)+"，可从画质菜单重试";log("STARTUP_UPGRADE_WAIT "+reason);}
  void prepareUpgrade(StreamSpec spec){
   if(!lowResStartup||upgradeFailed||pendingPlayer!=null||!shouldDecode()||outputSurface()==null)return;
   pendingSpec=spec;pendingOutputAt=memcActive()?memcView.counters()[2]:0;final int token=++upgradeGeneration;pendingReady=pendingFrame=false;final PlayerEngine[] warmed=new PlayerEngine[1];
   pendingPlayer=new PlayerEngine(getContext(),outputSurface(),spec,true,position(),new PlayerEngine.Events(){
    boolean current(){return !retired&&token==upgradeGeneration&&(pendingPlayer==warmed[0]||mp==warmed[0]);}
    public void prepared(){if(!current())return;if(!lowResStartup){playerPrepared();return;}PlayerEngine next=pendingPlayer;
     if(memcActive()&&(next.isHdr()&&!next.supportsHdrInterpolation()||next.getFrameRate()>=45)){memcBypassed=true;memcReason=next.isHdr()?"HDR 保持原生播放":"高帧率视频保持原生播放";pendingPlayer=null;next.release();hdrView.setVisibility(VISIBLE);memcView.setVisibility(GONE);scheduleDecode();return;}
     if(memcActive()){memcView.videoSize(next.getVideoWidth(),next.getVideoHeight());memcView.sourceFrameRate(next.getFrameRate());}pendingReady=true;next.speed(playbackSpeed);next.start();if(pendingFrame)promoteUpgrade();
    }
    public void size(int w,int h){if(!current())return;if(!lowResStartup)playerSize(w,h);else if(memcActive()){memcView.videoSize(w,h);memcView.sourceFrameRate(pendingPlayer==null?0:pendingPlayer.getFrameRate());}}
    public void frame(){if(!current())return;if(!lowResStartup)playerFrame();else{pendingFrame=true;promoteUpgrade();}}
    public void buffering(boolean value){if(current()&&!lowResStartup)buffering=value;}
    public void ended(){if(current()&&!lowResStartup)playerEnded();}
    public void error(String reason){if(!current())return;if(lowResStartup)upgradeFailed(reason);else playerError(reason);}
   });warmed[0]=pendingPlayer;
  }
  void promoteUpgrade(){
   if(!lowResStartup||pendingPlayer==null||!pendingReady||!pendingFrame||!full||memcActive()&&memcView.counters()[2]<=pendingOutputAt)return;
   // Wait for an actual output, including GPU/NPU warmup, before holding the
   // preview for one final alignment. Bound this hold independently of loading.
   if(upgradeAlignments==0&&Math.abs(pendingPlayer.getCurrentPosition()-position())>350){
    upgradeAlignments++;upgradeHolding=true;upgradeHoldAt=SystemClock.uptimeMillis();mp.pause();pendingFrame=false;
    pendingOutputAt=memcActive()?memcView.counters()[2]:0;pendingPlayer.seekTo(position(),MediaPlayer.SEEK_CLOSEST);return;
   }
   PlayerEngine preview=mp;mp=pendingPlayer;pendingPlayer=null;openedSpec=pendingSpec;pendingSpec=null;lowResStartup=upgradeHolding=false;ready=frameShown=true;loadingMedia=false;buffering=false;firstFrameAt=SystemClock.uptimeMillis();texture.setVisibility(INVISIBLE);rememberDimensions(mp.getVideoWidth(),mp.getVideoHeight());fitVideo();if(preview!=null)preview.release();applyPlayback();if(controls!=null){controls.syncDetails();controls.invalidate();}log("STARTUP_UPGRADED ms="+(SystemClock.uptimeMillis()-startupAt)+" quality="+openedSpec.quality);
  }
  void leaveFull(){release();resumeAt=-1;hdrView.setVisibility(GONE);memcView.setVisibility(GONE);texture.setVisibility(VISIBLE);scheduleDecode();}
  void seekSaved(){if(!ready||mp==null)return;try{boolean explicit=resumeAt>=0;int saved=explicit?resumeAt:video.demo?0:getPreferences(0).getInt("position_"+video.mediaKey(),video.partIndex==0?getPreferences(0).getInt("position_"+video.id,0):0);resumeAt=-1;if(!explicit&&mp.getDuration()>0&&saved>=mp.getDuration()-3000)saved=0;warmupPosition=Math.max(0,saved);mp.seekTo(warmupPosition,MediaPlayer.SEEK_CLOSEST);}catch(Exception ignored){}}
  int position(){try{return ready&&mp!=null?mp.getCurrentPosition():0;}catch(Exception e){return 0;}}
  void applyPlayback(){if(mp==null||!ready)return;try{float volume=full&&!appMuted&&(frameShown||!memcActive()||!MemcView.RIFE.equals(memcView.backend()))?1:0;mp.setVolume(volume,volume);mp.audio(full);mp.speed(full?playbackSpeed:1f);if(!active||warming||lowResStartup&&upgradeHolding||(features!=null&&features.keyboard)||(full&&(paused||subtitles.holding(this)))||(full&&!frameShown&&memcActive()&&MemcView.RIFE.equals(memcView.backend())&&mp.canPrepareWhilePaused())){if(mp.isPlaying())mp.pause();}else if(!mp.isPlaying()){if(completed){completed=false;mp.seekTo(0,MediaPlayer.SEEK_CLOSEST);}mp.start();}}catch(Exception ignored){}}
  // Container metadata may omit frame rate; PlayerEngine estimates it after decoding.
  // Recheck the full-quality player, never the temporary preview, once it is known.
  boolean bypassLateMemc(){PlayerEngine source=lowResStartup?pendingPlayer:mp;if(!memcActive()||source==null||(!source.isHdr()||source.supportsHdrInterpolation())&&source.getFrameRate()<45)return false;memcBypassed=true;memcReason=source.isHdr()?"HDR 保持原生播放":"高帧率视频保持原生播放";switchMemc(false);message(memcReason);return true;}
  void tick(){if(bypassLateMemc())return;if(lowResStartup&&pendingFrame)promoteUpgrade();if(lowResStartup&&!upgradeFailed&&(upgradeHolding&&SystemClock.uptimeMillis()-upgradeHoldAt>1500||SystemClock.uptimeMillis()-startupAt>15000))upgradeFailed("timeout");if(full&&memcActive()){memcView.sourceFrameRate(mp==null?0:mp.getFrameRate());memcView.sampleRates();}if(full&&ready&&decodedFrame&&!frameShown&&!waitingForRifeFrame())playerFrame();if(mp!=null&&(!ready||!frameShown)&&SystemClock.uptimeMillis()-prepareStarted>(video.local()?45000:20000)){video.error="视频加载超时";log("MEDIA_TIMEOUT "+video.id);release();}if(ready&&mp!=null&&!full&&position()>=10000&&SystemClock.uptimeMillis()-lastSeek>800){lastSeek=SystemClock.uptimeMillis();try{mp.seekTo(0,MediaPlayer.SEEK_CLOSEST);}catch(Exception ignored){}}if(full&&ready&&frameShown&&!video.demo&&SystemClock.uptimeMillis()-lastSaved>10000){lastSaved=SystemClock.uptimeMillis();getPreferences(0).edit().putInt("position_"+video.mediaKey(),position()).apply();if(cloud!=null)cloud.checkpoint(video,position(),mp.getDuration());}updatePlaybackStatus();overlay.invalidate();}
  void release(){upgradeGeneration++;if(pendingPlayer!=null){pendingPlayer.release();pendingPlayer=null;}pendingSpec=null;pendingReady=false;lowResStartup=upgradeHolding=false;upgradeFailed=false;ui.removeCallbacks(decode);decodeScheduled=false;reloadPending=false;ready=false;loadingMedia=false;frameShown=decodedFrame=false;if(mp!=null){PlayerEngine old=mp;mp=null;try{old.release();}catch(Exception ignored){}}if(retired&&heldFrame!=null){heldFrame.recycle();heldFrame=null;}}
  final class Overlay extends View {final Paint p=new Paint(3);final DanmakuTrack danTrack=new DanmakuTrack();long frameCount;Overlay(Context c){super(c);}void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setColor(color);p.setTextSize(size);p.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);p.setStyle(Paint.Style.FILL);c.drawText(s,x,y,p);}
   String frameRateLabel(){float source=mp==null?0:mp.getFrameRate();if(source<=0&&memcActive()&&memcView.inputFps()>0)source=memcView.inputFps();String label=source>0?String.format(Locale.ROOT,"源 %.0f fps",source):"源帧率检测中";if(memcActive()&&!lowResStartup){String backend=MemcView.RIFE.equals(memcView.backend())?"RIFE":"GL";label=source>0?String.format(Locale.ROOT,"%.0f→%.0f · ",source,source*2):"";label+=paused?backend+" 暂停":memcView.outputFps()>0?String.format(Locale.ROOT,backend+" %.0f fps",memcView.outputFps()):backend+" 预热中";}return label;}
   void drawFrameRate(Canvas c){String label=frameRateLabel();float u=playerControls.unit(Tile.this),right=getWidth()-16*u,top=14*u;p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.DEFAULT);p.setTextSize(16*u);float width=p.measureText(label)+20*u;p.setColor(0x99000000);c.drawRoundRect(right-width,top,right,top+30*u,6*u,6*u,p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.RIGHT);c.drawText(label,right-10*u,ChannelStyle.centeredBaseline(p,top,top+30*u),p);p.setTextAlign(Paint.Align.LEFT);}
   void drawGridCaption(Canvas c,float w,float pictureH){float total=getHeight(),pad=w*.029f,fs=w*.036f,available=w-2*pad;String title=(video.title.isEmpty()?"未命名视频":video.title).replace('\n',' ');p.setStyle(Paint.Style.FILL);p.setColor(selected?0xfffff8fa:Color.WHITE);c.drawRect(0,pictureH,w,total,p);p.setTypeface(Typeface.DEFAULT);p.setTextSize(fs);int first=Math.max(1,p.breakText(title,true,available,null));if(first<title.length()&&Character.isHighSurrogate(title.charAt(first-1)))first--;float y=pictureH+fs*1.35f;text(c,title.substring(0,first),pad,y,fs,ink,false);if(first<title.length())text(c,ellipsize(title.substring(first),p,fs,available),pad,y+fs*1.3f,fs,ink,false);String meta=!video.error.isEmpty()?video.error:video.up+(video.views.isEmpty()?"":"  ·  "+video.views+"播放");text(c,ellipsize(meta,p,fs*.74f,available),pad,total-fs*.62f,fs*.74f,muted,false);String duration=clockText(video.duration*1000);p.setTypeface(Typeface.DEFAULT);p.setTextSize(fs*.72f);float badge=p.measureText(duration)+fs*.7f,right=w-pad;p.setColor(0x99000000);c.drawRoundRect(right-badge,pictureH-fs*1.55f,right,pictureH-fs*.32f,fs*.22f,fs*.22f,p);text(c,duration,right-badge+fs*.35f,pictureH-fs*.64f,fs*.72f,Color.WHITE,false);if(selected){p.setColor(accent);c.drawRect(0,pictureH-2,w*Math.min(1,position()/10000f),pictureH,p);}}
   void drawPhoneCaption(Canvas c,float w,float h){float u=phoneCardUnit(),pad=4*u,fs=15*u,available=w-2*pad;String title=(video.title.isEmpty()?"未命名视频":video.title).replace('\n',' ');p.setColor(Color.WHITE);p.setStyle(Paint.Style.FILL);c.drawRect(0,h,w,getHeight(),p);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(fs);int first=p.breakText(title,true,available,null);if(first>0&&first<title.length()&&Character.isHighSurrogate(title.charAt(first-1)))first--;text(c,title.substring(0,first),pad,h+23*u,fs,ink,true);if(first<title.length())text(c,ellipsize(title.substring(first),p,fs,available),pad,h+43*u,fs,ink,true);String meta=!video.error.isEmpty()?video.error:video.up+(video.views.isEmpty()?"":" · "+video.views+"播放");text(c,ellipsize(meta,p,11*u,available),pad,h+62*u,11*u,muted,false);}
   @Override protected void onDraw(Canvas c){frameCount++;float w=getWidth(),h=pictureHeight(),unit=Math.max(1,w/430);if(!ready||!frameShown){int fallbackSave=c.save();if(!full&&phoneBrowsePage()){Path round=new Path();round.addRoundRect(new RectF(0,0,w,h),10*phoneCardUnit(),10*phoneCardUnit(),Path.Direction.CW);c.clipPath(round);}if(full){p.setColor(Color.BLACK);c.drawRect(0,0,w,h,p);}Bitmap b=previewFallback();if(b!=null){float scale=Math.min(w/b.getWidth(),h/b.getHeight());float bw=b.getWidth()*scale,bh=b.getHeight()*scale;c.drawBitmap(b,null,new RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),p);}else if(!full&&phoneBrowsePage()){p.setColor(0xffeef0f2);c.drawRect(0,0,w,h,p);}else{p.setColor(colors[Math.abs(video.id.hashCode())%colors.length]);c.drawRect(0,0,w,h,p);p.setColor(0x30ffffff);c.drawCircle(w*.78f,h*.24f,h*.3f,p);text(c,video.demo?"THOR CHANNELS":"BILIBILI",w*.11f,h*.43f,w*.08f,0xeeffffff,true);}c.restoreToCount(fallbackSave);}
    if(!full&&second!=null){drawGridCaption(c,w,h);}else if(!full&&phoneBrowsePage()){
     drawPhoneCaption(c,w,h);

    }else if(!full){
    p.setShader(new LinearGradient(0,h*.65f,0,h,new int[]{0,0xcc152837},null,Shader.TileMode.CLAMP));c.drawRect(0,h*.6f,w,h,p);p.setShader(null);float captionLift=4*Math.max(1,Math.min(gallery.getWidth(),gallery.getHeight())/720f);float fs=Math.max(13,w*(full?.025f:.04f));String title=ellipsize(video.title,p,fs,w-30*unit);text(c,title,14*unit,h-26*unit-captionLift,fs,Color.WHITE,true);String subtitle=!video.error.isEmpty()?video.error:full?(!ready?"正在打开视频…":!frameShown?"正在准备插帧画面…":paused?"已暂停 · X 继续":(buffering?"缓冲中 · ":"")+(openedSpec==null?"":openedSpec.label+" · ")+"X 暂停 · Y 更多"):ready?String.format(Locale.ROOT,"%02d / 10 · 静音预览",position()/1000):video.demo?"正在加载演示…":"正在加载 · 游客 360P";text(c,subtitle,14*unit,h-9*unit-captionLift,Math.max(10,fs*.69f),0xe8ffffff,false);
    if(!full){p.setColor(0xfffb7299);c.drawRect(0,h-2,w*Math.min(1,position()/10000f),h,p);}
    }
    if(showDan&&(selected||full)&&ready){float time=position()/1000f,size=Math.max(13,w*(full?.028f:.037f));p.setTextSize(size);p.setTypeface(Typeface.DEFAULT_BOLD);p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setShadowLayer(2,1,1,0xdd000000);danTrack.configure(video.dan,video.cid,w,h,size,danDensity,danRightward);for(DanmakuTrack.Flight f:danTrack.update(time,p::measureText))c.drawText(f.item.text,danTrack.x(f,time),danTrack.y(f),p);p.clearShadowLayer();}
    if(active&&!retired&&ready&&(!full||!paused)&&(showDan&&(selected||full)||full&&playerControls.visible))postInvalidateOnAnimation();
    if(full){subtitles.draw(c,Tile.this);playerControls.draw(c,Tile.this,p);playerControls.drawVolume(c,Tile.this,p);if(playerControls.visible)drawFrameRate(c);}
    if(!full&&second!=null){float stroke=selected?Math.max(3,w*.006f):1.2f,inset=stroke/2;p.setStyle(Paint.Style.STROKE);p.setColor(selected?accent:0xffe7e9ec);p.setStrokeWidth(stroke);c.drawRoundRect(inset,inset,w-inset,getHeight()-inset,18,18,p);p.setStyle(Paint.Style.FILL);}else if(selected&&!full&&!phoneBrowsePage()){p.setStyle(Paint.Style.STROKE);p.setColor(accent);p.setStrokeWidth(4);c.drawRoundRect(2,2,w-2,getHeight()-2,14,14,p);p.setStyle(Paint.Style.FILL);}}
  }
 }
 static String ellipsize(String s,Paint p,float size,float max){p.setTextSize(size);if(p.measureText(s)<=max)return s;int n=p.breakText(s,true,Math.max(0,max-p.measureText("…")),null);return s.substring(0,n)+"…";}

 interface VolumeAccess {int value();int max();void set(int value);}
 final class PlaybackControls {
  float pictureGestureScale;
  ScaleGestureDetector pictureScale;
  GestureDetector tapDetector;
  boolean suppressTapUntilUp,picturePanning,featureVideoDragged;
  float panLastX,panLastY,pictureFocusX,pictureFocusY,featureDownX,featureDownY;
  void ensureTapDetector(){
   if(tapDetector!=null)return;
   tapDetector=new GestureDetector(MainActivity.this,new GestureDetector.SimpleOnGestureListener(){
    @Override public boolean onDown(MotionEvent event){return true;}
    @Override public boolean onDoubleTap(MotionEvent event){
     Tile t=tile();
     if(t==null||features.active()||!t.ready||!t.frameShown||pictureControlHit(t,event.getX(),event.getY()))return false;
     togglePause();suppressTapUntilUp=true;return true;
    }
   });
   tapDetector.setIsLongpressEnabled(false);
  }
  void ensurePictureScale(){if(pictureScale!=null)return;pictureScale=new ScaleGestureDetector(MainActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
   @Override public boolean onScaleBegin(ScaleGestureDetector detector){Tile t=tile();if(t==null)return false;pictureGestureScale=phonePictureScale(t);pictureFocusX=detector.getFocusX();pictureFocusY=detector.getFocusY();cancelTouch();dragged=true;return true;}
   @Override public boolean onScale(ScaleGestureDetector detector){pictureGestureScale=Math.max(1,Math.min(4,pictureGestureScale*detector.getScaleFactor()));setPhonePictureZoom(pictureGestureScale,detector.getFocusX(),detector.getFocusY());panPhonePicture(detector.getFocusX()-pictureFocusX,detector.getFocusY()-pictureFocusY);pictureFocusX=detector.getFocusX();pictureFocusY=detector.getFocusY();return true;}
   @Override public void onScaleEnd(ScaleGestureDetector detector){if(phonePictureZoom<=1.04f){pictureGestureScale=1;setPhonePictureZoom(1,detector.getFocusX(),detector.getFocusY());}}
  });}
  boolean visible,scrubbing;int focus;long hideAt;int scrubPosition;float downX,downY;boolean wasVisible,dragged;
  int touchGesture;boolean touchPaused;int touchPosition,touchPointer;float touchLevel;String levelLabel="音量";
  VolumeAccess volumeAccess=new VolumeAccess(){public int value(){return audio.getStreamVolume(AudioManager.STREAM_MUSIC);}public int max(){return audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);}public void set(int value){audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);}};
  Window contentWindow(){return second!=null&&gallery.getContext()!=MainActivity.this?second.getWindow():getWindow();}
  float brightness(){float v=contentWindow().getAttributes().screenBrightness;if(v<0)try{v=android.provider.Settings.System.getInt(getContentResolver(),android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f;}catch(Exception e){v=.5f;}return Math.max(.02f,Math.min(1,v));}
  void brightness(float v){Window w=contentWindow();WindowManager.LayoutParams lp=w.getAttributes();lp.screenBrightness=Math.max(.02f,v);w.setAttributes(lp);}
  void cancelTouch(){if(touchGesture==PlaybackGesture.SEEK){paused=touchPaused;Tile t=tile();if(t!=null)t.applyPlayback();}touchGesture=0;scrubbing=false;}
  boolean stickScrubbing,pausedBeforeStick;float volumeFrom,volumeTarget;long volumeChanged,volumeUntil;
  void moveStickSeek(int direction,boolean first){Tile t=tile();if(t==null||!t.ready||duration(t)<=0)return;if(!stickScrubbing){stickScrubbing=true;scrubbing=true;scrubPosition=t.position();pausedBeforeStick=paused;paused=true;t.applyPlayback();}scrubPosition=ControllerStick.seek(scrubPosition,direction*(first?5000:2000),duration(t));focus=1;show();}
  void finishStickSeek(boolean commit){if(!stickScrubbing)return;Tile t=tile();stickScrubbing=false;scrubbing=false;paused=pausedBeforeStick;if(t!=null){if(commit)commitSeek(t,scrubPosition);t.applyPlayback();}show();}
  float displayedVolume(){float t=Math.max(0,Math.min(1,(SystemClock.uptimeMillis()-volumeChanged)/160f));return volumeFrom+(volumeTarget-volumeFrom)*(1-(1-t)*(1-t));}
  void showVolume(int value,int max){levelLabel="音量";showLevel(value,max);}
  void showLevel(int value,int max){volumeFrom=volumeUntil>SystemClock.uptimeMillis()?displayedVolume():value/(float)Math.max(1,max);volumeTarget=value/(float)Math.max(1,max);volumeChanged=SystemClock.uptimeMillis();volumeUntil=volumeChanged+1300;redraw();}
  void drawVolume(Canvas c,Tile t,Paint p){long now=SystemClock.uptimeMillis();if(now>=volumeUntil)return;float u=unit(t),w=t.getWidth(),h=t.getHeight(),alpha=Math.min(1,(volumeUntil-now)/220f),x=w/2,y=h*.28f;c.save();p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(Color.argb((int)(210*alpha),28,36,45));c.drawRoundRect(x-115*u,y-29*u,x+115*u,y+39*u,14*u,14*u,p);p.setColor(Color.argb((int)(255*alpha),255,255,255));p.setTypeface(Typeface.DEFAULT);p.setTextSize(19*u);p.setTextAlign(Paint.Align.CENTER);c.drawText(levelLabel+" "+Math.round(volumeTarget*100)+"%",x,y-2*u,p);p.setTextAlign(Paint.Align.LEFT);p.setColor(Color.argb((int)(110*alpha),255,255,255));c.drawRoundRect(x-87*u,y+15*u,x+87*u,y+20*u,3*u,3*u,p);p.setColor(Color.argb((int)(255*alpha),251,114,153));c.drawRoundRect(x-87*u,y+15*u,x-87*u+174*u*displayedVolume(),y+20*u,3*u,3*u,p);c.restore();if(active)t.overlay.postInvalidateOnAnimation();}
  Tile tile(){return gallery==null||current()==null?null:gallery.tiles.get(current().tileKey());}
  void show(){visible=true;hideAt=SystemClock.uptimeMillis()+4000;redraw();}
  void hide(){cancelTouch();visible=false;scrubbing=false;redraw();}
  void redraw(){Tile t=tile();if(t!=null)t.overlay.invalidate();}
  void tick(){if(visible&&!scrubbing&&!paused&&(features==null||!features.active())&&SystemClock.uptimeMillis()>hideAt)hide();}
  void toggle(){if(visible)hide();else{focus=0;show();}}
  void confirm(){if(!visible){focus=0;show();return;}activate(focus);}
  void cycle(int direction){int[] order=phoneFullscreenSupported()?new int[]{0,2,3,4,5,6,8,9,7,1}:new int[]{0,2,3,4,5,6,9,7,1};if(!visible){focus=direction>0?order[0]:order[order.length-1];show();return;}if(LiveSubtitles.available(tile())){order=Arrays.copyOf(order,order.length+1);order[order.length-1]=10;}int at=0;for(int i=0;i<order.length;i++)if(order[i]==focus)at=i;focus=order[Math.floorMod(at+direction,order.length)];show();}
  void activate(int item){focus=item;show();if(current()!=null&&current().local()&&(item==4||item==5||item==7)){hide();features.playbackInfo();return;}if(item==0)togglePause();else if(item==2){hide();features.quality();}else if(item==3){hide();features.speed();}else if(item==4){hide();features.danmaku();}else if(item==6){hide();features.home();}else if(item==5){hide();features.cacheCurrent(current());}else if(item==7){hide();features.writeDanmaku();}else if(item==8){togglePhoneFullscreen();}else if(item==9){toggleMemc();}else if(item==10){subtitles.toggle(tile());}}
  boolean key(int k){if(!visible)return false;
   if(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT){int d=k==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;if(focus==1)seekCommand(d);else moveFocus(d);show();return true;}
   if(k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_DPAD_DOWN){moveFocus(k==KeyEvent.KEYCODE_DPAD_UP?-1:1);show();return true;}return false;
  }
  void moveFocus(int delta){focus=Math.max(0,Math.min(LiveSubtitles.available(tile())?10:9,focus+delta));if(focus==8&&!phoneFullscreenSupported())focus+=delta;}
  float unit(Tile t){return Math.max(.5f,Math.min(t.getWidth()/720f,t.getHeight()/400f));}
  int duration(Tile t){return t.ready&&t.mp!=null?t.mp.getDuration():0;}
  RectF memcBounds(Tile t){float u=unit(t);return new RectF(24*u,57*u,126*u,97*u);}
  int memcColor(){return memcEnabled?0xfffb7299:0x885b616b;}
  void toggleMemc(){Tile t=tile();if(t==null||!t.full)return;boolean on=!memcEnabled;if(on&&t.mp!=null&&(t.mp.isHdr()&&!t.mp.supportsHdrInterpolation()||t.mp.getFrameRate()>=45)){message(t.mp.isHdr()?"HDR 视频保持原生播放":"高帧率视频无需插帧");return;}memcEnabled=on;getPreferences(0).edit().putBoolean("memcEnabled",on).apply();t.switchMemc(on);show();}
  RectF subtitleBounds(Tile t){float u=unit(t);return new RectF(138*u,57*u,300*u,97*u);}
  RectF danmakuInputBounds(Tile t){float u=unit(t);return new RectF(28*u,t.getHeight()-167*u,t.getWidth()-28*u,t.getHeight()-125*u);}
  boolean seekHit(Tile t,float x,float y){float u=unit(t);return duration(t)>0&&x>=24*u&&x<=t.getWidth()-24*u&&Math.abs(y-(t.getHeight()-80*u))<=23*u;}
  boolean pictureControlHit(Tile t,float x,float y){if(!visible)return false;float u=unit(t);return memcBounds(t).contains(x,y)||LiveSubtitles.available(t)&&subtitleBounds(t).contains(x,y)||danmakuInputBounds(t).contains(x,y)||y>=t.getHeight()-58*u;}
  int positionAt(Tile t,float x){float margin=28*unit(t),fraction=(x-margin)/Math.max(1,t.getWidth()-2*margin);return Math.round(Math.max(0,Math.min(1,fraction))*Math.max(0,duration(t)-1));}
  void commitSeek(Tile t,int pos){if(t.ready&&t.mp!=null&&duration(t)>0){int at=Math.max(0,Math.min(duration(t)-1,pos));t.mp.seekTo(at,MediaPlayer.SEEK_CLOSEST);t.lastSeek=SystemClock.uptimeMillis();t.completed=false;subtitles.seeked(t,at);}show();}
  boolean touch(Tile t,MotionEvent e){float x=e.getX(),y=e.getY();int action=e.getActionMasked();
   if(features!=null&&features.active()){
    if(action==MotionEvent.ACTION_DOWN){featureDownX=x;featureDownY=y;featureVideoDragged=false;}
    else if(action==MotionEvent.ACTION_MOVE&&Math.hypot(x-featureDownX,y-featureDownY)>12*unit(t))featureVideoDragged=true;
    else if(action==MotionEvent.ACTION_UP){boolean tapped=!featureVideoDragged&&Math.hypot(x-featureDownX,y-featureDownY)<=12*unit(t);featureVideoDragged=false;if(tapped){features.close();focus=0;show();}}
    else if(action==MotionEvent.ACTION_CANCEL)featureVideoDragged=false;
    return true;
   }
   ensureTapDetector();tapDetector.onTouchEvent(e);
   if(suppressTapUntilUp){if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)suppressTapUntilUp=false;return true;}
   if(phoneVideoFullscreen()){ensurePictureScale();pictureScale.onTouchEvent(e);if(pictureScale.isInProgress()||e.getPointerCount()>1){cancelTouch();dragged=true;return true;}}
   if(action==MotionEvent.ACTION_DOWN){cancelTouch();touchPointer=e.getPointerId(0);downX=x;downY=y;wasVisible=visible;dragged=false;touchPosition=t.position();touchPaused=paused;scrubbing=visible&&seekHit(t,x,y);touchLevel=downX<t.getWidth()/3f?brightness():volumeAccess.value()/(float)Math.max(1,volumeAccess.max());if(phoneVideoFullscreen()&&phonePictureZoom>1.01f&&!pictureControlHit(t,x,y)){picturePanning=true;panLastX=x;panLastY=y;dragged=true;}else if(scrubbing){focus=1;scrubPosition=positionAt(t,x);show();}return true;}
   if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_CANCEL){picturePanning=false;cancelTouch();dragged=true;return true;}
   if(e.getPointerCount()!=1||e.getPointerId(0)!=touchPointer)return true;
   if(action==MotionEvent.ACTION_MOVE){float dx=x-downX,dy=y-downY;if(Math.hypot(dx,dy)>12*unit(t))dragged=true;
    if(picturePanning){panPhonePicture(x-panLastX,y-panLastY);panLastX=x;panLastY=y;return true;}
    if(scrubbing&&touchGesture==0){scrubPosition=positionAt(t,x);show();return true;}
    if(touchGesture==0){touchGesture=PlaybackGesture.classify(downX,t.getWidth(),dx,dy,12*unit(t));if(touchGesture==PlaybackGesture.SEEK){if(duration(t)<=0){touchGesture=0;return true;}scrubbing=true;paused=true;t.applyPlayback();focus=1;}}
    if(touchGesture==PlaybackGesture.BRIGHTNESS){float value=PlaybackGesture.level(touchLevel,dy,t.getHeight());brightness(value);levelLabel="亮度";showLevel(Math.round(Math.max(.02f,value)*100),100);}
    else if(touchGesture==PlaybackGesture.VOLUME){int max=volumeAccess.max(),value=Math.round(PlaybackGesture.level(touchLevel,dy,t.getHeight())*max);if(value!=volumeAccess.value())volumeAccess.set(value);showVolume(value,max);}
    else if(touchGesture==PlaybackGesture.SEEK){scrubPosition=PlaybackGesture.seek(touchPosition,dx,t.getWidth(),duration(t));show();}return true;
   }
   if(action==MotionEvent.ACTION_UP){if(picturePanning){picturePanning=false;return true;}if(touchGesture==PlaybackGesture.SEEK){int pos=PlaybackGesture.seek(touchPosition,x-downX,t.getWidth(),duration(t));touchGesture=0;scrubbing=false;paused=touchPaused;commitSeek(t,pos);t.applyPlayback();return true;}if(touchGesture!=0){touchGesture=0;return true;}
    if(scrubbing){scrubbing=false;commitSeek(t,positionAt(t,x));return true;}if(dragged)return true;if(!wasVisible){focus=0;show();return true;}
    float u=unit(t);if(memcBounds(t).contains(x,y)){activate(9);return true;}if(LiveSubtitles.available(t)&&subtitleBounds(t).contains(x,y)){activate(10);return true;}if(danmakuInputBounds(t).contains(x,y)){activate(7);return true;}if(y>=t.getHeight()-58*u){int[] ids=phoneFullscreenSupported()?new int[]{0,2,3,4,5,6,8}:new int[]{0,2,3,4,5,6};int cell=Math.min(ids.length-1,(int)(x/t.getWidth()*ids.length));activate(ids[cell]);}else hide();return true;}return true;
  }
  void draw(Canvas c,Tile t,Paint p){if(!visible)return;float w=t.getWidth(),h=t.getHeight(),u=unit(t);p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,h-214*u,0,h,0,0xe6000000,Shader.TileMode.CLAMP));c.drawRect(0,h-214*u,w,h,p);p.setShader(new LinearGradient(0,0,0,90*u,0xbb000000,0,Shader.TileMode.CLAMP));c.drawRect(0,0,w,90*u,p);p.setShader(null);
   p.setColor(Color.WHITE);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(23*u);c.drawText(ellipsize(t.video.title,p,23*u,w-48*u-220*u),24*u,38*u,p);
   RectF memc=memcBounds(t);p.setColor(memcColor());c.drawRoundRect(memc,20*u,20*u,p);p.setColor(memcEnabled?Color.WHITE:0xffb3b7bf);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(18*u);p.setTextAlign(Paint.Align.CENTER);c.drawText("MEMC",memc.centerX(),ChannelStyle.centeredBaseline(p,memc.top,memc.bottom),p);if(focus==9){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*u);c.drawRoundRect(memc,20*u,20*u,p);p.setStyle(Paint.Style.FILL);}p.setTextAlign(Paint.Align.LEFT);
   if(LiveSubtitles.available(t)){RectF bounds=subtitleBounds(t);p.setColor(subtitles.enabled?0xfffb7299:0x885b616b);c.drawRoundRect(bounds,20*u,20*u,p);p.setColor(Color.WHITE);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(18*u);p.setTextAlign(Paint.Align.CENTER);c.drawText(subtitles.label(),bounds.centerX(),ChannelStyle.centeredBaseline(p,bounds.top,bounds.bottom),p);if(focus==10){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*u);c.drawRoundRect(bounds,20*u,20*u,p);p.setStyle(Paint.Style.FILL);}p.setTextAlign(Paint.Align.LEFT);}
   RectF input=danmakuInputBounds(t);p.setColor(focus==7?0xff6b3e4c:0xc43d4149);c.drawRoundRect(input,21*u,21*u,p);p.setColor(0xfff0f1f3);p.setTypeface(Typeface.DEFAULT);p.setTextSize(19*u);c.drawText(t.video.local()?"查看本地播放信息":"发个弹幕吧",input.left+18*u,ChannelStyle.centeredBaseline(p,input.top,input.bottom),p);p.setTextAlign(Paint.Align.RIGHT);p.setColor(0xffffa4c0);c.drawText(t.video.local()?"ⓘ":"✎",input.right-17*u,ChannelStyle.centeredBaseline(p,input.top,input.bottom),p);p.setTextAlign(Paint.Align.LEFT);
   p.setColor(Color.WHITE);int total=duration(t),pos=scrubbing?scrubPosition:t.position();float left=28*u,right=w-left,barY=h-80*u;
   p.setTypeface(Typeface.DEFAULT);p.setTextSize(19*u);String state=!t.video.error.isEmpty()?t.video.error:!t.ready?"正在打开视频…":!t.frameShown?"正在准备画面…":t.buffering?"缓冲中":t.openedSpec==null?"":t.openedSpec.label+(t.lowResStartup?(t.upgradeFailed?" · 高清待重试":" → "+(t.pendingSpec==null?"最高画质":t.pendingSpec.label)+" 准备中"):"");
   c.drawText(clockText(pos)+" / "+clockText(total),left,h-105*u,p);p.setTextAlign(Paint.Align.RIGHT);c.drawText(state,right,h-105*u,p);p.setTextAlign(Paint.Align.LEFT);
   p.setColor(focus==1?0xffeeeeee:0x66999999);c.drawRoundRect(left,barY-3*u,right,barY+3*u,3*u,3*u,p);float end=left+(right-left)*(total>0?Math.min(1,pos/(float)total):0);p.setColor(0xfffb7299);c.drawRoundRect(left,barY-3*u,end,barY+3*u,3*u,3*u,p);c.drawCircle(end,barY,(focus==1||scrubbing?9:6)*u,p);
   String[] labels={paused?"▶ 播放":"Ⅱ 暂停",t.video.local()?"信息":"画质",playbackSpeed+"×",t.video.local()?"HDR":"弹幕",t.video.local()?"文件":"缓存","更多"};if(phoneFullscreenSupported())labels=new String[]{labels[0],labels[1],labels[2],labels[3],labels[4],labels[5],phoneFullscreen?"退出全屏":"全屏"};int[] ids=phoneFullscreenSupported()?new int[]{0,2,3,4,5,6,8}:new int[]{0,2,3,4,5,6};p.setTextSize(20*u);
   for(int i=0;i<labels.length;i++){float x=w*(i+.5f)/labels.length;if(focus==ids[i]){p.setColor(0x66fb7299);c.drawRoundRect(x-w/(labels.length*2f+.5f),h-57*u,x+w/(labels.length*2f+.5f),h-13*u,8*u,8*u,p);}p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);c.drawText(labels[i],x,ChannelStyle.centeredBaseline(p,h-57*u,h-13*u),p);if(ids[i]==5&&!t.video.local()){CacheStore.Job job=cache.job(t.video);if(job!=null){CacheRing.draw(c,p,x,h-67*u,9*u,job.progress(),job.active());if(job.active())t.overlay.postInvalidateOnAnimation();}}}p.setTextAlign(Paint.Align.LEFT);
  }
 }
 static String clockText(int ms){int s=Math.max(0,ms/1000);return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.ROOT,"%02d:%02d",s/60,s%60);}

 final class Controls extends FrameLayout {
  final DetailPane details;final CreatorUi.Pane creatorPane;final RelationsPane relationsPane;
  Bitmap navigationBitmap;ValueAnimator navigation;View navigationContent;float navigationProgress=1;int navigationDirection=1,navigationFrom,navigationTo;boolean channelNavigation;final RectF navigationClip=new RectF();final Rect navigationRegion=new Rect();final Paint navigationPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  void beginNavigation(int direction){if(second!=null){finishNavigation();return;}captureNavigation(direction,false,navigationTab(),navigationTab());animateNavigation(1,null);}
  void beginContentNavigation(View content,int direction){
   if(second!=null||content==null||content.getWidth()==0||content.getHeight()==0){finishNavigation();return;}
   Rect area=new Rect(0,0,content.getWidth(),content.getHeight());offsetDescendantRectToMyCoords(content,area);
   captureNavigation(direction,false,navigationTab(),navigationTab());if(navigationBitmap!=null){navigationContent=content;navigationClip.set(area);navigationClip.intersect(0,0,getWidth(),getHeight());animateNavigation(1,null);}
  }
  void beginChannelNavigation(int direction,int from,int to,boolean manual){captureNavigation(direction,true,from,to);if(!manual)animateNavigation(1,null);}
  void captureNavigation(int direction,boolean channel,int from,int to){
   if(getWidth()==0||getHeight()==0||!active||dead)return;float scale=Math.min(1,960f/getWidth());Bitmap before=Bitmap.createBitmap(Math.max(1,Math.round(getWidth()*scale)),Math.max(1,Math.round(getHeight()*scale)),Bitmap.Config.ARGB_8888);Canvas snapshot=new Canvas(before);snapshot.scale(scale,scale);draw(snapshot);finishNavigation();navigationBitmap=before;navigationDirection=direction;navigationProgress=0;channelNavigation=channel;navigationFrom=from;navigationTo=to;navigationClip.set(0,channel?62*getWidth()/480f:0,getWidth(),getHeight());
  }
  void animateNavigation(float target,Runnable done){if(navigationBitmap==null){if(done!=null)done.run();return;}ValueAnimator old=navigation;navigation=null;if(old!=null)old.cancel();ValueAnimator motion=ValueAnimator.ofFloat(navigationProgress,target);navigation=motion;motion.setDuration(Math.max(140,Math.round(320*Math.abs(target-navigationProgress))));motion.setInterpolator(new PathInterpolator(.22f,.68f,.18f,1));motion.addUpdateListener(v->{navigationProgress=(float)v.getAnimatedValue();postInvalidateOnAnimation();});motion.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator ended){if(navigation==motion){finishNavigation();if(done!=null)done.run();}}});motion.start();}
  void finishNavigation(){ValueAnimator old=navigation;navigation=null;if(old!=null)old.cancel();navigationProgress=1;channelNavigation=false;navigationContent=null;if(navigationBitmap!=null){navigationBitmap.recycle();navigationBitmap=null;}invalidate();}
  @Override public void draw(Canvas c){if(navigationBitmap==null){super.draw(c);return;}super.draw(c);if(navigationContent!=null){navigationRegion.set(0,0,navigationContent.getWidth(),navigationContent.getHeight());offsetDescendantRectToMyCoords(navigationContent,navigationRegion);navigationClip.set(navigationRegion);navigationClip.intersect(0,0,getWidth(),getHeight());}c.save();c.clipRect(navigationClip);c.drawColor(paper);float span=navigationClip.width(),outgoing=-navigationDirection*span*navigationProgress;c.drawBitmap(navigationBitmap,null,new RectF(outgoing,0,outgoing+getWidth(),getHeight()),navigationPaint);c.translate(navigationDirection*span*(1-navigationProgress),0);super.draw(c);c.restore();}
  boolean pagerDragging;int pagerOrigin,pagerDirection;float pullDistance;boolean refreshing;ValueAnimator refreshMotion;int refreshGeneration;long refreshStarted;int refreshRequests;
  void settlePull(float to){if(refreshMotion!=null)refreshMotion.cancel();refreshMotion=ValueAnimator.ofFloat(pullDistance,to);refreshMotion.setDuration(280);refreshMotion.setInterpolator(new PathInterpolator(.22f,.68f,.18f,1));refreshMotion.addUpdateListener(v->{pullDistance=(float)v.getAnimatedValue();postInvalidateOnAnimation();});refreshMotion.start();}
  void resetRefresh(){refreshGeneration++;refreshing=false;pullDistance=0;if(refreshMotion!=null){refreshMotion.cancel();refreshMotion=null;}invalidate();}
  void finishRefresh(){if(!refreshing){if(pullDistance>0)settlePull(0);return;}int token=refreshGeneration;ui.postDelayed(()->{if(token==refreshGeneration){refreshing=false;settlePull(0);}},Math.max(0,450-(SystemClock.uptimeMillis()-refreshStarted)));}
  void startRefresh(){if(refreshing)return;refreshing=true;refreshGeneration++;refreshRequests++;refreshStarted=SystemClock.uptimeMillis();settlePull(48);refreshCurrentFeed();}
  boolean canRefresh(){return !playing&&!features.active()&&channel>=0&&channel<=DYNAMIC&&channel!=DEMO;}
  void drawRefresh(Canvas c){if(pullDistance<.5f&&!refreshing)return;float cy=62+Math.max(19,pullDistance*.5f);p.setColor(0xfffb7299);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);float angle=refreshing?(SystemClock.uptimeMillis()%900)*.4f:-90;c.drawArc(30,cy-10,50,cy+10,angle,refreshing?270:Math.min(310,60+pullDistance*3),false,p);p.setStyle(Paint.Style.FILL);text(c,refreshing?"正在刷新…":pullDistance>=56?"松开刷新":"下拉刷新",62,cy+5,13,muted,false);if(refreshing&&active)postInvalidateOnAnimation();}
  boolean insetFeature(){return second!=null?lowerDockVisible():features.tabRoot>=0&&!playing;}
  void featureTouch(MotionEvent e){MotionEvent adjusted=MotionEvent.obtain(e);float top=insetFeature()?62*getWidth()/480f:0;adjusted.offsetLocation(0,-top);features.touch(adjusted,getWidth(),getHeight()-top);adjusted.recycle();}

  final Paint p=new Paint(3);
  float touchX,touchY,touchRows,lastRowHeight=76,displayRows;int seenRevision=-1,maxScroll;boolean dragging,trackingList;VelocityTracker velocity;final OverScroller flinger;
  long lastDownloadQuery;String downloadId="",downloadNote="";ValueAnimator rowAnimation;
  Controls(Context c){super(c);setWillNotDraw(false);flinger=new OverScroller(c);setBackgroundColor(paper);setFocusable(true);setFocusableInTouchMode(true);details=new DetailPane(MainActivity.this,c);addView(details,new FrameLayout.LayoutParams(-1,1));details.setVisibility(GONE);creatorPane=creators.new Pane(c);addView(creatorPane,new FrameLayout.LayoutParams(-1,-1));creatorPane.setVisibility(GONE);relationsPane=new RelationsPane(MainActivity.this,c);addView(relationsPane,new FrameLayout.LayoutParams(-1,-1));relationsPane.setVisibility(GONE);}
  @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);finishNavigation();float u=w/480f;FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)details.getLayoutParams();lp.topMargin=Math.round(62*u);lp.height=Math.max(1,Math.round((bodyEnd()-62)*u));details.setLayoutParams(lp);syncDetails();}
  void syncDetails(){boolean personal=features.relations.active();relationsPane.setVisibility(personal?VISIBLE:GONE);if(personal){FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)relationsPane.getLayoutParams();int top=second==null?0:Math.round(62*getWidth()/480f);if(lp.topMargin!=top){lp.topMargin=top;relationsPane.setLayoutParams(lp);}relationsPane.bind();}boolean profile=creators.active()&&!features.active();creatorPane.setVisibility(profile?VISIBLE:GONE);if(profile)creatorPane.bind();boolean show=(playing||gridBrowsing()&&current()!=null)&&!profile&&!features.active()&&(second==null||lowerTab==0);int wanted=show?VISIBLE:GONE;if(details.getVisibility()!=wanted)details.setVisibility(wanted);if(show)details.bind(current(),!playing);}
  float logicalHeight(){return getHeight()*480f/Math.max(1,getWidth());}
  float bodyEnd(){return logicalHeight();}
  float rowHeight(){return Math.max(35,(bodyEnd()-62)/browse.visibleRows);}
  void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setColor(color);p.setTextSize(size);p.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);c.drawText(s,x,y,p);}
  void round(Canvas c,float x,float y,float w,float h,int color){p.setColor(color);c.drawRoundRect(x,y,x+w,y+h,12,12,p);}
  @Override protected void onDraw(Canvas original){
   float width=getWidth(),height=getHeight();if(width==0||height==0)return;syncDetails();if(creators.active()&&!features.active()||features.relations.active()&&second==null)return;boolean root=features.active()&&insetFeature();if(features.active()&&!root){features.draw(original,width,height);return;}
   Canvas c=original;c.save();float scale=width/480f;c.scale(scale,scale);float h=logicalHeight();ChannelStyle.backdrop(c,480,h,false);p.setColor(Color.WHITE);c.drawRect(0,0,480,62,p);p.setColor(0xffeef0f2);c.drawRect(0,61,480,62,p);String[] names=second!=null?(playing?lowerPlaybackTabs:lowerTabs):playing?detailTabs:tabs;int activeTab=second!=null?lowerPage():playing?detailTab:homeTab;float tabWidth=448f/names.length;
   float indicator=playing&&lowerTab==0?details.pagePosition:channelNavigation?navigationFrom+(navigationTo-navigationFrom)*navigationProgress:activeTab;for(int i=0;i<names.length;i++){float center=16+(i+.5f)*tabWidth;String label=names[i];if(playing&&i==0){if(details.thread!=null)label="回复";else if(second==null&&details.total>0)label+=" "+DetailPane.count(details.total);}p.setTextAlign(Paint.Align.CENTER);text(c,label,center,37,second!=null?17:playing?18:15,i==activeTab?accent:0xff61666d,i==activeTab);p.setTextAlign(Paint.Align.LEFT);}
   p.setColor(accent);if(indicator<names.length){float marker=16+(indicator+.5f)*tabWidth;c.drawRoundRect(marker-12,48,marker+12,51,2,2,p);}if(second!=null){ChannelStyle.keyBadge(c,p,"L2",16,31,13);ChannelStyle.keyBadge(c,p,"R2",464,31,13);}if(!playing&&!root&&!gridBrowsing())drawRows(c);else if(gridBrowsing()&&current()==null)text(c,ellipsize(status,p,17,428),26,140,17,muted,false);c.restore();if(root&&!features.relations.active()){c.save();c.translate(0,62*scale);features.draw(c,width,height-62*scale);c.restore();}
  }
  void stopScroll(){flinger.forceFinished(true);if(rowAnimation!=null){rowAnimation.cancel();rowAnimation=null;}dragging=false;trackingList=false;if(velocity!=null){velocity.recycle();velocity=null;}}
  void syncFeed(){if(seenRevision!=browse.revision){stopScroll();seenRevision=browse.revision;displayRows=browse.offset;}}
  void followSelection(boolean animated){syncDetails();syncFeed();flinger.forceFinished(true);if(rowAnimation!=null)rowAnimation.cancel();float target=browse.offset;if(!animated||Math.abs(displayRows-target)<.001f){displayRows=target;invalidate();return;}rowAnimation=ValueAnimator.ofFloat(displayRows,target);rowAnimation.setDuration(motionMs);rowAnimation.setInterpolator(new PathInterpolator(.22f,.68f,.18f,1));rowAnimation.addUpdateListener(a->{displayRows=(float)a.getAnimatedValue();postInvalidateOnAnimation();});rowAnimation.start();}
  void scrollRows(float rows){boolean changed=browse.scrollTo(rows);displayRows=browse.offset;maybeMoreVideos(false);if(changed&&gallery!=null)gallery.render(true);postInvalidateOnAnimation();}
  @Override public void computeScroll(){if(flinger.computeScrollOffset()){if(playing||(features!=null&&features.active())){stopScroll();return;}scrollRows(flinger.getCurrY()/rowHeight());postInvalidateOnAnimation();}}
  @Override protected void onDetachedFromWindow(){stopScroll();finishNavigation();resetRefresh();super.onDetachedFromWindow();}
  void drawRows(Canvas c){
   syncFeed();float rh=rowHeight();if(Math.abs(rh-lastRowHeight)>1){stopScroll();displayRows=browse.offset;lastRowHeight=rh;}
   int begin=Math.max(0,(int)Math.floor(displayRows));c.save();c.clipRect(16,62,464,bodyEnd());c.translate(0,pullDistance);
   for(int i=begin;i<Math.min(videos.size(),begin+browse.visibleRows+1);i++){
    Video v=videos.get(i);float y=62+(i-displayRows)*rh;if(y+rh<62||y>bodyEnd())continue;boolean selected=i==browse.selected;
    if(selected){round(c,18,y+2,444,rh-5,selectionFill);p.setColor(selectionColor);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);c.drawRoundRect(18,y+2,462,y+rh-3,12,12,p);p.setStyle(Paint.Style.FILL);c.drawRoundRect(23,y+rh*.24f,27,y+rh*.76f,2,2,p);}
    float mainY=y+rh*.43f,metaY=y+rh*.74f,font=Math.min(21,rh*.32f);
    text(c,ellipsize(v.title,p,font,408),30,mainY,font,ink,true);
    String shape=v.width<=0?"比例待识别":v.isPortrait()?"竖屏":"横屏";
    String meta=v.demo?shape+" · 演示视频 · 10 秒循环":shape+" · "+v.views+"播放 · "+String.format(Locale.ROOT,"%02d:%02d",v.duration/60,v.duration%60)+" · "+v.up;
    if(gallery!=null&&i>=browse.start()&&i<browse.end()&&!gallery.previewVisible(i))meta=(second!=null&&gallery.plan.mode==PreviewLayout.Mode.MIXED?"候补 · 选中即显示 · ":"仅标题 · ")+meta;
    text(c,ellipsize(meta,p,Math.min(14,rh*.22f),408),30,metaY,Math.min(14,rh*.22f),muted,false);
   }
   if(videos.size()>browse.visibleRows){float track=bodyEnd()-62,thumb=Math.max(16,track*browse.visibleRows/videos.size()),y=62+(track-thumb)*displayRows/Math.max(1,videos.size()-browse.visibleRows);p.setColor(dragging||!flinger.isFinished()?0xfffb7299:0xffd9dce1);c.drawRoundRect(456,y,460,y+thumb,2,2,p);}
   c.restore();String note=moreVideosLabel();if(!note.isEmpty()){
    round(c,80,bodyEnd()-28,320,25,0xf0fff5f8);p.setTextAlign(Paint.Align.CENTER);text(c,ellipsize(note,p,11,294),240,bodyEnd()-11,11,0xffc76687,false);p.setTextAlign(Paint.Align.LEFT);
   }if(videos.isEmpty())text(c,ellipsize(status,p,17,428),26,140+pullDistance,17,muted,false);drawRefresh(c);
  }
  String cachedDownloadText(Video v){if(!downloadId.equals(v.id)||SystemClock.uptimeMillis()-lastDownloadQuery>1000||downloadNote.isEmpty()){downloadId=v.id;downloadNote=downloadText(v);lastDownloadQuery=SystemClock.uptimeMillis();}return downloadNote;}
  List<String> wrap(String value,int width,int size){List<String> lines=new ArrayList<>();p.setTextSize(size);p.setTypeface(Typeface.DEFAULT);for(String paragraph:value.split("\n",-1)){if(paragraph.isEmpty()){lines.add("");continue;}for(int offset=0;offset<paragraph.length();){int count=p.breakText(paragraph,offset,paragraph.length(),true,width,null);count=Math.max(1,count);lines.add(paragraph.substring(offset,offset+count));offset+=count;}}return lines;}
  @Override public boolean onTouchEvent(MotionEvent e){
   if(lowerDockVisible()){float scale=480f/Math.max(1,getWidth()),x=e.getX()*scale,y=e.getY()*scale;int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){touchX=x;touchY=y;}if(touchY<62){if(action==MotionEvent.ACTION_UP&&y<62&&Math.hypot(x-touchX,y-touchY)<15){int count=playing?lowerPlaybackTabs.length:lowerTabs.length;selectLowerPage(Math.max(0,Math.min(count-1,(int)((x-16)/(448f/count)))));}return true;}if(features.active())featureTouch(e);return true;}
   float scale=480f/Math.max(1,getWidth()),y=e.getY()*scale,x=e.getX()*scale;int action=e.getActionMasked();boolean root=features.active()&&second==null&&features.tabRoot>=0&&!playing;
   if(features.active()&&!root){stopScroll();featureTouch(e);return true;}if(!root)syncFeed();
   if(action==MotionEvent.ACTION_DOWN){boolean moving=!flinger.isFinished();stopScroll();pagerDragging=false;touchX=x;touchY=y;touchRows=displayRows;trackingList=!playing&&!root&&y>=62&&y<=bodyEnd();if(trackingList)maybeMoreVideos(true);dragging=moving&&trackingList;if(trackingList){browse.scrollTo(displayRows);velocity=VelocityTracker.obtain();velocity.addMovement(e);}if(root&&y>=62)featureTouch(e);return true;}
   float dx=x-touchX,dy=y-touchY;if(velocity!=null)velocity.addMovement(e);
   if(action==MotionEvent.ACTION_MOVE&&!playing){
    if(!pagerDragging&&!dragging&&Math.abs(dx)>12&&Math.abs(dx)>Math.abs(dy)*1.25f){pagerOrigin=navigationTab();pagerDirection=dx<0?1:-1;resetRefresh();navigateHome(Math.floorMod(pagerOrigin+pagerDirection,tabs.length),pagerDirection,true);pagerDragging=true;}
    if(pagerDragging){navigationProgress=Math.max(0,Math.min(1,-dx*pagerDirection/480f));postInvalidateOnAnimation();return true;}
   }
   if(pagerDragging&&(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)){boolean accept=action==MotionEvent.ACTION_UP&&navigationProgress>=.18f;int origin=pagerOrigin;pagerDragging=false;stopScroll();animateNavigation(accept?1:0,accept?null:()->cancelHomeDrag(origin));return true;}
   if(action==MotionEvent.ACTION_CANCEL){stopScroll();if(!refreshing)settlePull(0);return true;}
   if(root){if(y<62){if(action==MotionEvent.ACTION_UP&&Math.abs(dx)<15&&Math.abs(dy)<15){int desired=Math.min(tabs.length-1,(int)((x-16)/(448f/tabs.length)));if(desired>=0)switchTab(desired-navigationTab());}}else featureTouch(e);return true;}
   if(action==MotionEvent.ACTION_MOVE&&trackingList){if(!dragging&&Math.abs(dy)>10&&Math.abs(dy)>Math.abs(dx))dragging=true;if(dragging){if(touchRows<=.01f&&dy>0&&canRefresh()){if(!refreshing)pullDistance=Math.min(96,dy*.5f);postInvalidateOnAnimation();}else if(!refreshing){pullDistance=0;scrollRows(touchRows-dy/rowHeight());}}return true;}
   if(action==MotionEvent.ACTION_UP){
    if(trackingList&&dragging){if(dy<0&&!refreshing)maybeMoreVideos(true);if(pullDistance>0){boolean refresh=pullDistance>=56;stopScroll();if(refresh)startRefresh();else if(!refreshing)settlePull(0);return true;}if(velocity!=null&&!refreshing){velocity.computeCurrentVelocity(1000,ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity());float speed=-velocity.getYVelocity()*scale;if(Math.abs(speed)>ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity()*scale){flinger.fling(0,Math.round(browse.offset*rowHeight()),0,Math.round(speed),0,0,0,Math.round(Math.max(0,videos.size()-browse.visibleRows)*rowHeight()));postInvalidateOnAnimation();}}if(velocity!=null){velocity.recycle();velocity=null;}dragging=false;trackingList=false;return true;}
    if(velocity!=null){velocity.recycle();velocity=null;}trackingList=false;if(Math.abs(dx)>45&&Math.abs(dx)>Math.abs(dy)){switchTab(dx<0?1:-1);return true;}if(Math.abs(dy)>25){if(!playing)scrollRows(touchRows-dy/rowHeight());else move(dy<0?1:-1);return true;}
    if(y<56){int desired=Math.min(playing?1:tabs.length-1,(int)((x-16)/(448f/(playing?2:tabs.length))));if(desired>=0)switchTab(desired-(playing?detailTab:navigationTab()));return true;}if(y<62||y>bodyEnd())return true;
    if(!playing&&!refreshing&&moreVideosFailed()&&(videos.isEmpty()||y>bodyEnd()-32)){maybeMoreVideos(true);return true;}if(!playing&&!refreshing){int index=(int)Math.floor(displayRows+(y-62)/rowHeight());if(index>=0&&index<videos.size()){if(index==browse.selected)play();else{browse.select(index);render(true);}}}return true;
   }return true;
  }

 }
}
