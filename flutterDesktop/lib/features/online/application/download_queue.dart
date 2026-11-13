import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../domain/catalog.dart';
import 'catalog_controller.dart';

enum DownloadPhase { queued, running, failed }

final class DownloadTask {
  const DownloadTask(
    this.entry,
    this.quality,
    this.sequence, {
    this.phase = DownloadPhase.queued,
    this.done = 0,
    this.total = 0,
    this.error,
  });
  final CatalogEntry entry;
  final AudioQuality quality;
  final int sequence, done, total;
  final DownloadPhase phase;
  final String? error;
  DownloadTask update(
    DownloadPhase phase, {
    int done = 0,
    int total = 0,
    String? error,
  }) => DownloadTask(
    entry,
    quality,
    sequence,
    phase: phase,
    done: done,
    total: total,
    error: error,
  );
}

final downloadQueueProvider =
    NotifierProvider<DownloadQueue, List<DownloadTask>>(DownloadQueue.new);
final downloadRevisionProvider = NotifierProvider<DownloadRevision, int>(
  DownloadRevision.new,
);

final class DownloadRevision extends Notifier<int> {
  @override
  int build() => 0;
  void saved() => state++;
}

// DesktopDownloadQueue: four workers, insertion order, one row per track,
// transient active jobs; verified media/journals remain owned by DownloadStore.
final class DownloadQueue extends Notifier<List<DownloadTask>> {
  final _running = <String, RequestScope>{};
  int _sequence = 0;
  bool _disposed = false;
  @override
  List<DownloadTask> build() {
    ref.onDispose(() {
      _disposed = true;
      for (final scope in _running.values) {
        scope.cancel();
      }
    });
    return const [];
  }

  void enqueue(
    CatalogEntry entry, {
    AudioQuality quality = AudioQuality.lossless,
  }) {
    if (_disposed ||
        entry.kind != CatalogKind.track ||
        state.any((task) => task.entry.key == entry.key))
      return;
    state = List.unmodifiable([
      ...state,
      DownloadTask(entry, quality, ++_sequence),
    ]);
    _pump();
  }

  void cancel(String key) {
    // Keep the worker's slot until cancellation has actually quiesced. A retry
    // cannot race its old writer and overwrite its journal.
    state = List.unmodifiable(state.where((task) => task.entry.key != key));
    _running[key]?.cancel();
    _pump();
  }

  void cancelAll() {
    state = const [];
    for (final scope in _running.values) {
      scope.cancel();
    }
  }

  void clearFailed() => state = List.unmodifiable(
    state.where((task) => task.phase != DownloadPhase.failed),
  );

  void retry(String key) {
    final task = state.where((task) => task.entry.key == key).firstOrNull;
    if (task == null || task.phase != DownloadPhase.failed) return;
    state = List.unmodifiable(state.where((task) => task.entry.key != key));
    enqueue(task.entry, quality: task.quality);
  }

  bool _current(DownloadTask task) =>
      !_disposed &&
      state.any(
        (row) =>
            row.entry.key == task.entry.key && row.sequence == task.sequence,
      );

  void _replace(DownloadTask task) {
    if (!_current(task)) return;
    state = List.unmodifiable([
      for (final row in state)
        if (row.sequence == task.sequence) task else row,
    ]);
  }

  void _pump() {
    if (_disposed) return;
    for (final task in state.toList()) {
      if (_running.length >= 4) break;
      if (task.phase != DownloadPhase.queued ||
          _running.containsKey(task.entry.key))
        continue;
      final scope = RequestScope();
      _running[task.entry.key] = scope;
      _replace(task.update(DownloadPhase.running));
      unawaited(_run(task, scope));
    }
  }

  Future<void> _run(DownloadTask task, RequestScope scope) async {
    try {
      final api = ref.read(onlinePortProvider);
      if (api == null) throw const ProviderFailure(ProviderProblem.unavailable);
      await api.download(task.entry, task.quality, scope, (done, total) {
        if (!scope.cancelled)
          _replace(
            task.update(DownloadPhase.running, done: done, total: total),
          );
      });
      scope.check();
      if (_current(task)) {
        state = List.unmodifiable(
          state.where((row) => row.sequence != task.sequence),
        );
        ref.read(downloadRevisionProvider.notifier).saved();
      }
    } catch (error) {
      if (!scope.cancelled)
        _replace(
          task.update(
            DownloadPhase.failed,
            error: error is ProviderFailure
                ? error.toString()
                : 'Download failed.',
          ),
        );
    } finally {
      scope.cancel();
      _running.remove(task.entry.key);
      _pump();
    }
  }
}
