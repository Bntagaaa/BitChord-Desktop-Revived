#include "dsp.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <locale>
#include <sstream>
#include <stdexcept>

namespace bc::dsp {
namespace {
constexpr double pi = 3.14159265358979323846;
constexpr std::array<float, slots> frequency{60,150,400,1000,2500,6000,14000,250,1000,4000};
enum Kind { bell, low, high };
constexpr std::array<Kind, slots> kind{low,bell,bell,bell,bell,bell,high,low,bell,high};
float response(size_t slot, float gain, float q, float hz) {
  if (std::abs(gain) < .01f) return 0;
  double a = std::pow(10., gain / 40.), a2 = a*a;
  double x = double(hz / frequency[slot]), x2 = x*x, qq = double(q*q), magnitude;
  if (kind[slot] == bell) {
    const double flat = (1-x2)*(1-x2);
    magnitude = std::sqrt((flat+x2*a2/qq)/(flat+x2/(a2*qq)));
  } else {
    const double common = x2*a/qq;
    const double lo = (a-x2)*(a-x2)+common, hi = (1-a*x2)*(1-a*x2)+common;
    magnitude = a*std::sqrt(kind[slot] == low ? lo/hi : hi/lo);
  }
  return float(20*std::log10(magnitude));
}
void preamp(Curve& c) {
  float peak = 0;
  for (int p=0; p<96; ++p) {
    float hz = float(20*std::pow(1000., double(p)/95)), sum = 0;
    for (size_t s=0; s<slots; ++s) sum += response(s,c.gain[s],c.q[s],hz);
    peak = std::max(peak,sum);
  }
  c.preamp = -peak;
}
void validate(uint32_t channels, uint32_t rate, size_t block) {
  if (!channels || channels>8 || rate<8000 || rate>192000 || !block || block>8192)
    throw std::invalid_argument("DSP format/buffer bound");
}
}
Curve manual(const std::array<float,7>& bands) {
  Curve c;
  for(size_t i=0;i<7;++i) { c.gain[i]=std::clamp(bands[i],-12.f,12.f); c.q[i]=1; }
  preamp(c); return c;
}
Curve tone(int x,int y,bool focused) {
  Curve c;
  float tilt=std::clamp(x,-5,5)*1.2f, contour=std::clamp(y,-5,5)*1.2f;
  c.gain[7]=-tilt; c.gain[8]=contour; c.gain[9]=tilt;
  c.q[7]=c.q[9]=focused?.9f:.5f; c.q[8]=focused?2.2f:.7f;
  preamp(c); return c;
}
bool parseSettings(const char* payload,Settings& out) {
  std::istringstream in(payload); in.imbue(std::locale::classic());
  int version,enabled,mode,x,y,focused,spatial; float balance;
  std::array<float,7> bands{};
  if(!(in>>version>>enabled>>mode>>x>>y>>focused>>balance>>spatial)) return false;
  if(version!=1 || (enabled!=0 && enabled!=1) || mode<0 || mode>1 || x<-5 || x>5 || y<-5 || y>5 ||
    (focused!=0 && focused!=1) || (spatial!=0 && spatial!=1) || !std::isfinite(balance) || std::abs(balance)>1) return false;
  for(auto& b:bands) if(!(in>>b) || !std::isfinite(b) || std::abs(b)>12) return false;
  in>>std::ws; if(!in.eof()) return false;
  Settings candidate; candidate.enabled=enabled!=0; candidate.spatial=spatial!=0; candidate.balance=balance;
  candidate.curve=mode==0?manual(bands):tone(x,y,focused!=0);
  out=candidate; return true;
}
void Equalizer::tune(const Settings& s) {
  target_=s.enabled?s.curve:Curve{}; target_balance_=s.enabled?std::clamp(s.balance,-1.f,1.f):0;
}
void Equalizer::configure(uint32_t channels,uint32_t rate) {
  validate(channels,rate,1);
  if(channels_==channels && rate_==rate) return;
  channels_=channels; rate_=rate; reset();
}
void Equalizer::reset() {
  state_.fill(0); running_.fill(false); current_=target_; balance_=target_balance_;
}
void Equalizer::process(float* data,size_t frames) {
  if(!channels_ || !rate_ || !frames) return;
  bool flat=std::abs(target_balance_)<.0005f && std::abs(target_.preamp)<.01f;
  bool settled=std::abs(balance_-target_balance_)<.0005f && std::abs(current_.preamp-target_.preamp)<.01f;
  for(size_t s=0;s<slots;++s) {
    flat=flat && std::abs(target_.gain[s])<.01f;
    settled=settled && std::abs(current_.gain[s]-target_.gain[s])<.01f;
  }
  if(flat && settled) return;
  for(size_t start=0;start<frames;start+=64) {
    std::array<size_t,slots> active{}; size_t count=0;
    for(size_t s=0;s<slots;++s) {
      current_.gain[s]+=(target_.gain[s]-current_.gain[s])*.08f;
      const float from=std::log(std::max(current_.q[s],.05f)), to=std::log(std::max(target_.q[s],.05f));
      current_.q[s]=std::exp(from+(to-from)*.08f);
      if(std::abs(current_.gain[s])<.01f) {
        if(running_[s]) for(uint32_t c=0;c<channels_;++c) state_[(c*slots+s)*2]=state_[(c*slots+s)*2+1]=0;
        running_[s]=false; continue;
      }
      active[count++]=s; running_[s]=true;
      float a=std::pow(10.f,current_.gain[s]/40.f), q=std::max(current_.q[s],.05f);
      float base=float(std::tan(pi*std::clamp(frequency[s],10.f,rate_*.45f)/rate_)), g,k;
      if(kind[s]==bell) { g=base; k=1.f/(q*a); m0_[s]=1; m1_[s]=k*(a*a-1); m2_[s]=0; }
      else if(kind[s]==low) { g=base/std::sqrt(a); k=1.f/q; m0_[s]=1; m1_[s]=k*(a-1); m2_[s]=a*a-1; }
      else { g=base*std::sqrt(a); k=1.f/q; m0_[s]=a*a; m1_[s]=k*(1-a)*a; m2_[s]=1-a*a; }
      float d=1.f/(1.f+g*(g+k)); a1_[s]=d; a2_[s]=g*d; a3_[s]=g*(g*d);
    }
    current_.preamp+=(target_.preamp-current_.preamp)*.08f;
    balance_+=(target_balance_-balance_)*.08f;
    float trim=std::pow(10.f,current_.preamp/20.f);
    for(size_t f=start;f<std::min(frames,start+64);++f) for(uint32_t c=0;c<channels_;++c) {
      float sample=data[f*channels_+c];
      for(size_t n=0;n<count;++n) {
        size_t s=active[n], i=(c*slots+s)*2;
        float ic1=state_[i],ic2=state_[i+1],v3=sample-ic2;
        float v1=a1_[s]*ic1+a2_[s]*v3, v2=ic2+a2_[s]*ic1+a3_[s]*v3;
        state_[i]=2*v1-ic1; state_[i+1]=2*v2-ic2;
        sample=m0_[s]*sample+m1_[s]*v1+m2_[s]*v2;
      }
      float balance=channels_==2?std::min(1.f,c==0?1-balance_:1+balance_):1.f;
      data[f*channels_+c]=sample*(trim*balance);
    }
    for(size_t n=0;n<count;++n) for(uint32_t c=0;c<channels_;++c) {
      size_t i=(c*slots+active[n])*2;
      if(std::abs(state_[i])<1e-12f) state_[i]=0;
      if(std::abs(state_[i+1])<1e-12f) state_[i+1]=0;
    }
  }
}
void Spatial::configure(uint32_t channels,uint32_t rate) {
  validate(channels,rate,1);
  if(channels_==channels && rate_==rate) return;
  channels_=channels; rate_=rate; delay_=size_t(std::lround(rate*.015f)); reset();
}
void Spatial::reset() { left_.fill(0);right_.fill(0);cursor_=0;low_left_=low_right_=0; }
void Spatial::process(float* samples,size_t frames,bool enabled) {
  if(!enabled || channels_!=2) return;
  for(size_t f=0;f<frames;++f) {
    float l=samples[f*2],r=samples[f*2+1],mid=(l+r)*.5f,side=(l-r)*.5f*2.5f;
    float wl=mid+side,wr=mid-side;
    low_left_+=.3f*(right_[cursor_]-low_left_); low_right_+=.3f*(left_[cursor_]-low_right_);
    wl+=low_left_*.2f; wr+=low_right_*.2f;
    left_[cursor_]=l;right_[cursor_]=r;cursor_=(cursor_+1)%delay_;
    samples[f*2]=wl*.82f;samples[f*2+1]=wr*.82f;
  }
}
Echo::Echo(uint32_t channels,uint32_t rate):channels_(channels),rate_(rate) {
  validate(channels,rate,1);ring_.resize((size_t(rate)+1)*channels);delay_=channels;
}
void Echo::configure(double seconds) {
  if(!std::isfinite(seconds)) throw std::invalid_argument("echo delay");
  delay_=size_t(std::clamp(seconds*rate_*channels_,double(channels_),double(ring_.size()-channels_)));
  std::fill(ring_.begin(),ring_.end(),0);cursor_=0;
}
float Echo::process(float sample,float send,float dry) {
  float delayed=ring_[(cursor_+ring_.size()-delay_)%ring_.size()];
  ring_[cursor_]=sample*send+delayed*.4f;cursor_=(cursor_+1)%ring_.size();
  return sample*dry+delayed*.5f;
}
SilenceSkipper::SilenceSkipper(uint32_t channels,uint32_t rate,size_t block):channels_(channels),minimum_(rate),kept_(rate/10),max_block_(block) {
  validate(channels,rate,block);
  pending_.resize(minimum_*channels);tail_.resize(kept_*channels);output_.resize((block+minimum_+kept_)*channels);
}
void SilenceSkipper::reset() { pending_frames_=tail_frames_=tail_write_=0;skipping_=false;skipped_=0; }
View SilenceSkipper::process(const float* data,size_t frames,bool enabled) {
  if(frames>max_block_) throw std::length_error("silence block bound");
  if(!enabled) return {data,frames};
  size_t produced=0;
  auto emit=[&](const float* p,size_t n,bool attenuate) {
    for(size_t i=0;i<n*channels_;++i) output_[produced*channels_+i]=attenuate?p[i]*.1f:p[i];
    produced+=n;
  };
  for(size_t f=0;f<frames;++f) {
    const float* frame=data+f*channels_;bool silent=true;
    for(uint32_t c=0;c<channels_;++c) if(std::abs(frame[c])>1024.f/32768.f) silent=false;
    if(silent) {
      if(skipping_) {
        std::copy_n(frame,channels_,tail_.data()+tail_write_*channels_);
        tail_write_=(tail_write_+1)%kept_;tail_frames_=std::min(tail_frames_+1,kept_);++skipped_;
      } else {
        std::copy_n(frame,channels_,pending_.data()+pending_frames_*channels_);++pending_frames_;
        if(pending_frames_>=minimum_) {
          emit(pending_.data(),kept_,true);skipped_+=pending_frames_-kept_;
          pending_frames_=tail_frames_=tail_write_=0;skipping_=true;
        }
      }
    } else {
      if(skipping_) {
        size_t start=tail_frames_<kept_?0:tail_write_;
        for(size_t i=0;i<tail_frames_;++i) emit(tail_.data()+((start+i)%kept_)*channels_,1,true);
        tail_frames_=tail_write_=0;
      } else if(pending_frames_) emit(pending_.data(),pending_frames_,false);
      pending_frames_=0;skipping_=false;emit(frame,1,false);
    }
  }
  return {output_.data(),produced};
}
Speed::Speed(uint32_t channels,uint32_t rate,size_t block):channels_(channels),hop_(std::max(64,int(std::lround(rate*.028f)))),search_(std::max(16,int(std::lround(rate*.007f)))),max_block_(block) {
  validate(channels,rate,block);
  // At 3x the nominal read can lead the last audible window by three hops;
  // retain both that lead and the previous join used by drain/return-to-1x.
  size_t capacity=block+size_t(hop_*8+search_*4);
  pending_.resize(capacity*channels);tail_.resize(size_t(hop_)*channels);
  output_.resize((capacity*4+size_t(hop_))*channels);
}
void Speed::reset() { pending_frames_=read_=last_join_=last_offset_=0;tail_present_=engaged_=false; }
float Speed::score(int start) const {
  if(start<0 || start+hop_>pending_frames_) return -std::numeric_limits<float>::infinity();
  float dot=0,energy=0;
  for(int f=0;f<hop_;f+=4) {
    float t=0,p=0;
    for(uint32_t c=0;c<channels_;++c) {t+=tail_[f*channels_+c];p+=pending_[(start+f)*channels_+c];}
    dot+=t*p;energy+=p*p;
  }
  return dot/std::sqrt(energy+1e-9f);
}
int Speed::offset(int from) const {
  int best_offset=std::clamp(last_offset_,-search_,search_);float best=score(from+best_offset);
  for(int o=-search_;o<=search_;++o) if(o!=best_offset) {
    float candidate=score(from+o);
    if(candidate>best+.001f*std::abs(best)) {best=candidate;best_offset=o;}
  }
  return best_offset;
}
View Speed::process(const float* data,size_t frames,float rate) {
  if(frames>max_block_ || !std::isfinite(rate) || rate<.25f || rate>3.f) throw std::invalid_argument("speed block/rate bound");
  if(std::abs(rate-1)<.001f && !engaged_) {reset();return {data,frames};}
  engaged_=true;
  if((size_t(pending_frames_)+frames)*channels_>pending_.size()) throw std::length_error("speed pending bound");
  std::copy_n(data,frames*channels_,pending_.data()+pending_frames_*channels_);pending_frames_+=int(frames);
  int advance=std::max(1,int(std::lround(hop_*rate))),read=read_;size_t produced=0;
  while(pending_frames_-read>=2*hop_+2*search_) {
    int at=tail_present_?read+offset(read):read;last_join_=at;
    if(produced+size_t(hop_)>output_.size()/channels_) throw std::length_error("speed output bound");
    for(int f=0;f<hop_;++f) {
      float w=float(f)/hop_;
      for(uint32_t c=0;c<channels_;++c) {
        float incoming=pending_[(at+f)*channels_+c],outgoing=tail_present_?tail_[f*channels_+c]:incoming;
        output_[(produced+f)*channels_+c]=outgoing*(1-w)+incoming*w;
      }
    }
    std::copy_n(pending_.data()+(at+hop_)*channels_,size_t(hop_)*channels_,tail_.data());
    tail_present_=true;last_offset_=at-read;produced+=hop_;read+=advance;
  }
  int drop=std::max(0,std::min(read-search_,tail_present_?last_join_+hop_:read));
  std::memmove(pending_.data(),pending_.data()+drop*channels_,size_t(pending_frames_-drop)*channels_*sizeof(float));
  pending_frames_-=drop;read_=read-drop;last_join_-=drop;
  return {output_.data(),produced};
}
View Speed::drain() {
  int from=tail_present_?last_join_+hop_:read_,rest=std::max(0,pending_frames_-from);
  std::copy_n(pending_.data()+from*channels_,size_t(rest)*channels_,output_.data());
  reset();return {output_.data(),size_t(rest)};
}
} // namespace bc::dsp
