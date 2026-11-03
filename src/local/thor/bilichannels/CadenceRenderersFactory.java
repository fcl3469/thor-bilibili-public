package local.thor.bilichannels;

import android.content.Context;import android.os.Handler;import java.nio.ByteBuffer;import java.util.ArrayList;
import androidx.media3.common.Format;import androidx.media3.common.MimeTypes;import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.mediacodec.*;import androidx.media3.exoplayer.video.*;

/** RIFE surfaces retain the audio clock while bounded timestamp repair prevents near-duplicate drops. */
final class CadenceRenderersFactory extends DefaultRenderersFactory {
 volatile String status="";
 CadenceRenderersFactory(Context context){super(context);}
 @Override protected void buildVideoRenderers(Context context,int extension,MediaCodecSelector selector,boolean fallback,Handler events,VideoRendererEventListener listener,long joining,ArrayList<Renderer> out){out.add(new Video(context,selector,joining,fallback,events,listener));}
 private final class Video extends MediaCodecVideoRenderer {
  final FrameCadence cadence=new FrameCadence();boolean pending;int index,observed;long original,adjusted,previousPts=Long.MIN_VALUE;final java.util.TreeMap<Long,Integer> observedGaps=new java.util.TreeMap<>();
  Video(Context context,MediaCodecSelector selector,long joining,boolean fallback,Handler events,VideoRendererEventListener listener){super(context,selector,joining,fallback,events,listener,50);}
  @Override protected void onPositionReset(long position,boolean joining,boolean streamReset)throws ExoPlaybackException{cadence.reset();pending=false;status="";observed=0;previousPts=Long.MIN_VALUE;observedGaps.clear();super.onPositionReset(position,joining,streamReset);}
  @Override protected boolean processOutputBuffer(long position,long elapsed,MediaCodecAdapter codec,ByteBuffer buffer,int bufferIndex,int flags,int samples,long pts,boolean decodeOnly,boolean last,Format format)throws ExoPlaybackException{
   if(!pending||index!=bufferIndex||original!=pts){index=bufferIndex;original=pts;if(!decodeOnly&&observed<97){if(previousPts!=Long.MIN_VALUE){long gap=Math.round((pts-previousPts)/1000.0);observedGaps.put(gap,observedGaps.getOrDefault(gap,0)+1);}previousPts=pts;if(++observed==97)android.util.Log.i("ThorPlayer","cadence observed gaps_ms="+observedGaps+" nominal="+format.frameRate);}boolean was=cadence.active(),avc=MimeTypes.VIDEO_H264.equals(format.sampleMimeType);if(!avc)cadence.reset();adjusted=!decodeOnly&&avc?cadence.map(pts,format.frameRate):pts;pending=true;if(cadence.active()!=was){status=cadence.active()?" · 时间戳兼容":"";android.util.Log.i("ThorPlayer","cadence correction="+cadence.active()+" fps="+cadence.fps());}}
   Format timed=cadence.active()?format.buildUpon().setFrameRate(cadence.fps()).build():format;
   boolean consumed=super.processOutputBuffer(position,elapsed,codec,buffer,bufferIndex,flags,samples,adjusted,decodeOnly,last,timed);if(consumed)pending=false;return consumed;
  }
 }
}
