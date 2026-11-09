import 'dart:async';
import 'dart:io';

import 'package:youtube_explode_dart/youtube_explode_dart.dart' as youtube;

import '../domain/models.dart';

class StreamFormat {
  const StreamFormat({
    this.codec,
    this.kbps,
    this.sampleRateHz,
    this.bitDepth,
    this.channels,
  });
  final String? codec;
  final int? kbps;
  final int? sampleRateHz;
  final int? bitDepth;
  final int? channels;
  String? get _name => codec?.toLowerCase().split('/').last.split(';').first;
  bool get isLossless =>
      const {
        'flac',
        'alac',
        'wav',
        'aiff',
        'ape',
        'wv',
        'wavpack',
        'dsf',
        'dff',
        'tta',
        'raw',
      }.contains(_name) ||
      (_name?.startsWith('pcm_') ?? false);
  bool get isHiRes =>
      isLossless && ((bitDepth ?? 0) > 16 || (sampleRateHz ?? 0) > 48000);
  bool get isDolbyAtmos => const {'eac3', 'eac3-joc', 'ec-3'}.contains(_name);
  String get summary => [
    ?codec,
    if (bitDepth != null) '$bitDepth-bit',
    if (sampleRateHz != null) '${sampleRateHz! / 1000} kHz',
    if (kbps != null) '$kbps kbps',
    if (channels != null)
      channels == 1
          ? 'Mono'
          : channels == 2
          ? 'Stereo'
          : '$channels channels',
  ].join(' · ');
}

/// Resolved URLs are short-lived transport data and must never be persisted.
class ResolvedStream {
  const ResolvedStream({
    required this.uri,
    this.headers = const {},
    this.format = const StreamFormat(),
    this.source = 'youtube',
    this.expiresAt,
    this.contentLength,
    this.windowedReads = false,
    this.videoHeight,
    this.allowDownloads = true,
    this.sourceQuality,
    this.container,
  });
  final String uri;
  final Map<String, String> headers;
  final StreamFormat format;
  final String source;
  final DateTime? expiresAt;
  final int? contentLength;
  final bool windowedReads;
  final int? videoHeight;
  final bool allowDownloads;

  /// Quality advertised by the selected provider, independently of the
  /// container/codec facts reported by [format].
  final String? sourceQuality;

  /// Transport container supplied by the provider, distinct from its codec.
  final String? container;
  String get url => uri;
  String? get mimeType =>
      format.codec?.contains('/') == true ? format.codec : null;
  String get extension {
    final containerName = container
        ?.toLowerCase()
        .split(';')
        .first
        .split('/')
        .last;
    if (containerName != null && containerName.isNotEmpty) {
      switch (containerName) {
        case 'mp4':
        case 'm4a':
        case 'aac':
        case 'x-m4a':
          return videoHeight != null ? 'mp4' : 'm4a';
        case 'mpeg':
        case 'mp3':
          return 'mp3';
        case 'wav':
        case 'wave':
        case 'x-wav':
          return 'wav';
        case 'flac':
        case 'x-flac':
          return 'flac';
        case 'ogg':
        case 'oga':
        case 'opus':
          return 'ogg';
        case 'webm':
          return 'webm';
        case 'aiff':
        case 'aif':
        case 'x-aiff':
          return 'aiff';
      }
    }
    final mime = mimeType?.toLowerCase() ?? '';
    if (mime.contains('webm') || format.codec?.toLowerCase() == 'opus') {
      return 'webm';
    }
    if (mime.contains('mpeg') || format.codec?.toLowerCase() == 'mp3') {
      return 'mp3';
    }
    if (mime.contains('flac') || format.codec?.toLowerCase() == 'flac') {
      return 'flac';
    }
    if (mime.contains('ogg') || format.codec?.toLowerCase() == 'vorbis') {
      return 'ogg';
    }
    if (mime.contains('wav') ||
        const {'wav', 'wave'}.contains(format.codec?.toLowerCase())) {
      return 'wav';
    }
    return videoHeight != null ? 'mp4' : 'm4a';
  }

  bool isFresh(DateTime now) =>
      expiresAt == null ||
      expiresAt!.isAfter(now.add(const Duration(seconds: 30)));
}

