// GPL-3.0-or-later. Independent C++ ports of the pinned Kotlin 348b613a
// DesktopEqualizer, EqualizerCurve, DesktopAudioProcessors, DesktopAudioSpeed
// and DesktopTransitionEcho. No JNI or prior Flutter implementation is used.
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace bc::dsp {
constexpr size_t slots = 10;
struct Curve {
  std::array<float, slots> gain{}, q{};
  float preamp = 0;
  Curve() { q.fill(.707f); }
};
Curve manual(const std::array<float, 7>& bands);
Curve tone(int x, int y, bool focused);
struct Settings {
  bool enabled = false, spatial = false;
  float balance = 0;
  Curve curve;
};
// Strict versioned control payload; parse/curve computation happen outside
// the deadline-critical PCM processing path. Reject NaN, extra/missing fields.
bool parseSettings(const char* payload, Settings& out);

class Equalizer {
 public:
  void tune(const Settings& settings);
  void configure(uint32_t channels, uint32_t rate);
  void reset();
  void process(float* samples, size_t frames);
 private:
  Curve target_, current_;
  float balance_ = 0, target_balance_ = 0;
  uint32_t channels_ = 0, rate_ = 0;
  std::array<float, slots> a1_{}, a2_{}, a3_{}, m0_{}, m1_{}, m2_{};
  std::array<float, slots * 2 * 8> state_{};
  std::array<bool, slots> running_{};
};

class Spatial {
 public:
  void configure(uint32_t channels, uint32_t rate);
  void reset();
  void process(float* samples, size_t frames, bool enabled);
 private:
  // 15 ms at the negotiated maximum rate, allocated before processing.
  std::array<float, 2880> left_{}, right_{};
  uint32_t channels_ = 0, rate_ = 0;
  size_t delay_ = 1, cursor_ = 0;
  float low_left_ = 0, low_right_ = 0;
};

class Echo {
 public:
  Echo(uint32_t channels, uint32_t rate);
  void configure(double seconds);
  float process(float sample, float send, float dry);
 private:
  uint32_t channels_, rate_;
  std::vector<float> ring_;
  size_t cursor_ = 0, delay_ = 1;
};

// Bounded preparation processors. Callers supply <= max_block frames, and
// consume each returned view before the next call. No growth during process.
// These are not advertised as engine capabilities until source-time segment
// integration and differential/runtime acceptance are implemented.
struct View { const float* data; size_t frames; };
class SilenceSkipper {
 public:
  SilenceSkipper(uint32_t channels, uint32_t rate, size_t max_block);
  View process(const float* samples, size_t frames, bool enabled);
  void reset();
  uint64_t skipped() const { return skipped_; }
 private:
  uint32_t channels_;
  size_t minimum_, kept_, max_block_, pending_frames_ = 0;
  size_t tail_frames_ = 0, tail_write_ = 0;
  bool skipping_ = false;
  uint64_t skipped_ = 0;
  std::vector<float> pending_, tail_, output_;
};

class Speed {
 public:
  Speed(uint32_t channels, uint32_t rate, size_t max_block);
  View process(const float* samples, size_t frames, float rate);
  View drain();
  void reset();
 private:
  uint32_t channels_;
  int hop_, search_, pending_frames_ = 0, read_ = 0, last_join_ = 0, last_offset_ = 0;
  size_t max_block_;
  bool tail_present_ = false, engaged_ = false;
  std::vector<float> pending_, tail_, output_;
  float score(int start) const;
  int offset(int from) const;
};
} // namespace bc::dsp
