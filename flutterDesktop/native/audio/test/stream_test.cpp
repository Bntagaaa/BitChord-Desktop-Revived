#include "decoder.h"
#include <chrono>
#include <iostream>
#include <thread>
int main(int argc,char** argv) {
 if(argc!=2) return 64;
 uint64_t frames=0;
 const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(15);
 {
  bc::Decoder decoder(argv[1]);
  while(!decoder.ready && !decoder.eof && std::chrono::steady_clock::now()<deadline)
   std::this_thread::sleep_for(std::chrono::milliseconds(1));
  if(!decoder.ready || decoder.rate!=48000 || decoder.channels!=2) return 1;
  while(std::chrono::steady_clock::now()<deadline) {
   const auto n=decoder.available();
   // The fixture is a constant S16 signal, not generated success in a sink.
   for(uint64_t i=0;i<n;i++) for(uint32_t c=0;c<2;c++)
    if(decoder.sample(i,c)!=float(256)/32768) return 2;
   decoder.consume(n); frames+=n;
   if(decoder.eof && !decoder.available()) break;
   std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  if(decoder.error || frames!=384000 || decoder.high_water>48000*2*2*4) return 3;
 }
 if(bc::live_decoders) return 4;
 std::cout<<"{\"frames\":"<<frames<<",\"exact_pcm\":true,\"live_decoders\":0}\n";
}