abstract interface class StreamResolver {
  Future<ResolvedStream> resolve(
    Song song, {
    bool video = false,
    int maxVideoHeight = 1080,
    bool refresh = false,
    bool forceRefresh = false,
  });
  void invalidate(Song song);
  Future<void> close();
}

typedef AuthenticatedPlayerLoader = Future<Map<String, dynamic>> Function(
  String videoId,
);
typedef AlternateStreamLoader = Future<ResolvedStream?> Function(Song song);

/// Bounded resolver cache: only eight manifests and eight URLs are retained.
/// An optional signed-in Innertube client supplies direct playable URLs; cipher
/// extraction is delegated to youtube_explode_dart, never reimplemented ad hoc.
class YoutubeStreamResolver implements StreamResolver {
  YoutubeStreamResolver({
    youtube.YoutubeExplode? youtubeClient,
    this.authenticatedPlayer,
    this.alternateSource,
    int? maxAudioKbps,
    this.timeout = const Duration(seconds: 25),
    this.cacheEntries = 8,
  }) : _youtube = youtubeClient ?? youtube.YoutubeExplode(),
       _maxAudioKbps = maxAudioKbps;

  final youtube.YoutubeExplode _youtube;
  final AuthenticatedPlayerLoader? authenticatedPlayer;
  final AlternateStreamLoader? alternateSource;
  int? _maxAudioKbps;
  int? get maxAudioKbps => _maxAudioKbps;
  set maxAudioKbps(int? value) {
    if (value != null && value <= 0) {
      throw ArgumentError.value(
        value,
        'maxAudioKbps',
        'Must be positive or null.',
      );
    }
    if (value == _maxAudioKbps) return;
    _maxAudioKbps = value;
    clearCache();
  }

  final Duration timeout;
  final int cacheEntries;
  final _cache = <String, ResolvedStream>{};
  final _manifests = <String, _ManifestEntry>{};
  final _pending = <String, Future<ResolvedStream>>{};
  final _pendingManifests = <String, Future<youtube.StreamManifest>>{};
  bool _closed = false;
  int _cacheRevision = 0;

  @override
  Future<ResolvedStream> resolve(
    Song song, {
    bool video = false,
    int maxVideoHeight = 1080,
    bool refresh = false,
    bool forceRefresh = false,
  }) async {
    if (_closed) throw StateError('The stream resolver is closed.');
    if (song.isLocal) return _local(song);
    final key = '${song.videoId}:$video:$maxVideoHeight';
    if (refresh || forceRefresh) invalidate(song);
    final hit = _cache.remove(key);
    if (hit != null && hit.isFresh(DateTime.now())) {
      _cache[key] = hit;
      return hit;
    }
    final inProgress = _pending[key];
    if (inProgress != null) return inProgress;
    final revision = _cacheRevision;
    final request = _resolveRemote(
      song,
      video,
      maxVideoHeight,
    ).timeout(timeout);
    _pending[key] = request;
    try {
      final resolved = await request;
      if (!_closed && revision == _cacheRevision) {
        _cache[key] = resolved;
        while (_cache.length > cacheEntries) {
          _cache.remove(_cache.keys.first);
        }
      }
      return resolved;
    } finally {
      if (identical(_pending[key], request)) {
        unawaited(_pending.remove(key));
      }
    }
  }

  Future<ResolvedStream> _local(Song song) async {
    final raw = song.localPath ?? song.localUri!;
    final parsed = Uri.tryParse(raw);
    final file = parsed?.scheme == 'file' ? File.fromUri(parsed!) : File(raw);
    if (parsed != null &&
        parsed.scheme.isNotEmpty &&
        parsed.scheme != 'file' &&
        !RegExp(r'^[A-Za-z]:[\\/]').hasMatch(raw)) {
      throw const PlaybackResolutionException(
        'This local URI cannot be opened on desktop. Select the original file again.',
      );
    }
    if (!await file.exists()) {
      throw const PlaybackResolutionException(
        'The local audio file has been moved or deleted.',
      );
    }
    return ResolvedStream(
      uri: file.absolute.uri.toString(),
      source: 'local',
      container: file.path.split('.').last,
      format: StreamFormat(codec: file.path.split('.').last.toUpperCase()),
      contentLength: await file.length(),
    );
  }

