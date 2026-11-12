#include "bitchord_audio.h"
#include <cstring>
extern "C" {
#include <libavutil/des.h>
#include <libavutil/mem.h>
}
// Provider protocol's public DES obfuscation key, not account encryption.
// Separate from all playback/real-time owners. Input/output are caller-owned.
int32_t bc_saavn_decode(const uint8_t* input,uint32_t length,uint8_t* output,uint32_t capacity) {
 if(!input || !output || !length || length%8 || length>4096 || capacity<length) return -1;
 auto* des=av_des_alloc(); if(!des) return -1;
 const uint8_t key[8]={'3','8','3','4','6','5','9','1'};
 if(av_des_init(des,key,64,1)<0) { av_free(des); return -1; }
 av_des_crypt(des,output,input,int(length/8),nullptr,1); av_free(des);
 const uint8_t pad=output[length-1];
 if(!pad || pad>8) { std::memset(output,0,length); return -1; }
 for(uint32_t i=length-pad;i<length;i++) if(output[i]!=pad) { std::memset(output,0,length); return -1; }
 return int32_t(length-pad);
}
