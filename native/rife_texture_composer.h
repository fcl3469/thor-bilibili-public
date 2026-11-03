#pragma once
// Preserve native frame dimensions; hardware texture filtering replaces repeated
// SSBO bilinear sampling. Explicit queue, image and host visibility, no CPU pixels.
#include "gpu.h"
#include "mat.h"
#include "pipeline.h"
#include "rife_descriptors.h"
#include <vector>
#include <string>
#include <stdexcept>
#include <memory>
namespace thor_texture {
using namespace ncnn;
inline void require(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
struct Texture {
 const ncnn::VulkanDevice* gpu;VkImage image=0;VkImageView view=0;VkDeviceMemory memory=0;int w,h;VkImageLayout layout=VK_IMAGE_LAYOUT_UNDEFINED;
 Texture(const ncnn::VulkanDevice* g,int width,int height,VkFormat format):gpu(g),w(width),h(height){try{
  VkFormatProperties props;vkGetPhysicalDeviceFormatProperties(g->info.physical_device(),format,&props);require(format!=VK_FORMAT_R8G8B8A8_UNORM||(props.optimalTilingFeatures&VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)!=0,"linear texture format unsupported");
  VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ii.imageType=VK_IMAGE_TYPE_2D;ii.format=format;ii.extent={uint32_t(w),uint32_t(h),1};ii.mipLevels=ii.arrayLayers=1;ii.samples=VK_SAMPLE_COUNT_1_BIT;ii.tiling=VK_IMAGE_TILING_OPTIMAL;ii.usage=VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_STORAGE_BIT;ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
  require(vkCreateImage(g->vkdevice(),&ii,nullptr,&image)==VK_SUCCESS,"texture image");
  VkMemoryRequirements req;vkGetImageMemoryRequirements(g->vkdevice(),image,&req);VkMemoryAllocateInfo mi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};mi.allocationSize=req.size;mi.memoryTypeIndex=g->find_memory_index(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,0,0);
  require(vkAllocateMemory(g->vkdevice(),&mi,nullptr,&memory)==VK_SUCCESS,"texture memory");require(vkBindImageMemory(g->vkdevice(),image,memory,0)==VK_SUCCESS,"texture bind");
  VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};vi.image=image;vi.viewType=VK_IMAGE_VIEW_TYPE_2D;vi.format=format;vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};require(vkCreateImageView(g->vkdevice(),&vi,nullptr,&view)==VK_SUCCESS,"texture view");
 }catch(...){release();throw;}}
 void release(){if(view)vkDestroyImageView(gpu->vkdevice(),view,nullptr);if(image)vkDestroyImage(gpu->vkdevice(),image,nullptr);if(memory)vkFreeMemory(gpu->vkdevice(),memory,nullptr);}
 ~Texture(){release();}
};
struct Shader {
 const ncnn::VulkanDevice* gpu;VkShaderModule module=0;VkDescriptorSetLayout descriptors=0;VkPipelineLayout layout=0;VkPipeline pipeline=0;std::vector<int> types;
 Shader(const ncnn::VulkanDevice* g,const char* code,size_t size,const std::vector<int>& bindings,VkSampler sampler,bool nearestFlow=false,bool wideOutput=false):gpu(g),types(bindings){try{
  std::string source(code,size);if(wideOutput){size_t at=0;while((at=source.find("rgba8",at))!=std::string::npos){source.replace(at,5,"rgba16f");at+=7;}}
  ncnn::Option opt;opt.use_fp16_packed=opt.use_fp16_storage=opt.use_fp16_arithmetic=false;std::vector<uint32_t> spv;require(ncnn::compile_spirv_module(source.data(),source.size(),opt,spv)==0,"texture shader compile");ncnn::ShaderInfo info;require(ncnn::resolve_shader_info(spv.data(),spv.size()*4,info)==0,"texture shader reflection");require(info.binding_count==int(types.size()),"texture shader bindings");
  module=g->compile_shader_module(spv.data(),spv.size()*4,8,8,1);require(module!=0,"texture module");
  std::vector<VkDescriptorSetLayoutBinding> b(types.size());for(size_t i=0;i<b.size();i++){b[i].binding=i;b[i].descriptorCount=1;b[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;b[i].descriptorType=types[i]==1?VK_DESCRIPTOR_TYPE_STORAGE_BUFFER:types[i]==2?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;b[i].pImmutableSamplers=types[i]==3?(nearestFlow&&i>=2?g->immutable_texelfetch_sampler():&sampler):nullptr;}
  VkDescriptorSetLayoutCreateInfo di{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};di.flags=g->info.support_VK_KHR_push_descriptor()?VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR:0;di.bindingCount=b.size();di.pBindings=b.data();require(vkCreateDescriptorSetLayout(g->vkdevice(),&di,nullptr,&descriptors)==VK_SUCCESS,"texture descriptors");
  require(g->create_pipeline_layout(info.push_constant_count,descriptors,&layout)==0,"texture pipeline layout");require(g->create_pipeline(module,layout,{},g->info.subgroup_size(),&pipeline)==0,"texture pipeline");
 }catch(...){release();throw;}}
 void release(){if(pipeline)vkDestroyPipeline(gpu->vkdevice(),pipeline,nullptr);if(layout)vkDestroyPipelineLayout(gpu->vkdevice(),layout,nullptr);if(descriptors)vkDestroyDescriptorSetLayout(gpu->vkdevice(),descriptors,nullptr);if(module)vkDestroyShaderModule(gpu->vkdevice(),module,nullptr);}
 ~Shader(){release();}
};
struct ImageBinding {
 VkImageView view;VkImageLayout layout;
 ImageBinding(Texture* t):view(t->view),layout(t->layout){}
 ImageBinding(VkImageView v,VkImageLayout l):view(v),layout(l){}
};
class Composer {
 const ncnn::VulkanDevice* gpu;VkDevice device;VkCommandPool pool=0;VkCommandBuffer cmd=0;VkFence fence=0;VkSampler sampler=0;bool fusedFlow=false,hdr=false;thor_gpu::Descriptors descriptors;
 std::unique_ptr<Shader> pack,compose,prepare,proxy;std::unique_ptr<Texture> previous,current,middleImage,flowMap,weightMap,previousFeature,currentFeature,previousProxy,currentProxy;
 VkFormat frameFormat()const{return hdr?VK_FORMAT_R16G16B16A16_SFLOAT:VK_FORMAT_R8G8B8A8_UNORM;}
 void begin(){descriptors.reset();require(vkResetCommandBuffer(cmd,0)==VK_SUCCESS,"texture reset");VkCommandBufferBeginInfo b{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};b.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;require(vkBeginCommandBuffer(cmd,&b)==VK_SUCCESS,"texture begin");VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};mb.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT;mb.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&mb,0,nullptr,0,nullptr);}
 void transition(Texture& t,VkImageLayout to){VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.srcAccessMask=t.layout==VK_IMAGE_LAYOUT_UNDEFINED?0:VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT;b.dstAccessMask=to==VK_IMAGE_LAYOUT_GENERAL?VK_ACCESS_SHADER_WRITE_BIT:to==VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL?VK_ACCESS_TRANSFER_WRITE_BIT:VK_ACCESS_SHADER_READ_BIT;b.oldLayout=t.layout;b.newLayout=to;b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.image=t.image;b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,0,nullptr,0,nullptr,1,&b);t.layout=to;}
 void dispatch(Shader& s,const std::vector<ncnn::VkMat>& buffers,const std::vector<ImageBinding>& images,const std::vector<int>& constants,int w,int h){
  std::vector<VkDescriptorBufferInfo> bi(s.types.size());std::vector<VkDescriptorImageInfo> ii(s.types.size());std::vector<VkWriteDescriptorSet> wr(s.types.size());size_t b=0,t=0;
  for(size_t i=0;i<wr.size();i++){wr[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};wr[i].dstBinding=i;wr[i].descriptorCount=1;if(s.types[i]==1){const auto& v=buffers[b++];bi[i]={v.buffer(),v.buffer_offset(),v.buffer_capacity()};wr[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;wr[i].pBufferInfo=&bi[i];}else{auto& v=images[t++];ii[i]={sampler,v.view,v.layout};wr[i].descriptorType=s.types[i]==2?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;wr[i].pImageInfo=&ii[i];}}
  vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,s.pipeline);descriptors.bind(cmd,s.layout,s.descriptors,wr);vkCmdPushConstants(cmd,s.layout,VK_SHADER_STAGE_COMPUTE_BIT,0,constants.size()*4,constants.data());vkCmdDispatch(cmd,(w+7)/8,(h+7)/8,1);
 }
 void finish(){VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};mb.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;mb.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_HOST_READ_BIT;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_HOST_BIT,0,1,&mb,0,nullptr,0,nullptr);require(vkEndCommandBuffer(cmd)==VK_SUCCESS,"texture end");require(vkResetFences(device,1,&fence)==VK_SUCCESS,"texture fence reset");VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.commandBufferCount=1;si.pCommandBuffers=&cmd;auto family=gpu->info.compute_queue_family_index();auto queue=gpu->acquire_queue(family);require(queue!=0,"texture queue");auto status=vkQueueSubmit(queue,1,&si,fence);gpu->reclaim_queue(family,queue);require(status==VK_SUCCESS&&vkWaitForFences(device,1,&fence,VK_TRUE,2000000000ULL)==VK_SUCCESS,"texture submit/wait");}
 void maps(int w,int h){
  if(!flowMap||flowMap->w!=w||flowMap->h!=h){flowMap=std::make_unique<Texture>(gpu,w,h,VK_FORMAT_R32G32B32A32_SFLOAT);weightMap=std::make_unique<Texture>(gpu,w,h,VK_FORMAT_R32_SFLOAT);}
  for(auto t:{flowMap.get(),weightMap.get()})transition(*t,VK_IMAGE_LAYOUT_GENERAL);
 }
 void composeFrame(int width,int height,int workWidth,int workHeight,int left,int top){
  for(auto t:{flowMap.get(),weightMap.get()})transition(*t,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  if(!middleImage||middleImage->w!=width||middleImage->h!=height)middleImage=std::make_unique<Texture>(gpu,width,height,frameFormat());
  transition(*middleImage,VK_IMAGE_LAYOUT_GENERAL);
  dispatch(*compose,{},{previous.get(),current.get(),flowMap.get(),weightMap.get(),middleImage.get()},{width,height,current->w,current->h,workWidth,workHeight,flowMap->w,flowMap->w*flowMap->h,left,top},width,height);transition(*middleImage,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);finish();
 }
public:
 Composer(const ncnn::VulkanDevice* g,const char* packCode,size_t packSize,const char* composeCode,size_t composeSize,bool fused=false,bool separate=false,bool hdrOutput=false):gpu(g),device(g->vkdevice()),fusedFlow(fused),hdr(hdrOutput),descriptors(g){try{
  VkSamplerCreateInfo s{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};s.magFilter=s.minFilter=VK_FILTER_LINEAR;s.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;s.addressModeU=s.addressModeV=s.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;s.maxAnisotropy=1;require(vkCreateSampler(device,&s,nullptr,&sampler)==VK_SUCCESS,"texture sampler");
  pack=std::make_unique<Shader>(g,packCode,packSize,(fusedFlow||separate)?std::vector<int>{1,1,2,2}:std::vector<int>{1,2,2},sampler);compose=std::make_unique<Shader>(g,composeCode,composeSize,std::vector<int>{3,3,3,3,2},sampler,true,hdr);
  VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pi.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;pi.queueFamilyIndex=g->info.compute_queue_family_index();require(vkCreateCommandPool(device,&pi,nullptr,&pool)==VK_SUCCESS,"texture pool");VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ai.commandPool=pool;ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ai.commandBufferCount=1;require(vkAllocateCommandBuffers(device,&ai,&cmd)==VK_SUCCESS,"texture command");VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};require(vkCreateFence(device,&fi,nullptr,&fence)==VK_SUCCESS,"texture fence");
 }catch(...){release();throw;}}
 void release(){if(pool)vkDeviceWaitIdle(device);reset();pack.reset();compose.reset();prepare.reset();proxy.reset();if(sampler)vkDestroySampler(device,sampler,nullptr);if(fence)vkDestroyFence(device,fence,nullptr);if(pool)vkDestroyCommandPool(device,pool,nullptr);}
 ~Composer(){release();}
 void reset(){previous.reset();current.reset();middleImage.reset();flowMap.reset();weightMap.reset();previousFeature.reset();currentFeature.reset();previousProxy.reset();currentProxy.reset();}
 void configurePrepare(const char* code,size_t size,bool separate=false){prepare=std::make_unique<Shader>(gpu,code,size,separate?std::vector<int>{3,1,1}:std::vector<int>{3,1},sampler);}
 void configureProxy(const char* code,size_t size){if(hdr)proxy=std::make_unique<Shader>(gpu,code,size,std::vector<int>{3,2},sampler);}
 const Texture& middle() const {require(bool(middleImage),"middle texture missing");return *middleImage;}
 const Texture& frame(bool old=false) const {require(old?bool(previous):bool(current),"texture frame missing");return old?*previous:*current;}
 const Texture& flowFrame(bool old=false)const{if(!hdr)return frame(old);require(old?bool(previousProxy):bool(currentProxy),"HDR motion proxy missing");return old?*previousProxy:*currentProxy;}
 const Texture& feature(bool old=false) const {require(old?bool(previousFeature):bool(currentFeature),"feature texture missing");return old?*previousFeature:*currentFeature;}
 VkSampler linearSampler()const{return sampler;}
 void uploadFeature(const ncnn::VkMat& map){
  require(map.w>0&&map.h>0&&map.c==4&&map.elemsize==2,"feature texture shape");
  if(currentFeature&&(currentFeature->w!=map.w||currentFeature->h!=map.h)){previousFeature.reset();currentFeature.reset();}
  std::swap(previousFeature,currentFeature);
  if(!currentFeature)currentFeature=std::make_unique<Texture>(gpu,map.w,map.h,VK_FORMAT_R16G16B16A16_SFLOAT);
  begin();transition(*currentFeature,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  VkBufferImageCopy copy{};copy.bufferOffset=map.buffer_offset();copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};copy.imageExtent={uint32_t(map.w),uint32_t(map.h),1};
  vkCmdCopyBufferToImage(cmd,map.buffer(),currentFeature->image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
  transition(*currentFeature,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);finish();
 }
 void import(Shader& converter,const ncnn::VkImageMat& decoded,ncnn::VkMat& normalized,int sourceWidth,int sourceHeight,int left,int top,int width,int height,int renderWidth,int renderHeight,int workWidth,int workHeight,bool linear,const ncnn::VkMat* timestep=nullptr){
  if(current&&(current->w!=renderWidth||current->h!=renderHeight))reset();std::swap(previous,current);if(!current)current=std::make_unique<Texture>(gpu,renderWidth,renderHeight,frameFormat());
  begin();VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;b.oldLayout=VK_IMAGE_LAYOUT_GENERAL;b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;b.srcQueueFamilyIndex=(~2u);b.dstQueueFamilyIndex=gpu->info.compute_queue_family_index();b.image=decoded.image();b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,0,nullptr,0,nullptr,1,&b);
  transition(*current,VK_IMAGE_LAYOUT_GENERAL);dispatch(converter,{},{ImageBinding(decoded.imageview(),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL),current.get()},{sourceWidth,sourceHeight,left,top,width,height,renderWidth,renderHeight,int(linear)},renderWidth,renderHeight);
  b.srcAccessMask=VK_ACCESS_SHADER_READ_BIT;b.dstAccessMask=0;b.oldLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;b.newLayout=VK_IMAGE_LAYOUT_GENERAL;b.srcQueueFamilyIndex=gpu->info.compute_queue_family_index();b.dstQueueFamilyIndex=(~2u);vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,nullptr,0,nullptr,1,&b);
  transition(*current,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);Texture* motion=current.get();if(hdr){require(bool(proxy),"HDR motion converter missing");std::swap(previousProxy,currentProxy);if(!currentProxy)currentProxy=std::make_unique<Texture>(gpu,renderWidth,renderHeight,VK_FORMAT_R8G8B8A8_UNORM);transition(*currentProxy,VK_IMAGE_LAYOUT_GENERAL);dispatch(*proxy,{},{current.get(),currentProxy.get()},{renderWidth,renderHeight},renderWidth,renderHeight);transition(*currentProxy,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);motion=currentProxy.get();}require(bool(prepare),"texture prepare missing");dispatch(*prepare,timestep?std::vector<ncnn::VkMat>{normalized,*timestep}:std::vector<ncnn::VkMat>{normalized},{motion},{0,0,renderWidth,renderHeight,workWidth,workHeight,normalized.w,normalized.h,renderWidth,renderHeight},normalized.w,normalized.h);finish();normalized.data->access_flags=VK_ACCESS_SHADER_WRITE_BIT;normalized.data->stage_flags=VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
 }
 void submit(const ncnn::VkMat& frame){
  if(current&&(current->w!=frame.w||current->h!=frame.h))reset();std::swap(previous,current);if(!current)current=std::make_unique<Texture>(gpu,frame.w,frame.h,VK_FORMAT_R8G8B8A8_UNORM);
  begin();transition(*current,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);VkBufferImageCopy cp{};cp.bufferOffset=frame.buffer_offset();cp.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};cp.imageExtent={uint32_t(frame.w),uint32_t(frame.h),1};vkCmdCopyBufferToImage(cmd,frame.buffer(),current->image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&cp);transition(*current,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);finish();
  frame.data->access_flags=VK_ACCESS_TRANSFER_READ_BIT;frame.data->stage_flags=VK_PIPELINE_STAGE_TRANSFER_BIT;
 }
 // Diagnostics only: production presentation samples the native image directly.
 void copyMiddle(ncnn::VkMat& output){require(bool(middleImage)&&output.w==middleImage->w&&output.h==middleImage->h,"middle readback shape");begin();transition(*middleImage,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);VkBufferImageCopy cp{};cp.bufferOffset=output.buffer_offset();cp.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};cp.imageExtent={uint32_t(output.w),uint32_t(output.h),1};vkCmdCopyImageToBuffer(cmd,middleImage->image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,output.buffer(),1,&cp);transition(*middleImage,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);VkMemoryBarrier b{VK_STRUCTURE_TYPE_MEMORY_BARRIER};b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;b.dstAccessMask=VK_ACCESS_HOST_READ_BIT;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&b,0,nullptr,0,nullptr);finish();output.data->access_flags=VK_ACCESS_TRANSFER_WRITE_BIT;output.data->stage_flags=VK_PIPELINE_STAGE_TRANSFER_BIT;}
 void run(const ncnn::VkMat& combined,int width,int height,int workWidth,int workHeight,int left,int top){
  require(previous&&current&&!fusedFlow,"texture frame pair/mode missing");begin();maps(combined.w,combined.h);
  dispatch(*pack,{combined},{flowMap.get(),weightMap.get()},{combined.w,combined.h,int(combined.cstep)},combined.w,combined.h);
  composeFrame(width,height,workWidth,workHeight,left,top);
 }
 void runSeparate(const ncnn::VkMat& flow,const ncnn::VkMat& mask,int width,int height,int workWidth,int workHeight){
  require(previous&&current&&!fusedFlow,"texture frame pair missing");begin();maps(flow.w,flow.h);
  dispatch(*pack,{flow,mask},{flowMap.get(),weightMap.get()},{flow.w,flow.h,int(flow.cstep)},flow.w,flow.h);
  composeFrame(width,height,workWidth,workHeight,0,0);
 }
 void runFused(const ncnn::VkMat& y1,const ncnn::VkMat& y0,int paddedWidth,int paddedHeight,int width,int height,int workWidth,int workHeight,int stages=2){
  require(previous&&current&&fusedFlow,"fused texture frame pair/mode missing");begin();maps(paddedWidth,paddedHeight);
  dispatch(*pack,{y1,y0},{flowMap.get(),weightMap.get()},{paddedWidth,paddedHeight,stages==4?1:4,stages-1,1},paddedWidth,paddedHeight);
  composeFrame(width,height,workWidth,workHeight,0,0);
 }
};
}
