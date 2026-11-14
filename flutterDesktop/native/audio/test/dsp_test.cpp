#include "dsp.h"
#include "listening.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

static bool audit_allocations=false;
static size_t allocations=0;
void* operator new(size_t n) {
  if(audit_allocations) ++allocations;
  if(void* p=std::malloc(n?n:1)) return p;
  throw std::bad_alloc();
}
void* operator new[](size_t n) { return ::operator new(n); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p,size_t) noexcept { std::free(p); }
void operator delete[](void* p,size_t) noexcept { std::free(p); }

using namespace bc::dsp;
void require(bool yes,const char* message) { if(!yes) throw std::runtime_error(message); }
std::vector<float> input(size_t frames,uint32_t channels) {
  uint32_t seed=17;std::vector<float> result(frames*channels);
  for(auto& sample:result) {seed=seed*1664525u+1013904223u;sample=(int(seed>>16)-32768)/65536.f;}
  return result;
}
double largest_error=0;
void compare(const std::string& root,const std::string& name,const std::vector<float>& actual,double tolerance) {
  std::ifstream file(root+"/"+name+".f32",std::ios::binary|std::ios::ate);
  require(bool(file),"missing Kotlin fixture");
  auto bytes=file.tellg();require(bytes==std::streamoff(actual.size()*sizeof(float)),"Kotlin exact sample count");
  std::vector<float> expected(actual.size());file.seekg(0);file.read(reinterpret_cast<char*>(expected.data()),bytes);
  double maximum=0;
  for(size_t i=0;i<actual.size();++i) {
    require(std::isfinite(actual[i]),"nonfinite DSP output");
    maximum=std::max(maximum,std::abs(double(actual[i])-expected[i]));
  }
  std::cout<<name<<" samples="<<actual.size()<<" max_abs="<<maximum<<"\n";
  require(maximum<=tolerance,"predeclared Kotlin PCM tolerance");largest_error=std::max(largest_error,maximum);
}
int main(int argc,char** argv) {
 try {
  require(argc==2,"fixture directory required");std::string root=argv[1];
  for(uint32_t rate:{8000u,44100u,48000u,96000u,192000u}) {
    for(uint32_t channels:{1u,2u,6u}) {
      Equalizer eq;Settings settings;settings.enabled=true;settings.balance=.35f;
      settings.curve=manual({12,-6,3,-9,7,-2,10});eq.tune(settings);eq.configure(channels,rate);
      auto data=input(2048,channels);
      audit_allocations=true;eq.process(data.data(),1024);audit_allocations=false;
      settings.curve=tone(-4,3,true);settings.balance=-.25f;eq.tune(settings);
      audit_allocations=true;eq.process(data.data()+1024*channels,1024);audit_allocations=false;
      compare(root,"eq-"+std::to_string(rate)+"-"+std::to_string(channels),data,.0001);
      // A seek/fresh-source reset must remove all integrator history.
      Equalizer fresh;fresh.tune(settings);fresh.configure(channels,rate);eq.reset();
      auto a=input(512,channels),b=a;eq.process(a.data(),512);fresh.process(b.data(),512);require(a==b,"EQ reset leaked old track history");
    }
    Spatial spatial;spatial.configure(2,rate);auto data=input(4096,2);
    audit_allocations=true;spatial.process(data.data(),4096,true);audit_allocations=false;
    compare(root,"spatial-"+std::to_string(rate),data,.000002);
    spatial.configure(1,rate);auto mono=input(512,1),original=mono;spatial.process(mono.data(),512,true);require(mono==original,"mono spatial bypass");
  }
  for(uint32_t rate:{8000u,48000u}) {
    SilenceSkipper skipper(2,rate,512);std::vector<float> data((rate*2+128)*2),output;
    for(size_t i=0;i<data.size();++i) data[i]=(i<128 || i>=rate*4)?.25f:.015625f;
    for(size_t at=0;at<data.size();at+=1024) {
      size_t frames=std::min<size_t>(512,(data.size()-at)/2);
      audit_allocations=true;auto view=skipper.process(data.data()+at,frames,true);audit_allocations=false;
      output.insert(output.end(),view.data,view.data+view.frames*2);
    }
    compare(root,"silence-"+std::to_string(rate),output,.000002);
    std::ifstream expected(root+"/silence-"+std::to_string(rate)+".skipped");uint64_t skipped=0;expected>>skipped;
    require(skipper.skipped()==skipped,"silence exact skipped-frame accounting");skipper.reset();require(skipper.skipped()==0,"silence reset");
  }
  for(float speed:{.25f,.75f,1.f,1.25f,3.f}) {
    Speed processor(2,8000,512);auto data=input(8192,2);std::vector<float> output;
    for(size_t at=0;at<data.size();at+=1024) {
      if(at==8192) speed=1;
      audit_allocations=true;auto view=processor.process(data.data()+at,512,speed);audit_allocations=false;
      output.insert(output.end(),view.data,view.data+view.frames*2);
    }
    auto rest=processor.drain();output.insert(output.end(),rest.data,rest.data+rest.frames*2);
    // The fixture name records the starting speed, including return-to-1x.
    static const int names[]{25,75,100,125,300};static size_t index=0;
    compare(root,"speed-"+std::to_string(names[index++]),output,.000002);
  }
  Echo echo(2,8000);echo.configure(.125);std::vector<float> data(8000);data[0]=1;
  audit_allocations=true;for(auto& sample:data) sample=echo.process(sample,1,.5f);audit_allocations=false;
  compare(root,"echo",data,.000002);
  for(uint32_t rate:{8000u,48000u}) for(float tempo:{.25f,.75f,1.f,1.25f,3.f}) {
    bc::ListeningPipeline pipeline(2,rate,0);pipeline.set(tempo,true);
    std::vector<float> source((rate*2+128)*2),output;
    std::vector<int64_t> times;
    for(size_t i=0;i<source.size();++i) source[i]=(i<128 || i>=rate*4)?.25f:.015625f;
    auto take=[&] {
      while(!pipeline.empty()) {
        audit_allocations=true;const auto view=pipeline.take(512);audit_allocations=false;
        if(view.frames) output.insert(output.end(),view.data,view.data+view.frames*2);
        for(size_t f=0;f<view.frames;++f) times.push_back(int64_t(pipeline.source()+f*pipeline.step()));
      }
    };
    for(size_t at=0;at<source.size();at+=1024) {
      const auto frames=std::min<size_t>(512,(source.size()-at)/2);
      audit_allocations=true;pipeline.push(source.data()+at,frames,true,1000000.0/rate);audit_allocations=false;
      take();
    }
    audit_allocations=true;pipeline.finish();audit_allocations=false;take();
    const auto name="pipeline-"+std::to_string(rate)+"-"+std::to_string(int(tempo*100));
    compare(root,name,output,.000002);
    std::ifstream file(root+"/"+name+".clock",std::ios::binary|std::ios::ate);
    require(file && file.tellg()==std::streamoff(times.size()*sizeof(int64_t)),"reference clock frame count");
    std::vector<int64_t> expected(times.size());file.seekg(0);file.read(reinterpret_cast<char*>(expected.data()),std::streamsize(expected.size()*8));
    for(size_t i=0;i<times.size();++i) require(std::abs(times[i]-expected[i])<=2,"reference source clock tolerance (2us)");
  }
  Settings parsed;
  require(parseSettings("1 1 0 0 0 0 0.5 1 12 -6 3 -9 7 -2 10",parsed),"valid atomic DSP settings");
  for(const char* bad:{"", "1 1", "2 1 0 0 0 0 0 0 0 0 0 0 0 0 0", "1 1 0 0 0 0 nan 0 0 0 0 0 0 0 0", "1 1 0 0 0 0 0 0 13 0 0 0 0 0 0", "1 1 0 0 0 0 0 0 0 0 0 0 0 0 0 extra"})
    require(!parseSettings(bad,parsed),"malformed DSP settings admitted");
  require(allocations==0,"DSP processing allocated after preparation");
  std::cout<<"{\"kotlin_cases\":38,\"process_allocations\":"<<allocations<<",\"max_abs\":"<<largest_error<<"}\n";
  return 0;
 } catch(const std::exception& e) {audit_allocations=false;std::cerr<<e.what()<<"\n";return 1;}
}
