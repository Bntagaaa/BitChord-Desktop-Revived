#include "decoder.h"
#include "bitchord_audio.h"
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <memory>
#include <regex>
#include <mutex>
#include <stdexcept>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
}
namespace bc {
std::atomic<uint32_t> live_decoders{0};
struct Resources {
 AVFormatContext* format=nullptr; AVCodecContext* codec=nullptr;
 AVPacket* packet=av_packet_alloc(); AVFrame* frame=av_frame_alloc();
 SwrContext* swr=nullptr; AVIOContext* io=nullptr; FILE* file=nullptr;
 ~Resources() {
  swr_free(&swr); av_frame_free(&frame); av_packet_free(&packet);
  avcodec_free_context(&codec); avformat_close_input(&format);
  if(io) { av_freep(&io->buffer); avio_context_free(&io); }
  if(file) fclose(file);
 }
};
struct Input { FILE* file; std::atomic<bool>* cancel; };
static int read_file(void* opaque, uint8_t* p, int n) {
 auto* i=static_cast<Input*>(opaque); if(*i->cancel) return AVERROR_EXIT;
 const auto count=fread(p,1,size_t(n),i->file);
 return count ? int(count) : (ferror(i->file) ? AVERROR(EIO) : AVERROR_EOF);
}
static int64_t seek_file(void* opaque, int64_t offset, int whence) {
 auto* i=static_cast<Input*>(opaque); if(*i->cancel) return AVERROR_EXIT;
#ifdef _WIN32
 if(whence==AVSEEK_SIZE) { auto at=_ftelli64(i->file); _fseeki64(i->file,0,SEEK_END); auto end=_ftelli64(i->file); _fseeki64(i->file,at,SEEK_SET); return end; }
 return _fseeki64(i->file,offset,whence & ~AVSEEK_FORCE)==0 ? _ftelli64(i->file) : AVERROR(EIO);
#else
 if(whence==AVSEEK_SIZE) { auto at=ftello(i->file); fseeko(i->file,0,SEEK_END); auto end=ftello(i->file); fseeko(i->file,at,SEEK_SET); return end; }
 return fseeko(i->file,offset,whence & ~AVSEEK_FORCE)==0 ? ftello(i->file) : AVERROR(EIO);
#endif
}
Decoder::Decoder(std::string path, int64_t seek) : worker([this,path=std::move(path),seek] {
 ++live_decoders;
 try { run(path,seek); } catch(...) { error=BC_ERROR_DECODE; }
 eof.store(true,std::memory_order_release); --live_decoders;
}) {}
Decoder::~Decoder() { cancel=true; if(worker.joinable()) worker.join(); }
void Decoder::run(const std::string& path, int64_t seek) {
 Resources r;
 // Only app-owned, tokenized loopback grants enter the network demuxer.
 // Upstream signed URLs/headers are owned by the asynchronous Dart IO broker.
 const bool broker=std::regex_match(path,std::regex(R"(^http://127\.0\.0\.1:[0-9]{1,5}/[a-f0-9]{64}$)"));
 Input input{nullptr,&cancel};
 r.format=avformat_alloc_context(); if(!r.format) throw std::bad_alloc();
 r.format->interrupt_callback={[](void* p) { return int(*static_cast<std::atomic<bool>*>(p)); }, &cancel};
 r.format->probesize=1024*1024; r.format->max_analyze_duration=3000000;
 if(!broker) {
  const auto local=std::filesystem::u8path(path);
  if(!std::filesystem::is_regular_file(local)) throw std::runtime_error("not a local regular file");
#ifdef _WIN32
  r.file=_wfopen(local.c_str(), L"rb");
#else
  r.file=fopen(path.c_str(),"rb");
#endif
  if(!r.file || !r.frame || !r.packet) throw std::bad_alloc();
  input.file=r.file;
  r.io=avio_alloc_context(static_cast<unsigned char*>(av_malloc(32768)),32768,0,&input,read_file,nullptr,seek_file);
  if(!r.io) throw std::bad_alloc();
  r.format->pb=r.io; r.format->flags|=AVFMT_FLAG_CUSTOM_IO;
 } else {
  static std::once_flag network;
  std::call_once(network,[] { avformat_network_init(); });
 }
 AVDictionary* options=nullptr;
 av_dict_set(&options,"protocol_whitelist",broker?"http,tcp":"file",0);
 av_dict_set(&options,"rw_timeout","15000000",0);
 av_dict_set(&options,"format_whitelist","wav,mp3,flac,ogg,mov,matroska,webm,aac,aiff,asf",0);
 int result=avformat_open_input(&r.format,broker?path.c_str():nullptr,nullptr,&options); av_dict_free(&options);
 if(result<0 || avformat_find_stream_info(r.format,nullptr)<0) throw std::runtime_error("probe");
 const int stream=av_find_best_stream(r.format,AVMEDIA_TYPE_AUDIO,-1,-1,nullptr,0);
 if(stream<0) throw std::runtime_error("no audio");
 auto* s=r.format->streams[stream]; auto* codec=avcodec_find_decoder(s->codecpar->codec_id);
 if(!codec) throw std::runtime_error("unsupported codec");
 r.codec=avcodec_alloc_context3(codec);
 if(!r.codec || avcodec_parameters_to_context(r.codec,s->codecpar)<0) throw std::runtime_error("codec");
 r.codec->thread_count=1;
 r.codec->pkt_timebase=s->time_base;
 if(avcodec_open2(r.codec,codec,nullptr)<0) throw std::runtime_error("codec open");
 rate=uint32_t(r.codec->sample_rate);
#if LIBAVUTIL_VERSION_MAJOR >= 57
 channels=uint32_t(r.codec->ch_layout.nb_channels);
 channel_mask=r.codec->ch_layout.order==AV_CHANNEL_ORDER_NATIVE?r.codec->ch_layout.u.mask:0;
 if(swr_alloc_set_opts2(&r.swr,&r.codec->ch_layout,AV_SAMPLE_FMT_FLT,int(rate),
    &r.codec->ch_layout,r.codec->sample_fmt,int(rate),0,nullptr)<0) throw std::runtime_error("resample");
#else
 channels=uint32_t(r.codec->channels);
 channel_mask=r.codec->channel_layout;
 int64_t layout=r.codec->channel_layout ? int64_t(r.codec->channel_layout) : av_get_default_channel_layout(int(channels));
 r.swr=swr_alloc_set_opts(nullptr,layout,AV_SAMPLE_FMT_FLT,int(rate),layout,r.codec->sample_fmt,int(rate),0,nullptr);
#endif
 if(rate<8000 || rate>192000 || channels<1 || channels>8) { error=BC_ERROR_FORMAT; return; }
 if(!r.swr || swr_init(r.swr)<0) throw std::runtime_error("resample init");
 duration=r.format->duration>0 ? r.format->duration : 0;
 const int64_t start=s->start_time==AV_NOPTS_VALUE ? 0 : av_rescale_q(s->start_time,s->time_base,AV_TIME_BASE_Q);
 origin=seek;
 if(seek>0 && av_seek_frame(r.format,stream,av_rescale_q(seek+start,AV_TIME_BASE_Q,s->time_base),AVSEEK_FLAG_BACKWARD)<0) {
  error=BC_ERROR_SEEK; return;
 }
 pcm.resize(size_t(rate)*channels*2); // Two seconds per decoder, never track-sized.
 ready.store(true,std::memory_order_release);
 std::vector<float> block(size_t(65536)*channels);
 bool draining=false; int64_t position=0;
 auto push=[&](const float* samples, int frames) {
  size_t at=0;
  while(at<size_t(frames) && !cancel) {
   const uint64_t written=write_at.load();
   const auto free=size_t(rate)*2-size_t(written-read_at.load(std::memory_order_acquire));
   const auto n=std::min(free,size_t(frames)-at);
   if(!n) { std::this_thread::sleep_for(std::chrono::milliseconds(2)); continue; }
   for(size_t f=0;f<n;f++) for(uint32_t c=0;c<channels;c++)
    pcm[((written+f)*channels+c)%pcm.size()]=samples[(at+f)*channels+c];
   write_at.store(written+n,std::memory_order_release);
   auto level=(written+n-read_at.load())*channels*sizeof(float);
   high_water.store(std::max(high_water.load(),level)); at+=n;
  }
 };
 while(!cancel) {
  result=avcodec_receive_frame(r.codec,r.frame);
  if(result==AVERROR_EOF) break;
  if(result==AVERROR(EAGAIN)) {
   if(draining) throw std::runtime_error("drain stalled");
   do {
    av_packet_unref(r.packet); result=av_read_frame(r.format,r.packet);
   } while(result>=0 && r.packet->stream_index!=stream && !cancel);
   if(cancel) return;
   if(result==AVERROR_EOF) { result=avcodec_send_packet(r.codec,nullptr); draining=true; }
   else if(result<0) throw std::runtime_error("read");
   else result=avcodec_send_packet(r.codec,r.packet);
   if(result<0) throw std::runtime_error("send");
   continue;
  }
  if(result<0 || r.frame->nb_samples>65536) throw std::runtime_error("decode/frame bound");
  const auto pts=r.frame->best_effort_timestamp;
  if(pts!=AV_NOPTS_VALUE) position=av_rescale_q(pts,s->time_base,AV_TIME_BASE_Q)-start;
  uint8_t* output=reinterpret_cast<uint8_t*>(block.data());
  const int count=swr_convert(r.swr,&output,65536,const_cast<const uint8_t**>(r.frame->extended_data),r.frame->nb_samples);
  if(count<0) throw std::runtime_error("convert");
  // libavcodec already applies encoder skip/discard side data at open.
  // Only an explicit seek needs timestamp trimming; trimming open at t=0
  // would discard MP3/Opus priming a second time.
  // Older FFmpeg decoders leave the final AAC frame padded while the
  // demuxed packet/frame duration carries its exact audible sample extent.
#if LIBAVUTIL_VERSION_MAJOR >= 58
  const auto frame_duration=r.frame->duration;
#else
  const auto frame_duration=r.frame->pkt_duration;
#endif
  const bool sample_precise_timebase=int64_t(s->time_base.den)>=int64_t(rate)*s->time_base.num;
  const int extent=frame_duration>0 && sample_precise_timebase ? int(std::min<int64_t>(count,av_rescale_q(frame_duration,s->time_base,AVRational{1,int(rate)}))) : count;
  const int skip=seek>0 ? int(std::min<int64_t>(extent,std::max<int64_t>(0,av_rescale_rnd(seek-position,rate,1000000,AV_ROUND_UP)))) : 0;
  if(extent>skip) push(block.data()+size_t(skip)*channels,extent-skip);
  position+=int64_t(count)*1000000/rate;
  av_frame_unref(r.frame);
 }
 // Preserve the resampler tail, including codec priming/end trimming done by FFmpeg.
 while(!cancel) {
  uint8_t* output=reinterpret_cast<uint8_t*>(block.data());
  const int count=swr_convert(r.swr,&output,65536,nullptr,0);
  if(count<=0) break; push(block.data(),count);
 }
}
}
