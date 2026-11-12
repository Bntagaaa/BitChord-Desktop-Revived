#include "bitchord_audio.h"
#include "decoder.h"
#include "sink.h"
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <algorithm>
#include <cstddef>
#include <stdexcept>
extern "C" {
#include <libavcodec/version.h>
#include <libswresample/swresample.h>
#include <libavutil/channel_layout.h>
}
static_assert(sizeof(bc_command)==4400 && offsetof(bc_command,path)==48,"command ABI");
static_assert(sizeof(bc_snapshot)==424 && offsetof(bc_snapshot,entry)==168,"snapshot ABI");
static_assert(sizeof(bc_event)==176 && offsetof(bc_event,entry)==48,"event ABI");
namespace bc {
using Clock=std::chrono::steady_clock;
static std::atomic<uint64_t> allocations{0};
struct Token {
 uint64_t selection=0, revision=0; char entry[128]{}, rendition[128]{};
 Token& operator=(const bc_command& c) { selection=c.selection; revision=c.revision; memcpy(entry,c.entry,128); memcpy(rendition,c.rendition,128); return *this; }
};
struct Segment { uint64_t first=0, last=0; double source=0, step=0; Token identity{}; };
class Engine {
public:
 std::mutex gate;
 std::deque<bc_command> commands;
 std::deque<bc_event> journal;
 bc_snapshot published{};
 std::atomic<bool> closing{false}, quiescent{false};
 std::atomic<uint64_t> admitted_selection{0}, admitted_revision{0};
 uint64_t last_id=0, next_event=0;
 uint32_t accepted_unacked=0, autonomous_unacked=0;
 std::thread thread;
 Engine() { published.struct_size=sizeof(published); published.abi=BC_ABI; thread=std::thread([this]{ run(); }); }
 ~Engine() { closing=true; if(thread.joinable()) thread.join(); }
private:
 bc_snapshot state{};
 std::unique_ptr<Decoder> current, upcoming;
 bc_command identity{}, nomination{};
 std::unique_ptr<Sink> sink;
 std::string endpoint;
 bool play=false; double speed=1, volume=1, fraction=0, source_frame=0;
 std::array<float,512*8> scratch{};
 std::array<double,64> matrix{};
 std::array<Segment,4096> segments{}; size_t seg_begin=0,seg_size=0;
 std::array<bc_event,256> staging{}; size_t staged=0;
 void event(const bc_command& c,uint32_t outcome,uint32_t error=0,uint32_t kind=BC_EVENT_COMMAND) {
  if(staged==staging.size()) throw std::runtime_error("event reservation violated");
  bc_event e{}; e.command_id=c.id; e.selection=c.selection; e.revision=c.revision;
  e.outcome=outcome; e.error=error; e.kind=kind; memcpy(e.entry,c.entry,sizeof(e.entry)); staging[staged++]=e;
 }
 void publish() {
  std::unique_lock<std::mutex> lock(gate,std::try_to_lock); if(!lock) return;
  for(size_t i=0;i<staged;i++) { staging[i].sequence=++next_event; if(staging[i].kind!=BC_EVENT_COMMAND) ++autonomous_unacked; journal.push_back(staging[i]); }
  staged=0;
  state.struct_size=sizeof(state); state.abi=BC_ABI; ++state.sequence; state.watermark=next_event;
  state.pending_commands=uint32_t(commands.size()); state.unacked_events=uint32_t(journal.size());
  state.decoders=live_decoders.load(); state.quiescent=quiescent?1:0;
  state.sampled_ns=bc_monotonic_ns();
  state.pcm_bytes=(current?current->bytes():0)+(upcoming?upcoming->bytes():0);
  state.pcm_high_water=std::max(state.pcm_high_water,(current?current->high_water.load():0)+(upcoming?upcoming->high_water.load():0));
  published=state;
 }
 void fail(uint32_t code) {
  state.error=code; state.phase=BC_ERROR; state.awaiting_audio=1; play=false;
  // Sink/decoder resources are retired by this owner, never in an OS callback.
  sink.reset(); current.reset(); upcoming.reset(); seg_size=0;
 }
 void reset_timeline(int64_t origin) {
  fraction=0; source_frame=0; seg_size=seg_begin=0;
  state.position_us=origin; state.submitted_frames=state.played_frames=0;
  ++state.discontinuity; state.awaiting_audio=1;
 }
 void process(const bc_command& c) {
  if(c.selection!=admitted_selection || c.revision!=admitted_revision) { event(c,BC_CANCELLED); return; }
  try {
   switch(c.type) {
    case BC_SELECT:
     sink.reset(); current.reset(); upcoming.reset(); identity=c;
     state.selection=c.selection; state.revision=c.revision; state.error=0;
     memcpy(state.entry,c.entry,128); memcpy(state.rendition,c.rendition,128);
     current=std::make_unique<Decoder>(c.path); play=c.value!=0; state.phase=BC_BUFFERING;
     reset_timeline(0); break;
    case BC_NOMINATE:
     upcoming.reset(); nomination=c; upcoming=std::make_unique<Decoder>(c.path); break;
    case BC_CLEAR_NEXT: upcoming.reset(); break;
    case BC_PLAY:
     if(state.phase==BC_ENDED && current) {
      if(sink) sink->flush(); current.reset(); current=std::make_unique<Decoder>(identity.path);
      state.phase=BC_BUFFERING; reset_timeline(0);
     }
     play=true; if(sink) sink->pause(false); break;
    case BC_PAUSE:
     play=false;
     if(sink) {
      clock();
      try { sink->pause(true); }
      catch(const PauseUnsupported&) {
       const auto at=state.position_us; sink->flush(); current.reset(); upcoming.reset();
       current=std::make_unique<Decoder>(identity.path,at); reset_timeline(at);
      }
     }
     state.phase=BC_PAUSED; break;
    case BC_STOP:
     play=false; sink.reset(); current.reset(); upcoming.reset(); reset_timeline(0); state.phase=BC_IDLE; break;
    case BC_SEEK:
     if(!current) { event(c,BC_COMMAND_FAILED,BC_ERROR_SEEK); return; }
     if(sink) sink->flush(); current.reset(); upcoming.reset();
     current=std::make_unique<Decoder>(identity.path,c.value); state.phase=BC_BUFFERING; reset_timeline(c.value);
     if(sink && play) sink->pause(false); break;
    case BC_RATE: speed=double(c.value)/1000000; break;
    case BC_VOLUME: volume=double(c.value)/1000000; break;
    case BC_DEVICE:
     endpoint=c.path;
     if(current) {
      const auto at=state.position_us; sink.reset(); current.reset(); upcoming.reset();
      current=std::make_unique<Decoder>(identity.path,at); state.phase=BC_BUFFERING; reset_timeline(at);
     } break;
    default: event(c,BC_COMMAND_FAILED,BC_INVALID); return;
   }
   event(c,BC_APPLIED);
  } catch(...) { event(c,BC_COMMAND_FAILED,BC_ERROR_DEVICE); fail(BC_ERROR_DEVICE); }
 }
 void clock() {
  if(!sink) return;
  state.submitted_frames=sink->submitted; state.played_frames=sink->played();
  while(seg_size>1 && segments[(seg_begin+1)%segments.size()].first<=state.played_frames) {
   seg_begin=(seg_begin+1)%segments.size(); --seg_size;
  }
  if(seg_size) {
   const auto& s=segments[seg_begin];
   const auto at=std::clamp(state.played_frames,s.first,s.last);
   state.position_us=int64_t(s.source+double(at-s.first)*s.step);
   if(std::strcmp(state.entry,s.identity.entry)!=0) {
    memcpy(state.entry,s.identity.entry,128); memcpy(state.rendition,s.identity.rendition,128);
    state.selection=s.identity.selection; state.revision=s.identity.revision;
    bc_command adopted{}; adopted.selection=s.identity.selection; adopted.revision=s.identity.revision; memcpy(adopted.entry,s.identity.entry,128);
    event(adopted,BC_APPLIED,0,BC_EVENT_ADOPTED);
   }
  }
  state.awaiting_audio=(state.submitted_frames==state.played_frames && state.phase!=BC_ENDED)?1:0;
 }
 void configure_matrix() {
  matrix.fill(0);
  if(current->channels==sink->channels) { for(uint32_t c=0;c<current->channels;c++) matrix[c*8+c]=1; return; }
#if LIBAVUTIL_VERSION_MAJOR >= 57
  AVChannelLayout input{},output{};
  if(current->channel_mask) av_channel_layout_from_mask(&input,current->channel_mask); else av_channel_layout_default(&input,int(current->channels));
  av_channel_layout_default(&output,int(sink->channels));
  const int result=swr_build_matrix2(&input,&output,0.7071067811865476,0.7071067811865476,0,1,1,matrix.data(),8,AV_MATRIX_ENCODING_NONE,nullptr);
  av_channel_layout_uninit(&input); av_channel_layout_uninit(&output);
#else
  const int64_t input=current->channel_mask?int64_t(current->channel_mask):av_get_default_channel_layout(int(current->channels));
  const int result=swr_build_matrix(input,av_get_default_channel_layout(int(sink->channels)),0.7071067811865476,0.7071067811865476,0,1,1,matrix.data(),8,AV_MATRIX_ENCODING_NONE,nullptr);
#endif
  if(result<0) throw std::runtime_error("channel matrix");
 }
 void render() {
  if(!current) return;
  if(identity.selection!=admitted_selection || identity.revision!=admitted_revision) return;
  if(current->error) { fail(uint32_t(current->error.load())); return; }
  if(!current->ready) { state.phase=BC_BUFFERING; return; }
  if(!sink) {
   sink=open_sink(current->rate,current->channels,endpoint); ++state.device_epoch;
   state.rate=sink->rate; state.channels=sink->channels; state.backend=sink->backend; state.timing_quality=sink->timing;
   state.duration_us=current->duration; state.precision_bits=sink->bits; configure_matrix(); sink->pause(!play);
  }
  clock();
  if(!play) { if(state.phase!=BC_ENDED) state.phase=BC_PAUSED; return; }
  state.phase=BC_PLAYING;
  const auto available=current->available();
  if(!available) {
   if(!current->eof) { ++state.underruns; state.phase=BC_BUFFERING; return; }
   if(upcoming && upcoming->ready && !upcoming->error && upcoming->rate==current->rate && upcoming->channels==current->channels && seg_size<segments.size()-2) {
    // Prepared same-format handoff preserves the open sink and all queued frames.
    current=std::move(upcoming); identity=nomination; fraction=source_frame=0; state.duration_us=current->duration;
    return;
   }
   if(sink->drained()) {
    clock();
    if(upcoming) {
     if(upcoming->error) { fail(uint32_t(upcoming->error.load())); return; }
     if(!upcoming->ready) { state.phase=BC_BUFFERING; return; }
     sink.reset(); current=std::move(upcoming); identity=nomination; reset_timeline(0);
     memcpy(state.entry,identity.entry,128); memcpy(state.rendition,identity.rendition,128);
     event(identity,BC_APPLIED,0,BC_EVENT_ADOPTED); return;
    }
    try { sink->pause(true); } catch(const PauseUnsupported&) { /* All accepted PCM was consumed. */ }
    play=false; state.phase=BC_ENDED; state.awaiting_audio=0;
   }
   return;
  }
  // Bound mapping memory even when a server stops consuming accepted PCM.
  if(seg_size>=segments.size()-1) return;
  const double step=speed*current->rate/sink->rate;
  uint32_t frames=uint32_t(std::min<double>(512,std::floor((double(available)-fraction)/step)));
  if(!frames) {
   if(current->eof) frames=1;
   else return;
  }
  const auto channels=current->channels;
  for(uint32_t f=0;f<frames;f++) {
   const double pos=fraction+double(f)*step;
   const auto a=std::min<uint64_t>(uint64_t(pos),available-1), b=std::min<uint64_t>(a+1,available-1);
   const double blend=pos-std::floor(pos);
   for(uint32_t out=0;out<sink->channels;out++) {
    double mixed=0; for(uint32_t c=0;c<channels;c++) mixed+=(current->sample(a,c)*(1-blend)+current->sample(b,c)*blend)*matrix[out*8+c];
    scratch[size_t(f)*sink->channels+out]=float(mixed*volume);
   }
  }
  const auto first=sink->submitted; const auto written=sink->write(scratch.data(),frames);
  if(!written) return;
  auto& segment=segments[(seg_begin+seg_size++)%segments.size()];
  segment.first=first; segment.last=first+written; segment.source=double(current->origin)+(source_frame+fraction)*1000000/current->rate;
  segment.step=step*1000000/current->rate; segment.identity=identity;
  const double old_fraction=fraction; const double consumed=fraction+written*step; const auto whole=std::min<uint64_t>(uint64_t(consumed),available);
  fraction=consumed-double(whole); if(current->eof && whole==available) { segment.step=(double(whole)-old_fraction)*1000000/current->rate/written; fraction=0; }
  source_frame+=double(whole); current->consume(whole);
  clock();
 }
 void run() noexcept {
  state.struct_size=sizeof(state); state.abi=BC_ABI;
  try {
   while(!closing) {
    std::array<bc_command,8> batch{}; size_t n=0;
    { std::unique_lock<std::mutex> lock(gate,std::try_to_lock);
      if(lock) while(n<batch.size() && !commands.empty()) { batch[n++]=commands.front(); commands.pop_front(); }
    }
    for(size_t i=0;i<n;i++) process(batch[i]);
    try { render(); } catch(const DeviceUnavailable&) { fail(BC_ERROR_NO_DEVICE); } catch(...) { fail(BC_ERROR_DEVICE); }
    publish(); std::this_thread::sleep_for(std::chrono::milliseconds(2));
   }
   current.reset(); upcoming.reset(); sink.reset(); state.phase=BC_CLOSING;
   // No deadline path remains. Account for every accepted command on shutdown.
   { std::lock_guard<std::mutex> lock(gate);
     while(!commands.empty()) { event(commands.front(),BC_CANCELLED); commands.pop_front(); }
   }
   quiescent=true;
   // Final snapshot must be visible before destroy can succeed.
   while(staged) { publish(); std::this_thread::yield(); }
   publish();
  } catch(...) { state.error=BC_ERROR_RESOURCE; current.reset(); upcoming.reset(); sink.reset(); state.phase=BC_CLOSING; quiescent=true; publish(); }
 }
};
static std::mutex registry_gate;
static std::shared_ptr<Engine> engine;
static uint64_t token=0;
static std::shared_ptr<Engine> acquire(uint64_t handle) {
 std::lock_guard<std::mutex> lock(registry_gate); return handle==token ? engine : nullptr;
}
}
#define BC_CATCH catch(...) { return BC_FAILED; }
extern "C" {
int32_t bc_get_caps(bc_caps* out) {
 if(!out || out->struct_size!=sizeof(bc_caps)) return BC_INVALID;
 *out={sizeof(bc_caps),BC_ABI,128,256,2,8,192000,0x1ff,sizeof(bc_command),sizeof(bc_snapshot),sizeof(bc_event),LIBAVCODEC_VERSION_INT}; return BC_OK;
}
int64_t bc_monotonic_ns() { return std::chrono::duration_cast<std::chrono::nanoseconds>(bc::Clock::now().time_since_epoch()).count(); }
int32_t bc_create(uint64_t* handle) { try {
 if(!handle) return BC_INVALID; std::lock_guard<std::mutex> lock(bc::registry_gate);
 if(bc::engine) return BC_BUSY; bc::engine=std::make_shared<bc::Engine>(); *handle=++bc::token; return BC_OK;
} BC_CATCH }
int32_t bc_submit(uint64_t handle,const bc_command* c) { try {
 auto e=bc::acquire(handle); if(!e) return BC_HANDLE;
 if(!c || c->struct_size!=sizeof(bc_command) || c->abi!=BC_ABI || c->reserved || c->id==0 || c->selection==0 ||
    c->type<BC_SELECT || c->type>BC_DEVICE || !memchr(c->path,0,sizeof(c->path)) || !memchr(c->entry,0,128) || !memchr(c->rendition,0,128)) return BC_INVALID;
 if((c->type==BC_SELECT || c->type==BC_NOMINATE) && (!c->path[0] || !c->entry[0] || !c->rendition[0])) return BC_INVALID;
 if(c->type==BC_SEEK && c->value<0) return BC_INVALID;
 if(c->type==BC_RATE && (c->value<250000 || c->value>3000000)) return BC_INVALID;
 if(c->type==BC_VOLUME && (c->value<0 || c->value>1000000)) return BC_INVALID;
 std::lock_guard<std::mutex> lock(e->gate); if(e->closing) return BC_CLOSED;
 if(c->id<=e->last_id) return BC_STALE;
 if(e->commands.size()>=128 || e->accepted_unacked*2+e->autonomous_unacked>=240) return BC_BUSY;
 if(c->type==BC_SELECT) {
  if(c->selection<=e->admitted_selection || c->revision<e->admitted_revision) return BC_STALE;
  e->admitted_selection=c->selection; e->admitted_revision=c->revision;
 } else if(c->selection!=e->admitted_selection || c->revision!=e->admitted_revision) return BC_STALE;
 e->commands.push_back(*c); e->last_id=c->id; ++e->accepted_unacked; return BC_OK;
} BC_CATCH }
int32_t bc_read(uint64_t h,bc_snapshot* out) { try {
 auto e=bc::acquire(h); if(!e) return BC_HANDLE; if(!out || out->struct_size!=sizeof(*out)) return BC_INVALID;
 std::unique_lock<std::mutex> lock(e->gate,std::try_to_lock); if(!lock) return BC_RETRY; *out=e->published; return BC_OK;
} BC_CATCH }
int32_t bc_poll(uint64_t h,bc_event* out,uint32_t capacity,uint32_t* count) { try {
 auto e=bc::acquire(h); if(!e) return BC_HANDLE;
 if(!out || !count || !capacity || capacity>256) return BC_INVALID;
 std::lock_guard<std::mutex> lock(e->gate); *count=uint32_t(std::min<size_t>(capacity,e->journal.size()));
 for(uint32_t i=0;i<*count;i++) out[i]=e->journal[i]; return BC_OK;
} BC_CATCH }
int32_t bc_ack(uint64_t h,uint64_t seq) { try {
 auto e=bc::acquire(h); if(!e) return BC_HANDLE;
 std::lock_guard<std::mutex> lock(e->gate); if(seq>e->next_event) return BC_INVALID;
 while(!e->journal.empty() && e->journal.front().sequence<=seq) { if(e->journal.front().kind==BC_EVENT_COMMAND) --e->accepted_unacked; else --e->autonomous_unacked; e->journal.pop_front(); }
 return BC_OK;
} BC_CATCH }
int32_t bc_shutdown(uint64_t h) { try { auto e=bc::acquire(h); if(!e) return BC_HANDLE; e->closing=true; return BC_OK; } BC_CATCH }
int32_t bc_destroy(uint64_t h) { try {
 std::lock_guard<std::mutex> lock(bc::registry_gate); if(!bc::engine || h!=bc::token) return BC_HANDLE;
 if(!bc::engine->quiescent) return BC_BUSY; bc::engine.reset(); return BC_OK;
} BC_CATCH }
void* bc_alloc(uint64_t bytes) { if(!bytes || bytes>16*1024*1024) return nullptr; auto* p=calloc(1,size_t(bytes)); if(p) ++bc::allocations; return p; }
void bc_free(void* p) { if(p) { free(p); --bc::allocations; } }
uint64_t bc_live_allocations() { return bc::allocations.load(); }
}
