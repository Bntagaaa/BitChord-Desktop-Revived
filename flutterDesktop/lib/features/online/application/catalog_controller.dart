import 'dart:async';
import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/domain/audio_contract.dart';
import '../domain/catalog.dart';
import '../domain/online_port.dart';
import 'catalog_playback.dart';

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
    this.pageError = false,
  });
  final List<CatalogEntry> entries;
  final List<CatalogShelf> shelves;
  final CatalogHeader? header;
  final SearchFilter filter;
  final bool loading;
  final bool pageError;
  final String? error, continuation, browseId, params;
  final String provider, query;
}

final class CatalogController extends Notifier<CatalogView> {
  RequestScope? _queryScope, _connectScope;
  int _generation = 0;
  bool _disposed = false;
  final _pages = <String, CatalogView>{};
  String? _pageKey;
  OnlinePort? get port => ref.read(onlinePortProvider);
  @override
  CatalogView build() {
    ref.onDispose(() {
      _disposed = true;
      cancel();
    });
    return const CatalogView();
  }

  void cancel() {
    ++_generation;
    _pages.clear();
    _queryScope?.cancel();
    if (!_disposed) {
      ref.read(catalogPlaybackProvider.notifier).cancel();
      state = CatalogView(
        provider: state.provider,
        query: state.query,
        filter: state.filter,
        browseId: state.browseId,
        params: state.params,
      );
    }
    _connectScope?.cancel();
  }

  // A small route working set keeps Search and a parent collection intact on
  // Back. Account changes call cancel(), invalidating personalized snapshots.
  bool activate(String key) {
    if (_pageKey == key) return !state.loading;
    if (_pageKey != null && !state.loading) {
      _pages.remove(_pageKey);
      _pages[_pageKey!] = state;
      while (_pages.length > 3) {
        _pages.remove(_pages.keys.first);
      }
    }
    _queryScope?.cancel();
    ++_generation;
    _pageKey = key;
    final saved = _pages.remove(key);
    state = saved ?? const CatalogView();
    return saved != null;
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
    if (api == null || (more && (state.loading || state.continuation == null)))
      return;
    // Guest users have no account library. Keep the real route identity so a
    // subsequent verified sign-in can refresh it, without any fake result.
    if (browseId == 'app:library' &&
        api is OnlineAccounts &&
        !api.signedIn) {
      _queryScope?.cancel();
      ++_generation;
      state = const CatalogView(browseId: 'app:library');
      return;
    }
    _queryScope?.cancel();
    final scope = _queryScope = RequestScope(), generation = ++_generation;
    final before = state;
    state = CatalogView(
      entries: more ? before.entries : const [],
      shelves: more ? before.shelves : const [],
      header: more ? before.header : null,
      continuation: more ? before.continuation : null,
      filter: filter,
      loading: true,
      provider: provider,
      query: query,
      browseId: browseId,
      params: params,
    );
    try {
      final page = browseId == 'app:library'
          ? api is OnlineLibrary
                ? await api.library(scope)
                : (throw const ProviderFailure(ProviderProblem.unsupported))
          : browseId == null
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
          continuation: more ? before.continuation : null,
          pageError: more,
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
  Future<void> retry() => load(
    provider: state.provider,
    query: state.query,
    browseId: state.browseId,
    params: state.params,
    filter: state.filter,
    more: state.pageError,
  );

  void clearSearch({
    String query = '',
    String provider = 'youtube',
    SearchFilter filter = SearchFilter.all,
  }) {
    _queryScope?.cancel();
    ++_generation;
    state = CatalogView(query: query, provider: provider, filter: filter);
  }

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

  Future<String?> play(
    CatalogEntry entry, {
    bool offline = false,
    List<CatalogEntry>? context,
    int? index,
  }) => ref
      .read(catalogPlaybackProvider.notifier)
      .select(entry, offline: offline, context: context, index: index);
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
          if (!_disposed) {
            state = next;
            ref.read(catalogPlaybackProvider.notifier).observe(next);
          }
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
