import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import '../../../core/domain/audio_contract.dart';
import '../../playback/application/local_playback.dart';
import '../../playback/domain/local_queue.dart';
import '../domain/catalog.dart';
import '../domain/online_port.dart';
import 'addon_provider.dart';
import 'download_store.dart';
import 'provider_http.dart';
import 'range_source.dart';
import 'source_broker.dart';
import 'youtube_music.dart';
import 'jiosaavn.dart';
import 'youtube_resolver.dart';
import 'artwork_loader.dart';
import '../../../core/platform/audio/native_api.dart';

final class OnlineRuntime
    implements
        OnlinePort,
        OnlineAccounts,
        OnlineArtwork,
        OnlineCapabilities,
        OnlineSuggestions,
        OnlineLibrary,
        OnlinePlaylistEditor,
        OnlinePlaybackIdentity {
  OnlineRuntime(this.playback, Directory downloads)
    : store = DownloadStore(downloads) {
    _providers['youtube'] = YouTubeMusic(
      const ProviderHttp(),
      resolver:
          YouTubeResolver.fromEnvironment() ?? YouTubeResolver.directOnly(),
    );
    _providers['jiosaavn'] = JioSaavn(
      const ProviderHttp(),
      NativeApi.bundledLibrary,
    );
  }
  final LocalPlayback playback;
  final DownloadStore store;
  final _artwork = ArtworkLoader();
  YouTubeMusic get _youtube => _providers['youtube']! as YouTubeMusic;
  @override
  bool get youtubeAudioConfigured => _youtube.resolver != null;
  @override
  bool get signedIn => _youtube.signedIn;
  @override
  Future<void> signIn(String cookie, RequestScope scope) =>
      _youtube.signIn(cookie, scope);
  @override
  Future<void> signOut() async {
    _youtube.signOut();
  }

  @override
  Future<List<String>> suggestions(String query, RequestScope scope) =>
      _youtube.suggestions(query, scope);

  @override
  Future<CatalogPage> library(RequestScope scope) => _youtube.library(scope);

  @override
  Future<String> createPlaylist(
    String title,
    PlaylistVisibility visibility,
    RequestScope scope,
  ) => _youtube.createPlaylist(title, visibility, scope);

  @override
  Future<void> renamePlaylist(
    String playlistId,
    String title,
    RequestScope scope,
  ) => _youtube.renamePlaylist(playlistId, title, scope);

  @override
  Future<void> deletePlaylist(String playlistId, RequestScope scope) =>
      _youtube.deletePlaylist(playlistId, scope);

  @override
  Future<Map<String, String>> addToPlaylist(
    String playlistId,
    List<String> videoIds,
    RequestScope scope,
  ) => _youtube.addToPlaylist(playlistId, videoIds, scope);

  @override
  Future<void> removeFromPlaylist(
    String playlistId,
    List<({String setVideoId, String videoId})> entries,
    RequestScope scope,
  ) => _youtube.removeFromPlaylist(playlistId, entries, scope);

  @override
  Future<Uint8List> artwork(Uri url, RequestScope scope) =>
      _artwork.load(url, scope);
  final _providers = <String, CatalogProvider>{};
  SourceBroker? _broker;
  Map<String, Object> _lastYoutubeStartup = const {};
  Map<String, Object> get lastYoutubeStartup =>
      Map.unmodifiable(_lastYoutubeStartup);
  Future<void> _tail = Future.value();
  bool _closed = false;
  int _entry = 0;
  // Playback/persistence has a single coordinator across polls, commands, close.
  Future<T> _ordered<T>(Future<T> Function() work) {
    final result = _tail.then((_) {
      if (_closed) throw const ProviderFailure(ProviderProblem.cancelled);
      return work();
    });
    _tail = result.then<void>((_) {}, onError: (Object _, StackTrace __) {});
    return result;
  }

  @override
  String get currentTitle => playback.state.queue.current?.title ?? '';
  @override
  String get currentEntryId => playback.state.queue.current?.entryId ?? '';
  @override
  String get currentSubtitle => playback.state.queue.current?.artist ?? '';

  @override
  List<String> get providers => List.unmodifiable(_providers.keys);
  CatalogProvider _provider(String id) =>
      _providers[id] ??
      (throw const ProviderFailure(ProviderProblem.unsupported));
  @override
  Future<void> connectAddon(String url, RequestScope scope) async {
    final candidate = AddonProvider(
      'addon',
      Uri.parse(url),
      const ProviderHttp(),
    );
    await candidate.manifest(scope);
    scope.check();
    _providers['addon'] = candidate;
  }

  @override
  Future<CatalogPage> search(
    String provider,
    String query,
    RequestScope scope, {
    String? continuation,
    SearchFilter filter = SearchFilter.all,
  }) => _provider(
    provider,
  ).search(query, scope, continuation: continuation, filter: filter);
  @override
  Future<CatalogPage> browse(
    String id,
    RequestScope scope, {
    String? continuation,
    String? params,
  }) => _provider(
    'youtube',
  ).browse(id, scope, continuation: continuation, params: params);
  @override
  Future<void> play(
    CatalogEntry entry,
    AudioQuality quality,
    RequestScope scope,
  ) async {
    final provider = _provider(entry.provider);
    // A selected YouTube source must remain YouTube. Do not hide a failed
    // integration by playing a similarly named recording from another provider.
    final generation = _youtube.accountGeneration;
    final startup = Stopwatch()..start();
    final media = await provider.resolve(entry, quality, scope);
    final resolveMs = startup.elapsedMilliseconds;
    scope.check();
    final broker = await SourceBroker.open(
      media,
      refresh: () => entry.provider == 'youtube'
          ? _youtube.renew(entry, quality, media, scope, generation: generation)
          : provider.resolve(entry, quality, scope),
    );
    final brokerMs = startup.elapsedMilliseconds - resolveMs;
    try {
      scope.check();
      await _ordered(() async {
        scope.check();
        final old = _broker;
        await playback.playSource(
          QueueEntry(
            entryId:
                'online:${DateTime.now().microsecondsSinceEpoch}:${++_entry}',
            catalogId: entry.id,
            renditionId: media.rendition,
            sourceId: media.provider,
            title: entry.title,
            artist: entry.subtitle,
          ),
          broker.path,
        );
        _broker = broker;
        if (entry.provider == 'youtube') {
          _lastYoutubeStartup = {
            ...?_youtube.resolver?.lastReport,
            'resolve_ms': resolveMs,
            'broker_ready_ms': brokerMs,
            'native_command_ms':
                startup.elapsedMilliseconds - resolveMs - brokerMs,
            'first_audible_audio': 'NOT_MEASURED',
          };
        }
        await old?.close();
      });
    } catch (_) {
      await broker.close();
      rethrow;
    }
  }

  @override
  Future<void> pause() => _ordered(playback.pause);
  @override
  Future<void> resume() => _ordered(playback.resume);
  @override
  Future<void> seek(int positionUs) =>
      _ordered(() => playback.seek(positionUs));
  @override
  Future<PlaybackSnapshot> snapshot() => _ordered(playback.refresh);
  @override
  Future<void> download(
    CatalogEntry entry,
    AudioQuality quality,
    RequestScope scope,
    void Function(int, int) progress,
  ) async {
    final provider = _provider(entry.provider);
    final generation = _youtube.accountGeneration;
    final media = await provider.resolve(entry, quality, scope);
    final source = RangeSource(
      media,
      const ProviderHttp(),
      scope,
      refresh: () => entry.provider == 'youtube'
          ? _youtube.renew(entry, quality, media, scope, generation: generation)
          : provider.resolve(entry, quality, scope),
    );
    try {
      await store.save(entry, source, progress: progress);
    } finally {
      source.close();
    }
  }

  @override
  Future<List<DownloadedEntry>> downloads() async => (await store.list())
      .map((d) => DownloadedEntry(d.entry, d.bytes))
      .toList();
  @override
  Future<void> playOffline(CatalogEntry entry, RequestScope scope) async {
    final rows = await store.list();
    scope.check();
    final download = rows.where((d) => d.entry.key == entry.key).firstOrNull;
    if (download == null)
      throw const ProviderFailure(ProviderProblem.integrity);
    await _ordered(() async {
      scope.check();
      await playback.playLocal(
        QueueEntry(
          entryId:
              'offline:${DateTime.now().microsecondsSinceEpoch}:${++_entry}',
          catalogId: entry.id,
          renditionId: 'offline:${entry.provider}',
          sourceId: entry.provider,
          title: entry.title,
          artist: entry.subtitle,
          localPath: download.path,
        ),
      );
      await _broker?.close();
      _broker = null;
    });
  }

  @override
  Future<void> removeDownload(CatalogEntry entry) => store.remove(entry);
  Future<void> close() async {
    await _tail;
    _closed = true;
    _youtube.signOut();
    try {
      await playback.close();
    } finally {
      await _broker?.close();
    }
  }
}
