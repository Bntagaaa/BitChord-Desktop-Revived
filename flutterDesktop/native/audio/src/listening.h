// DesktopAudioEngine.renderProcessed/play at pinned 348b613a: silence jumps
// start at the corresponding submitted frame; tempo scales the source slope.
#pragma once
#include "dsp.h"
#include <algorithm>
#include <stdexcept>
namespace bc {
class ListeningPipeline {
 public:
  ListeningPipeline(uint32_t channels,uint32_t rate,double origin)
      : channels_(channels),silence_(channels,rate,512),speed_(channels,rate,512),position_(origin) {}
  void reset(double origin) {
    silence_.reset();speed_.reset();quiet_={nullptr,0};output_={nullptr,0};
    quiet_at_=output_at_=0;finishing_=false;position_=origin;
  }
  void push(const float* pcm,size_t frames,bool skip,double source_step) {
    if(!empty() || finishing_) throw std::logic_error("listener backpressure");
    const auto before=silence_.skipped();
    quiet_=silence_.process(pcm,frames,skip);quiet_at_=0;source_step_=source_step;
    position_+=double(silence_.skipped()-before)*source_step;
  }
  void set(float tempo,bool skip) {
    tempo_=tempo;
    if(skip_!=skip) {silence_.reset();skip_=skip;}
  }
  bool empty() const {return quiet_at_==quiet_.frames && output_at_==output_.frames;}
  bool finished() const {return finishing_ && empty();}
  void finish() {
    if(!empty()) throw std::logic_error("listener drain before consumption");
    if(!finishing_) {
      finishing_=true;output_=speed_.drain();output_at_=0;output_step_=source_step_*tempo_;
    }
  }
  // Consume each view before the next call. Downstream partial writes retain
  // their own suffix, never ask this stage to process it a second time.
  dsp::View take(size_t maximum) {
    while(output_at_==output_.frames && quiet_at_<quiet_.frames) {
      const auto n=std::min<size_t>(512,quiet_.frames-quiet_at_);
      output_=speed_.process(quiet_.data+quiet_at_*channels_,n,tempo_);
      quiet_at_+=n;output_at_=0;output_step_=source_step_*tempo_;
    }
    const auto n=std::min(maximum,output_.frames-output_at_);
    const auto* data=n ? output_.data+output_at_*channels_ : nullptr;
    last_position_=position_;last_step_=output_step_;
    output_at_+=n;position_+=double(n)*output_step_;
    return {data,n};
  }
  double source() const {return last_position_;}
  double step() const {return last_step_;}
 private:
  uint32_t channels_;
  dsp::SilenceSkipper silence_;
  dsp::Speed speed_;
  dsp::View quiet_{nullptr,0},output_{nullptr,0};
  size_t quiet_at_=0,output_at_=0;
  double position_=0,last_position_=0,last_step_=0,source_step_=0,output_step_=0;
  float tempo_=1;
  bool skip_=false,finishing_=false;
};
} // namespace bc
