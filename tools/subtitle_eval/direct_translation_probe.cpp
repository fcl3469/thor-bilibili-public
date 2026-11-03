#include "translation.h"
#include <cstdio>
#include <chrono>
static void quiet(enum ggml_log_level,const char*,void*){}
int main(int argc,char** argv){
 if(argc<2)return 2;llama_log_set(quiet,nullptr);
 try{auto at=std::chrono::steady_clock::now();ThorTranslator translator(argv[1]);fprintf(stderr,"prepare_ms=%lld\n",(long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-at).count());
  for(int i=2;i<argc;i++){at=std::chrono::steady_clock::now();std::string result=translator.translate(argv[i]);printf("%s\n",result.c_str());fprintf(stderr,"translation_ms=%lld\n",(long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-at).count());fflush(stdout);}
 }catch(const std::exception& error){fprintf(stderr,"Direct translation error: %s\n",error.what());return 1;}return 0;
}
