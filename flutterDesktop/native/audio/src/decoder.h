#pragma once
#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>
namespace bc {
extern std::atomic<uint32_t> live_decoders;
// One producer/one consumer. Ready is published only after storage/format setup.
class Decoder {
public:
 std::atomic<bool> cancel{false}, ready{false}, eof{false};
 std::atomic<int> error{0};
 std::atomic<uint64_t> write_at{0}, read_at{0}, high_water{0};
 uint32_t rate=0, channels=0;
 uint64_t channel_mask=0;
 int64_t duration=0, origin=0;
 std::vector<float> pcm;
 explicit Decoder(std::string path, int64_t seek=0);
 ~Decoder();
 size_t available() const { return size_t(write_at.load(std::memory_order_acquire)-read_at.load()); }
 float sample(uint64_t frame, uint32_t channel) const {
  return pcm[((read_at.load()+frame)*channels+channel)%pcm.size()];
 }
 void consume(uint64_t frames) { read_at.fetch_add(frames, std::memory_order_release); }
 uint64_t bytes() const { return ready ? pcm.size()*sizeof(float) : 0; }
private:
 std::thread worker;
 void run(const std::string&, int64_t);
};
}
