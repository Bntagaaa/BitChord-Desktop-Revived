import 'dart:convert';
import 'dart:math';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

import '../domain/models.dart';
import 'addon_client.dart';
import 'innertube_parser.dart';
import 'song_matcher.dart';

export 'addon_client.dart';

/// URLs may contain private path/query tokens, so the registry uses the OS vault.
/// This is the HTTP addon protocol; it never downloads or executes addon code.
class AddonRegistry {
  AddonRegistry({FlutterSecureStorage? storage, http.Client? httpClient})
    : _storage =
          storage ??
          const FlutterSecureStorage(
            mOptions: MacOsOptions(usesDataProtectionKeychain: false),
          ),
      _http = httpClient;
  final FlutterSecureStorage _storage;
  final http.Client? _http;
  final _sources = <AddonConfig>[];
  final _clients = <String, AddonClient>{};
  final _rows = <String, AddonTrack>{};
  final Map<String, String> lastFailures = {};
  final Map<String, bool> _learnedDownloads = {};
  Future<void> _pendingWrite = Future.value();
  static const _key = 'bitchord_flutter_http_addons_v1';
  List<AddonConfig> get sources => List.unmodifiable(_sources);

  Future<void> restore() async {
    final raw = await _storage.read(key: _key);
    if (raw == null) return;
    final list = jsonDecode(raw);
    if (list is! List) {
      throw const FormatException('Stored addon configuration is invalid.');
    }
    final parsed = list
        .map(InnertubeParser.object)
        .map(AddonConfig.fromJson)
        .toList();
    for (final config in parsed) {
      AddonClient.normalizeBase(config.baseUrl);
    }
    _sources
      ..clear()
      ..addAll(parsed);
  }

  Future<void> _save() {
    final data = jsonEncode(_sources.map((s) => s.toJson()).toList());
    final write = _pendingWrite.then(
      (_) => _storage.write(key: _key, value: data),
    );
    _pendingWrite = write.catchError((Object _) {});
    return write;
  }

  AddonClient _client(AddonConfig source) => _clients.putIfAbsent(
    source.id,
    () => AddonClient(source.baseUrl, httpClient: _http),
  );

