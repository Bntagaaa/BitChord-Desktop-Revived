#include "sink.h"
#define NOMINMAX
#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <ksmedia.h>
#include <wrl/client.h>
#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <cmath>
namespace bc {
using Microsoft::WRL::ComPtr;
static void check(HRESULT hr) { if(FAILED(hr)) throw std::runtime_error("WASAPI failure"); }
class Wasapi final : public Sink {
 bool com=false, paused=true; UINT32 capacity=0; uint64_t last=0;
 ComPtr<IMMDeviceEnumerator> enumerator; ComPtr<IMMDevice> device;
 ComPtr<IAudioClient> client; ComPtr<IAudioRenderClient> render;
public:
 Wasapi(uint32_t r,uint32_t c,const std::string& endpoint,bool integer=false) {
  bits=integer?16:32;
  rate=r; channels=c; backend=2; timing=1;
  check(CoInitializeEx(nullptr,COINIT_MULTITHREADED)); com=true;
  try {
   check(CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,IID_PPV_ARGS(&enumerator)));
   if(endpoint.empty()) { if(FAILED(enumerator->GetDefaultAudioEndpoint(eRender,eMultimedia,&device))) throw DeviceUnavailable("WASAPI default endpoint unavailable"); }
   else {
    int count=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,endpoint.c_str(),-1,nullptr,0);
    if(count<=0) throw std::runtime_error("endpoint UTF8");
    std::wstring id(size_t(count),L'\0'); MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,endpoint.c_str(),-1,id.data(),count);
    if(FAILED(enumerator->GetDevice(id.c_str(),&device))) throw DeviceUnavailable("WASAPI endpoint unavailable");
   }
   check(device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,reinterpret_cast<void**>(client.GetAddressOf())));
   WAVEFORMATEXTENSIBLE format{};
   format.Format.wFormatTag=WAVE_FORMAT_EXTENSIBLE; format.Format.nChannels=WORD(c);
   format.Format.nSamplesPerSec=r; format.Format.wBitsPerSample=WORD(bits);
   format.Format.nBlockAlign=WORD(c*(bits/8)); format.Format.nAvgBytesPerSec=r*c*(bits/8);
   format.Format.cbSize=22; format.Samples.wValidBitsPerSample=WORD(bits);
   format.dwChannelMask=c==1?SPEAKER_FRONT_CENTER:c==2?(SPEAKER_FRONT_LEFT|SPEAKER_FRONT_RIGHT):0;
   format.SubFormat=bits==32?KSDATAFORMAT_SUBTYPE_IEEE_FLOAT:KSDATAFORMAT_SUBTYPE_PCM;
   check(client->Initialize(AUDCLNT_SHAREMODE_SHARED,AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM|AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
     1000000,0,&format.Format,nullptr));
   check(client->GetBufferSize(&capacity)); check(client->GetService(IID_PPV_ARGS(&render)));
  } catch(...) { render.Reset(); client.Reset(); device.Reset(); enumerator.Reset(); CoUninitialize(); com=false; throw; }
 }
 ~Wasapi() override {
  if(client) client->Stop(); render.Reset(); client.Reset(); device.Reset(); enumerator.Reset(); if(com) CoUninitialize();
 }
 uint32_t write(const float* samples,uint32_t frames) override {
  UINT32 padding=0; check(client->GetCurrentPadding(&padding));
  frames=std::min(frames,capacity-padding); if(!frames) return 0;
  BYTE* target=nullptr; check(render->GetBuffer(frames,&target));
  if(bits==32) memcpy(target,samples,size_t(frames)*channels*4);
  else { auto* output=reinterpret_cast<int16_t*>(target); for(size_t i=0;i<size_t(frames)*channels;i++) output[i]=int16_t(std::lrint(std::clamp(double(samples[i]),-1.0,32767.0/32768)*32768)); } check(render->ReleaseBuffer(frames,0)); submitted+=frames; return frames;
 }
 uint64_t played() override {
  UINT32 padding=0; check(client->GetCurrentPadding(&padding));
  if(!paused) last=std::max(last,submitted>padding ? submitted-padding : 0); return last;
 }
 void pause(bool p) override { if(paused==p) return; if(p) { last=played(); check(client->Stop()); } else check(client->Start()); paused=p; }
 void flush() override { check(client->Stop()); check(client->Reset()); paused=true; submitted=last=0; }
};
std::unique_ptr<Sink> open_sink(uint32_t rate,uint32_t channels,const std::string& endpoint) {
 for(uint32_t shape : {channels,uint32_t(2)}) for(uint32_t frequency : {rate,uint32_t(48000),uint32_t(44100)})
  for(bool integer : {false,true}) { try { return std::make_unique<Wasapi>(frequency,shape,endpoint,integer); } catch(const DeviceUnavailable&) { throw; } catch(...) {} }
 throw std::runtime_error("No compatible WASAPI endpoint/format");
}
}
