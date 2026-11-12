#include "bitchord_audio.h"
#include <chrono>
#include <cstring>
#include <iostream>
#include <thread>
// Real output tool, no null sink or simulated fallback inside the engine.
int main(int argc,char** argv) {
 uint64_t h=0; if(bc_create(&h)!=BC_OK) return 1;
 bc_command c{}; c.struct_size=sizeof(c); c.abi=BC_ABI; c.id=1; c.selection=1; c.revision=1;
 if(argc>2) { c.type=BC_DEVICE; std::strncpy(c.path,argv[2],sizeof(c.path)-1);
  // Device is configured after selection below, preserving the same selection.
 }
 c.type=BC_SELECT; c.value=1; std::strcpy(c.entry,"probe:1"); std::strcpy(c.rendition,"local:1");
 if(argc>1) std::strncpy(c.path,argv[1],sizeof(c.path)-1);
 else std::strcpy(c.path,"/unavailable-local-audio-fixture.wav");
 int error=bc_submit(h,&c);
 if(argc>2) { c.id=2; c.type=BC_DEVICE; std::strncpy(c.path,argv[2],sizeof(c.path)-1); error|=bc_submit(h,&c); }
 bc_snapshot s{}; s.struct_size=sizeof(s);
 const auto limit=std::chrono::steady_clock::now()+std::chrono::seconds(30);
 while(!error && std::chrono::steady_clock::now()<limit) {
  if(bc_read(h,&s)!=BC_OK) continue;
  if(s.phase==BC_ENDED || s.phase==BC_ERROR) break;
  std::this_thread::sleep_for(std::chrono::milliseconds(20));
 }
 const bool ended=s.phase==BC_ENDED;
 std::cout<<"{\"phase\":"<<s.phase<<",\"error\":"<<s.error<<",\"backend\":"<<s.backend
  <<",\"timing_quality\":"<<s.timing_quality<<",\"position_us\":"<<s.position_us
  <<",\"submitted_frames\":"<<s.submitted_frames<<",\"played_frames\":"<<s.played_frames
  <<",\"pcm_high_water\":"<<s.pcm_high_water<<",\"physical_audio\":\"unverified\"}"<<std::endl;
 bc_shutdown(h);
 for(int i=0;i<2000;i++) { s.struct_size=sizeof(s); if(bc_read(h,&s)==BC_OK && s.quiescent) break; std::this_thread::sleep_for(std::chrono::milliseconds(2)); }
 if(bc_destroy(h)!=BC_OK) return 3;
 return !error && s.error==0 && ended ? 0 : 2;
}
