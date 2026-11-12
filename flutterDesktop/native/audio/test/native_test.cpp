#include "bitchord_audio.h"
#include <chrono>
#include <atomic>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <set>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
static void require(bool v,const char* m) { if(!v) throw std::runtime_error(m); }
template<class F> static void until(F fn) {
 const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(6);
 while(!fn()) { require(std::chrono::steady_clock::now()<deadline,"deadline"); std::this_thread::sleep_for(std::chrono::milliseconds(2)); }
}
static void close(uint64_t h,uint64_t terminal=0) {
 require(bc_shutdown(h)==BC_OK,"shutdown"); require(bc_shutdown(h)==BC_OK,"idempotent shutdown");
 bc_snapshot s{}; until([&]{s.struct_size=sizeof(s);return bc_read(h,&s)==BC_OK && s.quiescent;});
 require(s.decoders==0 && s.pcm_bytes==0 && s.pending_commands==0,"quiescent resource ownership");
 if(terminal) { bc_event events[256];uint32_t count=0; require(bc_poll(h,events,256,&count)==BC_OK && count==1 && events[0].command_id==terminal,"shutdown retains terminal outcome"); }
 require(bc_destroy(h)==BC_OK,"destroy"); require(bc_destroy(h)==BC_HANDLE,"stale destroy");
 require(bc_read(h,&s)==BC_HANDLE,"stale read");
}
int main() {
 const auto dir=std::filesystem::temp_directory_path()/("bitchord-native-"+std::to_string(bc_monotonic_ns()));
 std::filesystem::create_directory(dir);
 try {
  bc_caps caps{}; require(bc_get_caps(&caps)==BC_INVALID,"ABI size rejection");
  caps.struct_size=sizeof(caps); require(bc_get_caps(&caps)==BC_OK && caps.command_size==sizeof(bc_command) && caps.snapshot_size==sizeof(bc_snapshot) && caps.event_size==sizeof(bc_event),"ABI layouts");
  const auto baseline=bc_live_allocations();
  auto* p=bc_alloc(128);require(p!=nullptr && bc_live_allocations()==baseline+1,"allocation");bc_free(p);bc_free(nullptr);
  require(!bc_alloc(17*1024*1024) && bc_live_allocations()==baseline,"free/bound");
  uint8_t hash[32]; require(bc_sha256(reinterpret_cast<const uint8_t*>("abc"),3,hash)==BC_OK && hash[0]==0xba && hash[31]==0xad,"SHA256");
  const auto db=(dir/"state.sqlite3").u8string(); const uint8_t a[]={1,2,3},b[]={4,5};
  require(bc_store_write(db.c_str(),a,3,0)==BC_OK,"transaction commit");
  require(bc_store_write(db.c_str(),b,2,0)==BC_STALE,"CAS conflict");
  uint8_t* data=nullptr;uint64_t len=0,revision=0;
  require(bc_store_read(db.c_str(),&data,&len,&revision)==BC_OK && len==3 && revision==1 && std::memcmp(a,data,3)==0,"restart/CAS rollback");bc_free(data);
  require(bc_store_write(db.c_str(),b,2,1)==BC_OK,"second transaction");
  const auto concurrent=(dir/"concurrent.sqlite3").u8string();
  require(bc_store_read(concurrent.c_str(),&data,&len,&revision)==BC_OK,"initialize concurrent store");bc_free(data);
  std::atomic<int> wins{0}, stale{0}; std::vector<std::thread> writers;
  for(int i=0;i<8;i++) writers.emplace_back([&] { const auto result=bc_store_write(concurrent.c_str(),a,3,0); if(result==BC_OK) ++wins; if(result==BC_STALE) ++stale; });
  for(auto& writer:writers) writer.join(); require(wins==1 && stale==7,"concurrent CAS serialization");
  for(int cycle=0;cycle<20;cycle++) {
   uint64_t h=0,other=0; require(bc_create(&h)==BC_OK,"create"); require(bc_create(&other)==BC_BUSY,"single engine");
   if(cycle==0) {
    bc_command c{};c.struct_size=sizeof(c);c.abi=BC_ABI;c.type=BC_SELECT;c.selection=1;c.revision=1;
    std::strcpy(c.path,"/missing-local-file.wav"); std::strcpy(c.entry,"duplicate:1");std::strcpy(c.rendition,"local:1");
    int accepted=0;std::set<uint64_t> ids;
    for(uint64_t i=1;i<=150;i++) {
     c.id=i;c.type=i==1?BC_SELECT:BC_PAUSE;
     auto result=bc_submit(h,&c); if(result==BC_OK) {accepted++;ids.insert(i);} else require(result==BC_BUSY,"bounded admission");
    }
    require(accepted==120,"reserved terminal credits bound");
    bc_event events[256];uint32_t count=0;
    until([&]{require(bc_poll(h,events,256,&count)==BC_OK,"poll");return int(count)==accepted;});
    std::set<uint64_t> outcomes;for(uint32_t i=0;i<count;i++) {require(events[i].kind==BC_EVENT_COMMAND,"command kind");outcomes.insert(events[i].command_id);}
    require(outcomes==ids,"one terminal per accepted command");
    uint32_t second=0;bc_poll(h,events,256,&second);require(second==count,"poll does not release journal");
    require(bc_ack(h,events[count-1].sequence+1)==BC_INVALID,"future ack rejected");
    require(bc_ack(h,events[count-1].sequence)==BC_OK,"ack credits");
    c.id=200;c.selection=2;c.type=BC_SELECT;std::strcpy(c.entry,"duplicate:2"); require(bc_submit(h,&c)==BC_OK,"new selection");
    c.id=201;c.selection=1;c.type=BC_PLAY;require(bc_submit(h,&c)==BC_STALE,"stale selection rejected");
   }
   close(h,cycle==0?200:0);
  }
  require(bc_live_allocations()==baseline,"FFI/store allocation balance");
  std::cout<<"{\"native_contract\":\"passed\",\"cycles\":20,\"physical_audio\":\"blocked: no listener or loopback instrument\"}"<<std::endl;
  std::filesystem::remove_all(dir);return 0;
 } catch(const std::exception& e) { std::cerr<<e.what()<<std::endl; return 1; }
}