  Future<ResolvedStream> _resolveRemote(
    Song song,
    bool video,
    int maxHeight,
  ) async {
    if (!video && alternateSource != null) {
      final alternate = await alternateSource!(song);
      if (alternate != null) return alternate;
    }
    if (!song.isYouTube) {
      throw const PlaybackResolutionException(
        'The selected music source is unavailable. Re-enable that source or choose another recording.',
      );
    }
    if (authenticatedPlayer != null) {
      try {
        final data = await authenticatedPlayer!(song.videoId).timeout(timeout);
        final direct = directStreamFromPlayer(
          data,
          video: video,
          maxVideoHeight: maxHeight,
          maxAudioKbps: maxAudioKbps,
        );
        if (direct != null) return direct;
      } on Exception {
        // Client/cipher compatibility can differ. The independent extractor is
        // the fallback; neither cookies nor signed URLs enter error messages.
      }
    }
    try {
      final manifest = await _manifest(song.videoId);
      if (video) {
        final candidates = manifest.videoOnly.toList();
        candidates.sort(
          (a, b) =>
              b.videoResolution.height.compareTo(a.videoResolution.height),
        );
        final permitted = candidates
            .where((s) => s.videoResolution.height <= maxHeight)
            .toList();
        if (permitted.isNotEmpty || candidates.isNotEmpty) {
          final stream = permitted.isNotEmpty
              ? permitted.first
              : candidates.last;
          return _transport(
            stream.url,
            codec: stream.videoCodec,
            container: stream.container.name,
            kbps: stream.bitrate.kiloBitsPerSecond.round(),
            length: stream.size.totalBytes,
            height: stream.videoResolution.height,
          );
        }
        final muxed = manifest.muxed.toList()
          ..sort(
            (a, b) =>
                b.videoResolution.height.compareTo(a.videoResolution.height),
          );
        if (muxed.isNotEmpty) {
          final stream = muxed.first;
          return _transport(
            stream.url,
            codec: stream.videoCodec,
            container: stream.container.name,
            kbps: stream.bitrate.kiloBitsPerSecond.round(),
            length: stream.size.totalBytes,
            height: stream.videoResolution.height,
          );
        }
        throw const PlaybackResolutionException(
          'This recording has no playable video rendition.',
        );
      }
      final candidates = manifest.audioOnly.toList()
        ..sort(
          (a, b) => b.bitrate.bitsPerSecond.compareTo(a.bitrate.bitsPerSecond),
        );
      final permitted = candidates
          .where(
            (s) =>
                maxAudioKbps == null ||
                s.bitrate.kiloBitsPerSecond <= maxAudioKbps!,
          )
          .toList();
      if (candidates.isNotEmpty) {
        final stream = permitted.isNotEmpty ? permitted.first : candidates.last;
        return _transport(
          stream.url,
          codec: stream.audioCodec,
          container: stream.container.name,
          kbps: stream.bitrate.kiloBitsPerSecond.round(),
          length: stream.size.totalBytes,
        );
      }
      final muxed = manifest.muxed.toList()
        ..sort(
          (a, b) => b.bitrate.bitsPerSecond.compareTo(a.bitrate.bitsPerSecond),
        );
      if (muxed.isNotEmpty) {
        final stream = muxed.first;
        return _transport(
          stream.url,
          codec: stream.audioCodec,
          container: stream.container.name,
          kbps: stream.bitrate.kiloBitsPerSecond.round(),
          length: stream.size.totalBytes,
        );
      }
      throw const PlaybackResolutionException(
        'No playable audio was returned for this recording.',
      );
    } on PlaybackResolutionException {
      rethrow;
    } on TimeoutException {
      rethrow;
    } on Exception {
      throw const PlaybackResolutionException(
        'YouTube could not provide a playable stream. It may require sign-in, be unavailable in your region, or need an extractor update.',
      );
    }
  }

