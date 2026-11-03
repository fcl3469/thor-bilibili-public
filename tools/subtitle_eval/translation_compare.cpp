#include "translation.h"
#include <cstdio>
#include <set>

static void quiet(enum ggml_log_level,const char*,void*){}
static long long micros(std::chrono::steady_clock::time_point at){return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-at).count();}

// Same weights and prompt, fresh state versus optimized state. Never plays audio.
int main(int argc,char** argv){
 if(argc<2)return 2;llama_log_set(quiet,nullptr);
 try{
  ThorTranslator fresh(argv[1],false),cached(argv[1],true);
  std::vector<std::string> cases={"こんにちは。","明日は雨です。","そういう意味で言ったんじゃない。","はい。","明日は雨です。","田中さんに手紙を渡してください。","先輩はまだ来ていません。","私はもう帰ります。","彼は来ないと言いました。","こんにちは。","はい。"};
  if(argc>2){cases.clear();for(int i=2;i<argc;i++)cases.push_back(argv[i]);}
  fresh.translate("こんばんは。");cached.translate("こんばんは。");std::set<std::string> seen;
  for(size_t i=0;i<cases.size();i++){
   std::string a,b;long long ta,tb;
   auto runA=[&]{auto at=std::chrono::steady_clock::now();a=fresh.translate(cases[i]);ta=micros(at);};
   auto runB=[&]{auto at=std::chrono::steady_clock::now();b=cached.translate(cases[i]);tb=micros(at);};
   if(i%2){runB();runA();}else{runA();runB();}
   bool equal=a==b,repeat=!seen.insert(cases[i]).second;
   printf("{\"case\":%zu,\"fresh_us\":%lld,\"cached_us\":%lld,\"repeat\":%s,\"equal\":%s}\n",i,ta,tb,repeat?"true":"false",equal?"true":"false");fflush(stdout);
   if(!equal){fprintf(stderr,"Translation differs at case %zu\n",i);return 1;}
  }
 }catch(const std::exception& error){fprintf(stderr,"Translation comparison: %s\n",error.what());return 1;}return 0;
}
