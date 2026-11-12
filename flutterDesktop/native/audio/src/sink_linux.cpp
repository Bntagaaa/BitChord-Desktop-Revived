#include "sink.h"
#include <pulse/pulseaudio.h>
#include <alsa/asoundlib.h>
#include <algorithm>
#include <chrono>
#include <thread>
#include <stdexcept>
namespace bc {
class Pulse final : public Sink {
 pa_mainloop* loop=nullptr; pa_context* context=nullptr; pa_stream* stream=nullptr;
 uint64_t last=0; bool paused=false;
 void pump() { for(int i=0;i<4;i++) { int result=0; if(pa_mainloop_iterate(loop,0,&result)<0) throw std::runtime_error("pulse iterate"); } }
 template<class F> void wait(F ready) {
  const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(2);
  while(!ready()) {
   if(std::chrono::steady_clock::now()>deadline || !PA_CONTEXT_IS_GOOD(pa_context_get_state(context))) throw std::runtime_error("pulse unavailable");
   pump(); std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }
 }
 void operation(pa_operation* op) {
  if(!op) throw std::runtime_error("pulse operation");
  try { wait([&] { return pa_operation_get_state(op)!=PA_OPERATION_RUNNING; }); }
  catch(...) { pa_operation_cancel(op); pa_operation_unref(op); throw; }
  const bool done=pa_operation_get_state(op)==PA_OPERATION_DONE; pa_operation_unref(op);
  if(!done) throw std::runtime_error("pulse cancelled");
 }
 void close() {
  if(stream) { pa_stream_disconnect(stream); pa_stream_unref(stream); stream=nullptr; }
  if(context) { pa_context_disconnect(context); pa_context_unref(context); context=nullptr; }
  if(loop) { pa_mainloop_free(loop); loop=nullptr; }
 }
public:
 Pulse(uint32_t r,uint32_t c,const std::string& endpoint) {
  rate=r; channels=c; backend=1; timing=2;
  try {
   loop=pa_mainloop_new(); if(!loop) throw std::bad_alloc();
   context=pa_context_new(pa_mainloop_get_api(loop),"BitChord");
   if(!context || pa_context_connect(context,nullptr,PA_CONTEXT_NOAUTOSPAWN,nullptr)<0) throw std::runtime_error("pulse connect");
   wait([&]{ return pa_context_get_state(context)==PA_CONTEXT_READY; });
   pa_sample_spec format{PA_SAMPLE_FLOAT32LE,r,uint8_t(c)};
   pa_channel_map map; if(!pa_channel_map_init_auto(&map,c,PA_CHANNEL_MAP_DEFAULT)) throw std::runtime_error("pulse channels");
   stream=pa_stream_new(context,"BitChord local PCM",&format,&map);
   pa_buffer_attr attr{uint32_t(-1),r*c*4/10,uint32_t(-1),r*c*4/100,uint32_t(-1)};
   if(!stream || pa_stream_connect_playback(stream,endpoint.empty()?nullptr:endpoint.c_str(),&attr,
      pa_stream_flags_t(PA_STREAM_START_CORKED|PA_STREAM_INTERPOLATE_TIMING|PA_STREAM_AUTO_TIMING_UPDATE|PA_STREAM_ADJUST_LATENCY),nullptr,nullptr)<0) throw std::runtime_error("pulse stream");
   wait([&]{ auto s=pa_stream_get_state(stream); if(!PA_STREAM_IS_GOOD(s)) throw std::runtime_error("pulse failed"); return s==PA_STREAM_READY; });
   paused=true;
  } catch(...) { close(); throw; }
 }
 ~Pulse() override { close(); }
 uint32_t write(const float* samples,uint32_t frames) override {
  pump(); if(pa_stream_get_state(stream)!=PA_STREAM_READY || pa_stream_is_suspended(stream)>0) throw std::runtime_error("pulse device lost");
  const size_t writable=pa_stream_writable_size(stream); if(writable==size_t(-1)) throw std::runtime_error("pulse write size");
  frames=uint32_t(std::min<size_t>(frames,writable/(channels*4)));
  if(frames && pa_stream_write(stream,samples,size_t(frames)*channels*4,nullptr,0,PA_SEEK_RELATIVE)<0) throw std::runtime_error("pulse write");
  submitted+=frames; return frames;
 }
 uint64_t played() override {
  pump(); if(pa_stream_get_state(stream)!=PA_STREAM_READY) throw std::runtime_error("pulse timing device lost");
  if(paused) return last;
  pa_usec_t latency=0; int negative=0;
  if(pa_stream_get_latency(stream,&latency,&negative)>=0) {
   const auto queued=negative ? 0 : (latency*rate+999999)/1000000;
   last=std::max(last,submitted>queued ? submitted-queued : 0);
  }
  return std::min(last,submitted);
 }
 void pause(bool p) override { if(p==paused) return; if(p) last=played(); operation(pa_stream_cork(stream,p?1:0,nullptr,nullptr)); paused=p; }
 void flush() override { operation(pa_stream_flush(stream,nullptr,nullptr)); submitted=last=0; }
};
class Alsa final : public Sink {
 snd_pcm_t* pcm=nullptr; uint64_t last=0; bool paused=true, can_pause=false;
public:
 Alsa(uint32_t r,uint32_t c,const std::string& endpoint) {
  rate=r; channels=c; backend=3; timing=2;
  if(snd_pcm_open(&pcm,endpoint.empty()?"default":endpoint.c_str(),SND_PCM_STREAM_PLAYBACK,SND_PCM_NONBLOCK)<0) throw std::runtime_error("ALSA open");
  if(snd_pcm_set_params(pcm,SND_PCM_FORMAT_FLOAT_LE,SND_PCM_ACCESS_RW_INTERLEAVED,c,r,1,100000)<0) {
   snd_pcm_close(pcm); pcm=nullptr; throw std::runtime_error("ALSA format");
  }
  snd_pcm_hw_params_t* hw=nullptr; snd_pcm_hw_params_alloca(&hw);
  if(snd_pcm_hw_params_current(pcm,hw)==0) can_pause=snd_pcm_hw_params_can_pause(hw)!=0;
 }
 ~Alsa() override { if(pcm) { snd_pcm_drop(pcm); snd_pcm_close(pcm); } }
 uint32_t write(const float* samples,uint32_t frames) override {
  auto n=snd_pcm_writei(pcm,samples,frames);
  if(n==-EAGAIN) return 0;
  if(n==-EPIPE) { if(snd_pcm_prepare(pcm)<0) throw std::runtime_error("ALSA recover"); return 0; }
  if(n<0) throw std::runtime_error("ALSA device lost");
  submitted+=uint64_t(n); return uint32_t(n);
 }
 uint64_t played() override {
  if(paused) return last;
  snd_pcm_sframes_t delay=0; if(snd_pcm_delay(pcm,&delay)<0) throw std::runtime_error("ALSA timing");
  const auto queued=uint64_t(std::max<snd_pcm_sframes_t>(0,delay));
  last=std::max(last,submitted>queued ? submitted-queued : 0); return std::min(last,submitted);
 }
 void pause(bool p) override {
  if(p==paused) return;
  if(p) { last=played(); if(!can_pause || snd_pcm_pause(pcm,1)<0) throw std::runtime_error("ALSA endpoint cannot pause without loss"); }
  else if(can_pause && snd_pcm_state(pcm)==SND_PCM_STATE_PAUSED) { if(snd_pcm_pause(pcm,0)<0) throw std::runtime_error("ALSA resume"); }
  paused=p;
 }
 void flush() override { if(snd_pcm_drop(pcm)<0 || snd_pcm_prepare(pcm)<0) throw std::runtime_error("ALSA flush"); submitted=last=0; paused=true; }
};
std::unique_ptr<Sink> open_sink(uint32_t rate,uint32_t channels,const std::string& endpoint) {
 if(endpoint.rfind("alsa:",0)==0) return std::make_unique<Alsa>(rate,channels,endpoint.substr(5));
 if(endpoint.rfind("pulse:",0)==0) return std::make_unique<Pulse>(rate,channels,endpoint.substr(6));
 try { return std::make_unique<Pulse>(rate,channels,endpoint); }
 catch(...) { if(!endpoint.empty()) throw; return std::make_unique<Alsa>(rate,channels,""); }
}
}