  Future<youtube.StreamManifest> _manifest(String id) async {
    final hit = _manifests.remove(id);
    if (hit != null &&
        DateTime.now().difference(hit.at) < const Duration(minutes: 4)) {
      _manifests[id] = hit;
      return hit.manifest;
    }
    final pending = _pendingManifests[id];
    if (pending != null) return pending;
    final revision = _cacheRevision;
    final request = _youtube.videos.streams.getManifest(id).timeout(timeout);
    _pendingManifests[id] = request;
    try {
      final manifest = await request;
      if (!_closed && revision == _cacheRevision) {
        _manifests[id] = _ManifestEntry(manifest, DateTime.now());
        while (_manifests.length > cacheEntries) {
          _manifests.remove(_manifests.keys.first);
        }
      }
      return manifest;
    } finally {
      if (identical(_pendingManifests[id], request)) {
        unawaited(_pendingManifests.remove(id));
      }
    }
  }

  @override
  void invalidate(Song song) {
    _cacheRevision++;
    _cache.removeWhere((key, _) => key.startsWith('${song.videoId}:'));
    _manifests.remove(song.videoId);
    _pending.removeWhere((key, _) => key.startsWith('${song.videoId}:'));
    _pendingManifests.remove(song.videoId);
  }

  void clearCache() {
    _cacheRevision++;
    _cache.clear();
    _manifests.clear();
    _pending.clear();
    _pendingManifests.clear();
  }

  @override
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _cache.clear();
    _manifests.clear();
    _pending.clear();
    _pendingManifests.clear();
    _youtube.close();
  }
}

ResolvedStream _transport(
  Uri url, {
  String? codec,
  String? container,
  int? kbps,
  int? length,
  int? height,
  Map<String, String> headers = const {},
}) {
  final expiry = int.tryParse(url.queryParameters['expire'] ?? '');
  return ResolvedStream(
    uri: url.toString(),
    headers: {'User-Agent': 'Mozilla/5.0', 'Accept': '*/*', ...headers},
    format: StreamFormat(codec: codec, kbps: kbps),
    container: container,
    contentLength: length,
    videoHeight: height,
    windowedReads: url.host.endsWith('.googlevideo.com'),
    expiresAt: expiry != null
        ? DateTime.fromMillisecondsSinceEpoch(expiry * 1000)
        : DateTime.now().add(const Duration(minutes: 4)),
  );
}

/// Only direct HTTPS formats are accepted here. A signatureCipher requires the
/// actual extractor; treating its encrypted signature as a URL is invalid.
ResolvedStream? directStreamFromPlayer(
  Map<String, dynamic> data, {
  bool video = false,
  int maxVideoHeight = 1080,
  int? maxAudioKbps,
}) {
  final streaming = data['streamingData'];
  if (streaming is! Map) return null;
  final formats =
      [
        ...?(streaming['adaptiveFormats'] as List?),
        ...?(streaming['formats'] as List?),
      ].whereType<Map>().where((item) {
        final mime = item['mimeType'] as String? ?? '';
        final uri = Uri.tryParse(item['url'] as String? ?? '');
        return uri != null &&
            uri.scheme == 'https' &&
            uri.host.isNotEmpty &&
            (video ? mime.startsWith('video/') : mime.startsWith('audio/'));
      }).toList();
  if (formats.isEmpty) return null;
  int metric(Map m) => video
      ? (m['height'] as num?)?.toInt() ?? 0
      : (m['bitrate'] as num?)?.toInt() ?? 0;
  formats.sort((a, b) => metric(b).compareTo(metric(a)));
  final limit = video ? maxVideoHeight : (maxAudioKbps ?? 10000) * 1000;
  final permitted = formats.where((m) => metric(m) <= limit).toList();
  final selected = permitted.isNotEmpty ? permitted.first : formats.last;
  return _transport(
    Uri.parse(selected['url'] as String),
    codec: selected['mimeType'] as String?,
    container: selected['mimeType'] as String?,
    kbps: ((selected['bitrate'] as num?)?.toDouble() ?? 0) ~/ 1000,
    length: int.tryParse('${selected['contentLength'] ?? ''}'),
    height: (selected['height'] as num?)?.toInt(),
  );
}

class _ManifestEntry {
  _ManifestEntry(this.manifest, this.at);
  final youtube.StreamManifest manifest;
  final DateTime at;
}

class PlaybackResolutionException implements Exception {
  const PlaybackResolutionException(this.message);
  final String message;
  @override
  String toString() => message;
}
