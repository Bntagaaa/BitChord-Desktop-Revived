// Real FFmpeg -> production Engine -> DSP -> collecting PCM sink. This checks
// partial-write correctness, NOT OS output/clock, hardware or audible parity.
#include "bitchord_audio.h"
#include "decoder.h"
#include "dsp.h"
#include "sink.h"
#include <chrono>
#include <cmath>
#include <cstring>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
std::vector<float> captured;
uint32_t format_channels=0;
bool partial=true;
void require(bool yes,const char* why) { if(!yes) throw std::runtime_error(why); }
template<class F> void until(F predicate) {
  auto end=std::chrono::steady_clock::now()+std::chrono::seconds(10);
  while(!predicate()) {require(std::chrono::steady_clock::now()<end,"engine deadline");std::this_thread::sleep_for(std::chrono::milliseconds(1));}
}
}
namespace bc {
class CollectingSink final:public Sink {
 uint32_t calls=0;
 public:
 CollectingSink(uint32_t r,uint32_t c) { rate=r;channels=c;format_channels=c; }
 uint32_t write(const float* pcm,uint32_t frames) override {
   // Exercise zero writes and suffix writes against actual processed PCM.
   if(partial && (++calls%5==0)) return 0;
   auto n=partial?std::min(17u,frames):frames;
   captured.insert(captured.end(),pcm,pcm+size_t(n)*channels);submitted+=n;return n;
 }
 uint64_t played() override {return submitted;}
 void pause(bool) override {}
 void flush() override {submitted=0;}
};
std::unique_ptr<Sink> open_sink(uint32_t r,uint32_t c,const std::string&) {return std::make_unique<CollectingSink>(r,c);}
}
int main(int argc,char** argv) {
 try {
  require(argc==2,"real WAV required");
  std::vector<float> baseline;
  for(int pass=0;pass<2;++pass) {
   captured.clear();partial=pass==1;uint64_t handle=0;
   require(bc_create(&handle)==BC_OK,"create");
   bc_command c{};c.struct_size=sizeof(c);c.abi=BC_ABI;c.selection=1;c.revision=1;c.id=1;c.type=BC_SELECT;
   std::strcpy(c.path,argv[1]);std::strcpy(c.entry,"occurrence-1");std::strcpy(c.rendition,"local");
   require(bc_submit(handle,&c)==BC_OK,"select paused");
   c.id=2;c.type=BC_DSP;std::strcpy(c.path,"1 1 0 0 0 0 .35 1 12 -6 3 -9 7 -2 10");
   require(bc_submit(handle,&c)==BC_OK,"atomic EQ/spatial settings");
   c.id=3;c.type=BC_PLAY;c.path[0]=0;require(bc_submit(handle,&c)==BC_OK,"play");
   bc_snapshot state{};
   until([&] {state.struct_size=sizeof(state);if(bc_read(handle,&state)!=BC_OK)return false;
     require(!state.error,"real decode/render failed");return state.phase==BC_ENDED;});
   require(state.played_frames==state.submitted_frames && state.played_frames>0,"accepted source clock");
   c.id=4;c.type=BC_DSP;std::strcpy(c.path,"1 1 0 0 0 0 0 0 99 0 0 0 0 0 0");
   require(bc_submit(handle,&c)==BC_INVALID,"reject out-of-range tuning without mutation");
   c.selection=2;require(bc_submit(handle,&c)==BC_INVALID,"invalid before admission");
   std::strcpy(c.path,"1 1 0 0 0 0 0 0 0 0 0 0 0 0 0");
   require(bc_submit(handle,&c)==BC_STALE,"stale settings cannot mutate active selection");
   require(bc_shutdown(handle)==BC_OK,"shutdown");
   until([&] {state.struct_size=sizeof(state);return bc_read(handle,&state)==BC_OK && state.quiescent;});
   require(state.decoders==0,"decoder release");require(bc_destroy(handle)==BC_OK,"destroy");
   if(pass==0) baseline=captured;
   else {
     require(captured.size()==baseline.size(),"partial writes lost/duplicated frames");
     double maximum=0;for(size_t i=0;i<captured.size();++i) maximum=std::max(maximum,std::abs(double(captured[i])-baseline[i]));
     require(maximum<=.0001,"partial writes reprocessed DSP state");
     std::cout<<"{\"real_decoded_frames\":"<<captured.size()/format_channels<<",\"partial_write_max_abs\":"<<maximum<<",\"physical_output\":false}\n";
   }
  }
  return 0;
 } catch(const std::exception& e) {std::cerr<<e.what()<<"\n";return 1;}
}
