// Real decoder/engine and a collecting, immediate-present test sink. This is
// deterministic transport validation, not physical output/listening evidence.
#include "bitchord_audio.h"
#include "decoder.h"
#include "sink.h"
#include "sleep.h"
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <iostream>
#include <thread>
#include <vector>

namespace {
void require(bool yes,const char* why) {if(!yes) throw std::runtime_error(why);}
template<class F> void until(F predicate) {
  const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(12);
  while(!predicate()) {require(std::chrono::steady_clock::now()<deadline,"engine deadline");std::this_thread::sleep_for(std::chrono::milliseconds(1));}
}
std::vector<float> captured;
bool partial=false;
uint32_t channels=0;
struct Client {
  uint64_t handle=0,id=0,selection=0;
  bc_snapshot state{};
  bool expired=false,adopted=false;
  Client() {require(bc_create(&handle)==BC_OK,"create");}
  ~Client() {
    bc_shutdown(handle);
    until([&]{return read().quiescent!=0;});
    require(state.decoders==0,"all decoder workers released");
    require(bc_destroy(handle)==BC_OK,"destroy");
  }
  bc_snapshot read() {
    state.struct_size=sizeof(state);
    const auto result=bc_read(handle,&state);
    require(result==BC_OK || result==BC_RETRY,"snapshot");
    if(result==BC_OK) require(!state.error,"real decode/output owner failed");
    bc_event events[256];uint32_t count=0;
    require(bc_poll(handle,events,256,&count)==BC_OK,"events");
    for(uint32_t i=0;i<count;++i) {
      expired|=events[i].kind==BC_EVENT_SLEEP;
      adopted|=events[i].kind==BC_EVENT_ADOPTED;
      require(events[i].outcome!=BC_COMMAND_FAILED,"command execution failed");
    }
    if(count) require(bc_ack(handle,events[count-1].sequence)==BC_OK,"ack");
    return state;
  }
  void send(uint32_t type,int64_t value=0,const char* path="",const char* entry="one") {
    bc_command c{};c.struct_size=sizeof(c);c.abi=BC_ABI;c.type=type;c.value=value;c.id=++id;
    if(type==BC_SELECT) ++selection;
    c.selection=selection;c.revision=selection;
    require(std::strlen(path)<sizeof(c.path),"path bound");std::strcpy(c.path,path);
    std::strcpy(c.entry,entry);std::strcpy(c.rendition,"test");
    require(bc_submit(handle,&c)==BC_OK,"command admission");
  }
};
}
namespace bc {
class CollectingSink final:public Sink {
  uint32_t calls=0;
 public:
  CollectingSink(uint32_t r,uint32_t c) {rate=r;channels=c;::channels=c;}
  uint32_t write(const float* data,uint32_t frames) override {
    if(partial && ++calls%5==0) return 0;
    const auto n=partial?std::min(73u,frames):frames;
    captured.insert(captured.end(),data,data+size_t(n)*channels);submitted+=n;return n;
  }
  uint64_t played() override {return submitted;}
  void pause(bool) override {}
  void flush() override {submitted=0;}
};
std::unique_ptr<Sink> open_sink(uint32_t r,uint32_t c,const std::string&) {return std::make_unique<CollectingSink>(r,c);}
}
int main(int argc,char** argv) {
 try {
  require(argc==2,"fixture path");
  bc::SleepTimer timer;
  timer.set(15*60000,1000000000);require(timer.remaining_ms(1000000000)==900000,"elapsed deadline");
  require(!timer.expired(900999999999LL) && timer.expired(901000000000LL),"deadline boundary");
  timer.set(-1,0);require(timer.after_track() && timer.remaining_ms(100)==-1 && !timer.expired(INT64_MAX),"after-track excludes deadline");
  timer.set(0,0);require(!timer.after_track() && !timer.expired(INT64_MAX),"cancel both modes");
  for(const int speed:{250000,750000,1000000,1250000,3000000}) {
    std::vector<float> baseline;
    for(int pass=0;pass<2;++pass) {
      captured.clear();partial=pass!=0;
      {
        Client c;c.send(BC_SELECT,0,argv[1]);c.send(BC_LISTEN,speed,"0");c.send(BC_PLAY);
        int64_t previous=0;
        until([&]{const auto s=c.read();require(s.position_us>=previous,"source clock reversed");previous=s.position_us;return s.phase==BC_ENDED;});
        require(c.state.played_frames==captured.size()/channels,"partial-write accepted frame accounting");
        require(c.state.position_us==c.state.duration_us,"EOF source clock is bounded at duration");
        if(speed<1000000) require(c.state.played_frames>24000,"slow tempo expands output");
        if(speed>1000000) require(c.state.played_frames<24000,"fast tempo compresses output");
      }
      if(pass==0) baseline=captured;
      else require(captured==baseline,"tempo PCM changed with partial/zero writes");
    }
  }
  partial=false;captured.clear();
  {
    Client c;c.send(BC_SELECT,0,argv[1]);
    until([&]{return c.read().phase==BC_PAUSED;});
    c.send(BC_NOMINATE,0,argv[1],"two");c.send(BC_SLEEP,-1);c.send(BC_PLAY);
    until([&]{return c.read().phase==BC_PAUSED && c.expired;});
    require(!c.adopted && std::strcmp(c.state.entry,"one")==0,"after-track suppresses prepared handoff");
    require(c.state.played_frames==24000,"after-track plays the full current track");
    c.expired=false;c.send(BC_PLAY);
    until([&]{return c.read().phase==BC_ENDED;});
    require(captured.size()/channels==48000,"resume after sleeping at EOF restarts track");
    // A subsequent explicit seek must retire the sleep-at-EOF restart marker.
    c.expired=false;c.send(BC_SLEEP,-1);c.send(BC_SEEK,100000);c.send(BC_PLAY);
    until([&]{return c.read().phase==BC_PAUSED && c.expired;});
    require(captured.size()/channels==67200,"seek after sleep resumes at requested position, not zero");
  }
  {
    Client c;c.send(BC_SLEEP,25); // May be armed with no current selection.
    until([&]{c.read();return c.expired;});
    require(c.state.phase==BC_PAUSED,"deadline enforced without a decoder or Dart poll scheduler");
    c.expired=false;c.send(BC_SLEEP,20);c.send(BC_SLEEP,0);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));c.read();require(!c.expired,"cancel invalidates deadline");
    c.send(BC_SELECT,0,argv[1]);until([&]{return c.read().phase==BC_PAUSED;});
    c.send(BC_SLEEP,20);c.expired=false;until([&]{c.read();return c.expired;});
    require(c.state.submitted_frames==0,"paused timer produces no new sound");
    c.send(BC_SLEEP,0);c.send(BC_PLAY);until([&]{return c.read().phase==BC_ENDED;});
  }
  std::cout<<"native listener speed/clock/partial-write and sleep integration passed\n";
 } catch(const std::exception& e) {std::cerr<<e.what()<<'\n';return 1;}
}
