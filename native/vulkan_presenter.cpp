#include "gpu.h"
#include "mat.h"
#include <android/native_window.h>
#include <android/data_space.h>
#include "vulkan_presenter.h"
#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include <chrono>
#include <android/log.h>

using namespace ncnn;
namespace {
void check(VkResult r,const char* operation){if(r!=VK_SUCCESS)throw std::runtime_error(std::string(operation)+": VkResult "+std::to_string(r));}
// ncnn's minimal Vulkan header omits the core blit entry point.
struct ThorImageBlit {VkImageSubresourceLayers srcSubresource;VkOffset3D srcOffsets[2];VkImageSubresourceLayers dstSubresource;VkOffset3D dstOffsets[2];};
using ThorCmdBlitImage=void(VKAPI_PTR*)(VkCommandBuffer,VkImage,VkImageLayout,VkImage,VkImageLayout,uint32_t,const ThorImageBlit*,VkFilter);
constexpr uint64_t timeout=2000000000ULL;
constexpr VkColorSpaceKHR hdr10Space=static_cast<VkColorSpaceKHR>(1000104008);
struct QueueLease {
 const ncnn::VulkanDevice* device; VkQueue queue;
 explicit QueueLease(const ncnn::VulkanDevice* d):device(d),queue(d->acquire_queue(d->info.compute_queue_family_index())) {
  if(!queue)throw std::runtime_error("Vulkan compute queue unavailable");
 }
 ~QueueLease(){device->reclaim_queue(device->info.compute_queue_family_index(),queue);}
};
}
struct VulkanPresenter::State {
 ANativeWindow* window=nullptr;
 VkInstance instance=VK_NULL_HANDLE;VkSurfaceKHR surface=VK_NULL_HANDLE;
 const ncnn::VulkanDevice* vkdev=nullptr;ThorCmdBlitImage blitImage=nullptr;
 VkPhysicalDevice physical=VK_NULL_HANDLE;VkDevice device=VK_NULL_HANDLE;
 uint32_t family=0;VkSwapchainKHR swapchain=VK_NULL_HANDLE;VkFormat format=VK_FORMAT_UNDEFINED;VkExtent2D extent{};bool hdr=false;VkColorSpaceKHR colorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
 std::vector<VkImage> images;
 VkCommandPool pool=VK_NULL_HANDLE;VkCommandBuffer command=VK_NULL_HANDLE;
 VkSemaphore acquired=VK_NULL_HANDLE;std::vector<VkSemaphore> finished;VkFence fence=VK_NULL_HANDLE;
 VkImage frame=VK_NULL_HANDLE;VkDeviceMemory frameMemory=VK_NULL_HANDLE;
 int width=0,height=0;
 unsigned presentCount=0;
 ~State(){
  if(device)vkDeviceWaitIdle(device);clearFrame();
  if(device){if(fence)vkDestroyFence(device,fence,nullptr);if(acquired)vkDestroySemaphore(device,acquired,nullptr);for(auto semaphore:finished)vkDestroySemaphore(device,semaphore,nullptr);if(pool)vkDestroyCommandPool(device,pool,nullptr);if(swapchain)vkdev->vkDestroySwapchainKHR(device,swapchain,nullptr);}
  if(surface)vkDestroySurfaceKHR(instance,surface,nullptr);if(window)ANativeWindow_release(window);
 }
 uint32_t memoryType(uint32_t mask,VkMemoryPropertyFlags flags){VkPhysicalDeviceMemoryProperties props;vkGetPhysicalDeviceMemoryProperties(physical,&props);for(uint32_t i=0;i<props.memoryTypeCount;i++)if((mask&(1u<<i))&&(props.memoryTypes[i].propertyFlags&flags)==flags)return i;throw std::runtime_error("Vulkan memory type unavailable");}
 void init(ANativeWindow* w,const ncnn::VulkanDevice* gpu){
  vkdev=gpu;instance=ncnn::get_gpu_instance();device=gpu->vkdevice();physical=gpu->info.physical_device();family=gpu->info.compute_queue_family_index();
  if(!gpu->info.support_VK_KHR_swapchain()||!ncnn::support_VK_KHR_android_surface)throw std::runtime_error("Vulkan Android presentation unavailable");
  blitImage=reinterpret_cast<ThorCmdBlitImage>(ncnn::vkGetDeviceProcAddr(device,"vkCmdBlitImage"));if(!blitImage)throw std::runtime_error("Vulkan blit unavailable");
  window=w;ANativeWindow_acquire(w);
  VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};sci.window=w;check(vkCreateAndroidSurfaceKHR(instance,&sci,nullptr,&surface),"vkCreateAndroidSurfaceKHR");
  VkBool32 supported=false;check(vkGetPhysicalDeviceSurfaceSupportKHR(physical,family,surface,&supported),"surface support");
  if(!supported)throw std::runtime_error("Inference queue cannot present to this display");
  VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pi.queueFamilyIndex=family;pi.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;check(vkCreateCommandPool(device,&pi,nullptr,&pool),"command pool");
  VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ai.commandPool=pool;ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ai.commandBufferCount=1;check(vkAllocateCommandBuffers(device,&ai,&command),"command buffer");
  VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};check(vkCreateSemaphore(device,&si,nullptr,&acquired),"acquire semaphore");
  VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};check(vkCreateFence(device,&fi,nullptr,&fence),"fence");createSwapchain();
 }
 void createSwapchain(){
  if(swapchain)check(vkDeviceWaitIdle(device),"resize completion");
  VkSurfaceCapabilitiesKHR caps;check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical,surface,&caps),"surface capabilities");
  if(!(caps.supportedUsageFlags&VK_IMAGE_USAGE_TRANSFER_DST_BIT))throw std::runtime_error("Display does not support Vulkan transfer output");
  uint32_t n=0;check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&n,nullptr),"surface formats");std::vector<VkSurfaceFormatKHR> formats(n);check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&n,formats.data()),"surface formats");
  colorSpace=hdr?hdr10Space:VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;format=VK_FORMAT_UNDEFINED;for(auto f:formats){bool bits=hdr?(f.format==VK_FORMAT_A2B10G10R10_UNORM_PACK32||f.format==VK_FORMAT_A2R10G10B10_UNORM_PACK32):(f.format==VK_FORMAT_R8G8B8A8_UNORM||f.format==VK_FORMAT_B8G8R8A8_UNORM);if(bits&&f.colorSpace==colorSpace){format=f.format;break;}}
  if(format==VK_FORMAT_UNDEFINED)throw std::runtime_error(hdr?"No HDR10 10-bit Vulkan surface format":"No SDR UNORM Vulkan surface format");
  VkFormatProperties fp;vkGetPhysicalDeviceFormatProperties(physical,format,&fp);if(!(fp.optimalTilingFeatures&VK_FORMAT_FEATURE_BLIT_DST_BIT))throw std::runtime_error("Display format does not support blit");
  extent=caps.currentExtent;if(extent.width==UINT32_MAX){extent.width=std::clamp<uint32_t>(std::max(1,ANativeWindow_getWidth(window)),caps.minImageExtent.width,caps.maxImageExtent.width);extent.height=std::clamp<uint32_t>(std::max(1,ANativeWindow_getHeight(window)),caps.minImageExtent.height,caps.maxImageExtent.height);}
  if(!extent.width||!extent.height)throw std::runtime_error("Empty Vulkan surface");
  // Blit output is upright in SurfaceView coordinates. currentTransform is a
  // promise that WE pre-rotated the pixels; declaring it without doing so makes
  // Android rotate the picture again (90/270 degrees on a landscape phone).
  if(!(caps.supportedTransforms&VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR))throw std::runtime_error("Upright Vulkan presentation unsupported");
  uint32_t buffers=caps.minImageCount+1;if(caps.maxImageCount)buffers=std::min(buffers,caps.maxImageCount);
  VkCompositeAlphaFlagBitsKHR alpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;for(auto a:{VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR})if(caps.supportedCompositeAlpha&a){alpha=a;break;}
  VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};ci.surface=surface;ci.minImageCount=buffers;ci.imageFormat=format;ci.imageColorSpace=colorSpace;ci.imageExtent=extent;ci.imageArrayLayers=1;ci.imageUsage=VK_IMAGE_USAGE_TRANSFER_DST_BIT;ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE;ci.preTransform=VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;ci.compositeAlpha=alpha;ci.presentMode=VK_PRESENT_MODE_FIFO_KHR;ci.clipped=VK_TRUE;ci.oldSwapchain=swapchain;
  VkSwapchainKHR next;check(vkdev->vkCreateSwapchainKHR(device,&ci,nullptr,&next),"swapchain");if(swapchain)vkdev->vkDestroySwapchainKHR(device,swapchain,nullptr);swapchain=next;
  if(ANativeWindow_setBuffersDataSpace(window,hdr?ADATASPACE_BT2020_PQ:ADATASPACE_SRGB)!=0)throw std::runtime_error("Output color space unavailable");
  __android_log_print(ANDROID_LOG_INFO,"ThorRIFE","swapchain extent=%ux%u displayTransform=%u pixelTransform=%u",extent.width,extent.height,unsigned(caps.currentTransform),unsigned(ci.preTransform));
  check(vkdev->vkGetSwapchainImagesKHR(device,swapchain,&n,nullptr),"swapchain images");images.resize(n);check(vkdev->vkGetSwapchainImagesKHR(device,swapchain,&n,images.data()),"swapchain images");
  for(auto semaphore:finished)vkDestroySemaphore(device,semaphore,nullptr);finished.assign(n,VK_NULL_HANDLE);VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};for(auto& semaphore:finished)check(vkCreateSemaphore(device,&si,nullptr,&semaphore),"present semaphore");
 }
 void clearFrame(){
  if(frame)vkDestroyImage(device,frame,nullptr);if(frameMemory)vkFreeMemory(device,frameMemory,nullptr);
  frame=VK_NULL_HANDLE;frameMemory=VK_NULL_HANDLE;width=height=0;
 }
 void resize(int w,int h){
  // Decoder buffers may include padded rows while synthesized frames do not.
  // Reuse the larger image instead of reallocating GPU memory twice per pair.
  if(w<=width&&h<=height)return;clearFrame();
  VkFormatProperties props;vkGetPhysicalDeviceFormatProperties(physical,VK_FORMAT_R8G8B8A8_UNORM,&props);if((props.optimalTilingFeatures&(VK_FORMAT_FEATURE_BLIT_SRC_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT))!=(VK_FORMAT_FEATURE_BLIT_SRC_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT))throw std::runtime_error("RGBA blit unsupported");
  VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ii.imageType=VK_IMAGE_TYPE_2D;ii.format=VK_FORMAT_R8G8B8A8_UNORM;ii.extent={uint32_t(w),uint32_t(h),1};ii.mipLevels=ii.arrayLayers=1;ii.samples=VK_SAMPLE_COUNT_1_BIT;ii.tiling=VK_IMAGE_TILING_OPTIMAL;ii.usage=VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;check(vkCreateImage(device,&ii,nullptr,&frame),"frame image");
  VkMemoryRequirements req;vkGetImageMemoryRequirements(device,frame,&req);VkMemoryAllocateInfo mi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};mi.allocationSize=req.size;mi.memoryTypeIndex=memoryType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);check(vkAllocateMemory(device,&mi,nullptr,&frameMemory),"frame memory");check(vkBindImageMemory(device,frame,frameMemory,0),"bind frame");width=w;height=h;
 }
 void barrier(VkImage image,VkImageLayout before,VkImageLayout after,VkAccessFlags src,VkAccessFlags dst,VkPipelineStageFlags from,VkPipelineStageFlags to){VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.srcAccessMask=src;b.dstAccessMask=dst;b.oldLayout=before;b.newLayout=after;b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.image=image;b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};vkCmdPipelineBarrier(command,from,to,0,0,nullptr,0,nullptr,1,&b);}
 void present(const ncnn::VkMat& rgba,int w,int h,int left,int top,VkImage external=VK_NULL_HANDLE,VkImageLayout externalLayout=VK_IMAGE_LAYOUT_UNDEFINED){
  auto started=std::chrono::steady_clock::now();auto elapsed=[](auto a,auto b){return std::chrono::duration_cast<std::chrono::microseconds>(b-a).count();};
  if(!external&&(rgba.empty()||rgba.elemsize!=4||rgba.elempack!=1||left<0||top<0||rgba.w<left+w||rgba.h<top+h))throw std::runtime_error("Invalid GPU RGBA frame");
  QueueLease lease(vkdev);VkQueue queue=lease.queue;
  if(!external)resize(rgba.w,rgba.h);VkImage source=external?external:frame;
  uint32_t index;VkResult result=vkdev->vkAcquireNextImageKHR(device,swapchain,timeout,acquired,VK_NULL_HANDLE,&index);if(result==VK_ERROR_OUT_OF_DATE_KHR){createSwapchain();result=vkdev->vkAcquireNextImageKHR(device,swapchain,timeout,acquired,VK_NULL_HANDLE,&index);}if(result!=VK_SUBOPTIMAL_KHR)check(result,"acquire image");
  auto acquiredAt=std::chrono::steady_clock::now();
  check(vkResetCommandBuffer(command,0),"reset commands");VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;check(vkBeginCommandBuffer(command,&begin),"begin commands");
  if(!external){
  VkBufferMemoryBarrier inputBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
  inputBarrier.srcAccessMask=rgba.data->access_flags;inputBarrier.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
  inputBarrier.srcQueueFamilyIndex=inputBarrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
  inputBarrier.buffer=rgba.buffer();inputBarrier.offset=rgba.buffer_offset();inputBarrier.size=uint64_t(rgba.w)*rgba.h*4;
  vkCmdPipelineBarrier(command,rgba.data->stage_flags,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,1,&inputBarrier,0,nullptr);
  barrier(frame,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
  VkBufferImageCopy copy{};copy.bufferOffset=rgba.buffer_offset();copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};copy.imageExtent={uint32_t(rgba.w),uint32_t(rgba.h),1};vkCmdCopyBufferToImage(command,rgba.buffer(),frame,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
  barrier(frame,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
  }else barrier(external,externalLayout,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
  barrier(images[index],VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
  ThorImageBlit blit{};blit.srcSubresource=blit.dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};blit.srcOffsets[0]={left,top,0};blit.srcOffsets[1]={left+w,top+h,1};blit.dstOffsets[1]={int32_t(extent.width),int32_t(extent.height),1};blitImage(command,source,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,images[index],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&blit,VK_FILTER_LINEAR);
  if(external)barrier(external,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,externalLayout,VK_ACCESS_TRANSFER_READ_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
  barrier(images[index],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,VK_ACCESS_TRANSFER_WRITE_BIT,0,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);check(vkEndCommandBuffer(command),"end commands");
  VkPipelineStageFlags stage=VK_PIPELINE_STAGE_TRANSFER_BIT;VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.waitSemaphoreCount=1;submit.pWaitSemaphores=&acquired;submit.pWaitDstStageMask=&stage;submit.commandBufferCount=1;submit.pCommandBuffers=&command;submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&finished[index];check(vkResetFences(device,1,&fence),"reset fence");check(vkQueueSubmit(queue,1,&submit,fence),"submit");check(vkWaitForFences(device,1,&fence,VK_TRUE,timeout),"render completion");
  auto renderedAt=std::chrono::steady_clock::now();
  // A semaphore belongs to its swapchain image and is reused only after that
  // image is acquired again. Presentation can continue while inference runs.
  VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};present.waitSemaphoreCount=1;present.pWaitSemaphores=&finished[index];present.swapchainCount=1;present.pSwapchains=&swapchain;present.pImageIndices=&index;result=vkdev->vkQueuePresentKHR(queue,&present);
  if(!external){rgba.data->access_flags=VK_ACCESS_TRANSFER_READ_BIT;rgba.data->stage_flags=VK_PIPELINE_STAGE_TRANSFER_BIT;}
  // Android reports SUBOPTIMAL every frame when the intentional IDENTITY
  // transform differs from the display hint. Rebuilding cannot change that.
  // Actual view size changes are handled by resizeSurface; out-of-date remains
  // a real swapchain invalidation and must still be recovered.
  if(result==VK_ERROR_OUT_OF_DATE_KHR)createSwapchain();else if(result!=VK_SUBOPTIMAL_KHR)check(result,"present");
  if(++presentCount%60==0)__android_log_print(ANDROID_LOG_INFO,"ThorRIFE","present us acquire=%lld copy=%lld queue=%lld extent=%ux%u",(long long)elapsed(started,acquiredAt),(long long)elapsed(acquiredAt,renderedAt),(long long)elapsed(renderedAt,std::chrono::steady_clock::now()),extent.width,extent.height);
 }
};
VulkanPresenter::VulkanPresenter(ANativeWindow* window,const ncnn::VulkanDevice* gpu):s(new State){s->init(window,gpu);}
VulkanPresenter::~VulkanPresenter()=default;
void VulkanPresenter::present(const ncnn::VkMat& rgba,int w,int h,int left,int top){s->present(rgba,w,h,left,top);}

void VulkanPresenter::presentImage(VkImage image,VkImageLayout layout,int w,int h,int left,int top){s->present(ncnn::VkMat(),w,h,left,top,image,layout);}

bool VulkanPresenter::resizeSurface(int w,int h){
 if(w<=0||h<=0||w>16384||h>16384)throw std::runtime_error("Invalid surface size");
 if(s->extent.width==uint32_t(w)&&s->extent.height==uint32_t(h))return false;
 check(vkDeviceWaitIdle(s->device),"surface resize completion");
 if(ANativeWindow_setBuffersGeometry(s->window,w,h,0)!=0)throw std::runtime_error("Surface geometry update failed");
 s->createSwapchain();
 if(s->extent.width!=uint32_t(w)||s->extent.height!=uint32_t(h))throw std::runtime_error("Surface resize did not take effect");
 return true;
}
int VulkanPresenter::surfaceWidth()const{return s->extent.width;}
int VulkanPresenter::surfaceHeight()const{return s->extent.height;}
void VulkanPresenter::setHdr(bool enabled){if(s->hdr==enabled)return;check(vkDeviceWaitIdle(s->device),"HDR transition completion");s->hdr=enabled;s->createSwapchain();}
bool VulkanPresenter::hdrOutput()const{return s->hdr;}
