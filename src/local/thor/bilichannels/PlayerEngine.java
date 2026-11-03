package local.thor.bilichannels;
import android.content.Context;import android.os.*;import android.view.Surface;
import androidx.media3.common.*;import androidx.media3.datasource.*;import androidx.media3.exoplayer.*;import androidx.media3.exoplayer.source.*;import androidx.media3.exoplayer.hls.HlsMediaSource;
/** Media3 has one application looper, separate from drawing. UI reads immutable snapshots. */
public final class PlayerEngine {
 public interface Events{void prepared();void size(int width,int height);void frame();void buffering(boolean value);void ended();void error(String message);}
 private static final HandlerThread THREAD=new HandlerThread("thor-player-control");
 private static final Handler MEDIA;static{THREAD.start();MEDIA=new Handler(THREAD.getLooper());}
 private final Handler ui=new Handler(Looper.getMainLooper());private final Events events;private final Surface outputSurface;private final Context application;private final android.view.Display renderDisplay;
 private ExoPlayer player;private DoviPlayer dovi;private DefaultDataSource.Factory data;private StreamSpec spec;private Player.Listener listener;private int attempt,sourceRevision,preparation;private boolean notified,switchingDovi,doviFrame,doviEnded;private int sourceStart;private String doviError="";
 private volatile boolean released,softwareDecoder;private volatile int revision;private boolean desiredPlaying;private volatile boolean audioEnabled;private float desiredSpeed=1,desiredVolume;
 private static final class State {
  final long position,duration,at;final int width,height,revision;final boolean advancing,hdr,hdrMemc;final float speed,frameRate;final String format;
  State(ExoPlayer p,int revision,float estimate){position=Math.max(0,p.getCurrentPosition());long d=p.getDuration();duration=d==C.TIME_UNSET?0:Math.max(0,d);VideoSize v=p.getVideoSize();width=v.width;height=v.height;advancing=p.isPlaying();speed=p.getPlaybackParameters().speed;at=SystemClock.elapsedRealtime();this.revision=revision;Format f=p.getVideoFormat();frameRate=f!=null&&f.frameRate>0?f.frameRate:estimate;int transfer=f==null||f.colorInfo==null?0:f.colorInfo.colorTransfer;hdr=transfer==C.COLOR_TRANSFER_ST2084||transfer==C.COLOR_TRANSFER_HLG;hdrMemc=false;if(f==null)format="视频轨道尚未确定";else{String color=transfer==C.COLOR_TRANSFER_ST2084?"PQ / HDR":transfer==C.COLOR_TRANSFER_HLG?"HLG":"SDR 或未知";format=(f.sampleMimeType==null?"未知编码":f.sampleMimeType)+" · "+color;} }
  State(DoviPlayer p,int revision){position=(long)(Math.max(0,p.number("time-pos"))*1000);duration=(long)(Math.max(0,p.number("duration"))*1000);width=(int)p.number("video-params/w");height=(int)p.number("video-params/h");advancing=p.desiredPlaying()&&!p.flag("pause")&&!p.flag("paused-for-cache");speed=(float)p.number("speed");frameRate=(float)p.number("estimated-vf-fps");at=SystemClock.elapsedRealtime();this.revision=revision;hdr=p.hdr();hdrMemc=hdr;format="Dolby Vision P5 · 原片解码 / "+(hdr?"HDR10 · 10bit 输出":"SDR 色彩映射");}
 }
 private volatile float estimatedFps;private String loggedFormat;
 private volatile State state;private volatile CadenceRenderersFactory cadenceFactory;
 public PlayerEngine(Context context,Surface surface,StreamSpec initial,boolean full,Events events){this(context,surface,initial,full,0,events);}
 public PlayerEngine(Context context,Surface surface,StreamSpec initial,boolean full,int startPosition,Events events){
  this.events=events;outputSurface=surface;audioEnabled=full;Context app=context.getApplicationContext();application=app;android.view.Display display=RifeMemcView.outputDisplay(surface);if(display==null)try{display=context.getDisplay();}catch(UnsupportedOperationException ignored){}renderDisplay=display;
  MEDIA.post(()->{if(released)return;try{
   DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent(BiliApi.UA).setDefaultRequestProperties(BiliApi.headers()).setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(false);
   data=new DefaultDataSource.Factory(app,http);
   // Keep previews small; full playback uses 32 MiB for streams and 96 MiB for local high bitrate files.
   DefaultLoadControl load=new AdaptiveLoadControl();
   DefaultRenderersFactory renderers;if(RifeMemcView.handles(surface)){cadenceFactory=new CadenceRenderersFactory(app);renderers=cadenceFactory;}else renderers=new DefaultRenderersFactory(app);renderers.setEnableDecoderFallback(true);
   if(RifeMemcView.handles(surface))renderers.setMediaCodecSelector((mime,secure,tunneling)->{
    java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> codecs=androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunneling);
    if(!mime.startsWith("video/"))return codecs;
    java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> hardware=new java.util.ArrayList<>();for(androidx.media3.exoplayer.mediacodec.MediaCodecInfo codec:codecs)if(codec.hardwareAccelerated)hardware.add(codec);return hardware;
   });
   player=new ExoPlayer.Builder(app).setLooper(THREAD.getLooper()).setLoadControl(load).setRenderersFactory(renderers).build();
   player.setVideoSurface(surface);player.setVolume(0);setAudio(full);setSource(initial,0,startPosition);MEDIA.post(tick);
  }catch(RuntimeException e){emit(revision,()->events.error(e.getClass().getSimpleName()));}});
 }
 /** Media3 still sees every call (including release) on its declared application looper. */
 private void command(Runnable r){if(released)return;MEDIA.post(()->{if(released||player==null)return;try{r.run();snapshot();}catch(RuntimeException e){emit(sourceRevision,()->events.error(e.getClass().getSimpleName()));}});}
 private void emit(int epoch,Runnable r){ui.post(()->{if(!released&&revision==epoch)r.run();});}
 private void snapshot(){if(released)return;if(dovi!=null){state=new State(dovi,sourceRevision);if(!notified&&dovi.flag("@loaded")&&state.width>0){notified=true;preparation++;emit(sourceRevision,()->events.size(state.width,state.height));emit(sourceRevision,()->events.prepared());}if(!doviFrame&&dovi.flag("@frame")){doviFrame=true;emit(sourceRevision,()->events.frame());}if(!doviEnded&&dovi.flag("@ended")){doviEnded=true;emit(sourceRevision,()->events.ended());}String error=dovi.value("@error");if(error!=null&&!error.isEmpty()&&!error.equals(doviError)){doviError=error;emit(sourceRevision,()->events.error("P5 兼容解码："+error));}boolean waiting=!notified||dovi.flag("paused-for-cache");emit(sourceRevision,()->events.buffering(waiting));}else if(player!=null)state=new State(player,sourceRevision,estimatedFps);}
 private void openDovi(Format format){if(dovi!=null||switchingDovi||released)return;switchingDovi=true;int position=(int)Math.max(sourceStart,player.getCurrentPosition());player.pause();player.stop();notified=false;doviFrame=doviEnded=false;doviError="";try{boolean hdr=DoviPlayer.supportsHdr(renderDisplay);RifeMemcView.format(outputSurface,format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setColorInfo(new ColorInfo.Builder().setColorSpace(hdr?C.COLOR_SPACE_BT2020:C.COLOR_SPACE_BT709).setColorRange(C.COLOR_RANGE_FULL).setColorTransfer(hdr?C.COLOR_TRANSFER_ST2084:C.COLOR_TRANSFER_SDR).build()).build());dovi=new DoviPlayer(application,outputSurface,spec.video.get(Math.min(attempt,spec.video.size()-1)),audioEnabled,position,format.width,format.height,renderDisplay);softwareDecoder=true;dovi.volume(desiredVolume);dovi.speed(desiredSpeed);dovi.playing(desiredPlaying);snapshot();}catch(Exception|LinkageError error){emit(sourceRevision,()->events.error("P5 兼容解码初始化失败："+error.getClass().getSimpleName()));}finally{switchingDovi=false;}}
 private final Runnable tick=new Runnable(){public void run(){if(released||player==null)return;snapshot();MEDIA.postDelayed(this,50);}};
 private void setSource(StreamSpec next,int epoch,int startPosition){softwareDecoder=false;if(dovi!=null){dovi.close();dovi=null;}sourceStart=startPosition;RifeMemcView.invalidate(outputSurface);if(listener!=null)player.removeListener(listener);sourceRevision=epoch;estimatedFps=0;loggedFormat=null;final long[] timing={Long.MIN_VALUE,0,0};player.setVideoFrameMetadataListener((pts,releaseTime,format,mediaFormat)->{if(released||revision!=epoch)return;if(format!=null&&!String.valueOf(format.id).equals(loggedFormat)){loggedFormat=String.valueOf(format.id);android.util.Log.i("ThorPlayer","format="+format.sampleMimeType+" "+format.width+"x"+format.height+" bitrate="+format.bitrate+" fps="+format.frameRate);}RifeMemcView.format(outputSurface,format);RifeMemcView.frameMetadata(outputSurface,pts,releaseTime);long interval=pts-timing[0];if(timing[0]!=Long.MIN_VALUE&&interval>=10000&&interval<=100000){timing[1]+=interval;timing[2]++;if(timing[2]>=15){estimatedFps=timing[2]*1000000f/timing[1];timing[1]=timing[2]=0;}}else timing[1]=timing[2]=0;timing[0]=pts;});spec=next;attempt=0;notified=false;player.pause();listener=new Player.Listener(){
  @Override public void onTracksChanged(Tracks tracks){if(released||sourceRevision!=epoch||dovi!=null||switchingDovi||!spec.audio.isEmpty())return;Format p5=null;boolean supported=false;for(Tracks.Group group:tracks.getGroups())if(group.getType()==C.TRACK_TYPE_VIDEO)for(int i=0;i<group.length;i++){Format f=group.getTrackFormat(i);supported|=group.isTrackSupported(i);String codecs=f.codecs==null?"":f.codecs;if(f.drmInitData==null&&MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType)&&(codecs.startsWith("dvhe.05.")||codecs.startsWith("dvh1.05.")))p5=f;}if(!supported&&p5!=null)openDovi(p5);}
  @Override public void onPlaybackStateChanged(int value){if(released||sourceRevision!=epoch||dovi!=null||switchingDovi)return;snapshot();android.util.Log.i("ThorPlayer","state="+value);emit(epoch,()->events.buffering(value==Player.STATE_BUFFERING));if(value==Player.STATE_READY&&!notified){preparation++;notified=true;emit(epoch,()->events.prepared());}if(value==Player.STATE_ENDED)emit(epoch,()->events.ended());}
  @Override public void onVideoSizeChanged(VideoSize size){snapshot();emit(epoch,()->events.size(size.width,size.height));}
  @Override public void onRenderedFirstFrame(){snapshot();emit(epoch,()->events.frame());}
  @Override public void onIsPlayingChanged(boolean playing){snapshot();}
  @Override public void onPlayerError(PlaybackException error){if(released||sourceRevision!=epoch)return;long pos=player.getCurrentPosition();if(++attempt<Math.max(spec.video.size(),spec.audio.size()))prepare(pos);else if(!(error.errorCode>=4000&&error.errorCode<5000&&RifeMemcView.decoderFailed(outputSurface,error.getErrorCodeName())))emit(epoch,()->events.error(error.getErrorCodeName()));}
 };player.addListener(listener);prepare(startPosition);snapshot();}
 private MediaSource source(String uri){
  DataSource.Factory transport=data;if(spec.external){java.util.Map<String,String> headers=new java.util.HashMap<>();headers.put("Referer","https://pan.xunlei.com/");transport=new DefaultDataSource.Factory(application,new DefaultHttpDataSource.Factory().setUserAgent(BiliApi.UA).setDefaultRequestProperties(headers).setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(false));}
  String path=android.net.Uri.parse(uri).getPath();boolean hls=spec.external&&(spec.mimeType.toLowerCase(java.util.Locale.ROOT).contains("mpegurl")||path!=null&&path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8"));
  return hls?new HlsMediaSource.Factory(transport).createMediaSource(MediaItem.fromUri(uri)):new ProgressiveMediaSource.Factory(transport).createMediaSource(MediaItem.fromUri(uri));
 }
 private void prepare(long position){String video=spec.video.get(Math.min(attempt,spec.video.size()-1));MediaSource src=source(video);if(!spec.audio.isEmpty())src=new MergingMediaSource(true,true,src,source(spec.audio.get(Math.min(attempt,spec.audio.size()-1))));player.setMediaSource(src,Math.max(0,position));player.prepare();
  final int pending=++preparation;
  if(audioEnabled&&attempt+1<Math.max(spec.video.size(),spec.audio.size()))MEDIA.postDelayed(()->{
   if(released||pending!=preparation||notified)return;
   android.util.Log.i("ThorPlayer","initial CDN stalled; trying backup "+(attempt+1));attempt++;prepare(position);
  },5000);
 }
 public void replaceSource(StreamSpec next){if(released)return;int epoch=++revision;desiredPlaying=false;command(()->setSource(next,epoch,0));}
 private void setAudio(boolean enabled){if(dovi!=null)dovi.audio(enabled);else player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,!enabled).build());}
 public void audio(boolean enabled){if(audioEnabled==enabled)return;audioEnabled=enabled;command(()->setAudio(enabled));}
 public void speed(float value){if(desiredSpeed==value)return;desiredSpeed=value;RifeMemcView.speed(outputSurface,value);command(()->{if(dovi!=null)dovi.speed(value);else player.setPlaybackSpeed(value);});}
 public int getCurrentPosition(){State s=state;if(s==null||s.revision!=revision||released)return 0;long pos=s.position;if(s.advancing&&desiredPlaying)pos+=(long)((SystemClock.elapsedRealtime()-s.at)*s.speed);return (int)Math.min(Integer.MAX_VALUE,s.duration>0?Math.min(s.duration,pos):pos);}
 public int getDuration(){State s=state;return s==null||s.revision!=revision?0:(int)Math.min(Integer.MAX_VALUE,s.duration);}
 public int getVideoWidth(){State s=state;return s==null||s.revision!=revision||released?0:s.width;}public int getVideoHeight(){State s=state;return s==null||s.revision!=revision||released?0:s.height;}
 public String getVideoInfo(){State s=state;CadenceRenderersFactory factory=cadenceFactory;return s==null||s.revision!=revision?"尚未开始解码":s.format+(factory==null?"":factory.status);}
 public float getFrameRate(){State s=state;return s==null||s.revision!=revision?0:s.frameRate;}
 public boolean isHdr(){State s=state;return s!=null&&s.revision==revision&&s.hdr;}
 public boolean canPrepareWhilePaused(){return !released&&softwareDecoder;}
 public boolean canInterpolateHdr(){State s=state;return s!=null&&s.revision==revision&&s.hdrMemc;}
 public boolean supportsHdrInterpolation(){return canInterpolateHdr()&&MemcView.RIFE.equals(MemcView.selected(application));}
 public boolean isPlaying(){return !released&&desiredPlaying;}
 public void setVolume(float left,float right){if(desiredVolume==left)return;desiredVolume=left;command(()->{if(dovi!=null)dovi.volume(left);else player.setVolume(left);});}
 public void start(){if(desiredPlaying)return;desiredPlaying=true;RifeMemcView.playing(outputSurface,true);command(()->{if(dovi!=null)dovi.playing(true);else player.play();});}public void pause(){RifeMemcView.playing(outputSurface,false);if(!desiredPlaying)return;desiredPlaying=false;command(()->{if(dovi!=null)dovi.playing(false);else player.pause();});}
 public void seekTo(int position,int ignored){RifeMemcView.invalidate(outputSurface);command(()->{if(dovi!=null)dovi.seek(position);else player.seekTo(Math.max(0,position));});}
 public void outputSize(int width,int height){if(width>0&&height>0&&!RifeMemcView.handles(outputSurface))command(()->{if(dovi!=null)dovi.set("android-surface-size",width+"x"+height);});}
 public void release(){if(released)return;released=true;desiredPlaying=false;MEDIA.post(()->{MEDIA.removeCallbacks(tick);if(dovi!=null){dovi.close();dovi=null;}if(player!=null){try{player.release();}catch(RuntimeException e){android.util.Log.w("ThorPlayer","Release failed: "+e.getClass().getSimpleName());}finally{player=null;}}});}
 /** TextureView gives us ownership until all preceding player releases have detached this surface. */
 public static void afterSurfaceDetach(Runnable cleanup){MEDIA.post(cleanup);}
 public static void releaseSurface(Surface surface,android.graphics.SurfaceTexture texture){MEDIA.post(()->{if(surface!=null)surface.release();texture.release();});}
 private final class AdaptiveLoadControl extends DefaultLoadControl {
  AdaptiveLoadControl(){super(new androidx.media3.exoplayer.upstream.DefaultAllocator(true,C.DEFAULT_BUFFER_SEGMENT_SIZE),15000,15000,45000,45000,700,700,1500,1500,C.LENGTH_UNSET,false,false,0,false);}
  @Override protected int calculateTargetBufferBytes(LoadControl.Parameters parameters,androidx.media3.exoplayer.trackselection.ExoTrackSelection[] selections){return audioEnabled?(spec!=null&&spec.video.get(0).startsWith("content:")?96:32)*1024*1024:6*1024*1024;}
  @Override public boolean shouldContinueLoading(LoadControl.Parameters parameters){if(!audioEnabled&&parameters.bufferedDurationUs>=11000000)return false;return super.shouldContinueLoading(parameters);}
 }
}
