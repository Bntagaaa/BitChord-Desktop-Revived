import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import '../domain/models.dart';
import 'innertube_parser.dart';

class AddonConfig {
  const AddonConfig({
    required this.id,
    required this.baseUrl,
    required this.label,
    this.enabled = true,
    this.allowDownloads = true,
  });
  final String id;
  final String baseUrl;
  final String label;
  final bool enabled;
  final bool allowDownloads;
  Map<String, Object?> toJson() => {
    'id': id,
    'baseUrl': baseUrl,
    'label': label,
    'enabled': enabled,
    'allowDownloads': allowDownloads,
  };
  factory AddonConfig.fromJson(JsonMap json) => AddonConfig(
    id: json['id'] as String,
    baseUrl: json['baseUrl'] as String,
    label: json['label'] as String,
    enabled: json['enabled'] as bool? ?? true,
    allowDownloads: json['allowDownloads'] as bool? ?? true,
  );
  AddonConfig copyWith({String? label, bool? enabled, bool? allowDownloads}) =>
      AddonConfig(
        id: id,
        baseUrl: baseUrl,
        label: label ?? this.label,
        enabled: enabled ?? this.enabled,
        allowDownloads: allowDownloads ?? this.allowDownloads,
      );
}

class AddonManifest {
  const AddonManifest({
    required this.id,
    required this.name,
    this.version = '',
    this.resources = const [],
    this.settings = const [],
    this.allowDownloads = true,
  });
  final String id;
  final String name;
  final String version;
  final List<String> resources;
  final List<JsonMap> settings;
  final bool allowDownloads;
  factory AddonManifest.fromJson(JsonMap raw) {
    final id = raw['id'] as String? ?? '';
    final resources = InnertubeParser.array(raw['resources'])
        .whereType<String>()
        .toList();
    if (id.trim().isEmpty) {
      throw const ServiceException(
        'This server did not return an addon manifest.',
      );
    }
    if (resources.isNotEmpty &&
        !resources.any((r) => r.toLowerCase() == 'search')) {
      throw const ServiceException(
        'This addon does not declare music search support.',
      );
    }
    return AddonManifest(
      id: id,
      name: raw['name'] as String? ?? id,
      version: raw['version'] as String? ?? '',
      resources: resources,
      settings: InnertubeParser.array(raw['settings'])
          .map(InnertubeParser.object)
          .toList(),
      allowDownloads: flag(raw['allowDownloads']) ?? true,
    );
  }
  static bool? flag(Object? value) => switch ('$value'.trim().toLowerCase()) {
    'true' || '1' || 'yes' || 'on' => true,
    'false' || '0' || 'no' || 'off' => false,
    _ => value is num ? value != 0 : null,
  };
}

class AddonTrack {
  const AddonTrack({
    required this.id,
    required this.title,
    required this.artist,
    this.album,
    this.duration,
    this.artworkUrl,
    this.format,
    this.quality,
    this.streamUrl,
    this.isAtmos = false,
  });
  final String id, title, artist;
  final String? album, artworkUrl, format, quality, streamUrl;
  final Duration? duration;
  final bool isAtmos;
  factory AddonTrack.fromJson(JsonMap raw) => AddonTrack(
    id: '${raw['id'] ?? ''}',
    title: raw['title'] as String? ?? '',
    artist: raw['artist'] as String? ?? '',
    album: raw['album'] as String?,
    duration: raw['duration'] is num
        ? Duration(milliseconds: ((raw['duration'] as num) * 1000).round())
        : null,
    artworkUrl:
        raw['artworkURL'] as String? ?? raw['albumArtworkURL'] as String?,
    format: raw['format'] as String?,
    quality: raw['audioQuality'] as String?,
    streamUrl: raw['streamURL'] as String?,
    isAtmos:
        raw['atmos'] == true ||
        RegExp(r'atmos|dolby|eac3|ec-3', caseSensitive: false).hasMatch(
          '${raw['format']} ${raw['audioQuality']} ${raw['audioMode']} ${raw['audioModes']}',
        ),
  );
  Song asSong(String sourceId) => Song(
    videoId: AddonTrackReference.encode(sourceId, id),
    title: title,
    artist: artist,
    albumName: album,
    thumbnailUrl: artworkUrl,
    durationText: duration == null ? null : formatDuration(duration!),
    sourceQuality:
        RegExp(
          r'flac|alac|lossless|hifi|hi.res',
          caseSensitive: false,
        ).hasMatch('$format $quality')
        ? 'LOSSLESS'
        : 'HIGH',
  );
}

