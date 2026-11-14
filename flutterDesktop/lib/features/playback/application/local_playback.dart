import '../../../core/domain/audio_contract.dart';
import '../../local_data/domain/local_state.dart';
import '../domain/local_queue.dart';
import '../domain/listening_settings.dart';
import '../domain/sleep_timer.dart';

// Application use cases own queue semantics/persistence; native owns all PCM/time.
final class LocalPlayback {
  LocalPlayback(this.repository, this.audio);
  final LocalRepository repository;
  final LocalAudioPort audio;
  late LocalState state;
  var _command = 0, _selection = 0, _nativeRevision = 0;
  bool _closed = false;
  bool get supportsListening =>
      audio is DspAudioCapability &&
      (audio as DspAudioCapability).supportsEqualizer;
  ListeningSettings get listening =>
      state.settings.listening ?? ListeningSettings();
  bool get supportsTempo =>
      audio is AdvancedAudioCapability &&
      (audio as AdvancedAudioCapability).supportsTempo;
  bool get supportsSleep =>
      audio is AdvancedAudioCapability &&
      (audio as AdvancedAudioCapability).supportsSleep;
  int? _sleepDeadlineNs;
  int _sleepCommand = 0;
  bool _sleepAfterTrack = false;
  Future<void> configureSleep(SleepRequest request) async {
    if (!supportsSleep || _closed) throw StateError('Native sleep unavailable');
    final now = await audio.monotonicNs();
    await _send(AudioOperation.sleep, value: request.nativeValue);
    _sleepCommand = _command;
    _sleepAfterTrack = request.afterTrack;
    _sleepDeadlineNs = request.minutes == null
        ? null
        : now + request.nativeValue * 1000000;
  }

  Future<SleepStatus> sleepStatus() async => SleepStatus(
    afterTrack: _sleepAfterTrack,
    remainingMs: _sleepDeadlineNs == null
        ? null
        : ((_sleepDeadlineNs! - await audio.monotonicNs()) ~/ 1000000).clamp(
            0,
            720 * 60000,
          ),
  );
  Future<void> _sendTempo(ListeningSettings settings) => _send(
    AudioOperation.listen,
    value: (settings.speed * 1000000).round(),
    transportPath: settings.skipSilence ? '1' : '0',
  );
  Future<void> initialize() async {
    state = await repository.read();
  }

  Future<void> _send(
    AudioOperation operation, {
    int value = 0,
    QueueEntry? entry,
    String? transportPath,
  }) async {
    final admission = await audio.submit(
      AudioRequest(
        commandId: ++_command,
        selection: _selection,
        queueRevision: _nativeRevision,
        operation: operation,
        value: value,
        path: transportPath ?? entry?.localPath ?? '',
        entryId: entry?.entryId ?? '',
        renditionId: entry?.renditionId ?? '',
      ),
    );
    if (admission != Admission.accepted)
      throw StateError('Audio command ${admission.name}');
  }

  Future<void> playLocal(QueueEntry entry) async {
    if (_closed || entry.localPath == null)
      throw StateError('Local file required');
    await playSource(entry, entry.localPath!);
  }

  // Transient broker grants are never persisted in queue/metadata.
  Future<void> playSource(QueueEntry entry, String transportPath) async {
    if (_closed) throw StateError('Playback closed');
    final updated = LocalState(
      queue: LocalQueue([entry]),
      revision: state.revision + 1,
      settings: state.settings,
      importCheckpoint: state.importCheckpoint,
      imported: state.imported,
    );
    await repository.write(updated, expectedRevision: state.revision);
    state = updated;
    ++_selection;
    _nativeRevision = state.revision;
    await _send(
      AudioOperation.select,
      value: supportsListening ? 0 : 1,
      entry: entry,
      transportPath: transportPath,
    );
    await _send(
      AudioOperation.volume,
      value: (state.settings.volume * 1000000).round(),
    );
    if (supportsListening)
      await _send(AudioOperation.dsp, transportPath: listening.nativePayload);
    if (supportsTempo) await _sendTempo(listening);
    if (supportsListening) await _send(AudioOperation.play);
  }

  Future<void> pause() => _send(AudioOperation.pause);
  Future<void> configureListening(ListeningSettings settings) async {
    if (_closed) throw StateError('Playback closed');
    if (!supportsListening) throw StateError('Native DSP unavailable');
    if (!supportsTempo && (settings.speed != 1 || settings.skipSilence))
      throw StateError('Native tempo unavailable');
    // Persist intent before submission, using the existing CAS document. The
    // native revision remains the active selection's revision until next play.
    final updated = LocalState(
      queue: state.queue,
      revision: state.revision + 1,
      positionUs: state.positionUs,
      settings: state.settings.withListening(settings),
      importCheckpoint: state.importCheckpoint,
      imported: state.imported,
    );
    await repository.write(updated, expectedRevision: state.revision);
    state = updated;
    if (_selection != 0) {
      await _send(AudioOperation.dsp, transportPath: settings.nativePayload);
      if (supportsTempo) await _sendTempo(settings);
    }
  }

  Future<void> resume() => _send(AudioOperation.play);
  Future<void> seek(int positionUs) =>
      _send(AudioOperation.seek, value: positionUs);
  Future<PlaybackSnapshot> refresh() async {
    final events = await audio.events();
    for (final event in events) {
      if (event.sleepExpired && event.commandId == _sleepCommand) {
        _sleepAfterTrack = false;
        _sleepDeadlineNs = null;
      }
      if (event.adopted &&
          event.selection == _selection &&
          event.queueRevision == _nativeRevision) {
        final queue = state.queue.afterHandoff(event.entryId);
        final updated = LocalState(
          queue: queue,
          revision: state.revision + 1,
          settings: state.settings,
          importCheckpoint: state.importCheckpoint,
          imported: state.imported,
        );
        await repository.write(updated, expectedRevision: state.revision);
        state = updated;
      }
      // Acknowledgment follows durable application of any semantic effect.
      await audio.acknowledge(event.sequence);
    }
    return audio.snapshot();
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    try {
      if (_selection != 0) {
        final snapshot = await refresh();
        final updated = LocalState(
          queue: state.queue,
          positionUs: snapshot.positionUs,
          revision: state.revision + 1,
          settings: state.settings,
          importCheckpoint: state.importCheckpoint,
          imported: state.imported,
        );
        await repository.write(updated, expectedRevision: state.revision);
        state = updated;
      }
    } finally {
      await audio.close();
    }
  }
}
