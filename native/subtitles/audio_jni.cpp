// Audio is decoded exclusively to a private PCM file; never to an audio device.
#include <jni.h>
#include "../vendor/mpv/client.h"
#include <atomic>
#include <chrono>
#include <stdexcept>
#include <string>

namespace {
std::atomic<unsigned> epoch{0};
std::string text(JNIEnv* env,jstring value){const char* bytes=env->GetStringUTFChars(value,nullptr);std::string result(bytes);env->ReleaseStringUTFChars(value,bytes);return result;}
}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_SubtitleNative_cancelAudio(JNIEnv*,jclass){++epoch;}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_SubtitleNative_extractAudio(JNIEnv* env,jclass,jstring source,jstring target,jstring certificates,jstring userAgent,jboolean cloud,jint start,jint duration){
 mpv_handle* player=nullptr;const unsigned request=epoch.load();
 try{
  if(start<0||duration<=0||duration>30000)throw std::runtime_error("无效的字幕音频区间");
  player=mpv_create();if(!player)throw std::runtime_error("音轨分析初始化失败");
  auto option=[&](const char* key,const std::string& value){if(mpv_set_option_string(player,key,value.c_str())<0)throw std::runtime_error(std::string("音轨分析不支持设置：")+key);};
  option("config","no");option("terminal","no");option("msg-level","all=no");option("vid","no");option("sid","no");
  option("ao","pcm");option("ao-pcm-file",text(env,target));option("ao-pcm-waveheader","yes");option("audio-samplerate","16000");option("audio-channels","mono");option("audio-format","s16");
  // These containers declare their tracks in the header. Avoid probing video packets
  // while collecting an audio-only subtitle window from a high bitrate source.
  // Performance hints are optional across the bundled libmpv builds. Probesize is
  // an integer AVFormat size, rather than mpv's byte-size option syntax.
  mpv_set_option_string(player,"demuxer-lavf-probe-info","nostreams");
  mpv_set_option_string(player,"demuxer-lavf-analyzeduration","0.1");
  mpv_set_option_string(player,"demuxer-lavf-probesize","262144");
  option("start",std::to_string(start/1000.0));option("length",std::to_string(duration/1000.0));option("ytdl","no");option("network-timeout","10");option("tls-verify","yes");option("tls-ca-file",text(env,certificates));option("user-agent",text(env,userAgent));if(cloud)option("referrer","https://pan.xunlei.com/");
  option("cache","no");option("demuxer-readahead-secs","0");option("demuxer-max-bytes","8MiB");
  int code=mpv_initialize(player);if(code<0)throw std::runtime_error("音轨分析初始化失败");std::string uri=text(env,source);
  const char* command[]={"loadfile",uri.c_str(),"replace",nullptr};if(mpv_command(player,command)<0)throw std::runtime_error("音轨无法打开");
  const auto at=std::chrono::steady_clock::now();bool done=false;
  while(!done){
   if(epoch.load()!=request)throw std::runtime_error("字幕音轨准备已取消");
   if(std::chrono::steady_clock::now()-at>std::chrono::seconds(30))throw std::runtime_error("字幕音轨准备超时，可关闭后重试");
   mpv_event* event=mpv_wait_event(player,.05);
   if(event->event_id==MPV_EVENT_END_FILE){auto* end=static_cast<mpv_event_end_file*>(event->data);if(end->error<0)throw std::runtime_error("音轨读取失败，请检查网络或原文件音轨");done=true;}
  }
  mpv_terminate_destroy(player);player=nullptr;
 }catch(const std::exception& error){if(player)mpv_terminate_destroy(player);env->ThrowNew(env->FindClass("java/io/IOException"),error.what());}
}
