#pragma once
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
#include <unistd.h>
#include "sherpa-onnx/csrc/offline-recognizer.h"
#include "sherpa-onnx/csrc/voice-activity-detector.h"

// Local Japanese recognition only; this class never owns an audio output.
class ThorRecognizer {
 std::unique_ptr<sherpa_onnx::OfflineRecognizer> recognizer;
 std::unique_ptr<sherpa_onnx::VoiceActivityDetector> vad;
public:
 const bool batch;
 explicit ThorRecognizer(const std::string& model,const std::string& libs):batch(access((model+"/encoder.onnx").c_str(),R_OK)==0){
  sherpa_onnx::OfflineRecognizerConfig config;config.feat_config.sampling_rate=16000;config.feat_config.feature_dim=80;config.model_config.tokens=model+"/tokens.txt";config.decoding_method="greedy_search";
  if(batch){
   config.model_config.provider="cpu";config.model_config.num_threads=2;
   config.model_config.transducer.encoder_filename=model+"/encoder.onnx";config.model_config.transducer.decoder_filename=model+"/decoder.onnx";config.model_config.transducer.joiner_filename=model+"/joiner.onnx";
   sherpa_onnx::VadModelConfig voice;voice.silero_vad.model=model+"/silero_vad.onnx";voice.silero_vad.threshold=.5;voice.silero_vad.min_speech_duration=.12;voice.silero_vad.min_silence_duration=.3;voice.silero_vad.max_speech_duration=30;voice.num_threads=1;
   if(!voice.Validate())throw std::runtime_error("语音检测模型校验失败");vad.reset(new sherpa_onnx::VoiceActivityDetector(voice,35));
  }else{
   setenv("ADSP_LIBRARY_PATH",(model+";"+libs+";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp").c_str(),1);
   config.model_config.provider="qnn";config.model_config.num_threads=1;config.model_config.sense_voice.model=model+"/libmodel.so";config.model_config.sense_voice.language="ja";config.model_config.sense_voice.use_itn=true;
   auto& qnn=config.model_config.sense_voice.qnn_config;qnn.backend_lib=libs+"/libQnnHtp.so";qnn.system_lib=libs+"/libQnnSystem.so";qnn.context_binary=model+"/model.bin";
  }
  if(!config.Validate())throw std::runtime_error("日语识别模型校验失败");recognizer.reset(new sherpa_onnx::OfflineRecognizer(config));
 }
 std::string recognize(std::vector<float>& samples){
  if(samples.empty()||samples.size()>(batch?480000:160000))throw std::runtime_error("字幕音轨区间长度无效");
  std::vector<std::pair<int,int>> regions;
  if(batch){
   // Normalize only analysis PCM; playback audio and system volume stay untouched.
   float peak=0;for(float value:samples)peak=std::max(peak,std::abs(value));float gain=peak>0?std::min(64.0f,std::max(1.0f,.5f/peak)):1;
   for(float& value:samples)value*=gain;vad->Reset();
   for(size_t at=0;at<samples.size();at+=512)vad->AcceptWaveform(samples.data()+at,std::min<size_t>(512,samples.size()-at));vad->Flush();
   while(!vad->Empty()){const auto& part=vad->Front();int start=std::max(0,part.start)/16,end=std::min<int>(samples.size(),part.start+part.samples.size())/16;if(end>start)regions.emplace_back(start,end);vad->Pop();}
  }
  sherpa_onnx::OfflineRecognitionResult result;
  if(!batch||!regions.empty()){auto stream=recognizer->CreateStream();stream->AcceptWaveform(16000,samples.data(),samples.size());recognizer->DecodeStream(stream.get());result=stream->GetResult();}
  if(batch){result.lang="<|ja|>";result.event="<|Speech|>";}
  std::string output=result.AsJsonString();
  if(batch){std::string extra=",\"speech_regions\":[";for(size_t i=0;i<regions.size();i++){if(i)extra+=",";extra+="["+std::to_string(regions[i].first)+","+std::to_string(regions[i].second)+"]";}extra+="]";auto end=output.find_last_of('}');if(end==std::string::npos)throw std::runtime_error("语音识别结果无效");output.insert(end,extra);}
  return output;
 }
};
