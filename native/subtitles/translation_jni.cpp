#include <jni.h>
#include "translation.h"
namespace {
std::string text(JNIEnv* env,jstring value){const char* bytes=env->GetStringUTFChars(value,nullptr);std::string result(bytes);env->ReleaseStringUTFChars(value,bytes);return result;}
void fail(JNIEnv* env,const char* error){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error);}
void quiet(enum ggml_log_level,const char*,void*){}
}
extern "C" JNIEXPORT jlong JNICALL Java_local_thor_bilichannels_SubtitleNative_createTranslator(JNIEnv* env,jclass,jstring model){try{llama_log_set(quiet,nullptr);return reinterpret_cast<jlong>(new ThorTranslator(text(env,model).c_str()));}catch(const std::exception& error){fail(env,error.what());return 0;}}
extern "C" JNIEXPORT jstring JNICALL Java_local_thor_bilichannels_SubtitleNative_translate(JNIEnv* env,jclass,jlong handle,jstring japanese){try{if(!handle)throw std::runtime_error("日中直译模型尚未就绪");return env->NewStringUTF(reinterpret_cast<ThorTranslator*>(handle)->translate(text(env,japanese)).c_str());}catch(const std::exception& error){fail(env,error.what());return nullptr;}}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_SubtitleNative_closeTranslator(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<ThorTranslator*>(handle);}
