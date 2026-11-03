#pragma once
#include "gpu.h"
#include <stdexcept>
#include <vector>
namespace thor_gpu {
// Mali drivers need not expose push descriptors. Allocate ordinary sets per
// submission; reset only after the previous submission's fence has completed.
class Descriptors {
 const ncnn::VulkanDevice* gpu;VkDescriptorPool pool=VK_NULL_HANDLE;
public:
 explicit Descriptors(const ncnn::VulkanDevice* g):gpu(g){}
 ~Descriptors(){if(pool)ncnn::vkDestroyDescriptorPool(gpu->vkdevice(),pool,nullptr);}
 void reset(){if(pool&&ncnn::vkResetDescriptorPool(gpu->vkdevice(),pool,0)!=VK_SUCCESS)throw std::runtime_error("descriptor pool reset");}
 void bind(VkCommandBuffer cmd,VkPipelineLayout layout,VkDescriptorSetLayout setLayout,std::vector<VkWriteDescriptorSet>& writes){
  if(gpu->info.support_VK_KHR_push_descriptor()){gpu->vkCmdPushDescriptorSetKHR(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,writes.size(),writes.data());return;}
  if(!pool){
   VkDescriptorPoolSize sizes[]={{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,256},{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,256},{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,256}};
   VkDescriptorPoolCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};info.maxSets=32;info.poolSizeCount=3;info.pPoolSizes=sizes;
   if(ncnn::vkCreateDescriptorPool(gpu->vkdevice(),&info,nullptr,&pool)!=VK_SUCCESS)throw std::runtime_error("descriptor pool creation");
  }
  VkDescriptorSetAllocateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};info.descriptorPool=pool;info.descriptorSetCount=1;info.pSetLayouts=&setLayout;
  VkDescriptorSet set=VK_NULL_HANDLE;if(ncnn::vkAllocateDescriptorSets(gpu->vkdevice(),&info,&set)!=VK_SUCCESS)throw std::runtime_error("descriptor allocation");
  for(auto& write:writes)write.dstSet=set;
  ncnn::vkUpdateDescriptorSets(gpu->vkdevice(),writes.size(),writes.data(),0,nullptr);
  ncnn::vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,nullptr);
 }
};
}
