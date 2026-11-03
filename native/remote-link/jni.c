#include <jni.h>
#include <stdlib.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
static void *go_error_log(void *arg){int fd=(int)(long)arg;char line[2048];ssize_t n;while((n=read(fd,line,sizeof(line)-1))>0){line[n]=0;__android_log_write(ANDROID_LOG_ERROR,"ThorLinkGo",line);}close(fd);return NULL;}
__attribute__((constructor(101))) static void early_go_error_log(void){int fds[2];if(pipe(fds)==0){dup2(fds[1],2);close(fds[1]);pthread_t t;if(pthread_create(&t,NULL,go_error_log,(void*)(long)fds[0])==0)pthread_detach(t);}}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved){return JNI_VERSION_1_6;}
extern char *thor_link_start(char *,char *,char *,unsigned long long);
extern char *thor_link_status(void);
extern char *thor_link_peer(char *);
extern char *thor_link_request(char *,char *,char *,char *);
extern char *thor_link_download(char *,char *,char *,char *);
extern void thor_link_free(char *);
static jstring result(JNIEnv *env,char *p){jstring r=(*env)->NewStringUTF(env,p?p:"{}");if(p)thor_link_free(p);return r;}
JNIEXPORT jstring JNICALL Java_local_thor_bilichannels_ThorLink_nativeStart(JNIEnv *env,jclass cls,jstring dir,jstring host,jstring ifs,jlong network){
 const char *d=(*env)->GetStringUTFChars(env,dir,0),*h=(*env)->GetStringUTFChars(env,host,0),*i=(*env)->GetStringUTFChars(env,ifs,0);
 char *out=thor_link_start((char*)d,(char*)h,(char*)i,(unsigned long long)network);
 (*env)->ReleaseStringUTFChars(env,dir,d);(*env)->ReleaseStringUTFChars(env,host,h);(*env)->ReleaseStringUTFChars(env,ifs,i);return result(env,out);
}
JNIEXPORT jstring JNICALL Java_local_thor_bilichannels_ThorLink_nativeStatus(JNIEnv *env,jclass cls){return result(env,thor_link_status());}
JNIEXPORT jstring JNICALL Java_local_thor_bilichannels_ThorLink_nativePeer(JNIEnv *env,jclass cls,jstring host){const char*h=(*env)->GetStringUTFChars(env,host,0);char*out=thor_link_peer((char*)h);(*env)->ReleaseStringUTFChars(env,host,h);return result(env,out);}
JNIEXPORT jstring JNICALL Java_local_thor_bilichannels_ThorLink_nativeRequest(JNIEnv *env,jclass cls,jstring method,jstring address,jstring body,jstring cookie){
 const char*m=(*env)->GetStringUTFChars(env,method,0),*a=(*env)->GetStringUTFChars(env,address,0),*b=(*env)->GetStringUTFChars(env,body,0),*c=(*env)->GetStringUTFChars(env,cookie,0);
 char*out=thor_link_request((char*)m,(char*)a,(char*)b,(char*)c);(*env)->ReleaseStringUTFChars(env,method,m);(*env)->ReleaseStringUTFChars(env,address,a);(*env)->ReleaseStringUTFChars(env,body,b);(*env)->ReleaseStringUTFChars(env,cookie,c);return result(env,out);
}
JNIEXPORT jstring JNICALL Java_local_thor_bilichannels_ThorLink_nativeDownload(JNIEnv *env,jclass cls,jstring address,jstring cookie,jstring file,jstring digest){
 const char*a=(*env)->GetStringUTFChars(env,address,0),*c=(*env)->GetStringUTFChars(env,cookie,0),*f=(*env)->GetStringUTFChars(env,file,0),*d=(*env)->GetStringUTFChars(env,digest,0);
 char*out=thor_link_download((char*)a,(char*)c,(char*)f,(char*)d);(*env)->ReleaseStringUTFChars(env,address,a);(*env)->ReleaseStringUTFChars(env,cookie,c);(*env)->ReleaseStringUTFChars(env,file,f);(*env)->ReleaseStringUTFChars(env,digest,d);return result(env,out);
}
