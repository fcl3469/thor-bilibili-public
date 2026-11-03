#pragma once
#include "llama.h"
#include <algorithm>
#include <chrono>
#include <list>
#include <stdexcept>
#include <string>
#include <vector>

// Japanese text goes directly into the Chinese translation instruction. No pivot translator.
class ThorTranslator {
 llama_model* model=nullptr;llama_context* context=nullptr;
 bool optimize;std::vector<llama_token> previousInput;
 std::list<std::pair<std::string,std::string>> memo;
public:
 explicit ThorTranslator(const char* path,bool optimize=true):optimize(optimize){
  ggml_backend_load_all();llama_model_params mp=llama_model_default_params();mp.n_gpu_layers=0;
  model=llama_model_load_from_file(path,mp);if(!model)throw std::runtime_error("日中直译模型加载失败");
  llama_context_params cp=llama_context_default_params();cp.n_ctx=768;cp.n_batch=256;cp.n_ubatch=128;cp.n_threads=3;cp.n_threads_batch=3;cp.no_perf=true;
  context=llama_init_from_model(model,cp);if(!context){llama_model_free(model);model=nullptr;throw std::runtime_error("日中直译运行内存不足");}
 }
 ~ThorTranslator(){if(context)llama_free(context);if(model)llama_model_free(model);}
 std::string translate(const std::string& japanese){
  if(japanese.empty()||japanese.size()>2000)throw std::runtime_error("无效的日语字幕长度");
  if(optimize)for(auto it=memo.begin();it!=memo.end();++it)if(it->first==japanese){std::string result=it->second;memo.splice(memo.begin(),memo,it);return result;}
  const std::string instruction="将以下日语对白翻译成简体中文，注意只需要输出翻译后的结果，不要额外解释：\n\n"+japanese;
  const llama_chat_message message={"user",instruction.c_str()};std::vector<char> formatted(instruction.size()*3+4096);
  int n=llama_chat_apply_template(llama_model_chat_template(model,nullptr),&message,1,true,formatted.data(),formatted.size());
  if(n<=0||n>=(int)formatted.size())throw std::runtime_error("日中直译提示格式不支持");
  const llama_vocab* vocab=llama_model_get_vocab(model);int count=-llama_tokenize(vocab,formatted.data(),n,nullptr,0,true,true);
  if(count<=0||count>500)throw std::runtime_error("日语对白过长，请重试字幕");std::vector<llama_token> tokens(count);
  if(llama_tokenize(vocab,formatted.data(),n,tokens.data(),count,true,true)<0)throw std::runtime_error("日语对白编码失败");
  int prefix=0;if(optimize){while(prefix<std::min<int>(previousInput.size(),count-1)&&previousInput[prefix]==tokens[prefix])prefix++;}
  auto memory=llama_get_memory(context);
  // Remove the entire previous suffix, including generated Chinese. Decode the
  // final prompt token again so logits always belong to the current request.
  if(prefix==0||!llama_memory_seq_rm(memory,0,prefix,-1)){llama_memory_clear(memory,true);prefix=0;}
  llama_sampler* sampler=llama_sampler_init_greedy();std::string output;
  const auto started=std::chrono::steady_clock::now();
  try{
   for(int at=prefix;at<count;at+=256){llama_batch batch=llama_batch_get_one(tokens.data()+at,std::min(256,count-at));if(llama_decode(context,batch))throw std::runtime_error("日中直译计算失败");}
   bool ended=false;
   for(int i=0;i<160;i++){
    if(std::chrono::steady_clock::now()-started>std::chrono::seconds(25))throw std::runtime_error("日中直译较慢，请稍后重试");
    llama_token token=llama_sampler_sample(sampler,context,-1);if(llama_vocab_is_eog(vocab,token)){ended=true;break;}
    char piece[512];int length=llama_token_to_piece(vocab,token,piece,sizeof(piece),0,false);if(length<0)throw std::runtime_error("中文字幕解码失败");output.append(piece,length);
    llama_batch batch=llama_batch_get_one(&token,1);if(llama_decode(context,batch))throw std::runtime_error("日中直译计算失败");
   }
   if(!ended)throw std::runtime_error("日中直译未正常结束，请重试");
  }catch(...){previousInput.clear();llama_memory_clear(memory,true);llama_sampler_free(sampler);throw;}
  llama_sampler_free(sampler);if(output.empty()){previousInput.clear();llama_memory_clear(memory,true);throw std::runtime_error("日中直译没有返回字幕");}
  if(optimize){previousInput=tokens;memo.emplace_front(japanese,output);if(memo.size()>128)memo.pop_back();}return output;
 }
};
