#include <jni.h>
#include <media/NdkImage.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <chrono>
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <sched.h>
#include "cpu.h"
#include "net.h"
#include "gpu.h"
#include "command.h"
#include "rife_ops.h"
#include "vulkan_presenter.h"
#include "rife_preprocess.comp.hex.h"
#include "rife_postprocess.comp.hex.h"
#include "rife_compose.comp.hex.h"
#include "rife_qnn.h"
#include "rife_texture_composer.h"
#include "rife_texture_flow_fused.comp.hex.h"
#include "rife_texture_flow_full.comp.hex.h"
#include "rife_texture_compose.comp.hex.h"
#include "rife_texture_import.comp.hex.h"
#include "rife_hdr_proxy.comp.hex.h"
#include "rife_texture_prepare.comp.hex.h"
#include "rife_texture_prepare_ncnn.comp.hex.h"
#include "rife_texture_flow_separate.comp.hex.h"

DEFINE_LAYER_CREATOR(Warp)
namespace {
// The private ncnn build exposes one compute queue. Serialize overlapping Surface
// sessions, including model upload/destruction, so that all frame barriers stay
// on that queue and no session can race a queue submission from another worker.
std::mutex gpuMutex;
std::string qnnDirectory,qnnLibraries;
int qnnQuality=720,qnnStages=2;
void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
void raise(JNIEnv* env,const std::exception& e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());}
std::vector<ncnn::vk_constant_type> constants(std::initializer_list<int> values){
 std::vector<ncnn::vk_constant_type> result(values.size());size_t i=0;
 for(int value:values)result[i++].i=value;return result;
}
struct ImportFrame {
 AHardwareBuffer* buffer=nullptr;
 uint64_t allocationId=0;
 int dataSpace=0;
 std::unique_ptr<ncnn::VkAndroidHardwareBufferImageAllocator> allocator;
 std::unique_ptr<ncnn::ImportAndroidHardwareBufferPipeline> pipeline;
 std::unique_ptr<thor_texture::Shader> textureImport;
 const ncnn::VulkanDevice* gpu=nullptr;VkSampler linearSampler=0;
 ncnn::VkImageMat image;
 ~ImportFrame(){textureImport.reset();if(linearSampler)ncnn::vkDestroySampler(gpu->vkdevice(),linearSampler,nullptr);image.release();pipeline.reset();allocator.reset();if(buffer)AHardwareBuffer_release(buffer);}
 void init(const ncnn::VulkanDevice* gpu,AHardwareBuffer* input,int space,const ncnn::Option& opt){
  this->gpu=gpu;buffer=input;dataSpace=space;AHardwareBuffer_acquire(buffer);
  allocator=std::make_unique<ncnn::VkAndroidHardwareBufferImageAllocator>(gpu,buffer);
  const VkFormat format=allocator->bufferFormatProperties.format;
  const bool rgb=format==VK_FORMAT_R8G8B8A8_UNORM||format==VK_FORMAT_B8G8R8A8_UNORM||format==VK_FORMAT_A2B10G10R10_UNORM_PACK32||format==VK_FORMAT_A2R10G10B10_UNORM_PACK32;
  require(rgb||allocator->samplerYcbcrConversion!=VK_NULL_HANDLE,"Decoder buffer cannot be sampled by Vulkan");
  // The AHB external format describes storage. Video metadata chooses the color
  // matrix/range; vendor suggestions alone may otherwise treat HD video as 601.
  const int standard=(space>>16)&63,range=(space>>27)&7;
  if(!rgb&&(standard||range)){
   auto& a=*allocator;
   VkExternalFormatANDROID external{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};external.externalFormat=a.bufferFormatProperties.externalFormat;
   VkSamplerYcbcrConversionCreateInfoKHR info{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO_KHR};
   info.pNext=&external;info.format=VK_FORMAT_UNDEFINED;
   info.ycbcrModel=standard==1?VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709:standard==6?VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_2020:standard>=2&&standard<=5?VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601:a.bufferFormatProperties.suggestedYcbcrModel;
   info.ycbcrRange=range==1?VK_SAMPLER_YCBCR_RANGE_ITU_FULL:range==2?VK_SAMPLER_YCBCR_RANGE_ITU_NARROW:a.bufferFormatProperties.suggestedYcbcrRange;
   info.components=a.bufferFormatProperties.samplerYcbcrConversionComponents;
   info.xChromaOffset=a.bufferFormatProperties.suggestedXChromaOffset;info.yChromaOffset=a.bufferFormatProperties.suggestedYChromaOffset;info.chromaFilter=VK_FILTER_NEAREST;
   VkSamplerYcbcrConversionKHR conversion=VK_NULL_HANDLE;
   require(gpu->vkCreateSamplerYcbcrConversionKHR(gpu->vkdevice(),&info,nullptr,&conversion)==VK_SUCCESS,"Video color conversion unsupported");
   gpu->vkDestroySamplerYcbcrConversionKHR(gpu->vkdevice(),a.samplerYcbcrConversion,nullptr);a.samplerYcbcrConversion=conversion;
  }
  image=ncnn::VkImageMat::from_android_hardware_buffer(allocator.get());require(!image.empty(),"Hardware buffer Vulkan import failed");
  pipeline=std::make_unique<ncnn::ImportAndroidHardwareBufferPipeline>(gpu);
  // The pinned overload that infers dimensions reads rotate_from before it is
  // initialized. Explicit dimensions must match raw's decoder-buffer stride.
  require(pipeline->create(allocator.get(),6,1,allocator->width(),allocator->height(),opt)==0,"Hardware buffer conversion pipeline failed");
  // Leave YCbCr chroma reconstruction unchanged; use linear luma sampling only
  // when the external format explicitly supports separate reconstruction filters.
  auto flags=allocator->bufferFormatProperties.formatFeatures;
  VkFormatFeatureFlags needed=VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT|(rgb?0:VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_SEPARATE_RECONSTRUCTION_FILTER_BIT_KHR);
  if((flags&needed)==needed){
   VkSamplerYcbcrConversionInfoKHR conversion{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO_KHR};conversion.conversion=allocator->samplerYcbcrConversion;
   VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};si.pNext=rgb?nullptr:&conversion;si.magFilter=si.minFilter=VK_FILTER_LINEAR;si.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;si.addressModeU=si.addressModeV=si.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;si.maxAnisotropy=1;
   require(ncnn::vkCreateSampler(gpu->vkdevice(),&si,nullptr,&linearSampler)==VK_SUCCESS,"Decoder linear sampler failed");
  }
  __android_log_print(ANDROID_LOG_DEBUG,"ThorRIFE","decoder sampling linear=%d features=%x",linearSampler!=0,unsigned(flags));
 }
};
// Keep only this session's calling worker on the performance cluster. Never
// change device governors or clocks; restore its original affinity on teardown.
struct WorkerAffinity {
 cpu_set_t original{};bool changed=false;
 void preferFast(){
  if(sched_getaffinity(0,sizeof(original),&original)!=0)return;
  const auto& fast=ncnn::get_cpu_thread_affinity_mask(2);cpu_set_t selected;CPU_ZERO(&selected);int count=0;
  for(int i=0;i<CPU_SETSIZE;i++)if(CPU_ISSET(i,&original)&&fast.is_enabled(i)){CPU_SET(i,&selected);count++;}
  if(count>0)changed=sched_setaffinity(0,sizeof(selected),&selected)==0;
  unsigned long long mask=0;for(int i=0;i<64;i++)if(CPU_ISSET(i,&selected))mask|=1ULL<<i;
  __android_log_print(ANDROID_LOG_INFO,"ThorRIFE","worker performance affinity active=%d cores=%d mask=%llx",int(changed),count,mask);
 }
 ~WorkerAffinity(){if(changed)sched_setaffinity(0,sizeof(original),&original);}
};
struct Engine {
 WorkerAffinity affinity;
 const ncnn::VulkanDevice* gpu=nullptr;
 std::unique_ptr<ncnn::VkBlobAllocator> allocator;
 std::unique_ptr<ncnn::VkStagingAllocator> staging;
 ncnn::Net net;
 ncnn::Option opt,importOpt;
 std::unique_ptr<VulkanPresenter> display;
 std::unique_ptr<ncnn::Pipeline> preprocess,postprocess,compose;
 std::unique_ptr<thor_hybrid::HybridRife> hybrid;
 std::unique_ptr<thor_texture::Composer> textureComposer;
 bool hybridAttempted=false;
 std::string hybridRoot,hybridLibs,apuRoot,fallbackReason;
 int lowEdge=0;
 int hybridQuality=720,activeQuality=720,hybridStages=2;
 std::vector<std::unique_ptr<ImportFrame>> imports;
 ncnn::VkMat previous,current,fullPrevious,fullCurrent,middle,timestep,raw,rgba;
 unsigned nativeFrames=0;
 int displayLong=0,displayShort=0,frameWidth=0,frameHeight=0;
 int width=0,height=0,workWidth=0,workHeight=0,cropLeft=0,cropTop=0;bool hasMiddle=false,reportedShape=false,hdr=false;
 Engine(ANativeWindow* window,const std::string& model,int longEdge,int shortEdge,int estimateEdge,const std::string& apu):apuRoot(apu),lowEdge(estimateEdge),displayLong(longEdge),displayShort(shortEdge){
  require(lowEdge==0||lowEdge==128||lowEdge==192||lowEdge==256,"Invalid low-resolution profile");
  if(!qnnDirectory.empty())affinity.preferFast();
  require(displayShort>0&&displayLong>=displayShort&&displayLong<=16384,"Invalid display bounds");
  static std::once_flag once;static int initResult=-1;
  std::call_once(once,[]{initResult=ncnn::create_gpu_instance();});
  require(initResult==0&&ncnn::get_gpu_count()>0,"No ncnn Vulkan GPU");
  int index=ncnn::get_default_gpu_index();require(index>=0,"No ncnn Vulkan device");gpu=ncnn::get_gpu_device(index);
  require(gpu&&gpu->is_valid(),"Vulkan device creation failed");
  require(gpu->info.support_VK_ANDROID_external_memory_android_hardware_buffer()&&gpu->info.support_VK_EXT_queue_family_foreign(),"GPU decoder-buffer sharing unavailable");
  require(gpu->info.compute_queue_count()==1,"Expected serialized Vulkan queue");
  allocator=std::make_unique<ncnn::VkBlobAllocator>(gpu);staging=std::make_unique<ncnn::VkStagingAllocator>(gpu);
  net.opt.use_vulkan_compute=true;net.opt.use_fp16_packed=false;
  net.opt.use_fp16_storage=gpu->info.support_fp16_storage();net.opt.use_fp16_arithmetic=gpu->info.support_fp16_arithmetic();net.opt.num_threads=1;
  net.set_vulkan_device(gpu);net.register_custom_layer("rife.Warp",Warp_layer_creator);
  require(net.load_param((model+"/flownet.param").c_str())==0,"RIFE model topology load failed");
  require(net.load_model((model+"/flownet.bin").c_str())==0,"RIFE model weights load failed");
  for(const auto* layer:net.layers())require(layer->support_vulkan,"RIFE layer would fall back to CPU");
  opt=net.opt;opt.blob_vkallocator=allocator.get();opt.workspace_vkallocator=allocator.get();opt.staging_vkallocator=staging.get();
  importOpt=opt;importOpt.use_fp16_packed=false;importOpt.use_fp16_storage=false;importOpt.use_fp16_arithmetic=false;
  preprocess=makePipeline(rife_preprocess_comp_data,sizeof(rife_preprocess_comp_data),12);
  compose=makePipeline(rife_compose_comp_data,sizeof(rife_compose_comp_data),10);
  hybridRoot=qnnDirectory;hybridLibs=qnnLibraries;hybridQuality=qnnQuality;hybridStages=qnnStages;
  if(lowEdge){hybridRoot=apuRoot;hybridQuality=lowEdge;hybridStages=2;require(opt.use_fp16_storage,"G90T RIFE requires GPU FP16 storage");}
  display=std::make_unique<VulkanPresenter>(window,gpu);
 }
 ~Engine(){reset();textureComposer.reset();hybrid.reset();raw.release();rgba.release();imports.clear();display.reset();preprocess.reset();postprocess.reset();compose.reset();net.clear();}
 std::unique_ptr<ncnn::Pipeline> makePipeline(const char* source,size_t size,int constants,bool half=true){
  ncnn::Option conversionOpt=opt;conversionOpt.use_fp16_arithmetic=false;if(!half)conversionOpt.use_fp16_storage=false;
  std::vector<uint32_t> code;require(ncnn::compile_spirv_module(reinterpret_cast<const char*>(source),size,conversionOpt,code)==0,"RIFE conversion shader compile failed");
  auto p=std::make_unique<ncnn::Pipeline>(gpu);p->set_optimal_local_size_xyz(8,8,1);
  require(p->create(code.data(),code.size()*sizeof(uint32_t),{})==0,"RIFE conversion pipeline creation failed");
  require(p->shader_info().push_constant_count==constants,"RIFE conversion shader parameters missing");return p;
 }
 void reset(){previous.release();current.release();fullPrevious.release();fullCurrent.release();middle.release();timestep.release();hasMiddle=false;if(hybrid)hybrid->reset();if(textureComposer)textureComposer->reset();}
 void submit(AHardwareBuffer* buffer,int left,int top,int w,int h,int space){
  require(buffer,"Missing decoder hardware buffer");AHardwareBuffer_Desc desc{};AHardwareBuffer_describe(buffer,&desc);
  require((desc.usage&AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE)!=0,"Decoder buffer lacks GPU sampling usage");
  using GetId=int(*)(const AHardwareBuffer*,uint64_t*);static auto getId=reinterpret_cast<GetId>(dlsym(RTLD_DEFAULT,"AHardwareBuffer_getId"));uint64_t allocationId=0;
  require(getId&&getId(buffer,&allocationId)==0,"Decoder allocation identity unavailable");
  require(w>0&&h>0&&w<=4096&&h<=4096&&left>=0&&top>=0&&uint64_t(left)+w<=desc.width&&uint64_t(top)+h<=desc.height&&desc.width<=8192&&desc.height<=8192,"Invalid decoder crop or size");
  int transfer=(space>>22)&31;bool nextHdr=transfer==7;require(transfer!=8,"HLG interpolation is not configured");require(!nextHdr||desc.format==AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM,"HDR interpolation requires mapped 10-bit RGB frames");
  if(hdr!=nextHdr){reset();textureComposer.reset();imports.clear();display->setHdr(nextHdr);hdr=nextHdr;}
  int longEdge=std::max(w,h),quality=lowEdge?lowEdge:std::min(hybridQuality,longEdge<=1280?720:longEdge<=1600?900:1080);
  if(w!=width||h!=height||left!=cropLeft||top!=cropTop){__android_log_print(ANDROID_LOG_INFO,"ThorRIFE","decoder buffer=%ux%u crop=%d,%d %dx%d fp16=%d",desc.width,desc.height,left,top,w,h,opt.use_fp16_storage);reset();if(hybrid&&(((w<h)!=(hybrid->w<hybrid->h))||activeQuality!=quality)){textureComposer.reset();hybrid.reset();hybridAttempted=false;}raw.release();rgba.release();imports.clear();}width=w;height=h;cropLeft=left;cropTop=top;
  if(!hybridAttempted&&!hybridRoot.empty()){
   hybridAttempted=true;
   try{hybrid=std::make_unique<thor_hybrid::HybridRife>(gpu,allocator.get(),hybridRoot,hybridLibs,w<h,quality,hybridStages,lowEdge!=0);activeQuality=quality;__android_log_print(ANDROID_LOG_INFO,"ThorRIFE","%s active, tensor=%dx%d stages=%d memory_type=%u flags=%u",hybrid->apuBackend?"GPU+APU NNAPI":"GPU+NPU HTP",hybrid->w,hybrid->h,hybrid->stages,allocator->buffer_memory_type_index,unsigned(gpu->info.physicalDeviceMemoryProperties().memoryTypes[allocator->buffer_memory_type_index].propertyFlags));}
   catch(const std::exception& e){hybrid.reset();fallbackReason=e.what();__android_log_print(ANDROID_LOG_WARN,"ThorRIFE","accelerator unavailable, retaining Vulkan: %s",e.what());}
  }
  require(!hdr||bool(hybrid),"HDR interpolation needs the GPU/NPU texture path");
  if(hybrid&&!textureComposer){
   textureComposer=std::make_unique<thor_texture::Composer>(gpu,hybrid->stages==4?rife_texture_flow_full_comp_data:rife_texture_flow_fused_comp_data,hybrid->stages==4?sizeof(rife_texture_flow_full_comp_data):sizeof(rife_texture_flow_fused_comp_data),rife_texture_compose_comp_data,sizeof(rife_texture_compose_comp_data),true,false,hdr);
   textureComposer->configurePrepare(rife_texture_prepare_comp_data,sizeof(rife_texture_prepare_comp_data));
   textureComposer->configureProxy(rife_hdr_proxy_comp_data,sizeof(rife_hdr_proxy_comp_data));
  }
  if(lowEdge&&!hybrid&&!textureComposer){
   textureComposer=std::make_unique<thor_texture::Composer>(gpu,rife_texture_flow_separate_comp_data,sizeof(rife_texture_flow_separate_comp_data),rife_texture_compose_comp_data,sizeof(rife_texture_compose_comp_data),false,true);
   textureComposer->configurePrepare(rife_texture_prepare_ncnn_comp_data,sizeof(rife_texture_prepare_ncnn_comp_data),true);
  }
  ImportFrame* input=nullptr;
  // Image.getHardwareBuffer() can wrap the same allocation with a new pointer.
  // Its stable allocation ID lets decoder slots reuse their Vulkan pipelines.
  for(auto& entry:imports)if(entry->allocationId==allocationId&&entry->dataSpace==space){input=entry.get();break;}
  if(!input){
   // Retain references while imported; cap the cache even if codec rotates pools.
   if(imports.size()>=64)imports.erase(imports.begin());
   __android_log_print(ANDROID_LOG_DEBUG,"ThorRIFE","import allocation cache miss id=%llu entries=%zu",(unsigned long long)allocationId,imports.size());
   auto entry=std::make_unique<ImportFrame>();entry->allocationId=allocationId;entry->init(gpu,buffer,space,importOpt);input=entry.get();imports.push_back(std::move(entry));
  }
  // Keep source detail up to the physical display's fit resolution, independent
  // of the current preview/fullscreen view size. Rotation keeps this bound stable.
  float renderScale=(hybrid||lowEdge)?std::min({1.0f,float(displayLong)/std::max(w,h),float(displayShort)/std::min(w,h)}):1.0f;
  frameWidth=std::max(1,int(std::lround(w*renderScale)));frameHeight=std::max(1,int(std::lround(h*renderScale)));
  // Estimate flow separately; the displayed frame is never enlarged from this input.
  float scale=std::min(1.0f,float(lowEdge?lowEdge:128)/std::max(w,h));workWidth=std::max(1,int(std::lround(w*scale)));workHeight=std::max(1,int(std::lround(h*scale)));
  int pw=(workWidth+31)/32*32,ph=(workHeight+31)/32*32;size_t element=opt.use_fp16_storage?2u:4u;
  if(hybrid){scale=std::min({1.0f,float(hybrid->w)/w,float(hybrid->h)/h});workWidth=std::max(1,int(std::lround(w*scale)));workHeight=std::max(1,int(std::lround(h*scale)));pw=hybrid->w;ph=hybrid->h;element=2;}
  previous=current;current=ncnn::VkMat();fullPrevious=fullCurrent;fullCurrent=ncnn::VkMat();middle.release();hasMiddle=false;
  current.create(pw,ph,3,element,1,allocator.get());require(!current.empty(),"RIFE GPU input allocation failed");
  if(textureComposer){
   if(!hybrid){timestep.create(pw,ph,1,element,1,allocator.get());require(!timestep.empty(),"RIFE timestep allocation failed");}
   if(!input->textureImport)input->textureImport=std::make_unique<thor_texture::Shader>(gpu,rife_texture_import_comp_data,sizeof(rife_texture_import_comp_data),std::vector<int>{3,2},input->linearSampler?input->linearSampler:input->pipeline->sampler,false,hdr);
   auto importAt=std::chrono::steady_clock::now();textureComposer->import(*input->textureImport,input->image,current,desc.width,desc.height,left,top,w,h,frameWidth,frameHeight,workWidth,workHeight,input->linearSampler!=0,hybrid?nullptr:&timestep);auto encodedAt=std::chrono::steady_clock::now();if(hybrid){hybrid->encode(current);textureComposer->uploadFeature(hybrid->currentFeatureMap());}
   if(++nativeFrames%30==0)__android_log_print(ANDROID_LOG_INFO,"ThorRIFE","native import_us=%lld encode_us=%lld",(long long)std::chrono::duration_cast<std::chrono::microseconds>(encodedAt-importAt).count(),(long long)std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-encodedAt).count());
  }else{
   timestep.create(pw,ph,1,element,1,allocator.get());fullCurrent.create(int(desc.width),int(desc.height),size_t(4),1,allocator.get());raw=fullCurrent;require(!fullCurrent.empty()&&!timestep.empty(),"RIFE GPU input allocation failed");
   ncnn::VkCompute cmd(gpu);cmd.record_import_android_hardware_buffer(input->pipeline.get(),input->image,raw);
   cmd.record_pipeline(preprocess.get(),{raw,current,timestep},constants({int(desc.width),int(raw.cstep),left,top,w,h,pw,ph,int(current.cstep),workWidth,workHeight,1}),current);require(cmd.submit_and_wait()==0,"GPU decoder import/preprocess failed");
  }
  // Completion includes the FOREIGN ownership release, so Java may close Image.
 }
 long interpolate(){
  require(!previous.empty()&&!current.empty(),"RIFE requires matching consecutive frames");
  auto start=std::chrono::steady_clock::now();ncnn::VkCompute cmd(gpu);auto ex=net.create_extractor();
  if(hybrid){
   if(textureComposer){const auto& p=textureComposer->flowFrame(true),&c=textureComposer->flowFrame(false),&pf=textureComposer->feature(true),&cf=textureComposer->feature(false);hybrid->setFrameTextures(p.view,c.view,pf.view,cf.view,c.layout,textureComposer->linearSampler());}
   const auto& combined=hybrid->infer(previous,current);
   auto composeAt=std::chrono::steady_clock::now();
   require(bool(textureComposer),"QNN texture composer missing");
   textureComposer->runFused(combined,hybrid->finalBase(),hybrid->w,hybrid->h,frameWidth,frameHeight,workWidth,workHeight,hybrid->stages);hasMiddle=true;
   if(nativeFrames%30==0)__android_log_print(ANDROID_LOG_INFO,"ThorRIFE","native refine_us=%lld compose_us=%lld",(long long)std::chrono::duration_cast<std::chrono::microseconds>(composeAt-start).count(),(long long)std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-composeAt).count());
   return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-start).count();
  }
  ex.set_blob_vkallocator(allocator.get());ex.set_workspace_vkallocator(allocator.get());ex.set_staging_vkallocator(staging.get());
  require(ex.input("in0",previous)==0&&ex.input("in1",current)==0&&ex.input("in2",timestep)==0,"RIFE GPU input binding failed");
  ex.set_light_mode(false);
  ncnn::VkMat packedFlow,packedMask,flow,mask;
  // The real-time path uses the trained first two RIFE refinement stages.
  // Later stages remain in the pinned model but exceed this phone's frame budget.
  require(ex.extract("/Add_1_output_0",packedFlow,cmd)==0&&!packedFlow.empty(),"RIFE Vulkan flow inference failed");
  require(ex.extract("/block1/Slice_1_output_0",packedMask,cmd)==0&&!packedMask.empty(),"RIFE Vulkan mask inference failed");
  gpu->convert_packing(packedFlow,flow,1,lowEdge?1:(opt.use_fp16_storage?2:1),cmd,opt);
  gpu->convert_packing(packedMask,mask,1,lowEdge?1:(opt.use_fp16_storage?2:1),cmd,opt);
  require(flow.w==current.w&&flow.h==current.h&&flow.c==4&&mask.w==flow.w&&mask.h==flow.h&&mask.c==1,"Unexpected RIFE flow/mask shape");
  if(lowEdge){
   require(cmd.submit_and_wait()==0,"GPU flow submission failed");
   textureComposer->runSeparate(flow,mask,frameWidth,frameHeight,workWidth,workHeight);hasMiddle=true;
   return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-start).count();
  }
  middle.create(width,height,size_t(4),1,allocator.get());require(!middle.empty(),"RIFE composition allocation failed");
  cmd.record_pipeline(compose.get(),{fullPrevious,fullCurrent,flow,mask,middle},constants({width,height,fullCurrent.w,int(fullCurrent.cstep),workWidth,workHeight,flow.w,int(flow.cstep),cropLeft,cropTop}),middle);
  require(cmd.submit_and_wait()==0,"RIFE GPU inference submission failed");
  require(middle.w==width&&middle.h==height&&middle.c==1&&middle.elempack==1&&middle.elemsize==4,"Unexpected RIFE GPU output shape/type");
  hasMiddle=true;return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-start).count();
 }
 void resize(int w,int h){if(display->resizeSurface(w,h)&&!current.empty())present(2);}
 void present(int which){
  require(which>=0&&which<=2,"Invalid RIFE output selector");require(which!=1||hasMiddle,"No synthesized RIFE frame");
  if(which==1){if(textureComposer){const auto& t=textureComposer->middle();display->presentImage(t.image,t.layout,t.w,t.h);}else display->present(middle,width,height);return;}
  if(textureComposer){const auto& frame=textureComposer->frame(which==0);display->presentImage(frame.image,frame.layout,frame.w,frame.h);return;}
  const auto& frame=which==0?fullPrevious:fullCurrent;require(!frame.empty(),"Missing GPU frame");
  display->present(frame,width,height,cropLeft,cropTop);
 }
};
Engine& engine(jlong handle){require(handle!=0,"RIFE session already closed");return *reinterpret_cast<Engine*>(handle);}
}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_RifeNative_configureHybrid(JNIEnv* env,jclass,jstring root,jstring libraries,jint quality,jint stages){
 std::lock_guard<std::mutex> lock(gpuMutex);
 const char* r=env->GetStringUTFChars(root,nullptr);if(!r)return;
 const char* l=env->GetStringUTFChars(libraries,nullptr);if(!l){env->ReleaseStringUTFChars(root,r);return;}
 qnnDirectory=r;qnnLibraries=l;qnnStages=stages==4?4:2;qnnQuality=qnnStages==4?(quality==720?720:540):((quality==900||quality==1080)?quality:720);env->ReleaseStringUTFChars(root,r);env->ReleaseStringUTFChars(libraries,l);
}
extern "C" JNIEXPORT jstring JNICALL Java_local_thor_bilichannels_RifeNative_description(JNIEnv* env,jclass,jlong h){
 std::lock_guard<std::mutex> lock(gpuMutex);try{auto& e=engine(h);std::string s=std::string(e.hybrid?(e.hybrid->apuBackend?"GPU+APU NNAPI":"GPU+NPU"):"Vulkan")+" · "+std::to_string(e.hybrid?e.hybrid->stages:2)+"阶段 · 估计 "+std::to_string(e.workWidth)+"×"+std::to_string(e.workHeight)+" · 合成 "+std::to_string(e.frameWidth)+"×"+std::to_string(e.frameHeight)+" · 显示 "+std::to_string(e.display->surfaceWidth())+"×"+std::to_string(e.display->surfaceHeight());if(e.hdr)s+=" · HDR10 / FP16合成 / 10bit输出";if(e.hybrid&&e.hybrid->apuBackend)s+=" · "+e.hybrid->apuDevices;else if(!e.fallbackReason.empty())s+=" · 加速器不可用："+e.fallbackReason;return env->NewStringUTF(s.c_str());}catch(const std::exception& e){raise(env,e);return nullptr;}
}
extern "C" JNIEXPORT jlong JNICALL Java_local_thor_bilichannels_RifeNative_create(JNIEnv* env,jclass,jobject surface,jstring directory,jint longEdge,jint shortEdge,jint estimateEdge,jstring apuDirectory){
 std::lock_guard<std::mutex> lock(gpuMutex);ANativeWindow* window=nullptr;const char* path=nullptr;const char* apu=nullptr;
 try{window=ANativeWindow_fromSurface(env,surface);require(window!=nullptr,"Invalid output Surface");path=env->GetStringUTFChars(directory,nullptr);require(path!=nullptr,"Invalid model path");apu=env->GetStringUTFChars(apuDirectory,nullptr);require(apu!=nullptr,"Invalid APU directory");auto e=std::make_unique<Engine>(window,path,longEdge,shortEdge,estimateEdge,apu);env->ReleaseStringUTFChars(apuDirectory,apu);apu=nullptr;env->ReleaseStringUTFChars(directory,path);ANativeWindow_release(window);return reinterpret_cast<jlong>(e.release());}
 catch(const std::exception& e){if(apu)env->ReleaseStringUTFChars(apuDirectory,apu);if(path)env->ReleaseStringUTFChars(directory,path);if(window)ANativeWindow_release(window);raise(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_RifeNative_destroy(JNIEnv*,jclass,jlong h){std::lock_guard<std::mutex> lock(gpuMutex);delete reinterpret_cast<Engine*>(h);}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_RifeNative_reset(JNIEnv* env,jclass,jlong h){std::lock_guard<std::mutex> lock(gpuMutex);try{engine(h).reset();}catch(const std::exception& e){raise(env,e);}}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_RifeNative_nativeSubmit(JNIEnv* env,jclass,jlong handle,jobject buffer,jint left,jint top,jint w,jint h,jint dataspace){
 std::lock_guard<std::mutex> lock(gpuMutex);try{engine(handle).submit(AHardwareBuffer_fromHardwareBuffer(env,buffer),left,top,w,h,dataspace);}catch(const std::exception& e){raise(env,e);}
}
extern "C" JNIEXPORT jlong JNICALL Java_local_thor_bilichannels_RifeNative_interpolate(JNIEnv* env,jclass,jlong h){std::lock_guard<std::mutex> lock(gpuMutex);try{return engine(h).interpolate();}catch(const std::exception& e){raise(env,e);return 0;}}
extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_RifeNative_present(JNIEnv* env,jclass,jlong h,jint which){std::lock_guard<std::mutex> lock(gpuMutex);try{engine(h).present(which);}catch(const std::exception& e){raise(env,e);}}

extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_RifeNative_resize(JNIEnv* env,jclass,jlong h,jint w,jint height){std::lock_guard<std::mutex> lock(gpuMutex);try{engine(h).resize(w,height);}catch(const std::exception& e){raise(env,e);}}

extern "C" JNIEXPORT void JNICALL Java_local_thor_bilichannels_RifeNative_submitImage(JNIEnv* env,jclass,jlong handle,jlong pointer,jint space){
 std::lock_guard<std::mutex> lock(gpuMutex);try{
  auto* image=reinterpret_cast<AImage*>(pointer);require(image,"Native image closed");AHardwareBuffer* buffer=nullptr;AImageCropRect crop{};
  require(AImage_getHardwareBuffer(image,&buffer)==AMEDIA_OK&&AImage_getCropRect(image,&crop)==AMEDIA_OK,"Native decoder buffer unavailable");
  engine(handle).submit(buffer,crop.left,crop.top,crop.right-crop.left,crop.bottom-crop.top,space);
 }catch(const std::exception& e){raise(env,e);}
}
