import 'dart:async';
import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/domain/audio_contract.dart';
import '../domain/catalog.dart';
import '../domain/online_port.dart';

final onlinePortProvider = Provider<OnlinePort?>((ref) => null);
final artworkProvider = FutureProvider.autoDispose.family<Uint8List?, Uri>((
  ref,
  url,
) async {
  final port = ref.read(onlinePortProvider);
  if (port is! OnlineArtwork) return null;
  final scope = RequestScope();
  ref.onDispose(scope.cancel);
  return (port as OnlineArtwork).artwork(url, scope);
});

final catalogProvider = NotifierProvider<CatalogController, CatalogView>(
  CatalogController.new,
);
final transportProvider =
    NotifierProvider<TransportController, PlaybackSnapshot?>(
      TransportController.new,
    );

final class CatalogView {
  const CatalogView({
    this.entries = const [],
    this.shelves = const [],
    this.header,
    this.filter = SearchFilter.all,
    this.loading = false,
    this.error,
    this.continuation,
    this.provider = 'youtube',
    this.query = '',
    this.browseId,
    this.params,
    this.downloadProgress,
  });
  final List<CatalogEntry> entries;
  final List<CatalogShelf> shelves;
  final CatalogHeader? header;
  final SearchFilter filter;
  final bool loading;
  final String? error, continuation, browseId, params, downloadProgress;
  final String provider, query;
}

final class CatalogController extends Notifier<CatalogView> {
  RequestScope? _queryScope, _playScope, _downloadScope, _connectScope;
  int _generation = 0;
  bool _disposed = false;
  OnlinePort? get port => ref.read(onlinePortProvider);
  @override
  CatalogView build() {
    ref.onDispose(() {
      _disposed = true;
      cancel();
      _playScope?.cancel();
    });
    return const CatalogView();
  }

  void cancel() {
    _queryScope?.cancel();
    _downloadScope?.cancel();
    _playScope?.cancel();
    _connectScope?.cancel();
  }

  String _message(Object e) =>
      e is ProviderFailure ? e.toString() : 'Provider operation failed.';
  Future<void> load({
    String provider = 'youtube',
    String query = '',
    String? browseId,
    String? params,
    bool more = false,
    SearchFilter filter = SearchFilter.all,
  }) async {
    final api = port;
    if (api == null || (more && state.continuation == null)) return;
    _queryScope?.cancel();
    final scope = _queryScope = RequestScope(), generation = ++_generation;
    final before = state;
    state = CatalogView(
      entries: more ? before.entries : const [],
      shelves: more ? before.shelves : const [],
      header: more ? before.header : null,
      filter: filter,
      loading: true,
      provider: provider,
      query: query,
      browseId: browseId,
      params: params,
    );
    try {
      final page = browseId == null
          ? await api.search(
              provider,
              query,
              scope,
              filter: filter,
              continuation: more ? before.continuation : null,
            )
          : await api.browse(
              browseId,
              scope,
              continuation: more ? before.continuation : null,
              params: params,
            );
      if (_disposed || generation != _generation || scope.cancelled) return;
      final combined = [if (more) ...before.entries, ...page.entries];
      final limited =
          combined.length > 800 ||
          (combined.length == 800 && page.continuation != null);
      state = CatalogView(
        entries: List.unmodifiable(combined.take(800)),
        shelves: _boundedShelves([
          if (more) ...before.shelves,
          ...page.shelves,
        ]),
        header: page.header ?? (more ? before.header : null),
        filter: filter,
        error: limited
            ? 'Showing the first 800 items. Further paging is not yet supported.'
            : null,
        continuation:
            limited || (page.continuation == before.continuation && more)
            ? null
            : page.continuation,
        provider: provider,
        query: query,
        browseId: browseId,
        params: params,
      );
    } catch (e) {
      if (!_disposed && generation == _generation && !scope.cancelled)
        state = CatalogView(
          entries: more ? before.entries : const [],
          shelves: more ? before.shelves : const [],
          header: more ? before.header : null,
          filter: filter,
          error: _message(e),
          provider: provider,
          query: query,
          browseId: browseId,
          params: params,
        );
    }
  }

  List<CatalogShelf> _boundedShelves(List<CatalogShelf> shelves) {
    var remaining = 800;
    final result = <CatalogShelf>[];
    for (final shelf in shelves.take(80)) {
      if (remaining <= 0) break;
      final entries = shelf.entries.take(remaining).toList();
      remaining -= entries.length;
      result.add(
        CatalogShelf(
          title: shelf.title,
          subtitle: shelf.subtitle,
          entries: entries,
          layout: shelf.layout,
          moreId: shelf.moreId,
          params: shelf.params,
        ),
      );
    }
    return List.unmodifiable(result);
  }

  Future<void> more() => load(
    provider: state.provider,
    query: state.query,
    browseId: state.browseId,
    params: state.params,
    more: true,
    filter: state.filter,
  );
  Future<String?> connect(String url) async {
    final api = port;
    if (api == null) return 'Music services are unavailable.';
    _connectScope?.cancel();
    final scope = _connectScope = RequestScope();
    try {
      await api.connectAddon(url, scope);
      return null;
    } catch (e) {
      return _message(e);
    } finally {
      scope.cancel();
    }
  }

  Future<String?> play(CatalogEntry entry, {bool offline = false}) async {
    final api = port;
    if (api == null) return 'Music services are unavailable.';
    _playScope?.cancel();
    final scope = _playScope = RequestScope();
    try {
      if (offline) {
        await api.playOffline(entry, scope);
      } else {
        await api.play(entry, AudioQuality.lossless, scope);
      }
      return null;
    } catch (e) {
      return _message(e);
    }
  }

  Future<String?> download(
    CatalogEntry entry,
    void Function(int, int) progress,
  ) async {
    final api = port;
    if (api == null) return 'Music services are unavailable.';
    if (_downloadScope != null) return 'A download is already active.';
    final scope = _downloadScope = RequestScope();
    try {
      await api.download(entry, AudioQuality.lossless, scope, progress);
      return null;
    } catch (e) {
      return _message(e);
    } finally {
      scope.cancel();
      _downloadScope = null;
    }
  }

  void cancelDownload() => _downloadScope?.cancel();
}

final class TransportController extends Notifier<PlaybackSnapshot?> {
  bool _busy = false, _disposed = false;
  @override
  PlaybackSnapshot? build() {
    final port = ref.read(onlinePortProvider);
    if (port != null) {
      final timer = Timer.periodic(const Duration(milliseconds: 500), (
        _,
      ) async {
        if (_busy) return;
        _busy = true;
        try {
          final next = await port.snapshot();
          if (!_disposed) state = next;
        } catch (_) {
        } finally {
          _busy = false;
        }
      });
      ref.onDispose(() {
        _disposed = true;
        timer.cancel();
      });
    }
    return null;
  }

  Future<void> toggle() async {
    final port = ref.read(onlinePortProvider);
    if (port == null) return;
    if (state?.phase == AudioPhase.playing) {
      await port.pause();
    } else {
      await port.resume();
    }
  }
}
