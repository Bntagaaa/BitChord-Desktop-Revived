import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/domain/audio_contract.dart';
import '../domain/catalog.dart';
import '../domain/online_port.dart';
import 'catalog_controller.dart';

final catalogPlaybackProvider =
    NotifierProvider<CatalogPlayback, CatalogPlaybackView>(CatalogPlayback.new);

final class CatalogPlaybackView {
  const CatalogPlaybackView({
    this.entries = const [],
    this.index = -1,
    this.offline = false,
    this.loading = false,
    this.error,
  });
  final List<CatalogEntry> entries;
  final int index;
  final bool offline, loading;
  final String? error;
  bool get hasNext => index >= 0 && index + 1 < entries.length;
  bool get hasPrevious => index > 0;
}

// Phase 4 sequential catalog context only. Local/native queue rules, DSP,
// prepared handoffs, repeat and Automix remain with their existing owners.
final class CatalogPlayback extends Notifier<CatalogPlaybackView> {
  RequestScope? _scope;
  bool _disposed = false, _awaitingEntry = false;
  String? _nativeEntry, _activeEntry, _expectedEntry;
  @override
  CatalogPlaybackView build() {
    ref.onDispose(() {
      _disposed = true;
      _scope?.cancel();
    });
    return const CatalogPlaybackView();
  }

  void cancel() {
    _scope?.cancel();
    _awaitingEntry = false;
    _activeEntry = null;
    _expectedEntry = null;
    state = const CatalogPlaybackView();
  }

  Future<String?> select(
    CatalogEntry entry, {
    bool offline = false,
    List<CatalogEntry>? context,
    int? index,
  }) async {
    final rows = context ?? [entry];
    final at = index ?? 0;
    if (rows.isEmpty ||
        rows.length > 800 ||
        at < 0 ||
        at >= rows.length ||
        rows[at].key != entry.key ||
        rows.any((row) => row.kind != CatalogKind.track)) {
      return 'Invalid playback context.';
    }
    return _play(List.unmodifiable(rows), at, offline);
  }

  Future<String?> _play(
    List<CatalogEntry> rows,
    int index,
    bool offline,
  ) async {
    final api = ref.read(onlinePortProvider);
    if (api == null) return 'Music services are unavailable.';
    _scope?.cancel();
    final scope = _scope = RequestScope();
    _awaitingEntry = true;
    _activeEntry = null;
    _expectedEntry = null;
    state = CatalogPlaybackView(
      entries: rows,
      index: index,
      offline: offline,
      loading: true,
    );
    try {
      if (offline) {
        await api.playOffline(rows[index], scope);
      } else {
        await api.play(rows[index], AudioQuality.lossless, scope);
      }
      scope.check();
      if (!_disposed && identical(_scope, scope)) {
        _expectedEntry = api is OnlinePlaybackIdentity
            ? (api as OnlinePlaybackIdentity).currentEntryId
            : null;
        state = CatalogPlaybackView(
          entries: rows,
          index: index,
          offline: offline,
        );
      }
      return null;
    } catch (error) {
      final message = error is ProviderFailure
          ? error.toString()
          : 'Provider operation failed.';
      if (!_disposed && identical(_scope, scope) && !scope.cancelled) {
        _awaitingEntry = false;
        state = CatalogPlaybackView(
          entries: rows,
          index: index,
          offline: offline,
          error: message,
        );
      }
      return message;
    }
  }

  Future<String?> next() async => state.hasNext
      ? _play(state.entries, state.index + 1, state.offline)
      : null;
  Future<String?> previous() async => state.hasPrevious
      ? _play(state.entries, state.index - 1, state.offline)
      : null;

  void observe(PlaybackSnapshot snapshot) {
    final changed =
        snapshot.entryId.isNotEmpty && snapshot.entryId != _nativeEntry;
    _nativeEntry = snapshot.entryId;
    if (_awaitingEntry &&
        !state.loading &&
        (_expectedEntry != null
            ? snapshot.entryId == _expectedEntry
            : changed)) {
      _activeEntry = snapshot.entryId;
      _awaitingEntry = false;
    }
    if (snapshot.phase == AudioPhase.ended &&
        _activeEntry == snapshot.entryId &&
        !state.loading &&
        state.error == null &&
        state.hasNext) {
      // _play sets loading/awaiting synchronously before another native poll.
      unawaited(next());
    }
    if (snapshot.phase == AudioPhase.error &&
        (_activeEntry == snapshot.entryId || _awaitingEntry)) {
      _awaitingEntry = false;
      state = CatalogPlaybackView(
        entries: state.entries,
        index: state.index,
        offline: state.offline,
        loading: false,
        error: 'Playback encountered an audio playback error.',
      );
    }
  }
}
