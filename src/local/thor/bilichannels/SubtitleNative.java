package local.thor.bilichannels;

/** Loaded only in the disposable subtitle process, independently of playback/RIFE. */
final class SubtitleNative {
 static {System.loadLibrary("mpv");System.loadLibrary("thor_subtitle_audio");System.loadLibrary("thor_subtitle_asr");System.loadLibrary("thor_subtitle_translate");}
 static native long createTranslator(String model);
 static native String translate(long handle,String japanese);
 static native void closeTranslator(long handle);
 static native long createRecognizer(String models,String libraries);
 static native String recognize(long handle,short[] pcm16);
 static native void closeRecognizer(long handle);
 static native void extractAudio(String source,String target,String certificates,String userAgent,boolean cloud,int startMs,int durationMs) throws java.io.IOException;
 static native void cancelAudio();
}
