#include <jni.h>
#include <android/native_window_jni.h>
#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include "vendor/mpv/client.h"
#include <string>
#include <stdexcept>
#include <mutex>
extern "C" int av_jni_set_java_vm(void*,void*);
extern "C" int av_jni_set_android_app_ctx(void*,void*);
namespace {
std::mutex applicationMutex; jobject applicationContext=nullptr;
struct Player {mpv_handle* handle=nullptr;ANativeWindow* window=nullptr;jobject surface=nullptr;bool loaded=false,frame=false,ended=false,hdr=false,stamped=false;int error=0;};
std::string utf(JNIEnv* env,jstring value){const char* data=env->GetStringUTFChars(value,nullptr);std::string copy(data);env->ReleaseStringUTFChars(value,data);return copy;}
void option(Player& p,const char* key,const char* value){int result=mpv_set_option_string(p.handle,key,value);if(result<0)throw std::runtime_error(std::string(key)+": "+mpv_error_string(result));}
void destroy(JNIEnv* env,Player* p){if(!p)return;if(p->handle)mpv_terminate_destroy(p->handle);if(p->window)ANativeWindow_release(p->window);if(p->surface)env->DeleteGlobalRef(p->surface);delete p;}
void events(Player& p){for(int i=0;i<100;i++){mpv_event* event=mpv_wait_event(p.handle,0);if(event->event_id==MPV_EVENT_NONE)break;if(event->event_id==MPV_EVENT_FILE_LOADED)p.loaded=true;else if(event->event_id==MPV_EVENT_PLAYBACK_RESTART)p.frame=true;else if(event->event_id==MPV_EVENT_END_FILE){auto* end=static_cast<mpv_event_end_file*>(event->data);p.error=end->error;p.ended=end->reason==MPV_END_FILE_REASON_EOF;}}if(p.hdr&&p.frame&&!p.stamped){ANativeWindow_setBuffersDataSpace(p.window,ADATASPACE_BT2020_PQ);p.stamped=true;}}
}
extern "C" JNIEXPORT jlong JNICALL Java_local_thor_bilichannels_DoviPlayer_nativeOpen(JNIEnv* env,jclass,jobject context,jobject surface,jstring uri,jstring ca,jstring ua,jboolean audio,jint start,jint frameWidth,jint frameHeight,jboolean hdr,jfloat peak){
 Player* p=new Player();p->hdr=hdr;try{
  {std::lock_guard<std::mutex> lock(applicationMutex);JavaVM* vm=nullptr;env->GetJavaVM(&vm);av_jni_set_java_vm(vm,nullptr);if(!applicationContext){applicationContext=env->NewGlobalRef(context);av_jni_set_android_app_ctx(applicationContext,nullptr);}}
  p->surface=env->NewGlobalRef(surface);p->window=ANativeWindow_fromSurface(env,surface);if(!p->window)throw std::runtime_error("Dolby output surface unavailable");
  if((hdr||frameWidth>0)&&ANativeWindow_setBuffersGeometry(p->window,frameWidth,frameHeight,hdr?AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:WINDOW_FORMAT_RGBA_8888)<0)throw std::runtime_error("Dolby RGB output unavailable");
  p->handle=mpv_create();if(!p->handle)throw std::runtime_error("Dolby decoder allocation failed");
  option(*p,"config","no");option(*p,"terminal","no");option(*p,"msg-level","all=no");option(*p,"idle","yes");option(*p,"keep-open","yes");option(*p,"vo","gpu-next");option(*p,"gpu-api","opengl");option(*p,"gpu-context","android");option(*p,"hwdec","no");option(*p,"aid",audio?"auto":"no");option(*p,"volume","0");option(*p,"pause","yes");option(*p,"input-default-bindings","no");option(*p,"input-vo-keyboard","no");option(*p,"osc","no");option(*p,"osd-level","0");option(*p,"ytdl","no");option(*p,"network-timeout","15");option(*p,"tls-verify","yes");option(*p,"tls-ca-file",utf(env,ca).c_str());option(*p,"user-agent",utf(env,ua).c_str());option(*p,"referrer","https://pan.xunlei.com/");
  // Apply P5 RPU reshaping to the original stream. HDR-capable displays receive
  // 10-bit BT.2020/PQ; SDR-only displays receive a separate SDR mapping.
  option(*p,"tone-mapping","auto");option(*p,"hdr-compute-peak","yes");option(*p,"gamut-mapping-mode","perceptual");option(*p,"target-prim",hdr?"bt.2020":"bt.709");option(*p,"target-trc",hdr?"pq":"srgb");option(*p,"target-peak",hdr?std::to_string(static_cast<int>(peak)).c_str():"203");if(hdr){option(*p,"egl-output-format","rgb10_a2");ANativeWindow_setBuffersDataSpace(p->window,ADATASPACE_BT2020_PQ);}option(*p,"target-colorspace-hint","no");option(*p,"cache-secs","3");option(*p,"demuxer-max-bytes","32MiB");option(*p,"demuxer-max-back-bytes","1MiB");option(*p,"vd-lavc-threads","8");
  int width=frameWidth>0?frameWidth:ANativeWindow_getWidth(p->window),height=frameHeight>0?frameHeight:ANativeWindow_getHeight(p->window);option(*p,"android-surface-size",(std::to_string(width)+"x"+std::to_string(height)).c_str());option(*p,"start",std::to_string(start/1000.0).c_str());int64_t wid=reinterpret_cast<int64_t>(p->surface);int error=mpv_set_option(p->handle,"wid",MPV_FORMAT_INT64,&wid);if(error<0)throw std::runtime_error(mpv_error_string(error));error=mpv_initialize(p->handle);if(error<0)throw std::runtime_error(mpv_error_string(error));std::string url=utf(env,uri);const char* command[]={"loadfile",url.c_str(),"replace",nullptr};error=mpv_command(p->handle,command);if(error<0)throw std::runtime_error(mpv_error_string(error));return reinterpret_cast<jlong>(p);
 }catch(const std::exception& error){__android_log_print(ANDROID_LOG_ERROR,"ThorDovi","Initialization: %s",error.what());destroy(env,p);env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());return 0;}
}
extern "C" JNIEXPORT jstring JNICALL Java_local_thor_bilichannels_DoviPlayer_nativeProperty(JNIEnv* env,jclass,jlong handle,jstring name){auto& p=*reinterpret_cast<Player*>(handle);events(p);std::string key=utf(env,name);const char* result=nullptr;if(key=="@loaded")result=p.loaded?"yes":"no";else if(key=="@frame")result=p.frame?"yes":"no";else if(key=="@ended")result=p.ended?"yes":"no";else if(key=="@error")result=p.error<0?mpv_error_string(p.error):"";if(key=="@dataspace")return env->NewStringUTF(std::to_string(ANativeWindow_getBuffersDataSpace(p.window)).c_str());if(key=="@format")return env->NewStringUTF(std::to_string(ANativeWindow_getFormat(p.window)).c_str());if(result)return env->NewStringUTF(result);char* text=mpv_get_property_string(p.handle,key.c_str());if(!text)return nullptr;jstring out=env->NewStringUTF(text);mpv_free(text);return out;}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_DoviPlayer_nativeSet(JNIEnv* env,jclass,jlong handle,jstring key,jstring value){auto& p=*reinterpret_cast<Player*>(handle);std::string name=utf(env,key);if(p.hdr&&name=="android-surface-size"){ANativeWindow_setBuffersGeometry(p.window,0,0,AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM);ANativeWindow_setBuffersDataSpace(p.window,ADATASPACE_BT2020_PQ);}mpv_set_property_string(p.handle,name.c_str(),utf(env,value).c_str());}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_DoviPlayer_nativeSeek(JNIEnv*,jclass,jlong handle,jdouble seconds){auto& p=*reinterpret_cast<Player*>(handle);std::string position=std::to_string(seconds);const char* command[]={"seek",position.c_str(),"absolute+exact",nullptr};mpv_command(p.handle,command);}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_DoviPlayer_nativeClose(JNIEnv* env,jclass,jlong handle){destroy(env,reinterpret_cast<Player*>(handle));}
