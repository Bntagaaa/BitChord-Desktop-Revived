#include "bitchord_audio.h"
#include <chrono>
#include <cmath>
#include <cstring>
#include <iostream>
#include <string>
#include <thread>

// Real output tool. Default: listen until the engine reports EOF or failure.
// Exit codes: 0 EOF, 2 playback failure, 3 process/API/shutdown failure,
// 4 configured timeout, 64 invalid arguments. Never print the source path.
int main(int argc, char** argv) {
 std::string path, device;
 double timeout=0;
 for(int i=1;i<argc;i++) {
  const std::string arg=argv[i];
  if(arg=="--timeout") {
   if(++i>=argc) return 64;
   try {
    size_t used=0; timeout=std::stod(argv[i],&used);
    if(used!=std::strlen(argv[i]) || !std::isfinite(timeout) || timeout<=0 || timeout>86400*30) return 64;
   } catch(...) { return 64; }
  } else if(arg=="--help") {
   std::cout<<"Usage: bitchord_audio_probe [--timeout seconds] <file> [device]\n"
    <<"Default: play until EOF. Exit: 0 EOF, 2 playback failure, 3 process failure, 4 timeout, 64 usage.\n";
   return 0;
  } else if(arg.rfind("--",0)==0) return 64;
  else if(path.empty()) path=arg;
  else if(device.empty()) device=arg;
  else return 64;
 }
 if(path.empty() || path.size()>=4096 || device.size()>=4096) return 64;
 uint64_t h=0;
 if(bc_create(&h)!=BC_OK) { std::cout<<"{\"outcome\":\"PROCESS_FAILURE\"}\n"; return 3; }
 bc_command c{}; c.struct_size=sizeof(c); c.abi=BC_ABI; c.id=1; c.selection=1; c.revision=1;
 c.type=BC_SELECT; c.value=1; std::strcpy(c.entry,"probe:1"); std::strcpy(c.rendition,"local:1");
 std::memcpy(c.path,path.c_str(),path.size()+1);
 int error=bc_submit(h,&c);
 if(!device.empty() && !error) {
  c.id=2; c.type=BC_DEVICE; std::memcpy(c.path,device.c_str(),device.size()+1);
  error=bc_submit(h,&c);
 }
 bc_snapshot s{}; s.struct_size=sizeof(s);
 const auto start=std::chrono::steady_clock::now();
 int code=3; const char* outcome="PROCESS_FAILURE";
 while(!error) {
  error=bc_read(h,&s);
  if(error==BC_RETRY) error=0;
  else if(error) break;
  if(s.phase==BC_ERROR || s.error) { code=2; outcome="PLAYBACK_FAILURE"; break; }
  if(s.phase==BC_ENDED) { code=0; outcome="EOF"; break; }
  if(timeout>0 && std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()>=timeout) {
   code=4; outcome="TIMED_OUT"; break;
  }
  std::this_thread::sleep_for(std::chrono::milliseconds(20));
 }
 // Preserve the terminal playback snapshot; shutdown changes phase/error.
 const auto terminal=s;
 bool quiescent=false;
 error=bc_shutdown(h);
 for(int i=0;!error && i<2000;i++) {
  s.struct_size=sizeof(s); error=bc_read(h,&s);
  if(error==BC_RETRY) { error=0; std::this_thread::sleep_for(std::chrono::milliseconds(2)); continue; }
  if(!error && s.quiescent) { quiescent=true; break; }
  std::this_thread::sleep_for(std::chrono::milliseconds(2));
 }
 const bool shutdown_ok=!error && quiescent && bc_destroy(h)==BC_OK;
 std::cout<<"{\"outcome\":\""<<outcome<<"\",\"shutdown_ok\":"<<(shutdown_ok?"true":"false")
  <<",\"phase\":"<<terminal.phase<<",\"error\":"<<terminal.error<<",\"backend\":"<<terminal.backend
  <<",\"timing_quality\":"<<terminal.timing_quality<<",\"position_us\":"<<terminal.position_us
  <<",\"submitted_frames\":"<<terminal.submitted_frames<<",\"played_frames\":"<<terminal.played_frames
  <<",\"pcm_high_water\":"<<terminal.pcm_high_water<<",\"physical_audio\":\"unverified\"}"<<std::endl;
 return shutdown_ok?code:3;
}
