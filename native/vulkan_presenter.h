#pragma once
#include <cstdint>
#include "gpu.h"
#include <memory>
struct ANativeWindow;
namespace ncnn {class VulkanDevice;class VkMat;}
// Shares ncnn's device and sole compute queue; accepts GPU-resident packed RGBA.
class VulkanPresenter {
public:
 VulkanPresenter(ANativeWindow* window,const ncnn::VulkanDevice* gpu);
 ~VulkanPresenter();
 bool resizeSurface(int width,int height);
 int surfaceWidth()const;
 int surfaceHeight()const;
 void setHdr(bool enabled);
 bool hdrOutput()const;
 void present(const ncnn::VkMat& rgba,int width,int height,int left=0,int top=0);
 void presentImage(VkImage image,VkImageLayout layout,int width,int height,int left=0,int top=0);
 VulkanPresenter(const VulkanPresenter&)=delete;
 VulkanPresenter& operator=(const VulkanPresenter&)=delete;
private:
 struct State;
 std::unique_ptr<State> s;
};
