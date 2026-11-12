import '../../../core/domain/audio_contract.dart';
import '../../local_data/domain/local_state.dart';
import '../domain/local_queue.dart';

// Application use cases own queue semantics/persistence; native owns all PCM/time.
final class LocalPlayback {
  LocalPlayback(this.repository, this.audio);
  final LocalRepository repository;
  final LocalAudioPort audio;
  late LocalState state;
  var _command = 0, _selection = 0, _nativeRevision = 0;
  bool _closed = false;
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
      value: 1,
      entry: entry,
      transportPath: transportPath,
    );
    await _send(
      AudioOperation.volume,
      value: (state.settings.volume * 1000000).round(),
    );
    // Native rate is resampling rate. Pitch-preserving Kotlin speed is Phase 5.
  }

  Future<void> pause() => _send(AudioOperation.pause);
  Future<void> resume() => _send(AudioOperation.play);
  Future<void> seek(int positionUs) =>
      _send(AudioOperation.seek, value: positionUs);
  Future<PlaybackSnapshot> refresh() async {
    final events = await audio.events();
    for (final event in events) {
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
