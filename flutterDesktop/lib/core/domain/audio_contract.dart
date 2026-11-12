// Future native boundary contracts. There is deliberately no backend implementation.
enum AudioPhase {
  idle,
  resolving,
  buffering,
  playing,
  paused,
  ended,
  failed,
  closing,
}

enum CommandOutcome { applied, superseded, cancelled, failed }

final class AudioIdentity {
  const AudioIdentity({
    required this.sessionEpoch,
    required this.selectionGeneration,
    required this.queueRevision,
    required this.entryId,
    required this.renditionId,
  });
  final int sessionEpoch;
  final int selectionGeneration;
  final int queueRevision;
  final String entryId;
  final String renditionId;
}

final class PlaybackSnapshot {
  const PlaybackSnapshot({
    required this.sequence,
    required this.eventWatermark,
    required this.identity,
    required this.phase,
    required this.positionUs,
    required this.sampledAtNativeNs,
    required this.clockEpoch,
    required this.discontinuity,
    required this.awaitingAudio,
  });
  final int sequence;
  final int eventWatermark;
  final AudioIdentity? identity;
  final AudioPhase phase;
  final int positionUs;
  final int sampledAtNativeNs;
  final int clockEpoch;
  final int discontinuity;
  final bool awaitingAudio;
}

// Only the future service isolate may implement/use this port. Acknowledgment is
// separate from snapshot reading; submission is not audible completion.
abstract interface class NativeAudioBridge {
  Future<Admission> submit(AudioCommand command);
  Future<PlaybackSnapshot> readSnapshot();
  Future<List<AudioEvent>> drainEvents({required int maxBytes});
  Future<void> acknowledge(int contiguousSequence);
  Future<ShutdownStatus> beginShutdown();
  Future<void> destroyAfterQuiescence();
}

enum ShutdownStatus { pending, quiescent }

final class AudioEvent {
  const AudioEvent({
    required this.sequence,
    required this.commandId,
    required this.identity,
    required this.outcome,
  });
  final int sequence;
  final int commandId;
  final AudioIdentity identity;
  final CommandOutcome outcome;
}

enum Admission { accepted, busy, stale, closed, invalid }

sealed class AudioCommand {
  const AudioCommand(this.commandId, this.identity);
  final int commandId;
  final AudioIdentity identity;
}

final class SeekAudio extends AudioCommand {
  const SeekAudio(super.commandId, super.identity, this.positionUs);
  final int positionUs;
}

final class SetPlayIntent extends AudioCommand {
  const SetPlayIntent(super.commandId, super.identity, {required this.play});
  final bool play;
}
