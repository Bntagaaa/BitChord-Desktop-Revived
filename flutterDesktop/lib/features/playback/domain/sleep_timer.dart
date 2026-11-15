// DesktopSleepTimer.kt at pinned 348b613a. The native owner enforces the
// deadline and after-track rule; this port only requests/displays session state.
final class SleepRequest {
  const SleepRequest.cancel() : minutes = null, afterTrack = false;
  const SleepRequest.afterTrack() : minutes = null, afterTrack = true;
  SleepRequest.minutes(int value) : minutes = value, afterTrack = false {
    if (value < 5 || value > 720) throw ArgumentError('Sleep minutes');
  }
  final int? minutes;
  final bool afterTrack;
  int get nativeValue => afterTrack ? -1 : (minutes ?? 0) * 60000;
}

final class SleepStatus {
  const SleepStatus({this.remainingMs, this.afterTrack = false});
  final int? remainingMs;
  final bool afterTrack;
  bool get active => afterTrack || remainingMs != null;
}

abstract interface class SleepPort {
  bool get supportsSleep;
  Future<void> configureSleep(SleepRequest request);
  Future<SleepStatus> sleepStatus();
}

abstract interface class TempoPort {
  bool get supportsTempo;
}
