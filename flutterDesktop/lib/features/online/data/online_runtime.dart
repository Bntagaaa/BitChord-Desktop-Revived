import 'dart:async';
import 'dart:io';
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
import '../../../core/platform/audio/native_api.dart';

final class OnlineRuntime implements OnlinePort {
  OnlineRuntime(this.playback, Directory downloads)
    : store = DownloadStore(downloads) {
    _providers['youtube'] = YouTubeMusic(const ProviderHttp());
    _providers['jiosaavn'] = JioSaavn(
      const ProviderHttp(),
      NativeApi.bundledLibrary,
    );
  }
  final LocalPlayback playback;
  final DownloadStore store;
  final _providers = <String, CatalogProvider>{};
  SourceBroker? _broker;
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
  }) => _provider(provider).search(query, scope, continuation: continuation);
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
    final media = await provider.resolve(entry, quality, scope);
    scope.check();
    final broker = await SourceBroker.open(
      media,
      refresh: () => provider.resolve(entry, quality, scope),
    );
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
            sourceId: entry.provider,
            title: entry.title,
            artist: entry.subtitle,
          ),
          broker.path,
        );
        _broker = broker;
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
    final source = RangeSource(
      await provider.resolve(entry, quality, scope),
      const ProviderHttp(),
      scope,
      refresh: () => provider.resolve(entry, quality, scope),
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
  Future<void> playOffline(CatalogEntry entry) async {
    final rows = await store.list();
    final download = rows.where((d) => d.entry.key == entry.key).firstOrNull;
    if (download == null)
      throw const ProviderFailure(ProviderProblem.integrity);
    await _ordered(() async {
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
    try {
      await playback.close();
    } finally {
      await _broker?.close();
    }
  }
}
