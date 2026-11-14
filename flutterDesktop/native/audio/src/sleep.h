// DesktopSleepTimer.kt at pinned 348b613a: elapsed-realtime deadline,
// mutually exclusive after-track mode, cancellation, and remaining time.
#pragma once
#include <cstdint>
namespace bc {
class SleepTimer {
 public:
  // 0 cancels; -1 means after this track; positive values are milliseconds.
  void set(int64_t milliseconds, int64_t now) {
    after_track_ = milliseconds == -1;
    deadline_ = milliseconds > 0 ? now + milliseconds * 1000000 : 0;
  }
  bool after_track() const { return after_track_; }
  bool expired(int64_t now) const { return deadline_ && now >= deadline_; }
  int64_t remaining_ms(int64_t now) const {
    return deadline_ ? (now < deadline_ ? (deadline_ - now) / 1000000 : 0) : -1;
  }
 private:
  int64_t deadline_ = 0;
  bool after_track_ = false;
};
} // namespace bc
