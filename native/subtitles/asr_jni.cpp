#include <jni.h>
#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "sherpa-onnx/csrc/offline-recognizer.h"
#include "recognition.h"

namespace {
std::string text(JNIEnv* env,jstring value){const char* bytes=env->GetStringUTFChars(value,nullptr);std::string result(bytes);env->ReleaseStringUTFChars(value,bytes);return result;}
void fail(JNIEnv* env,const char* message){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message);}
}
extern "C" JNIEXPORT jlong JNICALL Java_local_thor_bilichannels_SubtitleNative_createRecognizer(JNIEnv* env,jclass,jstring directory,jstring libraries){
 try{
  const std::string model=text(env,directory),libs=text(env,libraries);
  return reinterpret_cast<jlong>(new ThorRecognizer(model,libs));
 }catch(const std::exception& error){fail(env,error.what());return 0;}
}
extern "C" JNIEXPORT jstring JNICALL Java_local_thor_bilichannels_SubtitleNative_recognize(JNIEnv* env,jclass,jlong handle,jshortArray pcm){
 try{
  if(!handle)throw std::runtime_error("日语识别尚未准备好");
  if(!pcm)throw std::runtime_error("字幕音频为空");jsize count=env->GetArrayLength(pcm);
  auto* recognizer=reinterpret_cast<ThorRecognizer*>(handle);
  if(count<=0||count>(recognizer->batch?480000:160000))throw std::runtime_error("字幕音轨区间长度无效");
  std::vector<jshort> input(count);env->GetShortArrayRegion(pcm,0,count,input.data());if(env->ExceptionCheck())return nullptr;
  std::vector<float> samples(count);for(jsize i=0;i<count;i++)samples[i]=input[i]/32768.0f;
  return env->NewStringUTF(recognizer->recognize(samples).c_str());
 }catch(const std::exception& error){fail(env,error.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_SubtitleNative_closeRecognizer(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<ThorRecognizer*>(handle);}