  Future<AddonConfig> addSource(String url, {String? label}) async {
    final base = AddonClient.normalizeBase(url).toString();
    if (_sources.any(
      (s) => AddonClient.normalizeBase(s.baseUrl).toString() == base,
    )) {
      throw const ServiceException('This addon is already configured.');
    }
    final client = AddonClient(base, httpClient: _http);
    try {
      final manifest = await client.manifest();
      final random = Random.secure();
      final id = List.generate(
        12,
        (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
      ).join();
      final source = AddonConfig(
        id: id,
        baseUrl: base,
        label: label?.trim().isNotEmpty == true ? label!.trim() : manifest.name,
        allowDownloads: manifest.allowDownloads,
      );
      _sources.add(source);
      try {
        await _save();
      } catch (_) {
        _sources.remove(source);
        rethrow;
      }
      _clients[id] = client;
      _learnedDownloads[id] = manifest.allowDownloads;
      return source;
    } catch (_) {
      client.close();
      rethrow;
    }
  }

  Future<void> removeSource(String id) async {
    final old = [..._sources];
    _sources.removeWhere((s) => s.id == id);
    try {
      await _save();
    } catch (_) {
      _sources
        ..clear()
        ..addAll(old);
      rethrow;
    }
    _clients.remove(id)?.close();
    _rows.removeWhere((key, _) => key.startsWith('addon:$id\u001f'));
    lastFailures.remove(id);
    _learnedDownloads.remove(id);
  }

  Future<void> setEnabled(String id, bool enabled) async {
    final index = _sources.indexWhere((s) => s.id == id);
    if (index < 0) throw const ServiceException('This addon no longer exists.');
    final old = _sources[index];
    _sources[index] = old.copyWith(enabled: enabled);
    try {
      await _save();
    } catch (_) {
      _sources[index] = old;
      rethrow;
    }
  }

  /// [to] is the final index after moving [from], not a drag insertion index.
  Future<void> reorder(int from, int to) async {
    if (from < 0 ||
        from >= _sources.length ||
        to < 0 ||
        to >= _sources.length) {
      throw RangeError('Addon source index is outside the configured list.');
    }
    if (from == to) return;
    final old = [..._sources];
    _sources.insert(to, _sources.removeAt(from));
    try {
      await _save();
    } catch (_) {
      _sources
        ..clear()
        ..addAll(old);
      rethrow;
    }
    clearCache();
  }

  bool allowsDownloads(String sourceId) =>
      _learnedDownloads[sourceId] ??
      _sources.where((s) => s.id == sourceId).firstOrNull?.allowDownloads ??
      true;

  Future<List<Song>> search(String query, {String quality = 'LOSSLESS'}) async {
    final batches = await Future.wait(
      _sources.where((s) => s.enabled).map((source) async {
        try {
          final client = _client(source);
          final manifest = await client.manifest();
          _learnedDownloads[source.id] = manifest.allowDownloads;
          final tracks = await client.search(query, quality: quality);
          for (final track in tracks) {
            _remember(source.id, track);
          }
          lastFailures.remove(source.id);
          return tracks.map((track) => track.asSong(source.id)).toList();
        } on Exception catch (error) {
          lastFailures[source.id] = error is ServiceException
              ? error.message
              : 'The addon could not be reached.';
          return <Song>[];
        }
      }),
    );
    return batches.expand((b) => b).toList();
  }

  Future<AddonStream?> resolve(
    Song song, {
    String quality = 'LOSSLESS',
    bool forDownload = false,
  }) async {
    if (song.isLocal || song.isVideo) return null;
    final reference = parseTrackKey(song.videoId);
    if (reference != null) {
      final source = _sources
          .where((s) => s.id == reference.sourceId && s.enabled)
          .firstOrNull;
      if (source == null) {
        throw const ServiceException(
          'The addon for this track is disabled or removed.',
        );
      }
      return _resolveTrack(source, reference.trackId, quality, forDownload);
    }
    if (quality.toUpperCase() != 'LOSSLESS') return null;
    for (final source in _sources.where((s) => s.enabled)) {
      try {
        final client = _client(source);
        final manifest = await client.manifest();
        _learnedDownloads[source.id] = manifest.allowDownloads;
        if (forDownload && !manifest.allowDownloads) continue;
        final tracks = await client.search(
          '${song.title} ${song.artist}',
          quality: quality,
        );
        for (final track in tracks) {
          _remember(source.id, track);
        }
        final matched = SongMatcher.best(
          tracks.map((t) => t.asSong(source.id)),
          title: song.title,
          artist: song.artist,
          duration: song.duration,
          album: song.albumName,
        );
        if (matched == null) continue;
        final ref = parseTrackKey(matched.videoId)!;
        final stream = await _resolveTrack(
          source,
          ref.trackId,
          quality,
          forDownload,
        );
        lastFailures.remove(source.id);
        return stream;
      } on Exception catch (error) {
        lastFailures[source.id] = error is ServiceException
            ? error.message
            : 'The addon could not be reached.';
      }
    }
    return null;
  }

  Future<AddonStream> _resolveTrack(
    AddonConfig source,
    String trackId,
    String quality,
    bool forDownload,
  ) async {
    final client = _client(source);
    final manifest = await client.manifest();
    _learnedDownloads[source.id] = manifest.allowDownloads;
    if (forDownload && !manifest.allowDownloads) {
      throw const ServiceException('This addon allows playback only.');
    }
    try {
      return await client.stream(
        trackId,
        source.id,
        quality: quality,
        forDownload: forDownload,
      );
    } on ServiceException catch (error) {
      final track = _rows[trackKey(source.id, trackId)];
      if (error.statusCode != 404 || track?.streamUrl == null) rethrow;
      return AddonStream.fromJson(
        {
          'url': track!.streamUrl,
          'format': track.format,
          'quality': track.quality,
        },
        source.id,
        manifest.allowDownloads,
      );
    }
  }

  void _remember(String id, AddonTrack track) {
    final key = trackKey(id, track.id);
    _rows.remove(key);
    _rows[key] = track;
    while (_rows.length > 400) {
      _rows.remove(_rows.keys.first);
    }
  }

  static String trackKey(String sourceId, String trackId) =>
      AddonTrackReference.encode(sourceId, trackId);
  static ({String sourceId, String trackId})? parseTrackKey(String value) =>
      AddonTrackReference.parse(value);

  void clearCache() {
    for (final client in _clients.values) {
      client.clear();
    }
    _rows.clear();
  }

  void close() {
    for (final client in _clients.values) {
      client.close();
    }
    _clients.clear();
    _rows.clear();
  }
}
