#pragma once
// Explicit host/GPU synchronization for mapped Vulkan tensors.
// ORT/QNN may still copy internally; this is NOT a DMA-BUF zero-copy claim.
#include "pipeline.h"
#include "rife_descriptors.h"
#include <fstream>
#include <iterator>
namespace thor_hybrid {
using namespace ncnn; // ncnn dynamically loads the Vulkan entry points.
inline void check(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
struct SharedCompute {
    const ncnn::VulkanDevice* gpu;
    VkDevice device;thor_gpu::Descriptors descriptors;
    VkCommandPool pool=VK_NULL_HANDLE;
    VkCommandBuffer cmd=VK_NULL_HANDLE;
    VkFence fence=VK_NULL_HANDLE;
    std::unique_ptr<ncnn::Pipeline> prepare,flow,prepareFull,flowFull,refinePrepare,middlePrepare,middleTex,refineTex;
    SharedCompute(const ncnn::VulkanDevice* g,const std::string& path,bool full=false):gpu(g),device(g->vkdevice()),descriptors(g){
        auto pipeline=[&](const char* name){
            std::ifstream f(path+"/"+name);check(bool(f),"shader file");
            std::string code((std::istreambuf_iterator<char>(f)),std::istreambuf_iterator<char>());
            ncnn::Option opt;opt.use_fp16_storage=false;opt.use_fp16_arithmetic=false;
            std::vector<uint32_t> spv;check(ncnn::compile_spirv_module(code.data(),code.size(),opt,spv)==0,"compile shader");
            auto p=std::make_unique<ncnn::Pipeline>(gpu);p->set_local_size_xyz(8,8,1);
            check(p->create(spv.data(),spv.size()*4,{})==0,"create pipeline");return p;
        };
        prepare=pipeline("rife_hybrid_prepare_vec.comp");flow=pipeline("rife_hybrid_flow.comp");
        if(full){middlePrepare=pipeline("rife_hybrid_middle_prepare.comp");middleTex=pipeline("rife_hybrid_middle_tex.comp");prepareFull=pipeline("rife_hybrid_prepare_full.comp");flowFull=pipeline("rife_hybrid_flow_full.comp");refinePrepare=pipeline("rife_hybrid_refine_prepare.comp");refineTex=pipeline("rife_hybrid_refine_tex.comp");}
        VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pi.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;pi.queueFamilyIndex=gpu->info.compute_queue_family_index();
        check(vkCreateCommandPool(device,&pi,nullptr,&pool)==VK_SUCCESS,"command pool");
        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ai.commandPool=pool;ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ai.commandBufferCount=1;
        check(vkAllocateCommandBuffers(device,&ai,&cmd)==VK_SUCCESS,"command buffer");
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};check(vkCreateFence(device,&fi,nullptr,&fence)==VK_SUCCESS,"fence");
    }
    ~SharedCompute(){if(fence)vkDestroyFence(device,fence,nullptr);if(pool)vkDestroyCommandPool(device,pool,nullptr);}
    void begin(){descriptors.reset();check(vkResetCommandBuffer(cmd,0)==VK_SUCCESS,"reset cmd");VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;check(vkBeginCommandBuffer(cmd,&bi)==VK_SUCCESS,"begin cmd");
        VkMemoryBarrier b{VK_STRUCTURE_TYPE_MEMORY_BARRIER};b.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT;b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&b,0,nullptr,0,nullptr);
    }
    void dispatch(ncnn::Pipeline* p,const std::vector<ncnn::VkMat>& mats,const std::vector<int>& constants,int w,int h,int c){
        std::vector<VkDescriptorBufferInfo> infos(mats.size());std::vector<VkWriteDescriptorSet> writes(mats.size());
        for(size_t i=0;i<mats.size();i++){infos[i]={mats[i].buffer(),mats[i].buffer_offset(),mats[i].buffer_capacity()};writes[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&infos[i];}
        vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,p->pipeline());
        descriptors.bind(cmd,p->pipeline_layout(),p->descriptorset_layout(),writes);
        vkCmdPushConstants(cmd,p->pipeline_layout(),VK_SHADER_STAGE_COMPUTE_BIT,0,constants.size()*4,constants.data());
        vkCmdDispatch(cmd,(w+7)/8,(h+7)/8,c);
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    }
    void dispatchTextured(ncnn::Pipeline* p,const std::vector<ncnn::VkMat>& mats,VkImageView a,VkImageView b,VkImageView ea,VkImageView eb,VkImageLayout layout,VkSampler sampler,const std::vector<int>& constants,int w,int h,int c){
        check(mats.size()==4,"textured dispatch buffers");
        VkDescriptorImageInfo images[4]={{sampler,a,layout},{sampler,b,layout},{sampler,ea,layout},{sampler,eb,layout}};
        std::vector<VkDescriptorBufferInfo> infos(mats.size());
        std::vector<VkWriteDescriptorSet> writes(4+mats.size());
        for(int i=0;i<4;i++){writes[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;writes[i].pImageInfo=&images[i];}
        for(size_t i=0;i<mats.size();i++){infos[i]={mats[i].buffer(),mats[i].buffer_offset(),mats[i].buffer_capacity()};auto& w=writes[4+i];w={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};w.dstBinding=4+i;w.descriptorCount=1;w.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;w.pBufferInfo=&infos[i];}
        vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,p->pipeline());
        descriptors.bind(cmd,p->pipeline_layout(),p->descriptorset_layout(),writes);
        vkCmdPushConstants(cmd,p->pipeline_layout(),VK_SHADER_STAGE_COMPUTE_BIT,0,constants.size()*4,constants.data());
        vkCmdDispatch(cmd,(w+7)/8,(h+7)/8,c);
        VkMemoryBarrier textureBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};textureBarrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;textureBarrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&textureBarrier,0,nullptr,0,nullptr);
    }
    void finish(){
        VkMemoryBarrier b{VK_STRUCTURE_TYPE_MEMORY_BARRIER};b.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;b.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&b,0,nullptr,0,nullptr);
        check(vkEndCommandBuffer(cmd)==VK_SUCCESS,"end cmd");check(vkResetFences(device,1,&fence)==VK_SUCCESS,"reset fence");
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.commandBufferCount=1;si.pCommandBuffers=&cmd;
        auto family=gpu->info.compute_queue_family_index();auto queue=gpu->acquire_queue(family);check(queue!=VK_NULL_HANDLE,"queue");
        VkResult status=vkQueueSubmit(queue,1,&si,fence);gpu->reclaim_queue(family,queue);check(status==VK_SUCCESS,"submit");
        check(vkWaitForFences(device,1,&fence,VK_TRUE,10000000000ULL)==VK_SUCCESS,"wait");
    }
};
} // namespace thor_hybrid
using thor_hybrid::SharedCompute;
