#include "decoder.h"
#include <chrono>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <set>
#include <stdexcept>
#include <thread>
#include <vector>
static void require(bool v,const char* m) { if(!v) throw std::runtime_error(m); }
template<class F> static void until(F fn) {
 const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(6);
 while(!fn()) { require(std::chrono::steady_clock::now()<deadline,"deadline"); std::this_thread::sleep_for(std::chrono::milliseconds(2)); }
}
static void word(std::ofstream& f,uint32_t v,int n) { for(int i=0;i<n;i++) f.put(char((v>>(i*8))&255)); }
static void wav(const std::filesystem::path& path,int frames) {
 std::ofstream f(path,std::ios::binary); f.write("RIFF",4); word(f,36+frames*4,4); f.write("WAVEfmt ",8);
 word(f,16,4);word(f,1,2);word(f,2,2);word(f,48000,4);word(f,192000,4);word(f,4,2);word(f,16,2);
 f.write("data",4); word(f,frames*4,4);
 for(int i=0;i<frames;i++) { word(f,uint16_t(i%16000-8000),2); word(f,uint16_t(8000-i%16000),2); }
}
int main(int argc,char** argv) {
 const auto dir=std::filesystem::temp_directory_path()/("bitchord-decode-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
 std::filesystem::create_directory(dir);
 try {
  const auto file=dir/"ramp.wav"; wav(file,24000);
  for(int seek : {0,125000}) {
   bc::Decoder d(file.u8string(),seek); until([&]{return d.ready.load() || d.error.load();}); require(d.ready && !d.error,"real WAV decode");
   int frames=0; const int offset=seek*48/1000;
   until([&]{
    const auto n=d.available();
    for(uint64_t i=0;i<n;i++) {
     const auto expected=float((frames+int(i)+offset)%16000-8000)/32768;
     require(std::abs(d.sample(i,0)-expected)<0.000001f && std::abs(d.sample(i,1)+expected)<0.000001f,"PCM differential/ramp seek");
    }
    frames+=int(n); d.consume(n); return d.eof && d.available()==0;
   });
   require(frames==24000-offset,"decoder tail/frame count"); require(d.high_water<=d.pcm.size()*4,"bounded PCM");
  }
  const auto longfile=dir/"long.wav";wav(longfile,48000*4);
  { bc::Decoder d(longfile.u8string());until([&]{return d.ready.load() && d.available()==96000;});require(!d.eof,"producer backpressure"); }
  require(bc::live_decoders==0,"cancel/join at full ring");
  { bc::Decoder d((dir/"missing").u8string());until([&]{return d.eof.load();});require(d.error!=0,"missing file distinct failure"); }
  if(argc>1) for(const auto* extension : {"wav","flac","mp3","m4a","aac","ogg","webm"}) {
   const auto fixture=std::filesystem::path(argv[1])/(std::string("tone.")+extension);
   std::ifstream reference(fixture.string()+".f32",std::ios::binary);
   require(bool(reference),"PCM oracle missing");
   bc::Decoder d(fixture.u8string()); until([&]{return d.ready.load() || d.error.load();});
   require(d.ready && !d.error,"codec decode open");
   uint64_t frames=0;
   until([&]{
    const auto n=d.available();
    for(uint64_t i=0;i<n;i++) for(uint32_t c=0;c<d.channels;c++) {
     float expected=0; reference.read(reinterpret_cast<char*>(&expected),4);
     require(bool(reference),"codec produced excess tail");
     const float tolerance=(std::strcmp(extension,"wav")==0 || std::strcmp(extension,"flac")==0)?0.0000001f:0.001f;
     require(std::isfinite(d.sample(i,c)) && std::abs(d.sample(i,c)-expected)<=tolerance,"codec PCM tolerance");
    }
    frames+=n; d.consume(n); return d.eof && d.available()==0;
   });
   require(reference.peek()==std::char_traits<char>::eof(),"codec truncated tail");
   require(frames>0 && !d.error,"codec frames/error");
   std::cout<<extension<<" "<<frames<<" frames"<<std::endl;
  }
  std::filesystem::remove_all(dir);std::cout<<"exact WAV PCM, seek, tail and bounded cancellation passed"<<std::endl;return 0;
 } catch(const std::exception& e) {std::cerr<<e.what()<<std::endl;return 1;}
}
