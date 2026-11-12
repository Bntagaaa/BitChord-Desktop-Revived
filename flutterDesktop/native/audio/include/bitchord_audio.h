#ifndef BITCHORD_AUDIO_H
#define BITCHORD_AUDIO_H
#include <stdint.h>
#ifdef _WIN32
#ifdef BC_BUILD
#define BC_API __declspec(dllexport)
#else
#define BC_API __declspec(dllimport)
#endif
#else
#define BC_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
/* ABI 1: little-endian x64, caller structs, copied UTF-8, integer us/ns.
 * Every accepted command has one terminal journal record. Poll does not ack.
 * No PCM or borrowed internal pointers cross this boundary. */
#define BC_ABI 1
#define BC_OK 0
#define BC_BUSY 1
#define BC_INVALID 2
#define BC_HANDLE 3
#define BC_CLOSED 4
#define BC_RETRY 5
#define BC_STALE 6
#define BC_FAILED 7
#define BC_SELECT 1
#define BC_PLAY 2
#define BC_PAUSE 3
#define BC_SEEK 4
#define BC_RATE 5
#define BC_VOLUME 6
#define BC_STOP 7
#define BC_NOMINATE 8
#define BC_CLEAR_NEXT 9
#define BC_DEVICE 10
#define BC_IDLE 0
#define BC_BUFFERING 1
#define BC_PLAYING 2
#define BC_PAUSED 3
#define BC_ENDED 4
#define BC_ERROR 5
#define BC_CLOSING 6
#define BC_APPLIED 0
#define BC_CANCELLED 1
#define BC_COMMAND_FAILED 2
#define BC_EVENT_COMMAND 0
#define BC_EVENT_ADOPTED 1
#define BC_ERROR_DECODE 100
#define BC_ERROR_DEVICE 101
#define BC_ERROR_SEEK 102
#define BC_ERROR_RESOURCE 103
#define BC_ERROR_FORMAT 104

typedef struct bc_caps {
 uint32_t struct_size, abi, command_capacity, event_capacity;
 uint32_t max_decoders, max_channels, max_rate, flags;
 uint32_t command_size, snapshot_size, event_size, ffmpeg_version;
} bc_caps;
typedef struct bc_command {
 uint32_t struct_size, abi;
 uint64_t id, selection, revision;
 uint32_t type, reserved;
 int64_t value;
 char path[4096];
 char entry[128];
 char rendition[128];
} bc_command;
typedef struct bc_snapshot {
 uint32_t struct_size, abi;
 uint64_t sequence, watermark, selection, revision;
 int64_t position_us, duration_us, sampled_ns;
 uint64_t discontinuity, submitted_frames, played_frames;
 uint64_t pcm_bytes, pcm_high_water, underruns, device_epoch;
 uint32_t phase, error, rate, channels;
 uint32_t pending_commands, unacked_events, decoders, timing_quality;
 uint32_t quiescent, awaiting_audio, backend, reserved;
 char entry[128];
 char rendition[128];
} bc_snapshot;
typedef struct bc_event {
 uint64_t sequence, command_id, selection, revision;
 uint32_t outcome, error, kind, reserved;
 char entry[128];
} bc_event;
BC_API int32_t bc_get_caps(bc_caps* out);
BC_API int64_t bc_monotonic_ns(void);
BC_API int32_t bc_create(uint64_t* handle);
BC_API int32_t bc_submit(uint64_t handle, const bc_command* command);
BC_API int32_t bc_read(uint64_t handle, bc_snapshot* out);
BC_API int32_t bc_poll(uint64_t handle, bc_event* out, uint32_t capacity, uint32_t* count);
BC_API int32_t bc_ack(uint64_t handle, uint64_t sequence);
BC_API int32_t bc_shutdown(uint64_t handle);
BC_API int32_t bc_destroy(uint64_t handle);
/* Caller buffers from bc_alloc are freed exactly once with bc_free. Max 16 MiB. */
BC_API void* bc_alloc(uint64_t bytes);
BC_API void bc_free(void* memory);
BC_API uint64_t bc_live_allocations(void);
/* Durable single-document CAS transaction (SQLite WAL/FULL). Native allocated
 * read bytes belong to caller; close database before returning. No secrets. */
BC_API int32_t bc_store_read(const char* path, uint8_t** bytes, uint64_t* length, uint64_t* revision);
BC_API int32_t bc_store_write(const char* path, const uint8_t* bytes, uint64_t length, uint64_t expected_revision);
BC_API int32_t bc_sha256(const uint8_t* bytes, uint64_t length, uint8_t* digest);
#ifdef __cplusplus
}
#endif
#endif