class AddonStream {
  const AddonStream({
    required this.uri,
    required this.sourceId,
    this.format,
    this.codec,
    this.kbps,
    this.sampleRateHz,
    this.bitDepth,
    this.headers = const {},
    this.allowDownloads = true,
    this.transport,
  });
  final String uri, sourceId;
  final String? format, codec, transport;
  final int? kbps, sampleRateHz, bitDepth;
  final Map<String, String> headers;
  final bool allowDownloads;
  factory AddonStream.fromJson(
    JsonMap raw,
    String sourceId,
    bool allowDownloads,
  ) {
    final encrypted = raw['encrypted'];
    if (AddonManifest.flag(encrypted) == true ||
        (encrypted is String &&
            encrypted.isNotEmpty &&
            !['false', 'none', '0'].contains(encrypted.toLowerCase()))) {
      throw const ServiceException(
        'This addon returned encrypted media that cannot be played.',
      );
    }
    final url = raw['url'] as String? ?? '';
    final uri = Uri.tryParse(url);
    if (uri == null ||
        !['https', 'http'].contains(uri.scheme) ||
        uri.host.isEmpty) {
      throw ServiceException(
        raw['error'] is String
            ? raw['error'] as String
            : 'The addon returned no playable stream.',
      );
    }
    final text =
        '${raw['quality']} ${raw['streamQuality']} ${raw['audioQuality']} ${raw['format']}';
    double? number(Object? value) =>
        value is num ? value.toDouble() : double.tryParse('$value');
    final rate =
        number(raw['sampleRate']) ??
        double.tryParse(
          RegExp(
                r'([\d.]+)\s*kHz',
                caseSensitive: false,
              ).firstMatch(text)?[1] ??
              '',
        );
    final bitrate =
        number(raw['bitrate']) ??
        double.tryParse(
          RegExp(r'(\d+)\s*kbps', caseSensitive: false).firstMatch(text)?[1] ??
              '',
        );
    final bits =
        number(raw['bitDepth'])?.toInt() ??
        int.tryParse(
          RegExp(
                r'(\d+)\s*-?\s*bit',
                caseSensitive: false,
              ).firstMatch(text)?[1] ??
              '',
        );
    final headers = <String, String>{};
    for (final entry in InnertubeParser.object(raw['headers']).entries) {
      if (entry.value is String &&
          !RegExp(r'[\r\n]').hasMatch(entry.value as String) &&
          RegExp(r'^[A-Za-z0-9-]+$').hasMatch(entry.key) &&
          !{
            'host',
            'connection',
            'content-length',
          }.contains(entry.key.toLowerCase())) {
        headers[entry.key] = entry.value as String;
      }
    }
    return AddonStream(
      uri: uri.toString(),
      sourceId: sourceId,
      format: raw['format'] as String?,
      codec:
          raw['codec'] as String? ??
          raw['fileCodec'] as String? ??
          raw['format'] as String?,
      sampleRateHz: rate == null
          ? null
          : (rate < 1000 ? rate * 1000 : rate).round(),
      kbps: bitrate == null
          ? null
          : (bitrate > 3000 ? bitrate / 1000 : bitrate).round(),
      bitDepth: bits,
      headers: headers,
      allowDownloads: allowDownloads,
      transport: raw['manifest'] as String? ?? raw['mediaType'] as String?,
    );
  }
}

class AddonClient {
  AddonClient(String url, {http.Client? httpClient})
    : base = normalizeBase(url),
      _http = httpClient ?? http.Client(),
      _ownsClient = httpClient == null;
  final Uri base;
  final http.Client _http;
  final bool _ownsClient;
  final _cache = <String, ({DateTime expires, JsonMap value})>{};
  DateTime _quietUntil = DateTime.fromMillisecondsSinceEpoch(0);
  bool _closed = false;

  static Uri normalizeBase(String value) {
    var uri = Uri.tryParse(value.trim());
    if (uri == null ||
        !{'https', 'http'}.contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty) {
      throw const ServiceException(
        'Enter an HTTP or HTTPS addon manifest URL.',
      );
    }
    final segments = uri.pathSegments.where((s) => s.isNotEmpty).toList();
    if (segments.lastOrNull?.toLowerCase().endsWith('.json') == true) {
      segments.removeLast();
    }
    uri = uri.replace(pathSegments: segments, fragment: '');
    return uri;
  }

  Uri endpoint(
    List<String> segments, [
    Map<String, String> params = const {},
  ]) => base.replace(
    pathSegments: [
      ...base.pathSegments.where((p) => p.isNotEmpty),
      ...segments,
    ],
    queryParameters: {...base.queryParameters, ...params},
  );

  Future<AddonManifest> manifest() async => AddonManifest.fromJson(
    await _get(endpoint(['manifest.json']), const Duration(minutes: 10)),
  );

