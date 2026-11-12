// All PCM, device ownership and played-frame time stay in native code.
enum AudioOperation {
  select,
  play,
  pause,
  seek,
  rate,
  volume,
  stop,
  nominate,
  clearNext,
  device,
}

enum AudioPhase { idle, buffering, playing, paused, ended, failed, closing }

final class AudioRequest {
  const AudioRequest({
    required this.commandId,
    required this.selection,
    required this.queueRevision,
    required this.operation,
    this.value = 0,
    this.path = '',
    this.entryId = '',
    this.renditionId = '',
  });
  final int commandId, selection, queueRevision, value;
  final AudioOperation operation;
  final String path, entryId, renditionId;
}

final class PlaybackSnapshot {
  const PlaybackSnapshot({
    required this.phase,
    required this.positionUs,
    required this.durationUs,
    required this.sampledAtNativeNs,
    required this.discontinuity,
    required this.entryId,
    required this.awaitingAudio,
    required this.error,
    required this.submittedFrames,
    required this.playedFrames,
    required this.deviceEpoch,
    required this.pcmBytes,
    required this.pcmHighWater,
    required this.quiescent,
    required this.timingQuality,
    required this.backend,
  });
  final AudioPhase phase;
  final int positionUs, durationUs, sampledAtNativeNs, discontinuity, error;
  final int submittedFrames, playedFrames, deviceEpoch, pcmBytes, pcmHighWater;
  final int timingQuality, backend;
  final String entryId;
  final bool awaitingAudio, quiescent;
}

final class AudioEvent {
  const AudioEvent({
    required this.sequence,
    required this.commandId,
    required this.selection,
    required this.queueRevision,
    required this.entryId,
    required this.outcome,
    required this.error,
    required this.adopted,
  });
  final int sequence, commandId, selection, queueRevision, outcome, error;
  final String entryId;
  final bool adopted;
}

enum Admission {
  accepted,
  busy,
  invalid,
  invalidHandle,
  closed,
  retry,
  stale,
  failed,
}

abstract interface class LocalAudioPort {
  Future<Admission> submit(AudioRequest request);
  Future<PlaybackSnapshot> snapshot();
  Future<List<AudioEvent>> events();
  Future<void> acknowledge(int sequence);
  Future<int> monotonicNs();
  Future<void> close();
}
