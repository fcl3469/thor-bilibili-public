#include <jni.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <poll.h>
#include <unistd.h>
#include <cerrno>
#include <chrono>
#include <memory>
#include <stdexcept>
namespace {
void check(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
void error(JNIEnv* env,const std::exception& e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());}
struct Reader {
 AImageReader* reader=nullptr;JavaVM* vm=nullptr;jobject callback=nullptr;jmethodID run=nullptr;
 static void available(void* context,AImageReader*){
  auto& self=*static_cast<Reader*>(context);JNIEnv* env=nullptr;bool attached=false;
  if(self.vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){if(self.vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;attached=true;}
  env->CallVoidMethod(self.callback,self.run);
  if(env->ExceptionCheck()){env->ExceptionDescribe();env->ExceptionClear();}
  if(attached)self.vm->DetachCurrentThread();
 }
 void close(JNIEnv* env){
  // AImageReader deletion joins its callback looper before releasing the global.
  if(reader){AImageReader_delete(reader);reader=nullptr;}
  if(callback){env->DeleteGlobalRef(callback);callback=nullptr;}
 }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_local_thor_bilichannels_RifeImageReader_create(JNIEnv* env,jclass,jobject callback){
 auto r=std::make_unique<Reader>();try{
  check(env->GetJavaVM(&r->vm)==JNI_OK,"Reader JVM unavailable");r->callback=env->NewGlobalRef(callback);check(r->callback,"Reader callback unavailable");
  jclass clazz=env->GetObjectClass(callback);r->run=env->GetMethodID(clazz,"run","()V");env->DeleteLocalRef(clazz);check(r->run,"Reader callback method unavailable");
  check(AImageReader_newWithUsage(1920,1080,AIMAGE_FORMAT_PRIVATE,AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,4,&r->reader)==AMEDIA_OK,"Native decoder reader creation failed");
  AImageReader_ImageListener listener{r.get(),Reader::available};check(AImageReader_setImageListener(r->reader,&listener)==AMEDIA_OK,"Native decoder listener failed");return reinterpret_cast<jlong>(r.release());
 }catch(const std::exception& e){r->close(env);error(env,e);return 0;}
}
extern "C" JNIEXPORT jobject JNICALL Java_local_thor_bilichannels_RifeImageReader_surface(JNIEnv* env,jclass,jlong handle){
 try{check(handle,"Reader closed");ANativeWindow* window=nullptr;check(AImageReader_getWindow(reinterpret_cast<Reader*>(handle)->reader,&window)==AMEDIA_OK,"Reader surface unavailable");auto surface=ANativeWindow_toSurface(env,window);check(surface,"Reader Java surface unavailable");return surface;}catch(const std::exception& e){error(env,e);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_local_thor_bilichannels_RifeImageReader_acquire(JNIEnv* env,jclass,jlong handle){
 AImage* image=nullptr;int fd=-1;try{
  check(handle,"Reader closed");int rc=AImageReader_acquireLatestImageAsync(reinterpret_cast<Reader*>(handle)->reader,&image,&fd);
  if(rc==AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE)return nullptr;check(rc==AMEDIA_OK&&image,"Native decoder acquire failed");
  if(fd>=0){
   auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(2);pollfd fence{fd,POLLIN,0};
   do{int remaining=std::chrono::duration_cast<std::chrono::milliseconds>(deadline-std::chrono::steady_clock::now()).count();check(remaining>0,"Decoder fence timed out");rc=poll(&fence,1,remaining);}while(rc<0&&errno==EINTR);
   check(rc>0&&(fence.revents&POLLIN)&&!(fence.revents&(POLLERR|POLLNVAL)),"Decoder fence wait failed");close(fd);fd=-1;
  }
  int64_t pts=0;AImageCropRect crop{};check(AImage_getTimestamp(image,&pts)==AMEDIA_OK&&AImage_getCropRect(image,&crop)==AMEDIA_OK,"Decoder image metadata unavailable");
  jlong values[]={reinterpret_cast<jlong>(image),pts,crop.right-crop.left,crop.bottom-crop.top};auto result=env->NewLongArray(4);check(result,"Decoder metadata allocation failed");env->SetLongArrayRegion(result,0,4,values);return result;
 }catch(const std::exception& e){if(fd>=0)close(fd);if(image)AImage_delete(image);error(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_RifeImageReader_release(JNIEnv*,jclass,jlong image){if(image)AImage_delete(reinterpret_cast<AImage*>(image));}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_RifeImageReader_destroy(JNIEnv* env,jclass,jlong handle){if(handle){auto* r=reinterpret_cast<Reader*>(handle);r->close(env);delete r;}}