  Future<Map<String, String>> _settings(String quality) async {
    final manifest = await this.manifest();
    final defaults = <String, String>{};
    for (final setting in manifest.settings) {
      final key = setting['key'];
      final value = setting['default'];
      if (key is String &&
          key.isNotEmpty &&
          (value is String || value is num || value is bool)) {
        defaults[key] = '$value';
      }
    }
    final qualitySetting = manifest.settings
        .where((s) => s['key'] == 'quality')
        .firstOrNull;
    final options = InnertubeParser.array(qualitySetting?['options'])
        .map((o) => '${InnertubeParser.object(o)['value'] ?? ''}')
        .where((s) => s.isNotEmpty)
        .toList();
    var selected = options
        .where((o) => o.toLowerCase() == quality.toLowerCase())
        .firstOrNull;
    final terms = quality.toUpperCase() == 'LOSSLESS'
        ? ['lossless', 'flac', 'hifi', 'hi-res', 'hires', 'max', 'best']
        : quality.toUpperCase() == 'LOW'
        ? ['low', '96', '128', 'min']
        : ['high', '320', 'normal', 'standard'];
    selected ??= options
        .where((o) => terms.any(o.toLowerCase().contains))
        .firstOrNull;
    defaults['quality'] = selected ?? options.firstOrNull ?? quality;
    // Preserve the addon's chosen Atmos default. Its absence requests stereo;
    // actual native codec availability is not inferred from a quality label.
    return defaults;
  }

  Future<List<AddonTrack>> search(
    String query, {
    String quality = 'LOSSLESS',
  }) async {
    if (query.trim().isEmpty) return const [];
    final raw = await _get(
      endpoint(['search'], {...await _settings(quality), 'q': query.trim()}),
      const Duration(minutes: 10),
    );
    return InnertubeParser.array(raw['tracks'])
        .map(InnertubeParser.object)
        .map(AddonTrack.fromJson)
        .where((t) => t.id.isNotEmpty && t.title.isNotEmpty)
        .toList();
  }

  Future<AddonStream> stream(
    String trackId,
    String sourceId, {
    String quality = 'LOSSLESS',
    bool forDownload = false,
  }) async {
    final manifest = await this.manifest();
    if (forDownload && !manifest.allowDownloads) {
      throw const ServiceException(
        'This addon permits playback only and does not allow downloads.',
      );
    }
    return AddonStream.fromJson(
      await _get(
        endpoint(['stream', trackId], await _settings(quality)),
        const Duration(minutes: 2),
      ),
      sourceId,
      manifest.allowDownloads,
    );
  }

  Future<JsonMap> _get(Uri uri, Duration ttl) async {
    if (_closed) throw StateError('The addon client is closed.');
    final key = uri.toString();
    final hit = _cache.remove(key);
    if (hit != null && hit.expires.isAfter(DateTime.now())) {
      _cache[key] = hit;
      return hit.value;
    }
    if (_quietUntil.isAfter(DateTime.now())) {
      throw const ServiceException(
        'This addon is rate limiting requests. Try again shortly.',
        retryable: true,
      );
    }
    final response =
        await (() async {
          final request = http.Request('GET', uri)
            ..headers.addAll({
              'Accept': 'application/json',
              'User-Agent': 'BitChord/1.9.2-Flutter',
            });
          final streamed = await _http.send(request);
          final bytes = BytesBuilder(copy: false);
          await for (final chunk in streamed.stream) {
            if (bytes.length + chunk.length > 4 * 1024 * 1024) {
              throw const ServiceException(
                'Addon response exceeds the size limit.',
              );
            }
            bytes.add(chunk);
          }
          return http.Response.bytes(
            bytes.takeBytes(),
            streamed.statusCode,
            headers: streamed.headers,
          );
        })().timeout(
          const Duration(seconds: 45),
          onTimeout: () => throw const ServiceException(
            'This addon timed out.',
            retryable: true,
          ),
        );
    if (response.statusCode == 404) {
      throw const ServiceException(
        'The addon could not find this resource.',
        statusCode: 404,
      );
    }
    if (response.statusCode == 429) {
      final stated =
          double.tryParse(response.headers['retry-after'] ?? '') ?? 2;
      _quietUntil = DateTime.now().add(
        Duration(milliseconds: (stated * 1000).clamp(500, 60000).round()),
      );
      throw const ServiceException(
        'This addon is rate limiting requests.',
        statusCode: 429,
        retryable: true,
      );
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ServiceException(
        'Addon request failed (HTTP ${response.statusCode}).',
        statusCode: response.statusCode,
        retryable: response.statusCode >= 500,
      );
    }
    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const ServiceException('This addon returned invalid JSON.');
    }
    _cache[key] = (expires: DateTime.now().add(ttl), value: decoded);
    while (_cache.length > 48) {
      _cache.remove(_cache.keys.first);
    }
    return decoded;
  }

  void clear() => _cache.clear();
  void close() {
    _closed = true;
    _cache.clear();
    if (_ownsClient) _http.close();
  }
}

class AddonTrackReference {
  static String encode(String sourceId, String trackId) =>
      'addon:$sourceId\u001f$trackId';
  static ({String sourceId, String trackId})? parse(String value) {
    if (!value.startsWith('addon:')) return null;
    final index = value.indexOf('\u001f', 6);
    if (index < 7 || index == value.length - 1) return null;
    return (
      sourceId: value.substring(6, index),
      trackId: value.substring(index + 1),
    );
  }
}
