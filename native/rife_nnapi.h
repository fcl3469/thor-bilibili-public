#pragma once
// NNAPI selects only a named MediaTek accelerator, never its CPU/GPU delegates.
// Model I/O stays FP16. Vulkan mapping is not a claim of zero-copy inside NNAPI.
#include <android/NeuralNetworks.h>
#include <android/log.h>
#include <fstream>
#include <cstring>
#include <cctype>
#include <vector>
#include <memory>
#include <stdexcept>
namespace thor_apu {
inline void status(int code,const char* operation){if(code!=ANEURALNETWORKS_NO_ERROR)throw std::runtime_error(std::string("NNAPI ")+operation+" error="+std::to_string(code));}
inline bool acceleratorName(std::string name){
 for(char& c:name)c=char(std::tolower(static_cast<unsigned char>(c)));
 if(name.find("cpu")!=std::string::npos||name.find("gpu")!=std::string::npos)return false;
 for(auto token:{"mediatek","mtk","neuron","mdla","vpu","apu"})if(name.find(token)!=std::string::npos)return true;
 return false;
}
struct Device {const ANeuralNetworksDevice* handle;std::string name;};
inline std::vector<Device> devices(){
 uint32_t count=0;status(ANeuralNetworks_getDeviceCount(&count),"device count");std::vector<Device> result;
 for(uint32_t i=0;i<count;i++){
  ANeuralNetworksDevice* d=nullptr;const char* name=nullptr;int32_t type=0;int64_t level=0;
  status(ANeuralNetworks_getDevice(i,&d),"device");status(ANeuralNetworksDevice_getName(d,&name),"name");
  status(ANeuralNetworksDevice_getType(d,&type),"type");status(ANeuralNetworksDevice_getFeatureLevel(d,&level),"feature level");
  __android_log_print(ANDROID_LOG_INFO,"ThorRIFE","NNAPI device=%s type=%d level=%lld",name?name:"?",type,(long long)level);
  if(type==ANEURALNETWORKS_DEVICE_ACCELERATOR&&level>=ANEURALNETWORKS_FEATURE_LEVEL_3&&name&&acceleratorName(name))result.push_back({d,name});
 }
 if(result.empty())throw std::runtime_error("No MediaTek NNAPI accelerator exposed (CPU/GPU excluded)");return result;
}
class Island {
 using Model=std::unique_ptr<ANeuralNetworksModel,decltype(&ANeuralNetworksModel_free)>;
 using Compilation=std::unique_ptr<ANeuralNetworksCompilation,decltype(&ANeuralNetworksCompilation_free)>;
 Model model{nullptr,ANeuralNetworksModel_free};Compilation compilation{nullptr,ANeuralNetworksCompilation_free};
 // setOperandValue may reference large constants until compilation finishes.
 std::vector<unsigned char> weights;
 uint32_t operands=0,operations=0;size_t inputBytes=0,outputBytes=0;
 uint32_t operand(int32_t type,std::vector<uint32_t> dims,const void* value=nullptr,size_t bytes=0){
  ANeuralNetworksOperandType t{type,uint32_t(dims.size()),dims.data(),0,0};
  status(ANeuralNetworksModel_addOperand(model.get(),&t),"operand");uint32_t id=operands++;
  if(value)status(ANeuralNetworksModel_setOperandValue(model.get(),id,value,bytes),"constant");return id;
 }
 uint32_t integer(int32_t v){return operand(ANEURALNETWORKS_INT32,{},&v,sizeof(v));}
 uint32_t tensor(std::vector<uint32_t> shape,const void* value=nullptr,size_t bytes=0){return operand(ANEURALNETWORKS_TENSOR_FLOAT16,shape,value,bytes);}
 void op(int32_t code,std::vector<uint32_t> input,uint32_t output){status(ANeuralNetworksModel_addOperation(model.get(),code,input.size(),input.data(),1,&output),"operation");operations++;}
public:
 std::string deviceName;std::vector<int64_t> shape,outputShape;
 Island(const std::string& path,int w,int h,int channels,const std::vector<Device>& targets){
  std::ifstream file(path,std::ios::binary);if(!file)throw std::runtime_error("Missing NNAPI weights");
  weights.assign(std::istreambuf_iterator<char>(file),{});
  if(weights.size()<12||weights.size()>32*1024*1024||memcmp(weights.data(),"RFNN0001",8)!=0)throw std::runtime_error("Invalid NNAPI weights");
  size_t at=8;auto u32=[&](){if(at+4>weights.size())throw std::runtime_error("Truncated NNAPI weights");uint32_t v;memcpy(&v,weights.data()+at,4);at+=4;return v;};
  auto bytes=[&](size_t n){if(n>weights.size()-at)throw std::runtime_error("Truncated NNAPI tensor");const void* p=weights.data()+at;at+=n;return p;};
  ANeuralNetworksModel* m=nullptr;status(ANeuralNetworksModel_create(&m),"create model");model.reset(m);
  shape={1,h,w,channels};inputBytes=size_t(w)*h*channels*2;
  uint32_t x=tensor({1,uint32_t(h),uint32_t(w),uint32_t(channels)}),input=x;
  const uint32_t none=integer(ANEURALNETWORKS_FUSED_NONE);uint8_t layout=0;
  const uint32_t nhwc=operand(ANEURALNETWORKS_BOOL,{},&layout,1);uint16_t alpha=0x3266; // float16(0.2)
  const uint32_t slope=tensor({1},&alpha,2);
  uint32_t count=u32();if(count!=4&&count!=11)throw std::runtime_error("Invalid NNAPI layer count");
  for(uint32_t i=0;i<count;i++){
   uint32_t transpose=u32(),co=u32(),ci=u32(),kh=u32(),kw=u32(),stride=u32(),pad=u32(),flags=u32();
   if(transpose>1||ci!=uint32_t(channels)||co==0||co>256||kh<1||kh>5||kw<1||kw>5||stride<1||stride>2||pad>2||flags>3)throw std::runtime_error("Invalid NNAPI layer geometry");
   size_t n=size_t(co)*kh*kw*ci*2;auto filter=tensor({co,kh,kw,ci},bytes(n),n);auto bias=tensor({co},bytes(co*2),co*2);
   int ow=transpose?(w-1)*stride+kw-2*pad:(w+2*pad-kw)/stride+1;
   int oh=transpose?(h-1)*stride+kh-2*pad:(h+2*pad-kh)/stride+1;
   if(ow<=0||oh<=0||ow>512||oh>512)throw std::runtime_error("Invalid NNAPI output geometry");
   std::vector<uint32_t> dims={1,uint32_t(oh),uint32_t(ow),co};auto y=tensor(dims),p=integer(pad),s=integer(stride);
   op(transpose?ANEURALNETWORKS_TRANSPOSE_CONV_2D:ANEURALNETWORKS_CONV_2D,{x,filter,bias,p,p,p,p,s,s,none,nhwc},y);
   if(flags&2){auto beta=tensor({co},bytes(co*2),co*2);auto scaled=tensor(dims);op(ANEURALNETWORKS_MUL,{y,beta,none},scaled);y=scaled;if(ow!=w||oh!=h||co!=ci)throw std::runtime_error("Invalid NNAPI residual");auto sum=tensor(dims);op(ANEURALNETWORKS_ADD,{y,x,none},sum);y=sum;}
   if(flags&1){auto activated=tensor(dims);op(ANEURALNETWORKS_PRELU,{y,slope},activated);y=activated;}
   x=y;w=ow;h=oh;channels=co;
  }
  if(at!=weights.size())throw std::runtime_error("Trailing NNAPI weights");
  outputShape={1,h,w,channels};outputBytes=size_t(w)*h*channels*2;
  status(ANeuralNetworksModel_identifyInputsAndOutputs(model.get(),1,&input,1,&x),"model I/O");status(ANeuralNetworksModel_finish(model.get()),"finish model");
  std::string errors;
  for(const auto& target:targets){
   try{
    std::unique_ptr<bool[]> supported(new bool[operations]{});
    status(ANeuralNetworksModel_getSupportedOperationsForDevices(model.get(),&target.handle,1,supported.get()),"query operations");
    for(uint32_t i=0;i<operations;i++)if(!supported[i])throw std::runtime_error("unsupported FP16 operation "+std::to_string(i));
    ANeuralNetworksCompilation* c=nullptr;int rc=ANeuralNetworksCompilation_createForDevices(model.get(),&target.handle,1,&c);Compilation candidate(c,ANeuralNetworksCompilation_free);status(rc,"create compilation");
    status(ANeuralNetworksCompilation_setPreference(c,ANEURALNETWORKS_PREFER_FAST_SINGLE_ANSWER),"preference");
    status(ANeuralNetworksCompilation_finish(c),"compile");compilation=std::move(candidate);deviceName=target.name;break;
   }catch(const std::exception& e){errors+=target.name+": "+e.what()+"; ";}
  }
  if(!compilation)throw std::runtime_error("APU graph unavailable: "+errors);
  __android_log_print(ANDROID_LOG_INFO,"ThorRIFE","NNAPI compiled %s on %s (all %u ops, FP16, no CPU/GPU fallback)",path.c_str(),deviceName.c_str(),operations);
 }
 void run(const void* input,void* output){
  ANeuralNetworksExecution* e=nullptr;status(ANeuralNetworksExecution_create(compilation.get(),&e),"execution");
  std::unique_ptr<ANeuralNetworksExecution,decltype(&ANeuralNetworksExecution_free)> execution(e,ANeuralNetworksExecution_free);
  status(ANeuralNetworksExecution_setInput(e,0,nullptr,input,inputBytes),"input");
  status(ANeuralNetworksExecution_setOutput(e,0,nullptr,output,outputBytes),"output");
  status(ANeuralNetworksExecution_compute(e),"compute");
 }
};
}
