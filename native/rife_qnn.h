#pragma once
#include "onnxruntime_cxx_api.h"
#include "rife_qnn_compute.h"
#include <array>
#include <chrono>
#include <android/log.h>
#include "rife_nnapi.h"
namespace thor_hybrid {
struct ConvIsland {
 Ort::Session session{nullptr};
 std::unique_ptr<thor_apu::Island> apu;
 Ort::MemoryInfo memory=Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);
 std::vector<int64_t> shape,outputShape;
 ConvIsland(Ort::Env& env,const std::string& path,const std::string& backend){
  Ort::SessionOptions options;options.SetIntraOpNumThreads(1);
  options.AddConfigEntry("session.disable_cpu_ep_fallback","1");
  options.AppendExecutionProvider("QNN",{{"backend_path",backend},{"enable_htp_fp16_precision","1"},
   {"htp_performance_mode","burst"},{"htp_graph_finalization_optimization_mode","3"}});
  session=Ort::Session(env,path.c_str(),options);
  shape=session.GetInputTypeInfo(0).GetTensorTypeAndShapeInfo().GetShape();
  outputShape=session.GetOutputTypeInfo(0).GetTensorTypeAndShapeInfo().GetShape();
  check(session.GetInputTypeInfo(0).GetTensorTypeAndShapeInfo().GetElementType()==ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16&&session.GetOutputTypeInfo(0).GetTensorTypeAndShapeInfo().GetElementType()==ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16,"QNN requires FP16 model I/O");
  check(shape.size()==4&&shape[0]==1&&outputShape.size()==4&&outputShape[0]==1,"Unexpected QNN tensor rank");
 }
 ConvIsland(const std::string& path,int w,int h,int channels,const std::vector<thor_apu::Device>& devices){
  apu=std::make_unique<thor_apu::Island>(path,w,h,channels,devices);shape=apu->shape;outputShape=apu->outputShape;
 }
 void run(const ncnn::VkMat& input,ncnn::VkMat& output){
  check(input.w==shape[2]&&input.h==shape[1]&&input.c==shape[3]&&input.elemsize==2&&input.cstep==size_t(input.w*input.h),"QNN input layout mismatch");
  check(output.w==outputShape[2]&&output.h==outputShape[1]&&output.c==outputShape[3]&&output.elemsize==2&&output.cstep==size_t(output.w*output.h),"QNN output layout mismatch");
  check(input.allocator->invalidate(input.data)==0,"QNN input cache invalidate failed");
  if(apu){apu->run(input.mapped_ptr(),output.mapped_ptr());check(output.allocator->flush(output.data)==0,"APU output cache flush failed");return;}
  auto x=Ort::Value::CreateTensor<Ort::Float16_t>(memory,(Ort::Float16_t*)input.mapped_ptr(),input.total(),shape.data(),4);
  auto y=Ort::Value::CreateTensor<Ort::Float16_t>(memory,(Ort::Float16_t*)output.mapped_ptr(),output.total(),outputShape.data(),4);
  const char* inputs[]={"x"};const char* outputs[]={"y"};
  session.Run(Ort::RunOptions{nullptr},inputs,&x,1,outputs,&y,1);
  check(output.allocator->flush(output.data)==0,"QNN output cache flush failed");
 }
};
class HybridRife {
 ncnn::VkBlobAllocator* allocator;
 Ort::Env env{ORT_LOGGING_LEVEL_WARNING,"ThorRifeQNN"};
 SharedCompute compute;
 std::unique_ptr<ConvIsland> encoder,block0,block1,block2,block3;
 ncnn::VkMat previousFeature,currentFeature,x0,x1,y0,y1,x2,x3,y2,y3,flow1,flow2;
 bool featureReady=false;
 unsigned profileFrame=0;
 VkImageView previousTexture=VK_NULL_HANDLE,currentTexture=VK_NULL_HANDLE,previousFeatureTexture=VK_NULL_HANDLE,currentFeatureTexture=VK_NULL_HANDLE;
 VkImageLayout textureLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;VkSampler textureSampler=VK_NULL_HANDLE;
public:
 int w=0,h=0,stages=2;
 bool apuBackend=false;std::string apuDevices;
 HybridRife(const ncnn::VulkanDevice* g,ncnn::VkBlobAllocator* a,const std::string& root,const std::string& libs,bool portrait,int quality,int count=2,bool useApu=false):allocator(a),compute(g,root,count==4),stages(count),apuBackend(useApu){
  // ncnn chooses a memory type lazily at the first allocation. Its default
  // mappable=false before that point does not describe the actual GPU memory.
  ncnn::VkMat allocationProbe(4,size_t(4),allocator);
  check(!allocationProbe.empty(),"QNN allocator initialization failed");
  check(allocator->mappable,"QNN requires host-visible Vulkan memory");
  if(useApu){
   check(stages==2&&(quality==128||quality==192||quality==256),"Invalid APU profile");
   w=quality;h=((quality*9/16+31)/32)*32;if(portrait)std::swap(w,h);
   auto targets=thor_apu::devices();
   encoder=std::make_unique<ConvIsland>(root+"/encode.rfnn",w,h,3,targets);
   block0=std::make_unique<ConvIsland>(root+"/block0.rfnn",w/8,h/8,15,targets);
   block1=std::make_unique<ConvIsland>(root+"/block1.rfnn",w/4,h/4,20,targets);
   apuDevices=encoder->apu->deviceName+"/"+block0->apu->deviceName+"/"+block1->apu->deviceName;
  }else{
  check((stages==2&&(quality==720||quality==900||quality==1080))||(stages==4&&(quality==540||quality==720)),"Invalid estimation profile");
  std::string directory=root+(portrait?"/portrait":"/landscape")+(stages==4?"-full-"+std::to_string(quality):quality==720?"":"-"+std::to_string(quality)),backend=libs+"/libQnnHtp.so";
  // DSP images remain assets, never arm64 JNI libraries. QNN selects the SoC.
  std::string dsp=root+";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp";
  setenv("ADSP_LIBRARY_PATH",dsp.c_str(),1);
  encoder=std::make_unique<ConvIsland>(env,directory+"/encode.onnx",backend);
  w=int(encoder->shape[2]);h=int(encoder->shape[1]);
  check(w>=32&&h>=32&&w<=1920&&h<=1920&&w%32==0&&h%32==0&&encoder->shape[3]==3,"Invalid QNN model geometry");
  block0=std::make_unique<ConvIsland>(env,directory+"/block0.onnx",backend);
  block1=std::make_unique<ConvIsland>(env,directory+"/block1.onnx",backend);
  }
  auto make=[&](int width,int height,int channels,bool fp32=false){ncnn::VkMat m(width,height,channels,size_t(fp32?4:2),1,allocator);check(!m.empty(),"QNN buffer allocation failed");return m;};
  previousFeature=make(w,h,4);currentFeature=make(w,h,4);
  x0=make(w/8,h/8,15);x1=make(w/4,h/4,20);y0=make(w/16,h/16,24);y1=make(w/8,h/8,24);
  if(stages==4){
   std::string directory=root+(portrait?"/portrait-full-":"/landscape-full-")+std::to_string(quality),backend=libs+"/libQnnHtp.so";
   block2=std::make_unique<ConvIsland>(env,directory+"/block2.onnx",backend);
   block3=std::make_unique<ConvIsland>(env,directory+"/block3.onnx",backend);
   x2=make(w/2,h/2,20);x3=make(w,h,20);y2=make(w/4,h/4,24);y3=make(w/2,h/2,24);
   flow1=make(w,h,5,true);flow2=make(w,h,5,true);
  }
 }
 const ncnn::VkMat& finalBase()const{return stages==4?flow2:y0;}
 const ncnn::VkMat& currentFeatureMap()const{return currentFeature;}
 void setFrameTextures(VkImageView previous,VkImageView current,VkImageView previousFeatureView,VkImageView currentFeatureView,VkImageLayout layout,VkSampler sampler){previousTexture=previous;currentTexture=current;previousFeatureTexture=previousFeatureView;currentFeatureTexture=currentFeatureView;textureLayout=layout;textureSampler=sampler;}
 void reset(){featureReady=false;}
 void encode(const ncnn::VkMat& current){
  // Composer import already fences shader-write -> host-read visibility.
  if(featureReady)std::swap(previousFeature,currentFeature);
  encoder->run(current,currentFeature);featureReady=true;
 }
 const ncnn::VkMat& infer(const ncnn::VkMat& previous,const ncnn::VkMat& current){
  using Clock=std::chrono::steady_clock;Clock::time_point marks[9];int mark=0;marks[mark++]=Clock::now();
  check(featureReady,"QNN encoded frame missing");
  compute.begin();compute.dispatch(compute.prepare.get(),{previous,current,previousFeature,currentFeature,y0,x0},{w,h,8,0,1},w/8,h/8,1);compute.finish();
  marks[mark++]=Clock::now();
  block0->run(x0,y0);
  marks[mark++]=Clock::now();
  compute.begin();
  compute.dispatch(compute.prepare.get(),{previous,current,previousFeature,currentFeature,y0,x1},{w,h,4,1,1},w/4,h/4,1);compute.finish();
  marks[mark++]=Clock::now();
  block1->run(x1,y1);
  marks[mark++]=Clock::now();
  if(stages==4){
   // Retain the sparse first-stage path. Materialize only the flow needed by
   // later refinements, then fuse each reconstruction with the next prepare.
   compute.begin();
   // Reconstruct each stage-2 flow pixel once while preparing x2. Resident
   // RGBA frame textures replace four manual bilinear reads of each endpoint.
   if(compute.middleTex&&previousTexture&&currentTexture&&previousFeatureTexture&&currentFeatureTexture)
    compute.dispatchTextured(compute.middleTex.get(),{flow1,x2,y1,y0},previousTexture,currentTexture,previousFeatureTexture,currentFeatureTexture,textureLayout,textureSampler,{w,h,2,2,1},w/2,h/2,1);
   else
    compute.dispatch(compute.middlePrepare.get(),{previous,current,previousFeature,currentFeature,flow1,x2,y1,y0},{w,h,2,2,1},w/2,h/2,1);
   compute.finish();
   marks[mark++]=Clock::now();
   block2->run(x2,y2);
   marks[mark++]=Clock::now();
   compute.begin();
   // Keep the accumulated FP32 flow in registers while building x3. Save it
   // for final composition without a second full-frame read or dispatch.
   if(compute.refineTex&&previousTexture&&currentTexture&&previousFeatureTexture&&currentFeatureTexture)
    compute.dispatchTextured(compute.refineTex.get(),{flow2,x3,y2,flow1},previousTexture,currentTexture,previousFeatureTexture,currentFeatureTexture,textureLayout,textureSampler,{w,h,1,3,1},w,h,1);
   else
    compute.dispatch(compute.refinePrepare.get(),{previous,current,previousFeature,currentFeature,flow1,x3,y2,flow2},{w,h,1,3,1},w,h,1);
   compute.finish();
   marks[mark++]=Clock::now();
   block3->run(x3,y3);
   marks[mark++]=Clock::now();
   if(++profileFrame%30==0){
    long long us[8];for(int i=0;i<8;i++)us[i]=std::chrono::duration_cast<std::chrono::microseconds>(marks[i+1]-marks[i]).count();
    __android_log_print(ANDROID_LOG_INFO,"ThorRIFE","stages us prepare0=%lld block0=%lld prepare1=%lld block1=%lld prepare2=%lld block2=%lld prepare3=%lld block3=%lld",us[0],us[1],us[2],us[3],us[4],us[5],us[6],us[7]);
   }
   return y3;
  }
  return y1; // Final flow reconstruction is fused directly into texture storage.
 }
};
}
