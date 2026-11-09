import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:pointycastle/api.dart';
import 'package:pointycastle/block/desede_engine.dart';

import '../domain/models.dart';
import 'addon_client.dart';
import 'innertube_client.dart';
import 'innertube_parser.dart';
import 'song_matcher.dart';

/// DesktopJioSaavn's public catalogue contract and CDN URL decoder.
/// The fixed DES key decodes legacy URL obfuscation; it is not credential storage.
class JioSaavnClient {
  JioSaavnClient({http.Client? httpClient})
    : _http = httpClient ?? http.Client(),
      _ownsClient = httpClient == null;
  final http.Client _http;
  final bool _ownsClient;
  final _cache = <String, ({DateTime expires, JsonMap value})>{};
  bool _closed = false;
  static const sourceId = 'jiosaavn';
  static const trackPrefix = 'jiosaavn:';

  Future<List<Song>> search(String query, {int limit = 25}) async {
    if (query.trim().isEmpty) return const [];
    final raw = await _get('search.getResults', {
      'q': query.trim(),
      'p': '1',
      'n': '${limit.clamp(1, 50)}',
    });
    final songs = InnertubeParser.array(
      raw['results'],
    ).map(InnertubeParser.object).map(parseSong).whereType<Song>().toList();
    // Source1.9.2 prefers an explicit release when duplicate clean cuts exist.
    final explicit = songs.where((s) => s.isExplicit == true);
    final other = songs.where((s) => s.isExplicit != true);
    return [...explicit, ...other].take(limit.clamp(1, 50)).toList();
  }

  Future<AddonStream?> resolve(Song song, {String quality = 'HIGH'}) async {
    if (song.isLocal || song.isVideo) return null;
    if (song.videoId.startsWith(trackPrefix)) return stream(song.videoId);
    if (!{'HIGH', 'LOSSLESS'}.contains(quality.toUpperCase())) return null;
    final candidates = await search('${song.title} ${song.artist}');
    final match = SongMatcher.best(
      candidates,
      title: song.title,
      artist: song.artist,
      duration: song.duration,
      album: song.albumName,
    );
    return match == null ? null : stream(match.videoId);
  }

  Future<AddonStream?> stream(String trackKey) async {
    final id = trackKey.startsWith(trackPrefix)
        ? trackKey.substring(trackPrefix.length)
        : trackKey;
    if (id.isEmpty) return null;
    final root = await _get('song.getDetails', {'pids': id});
    final listed = InnertubeParser.array(root['songs']);
    final raw = InnertubeParser.object(
      listed.firstOrNull ??
          root[id] ??
          root.values.whereType<Map>().firstOrNull,
    );
    if (raw.isEmpty) return null;
    final info = InnertubeParser.object(raw['more_info']);
    final url = decodeMediaUrl(info['encrypted_media_url'] as String? ?? '');
    if (url == null) return null;
    final best = bestUrl(url, '${info['320kbps']}'.toLowerCase() == 'true');
    return AddonStream(
      uri: best.url,
      sourceId: sourceId,
      codec: 'aac',
      format: 'mp4',
      kbps: best.kbps,
      allowDownloads: true,
    );
  }

  static Song? parseSong(JsonMap raw) {
    final id = '${raw['id'] ?? ''}';
    final title = raw['title'] as String? ?? '';
    if (id.isEmpty || title.isEmpty) return null;
    final info = InnertubeParser.object(raw['more_info']);
    final names =
        InnertubeParser.array(
              InnertubeParser.at(info, ['artistMap', 'primary_artists']),
            )
            .map((a) => InnertubeParser.object(a)['name'] as String? ?? '')
            .where((s) => s.isNotEmpty)
            .toList();
    final seconds = int.tryParse('${info['duration']}');
    final art = (raw['image'] as String? ?? '')
        .replaceAll(RegExp(r'150x150|50x50'), '500x500')
        .replaceFirst(RegExp(r'^http://'), 'https://');
    return Song(
      videoId: '$trackPrefix$id',
      title: _entities(title),
      artist: names.isEmpty
          ? 'Unknown Artist'
          : names.map(_entities).join(', '),
      albumName: info['album'] is String
          ? _entities(info['album'] as String)
          : null,
      thumbnailUrl: art.isEmpty ? null : art,
      durationText: seconds == null
          ? null
          : formatDuration(Duration(seconds: seconds)),
      sourceQuality: 'HIGH',
      isExplicit: {
        '1',
        'true',
      }.contains('${raw['explicit_content']}'.toLowerCase()),
      playbackSource: 'JioSaavn',
      playbackSourceType: 'jiosaavn',
      playbackSourceId: sourceId,
    );
  }

  /// Equivalent to the source's DES/ECB/PKCS5Padding. Repeating K three times
  /// makes Triple DES EDE exactly DES, using the maintained PointyCastle engine.
  static String? decodeMediaUrl(String encoded) {
    if (encoded.isEmpty) return null;
    try {
      final encrypted = base64.decode(encoded);
      if (encrypted.isEmpty || encrypted.length % 8 != 0) return null;
      final key = utf8.encode('38346591');
      final engine = DESedeEngine()
        ..init(
          false,
          KeyParameter(Uint8List.fromList([...key, ...key, ...key])),
        );
      final plain = Uint8List(encrypted.length);
      for (var offset = 0; offset < encrypted.length; offset += 8) {
        engine.processBlock(encrypted, offset, plain, offset);
      }
      final padding = plain.last;
      if (padding < 1 ||
          padding > 8 ||
          plain.skip(plain.length - padding).any((b) => b != padding)) {
        return null;
      }
      final decoded = utf8
          .decode(plain.sublist(0, plain.length - padding))
          .trim();
      final uri = Uri.tryParse(decoded);
      if (uri == null ||
          !{'http', 'https'}.contains(uri.scheme) ||
          uri.host.isEmpty) {
        return null;
      }
      return uri.toString();
    } on FormatException {
      return null;
    } on ArgumentError {
      return null;
    }
  }

  static ({String url, int? kbps}) bestUrl(String url, bool supports320) {
    final uri = Uri.parse(url);
    final suffix = RegExp(
      r'_(48|96|160|320)\.(mp4|aac|mp3)$',
    ).firstMatch(uri.path);
    if (suffix == null) return (url: url, kbps: supports320 ? 320 : null);
    if (!supports320) return (url: url, kbps: int.parse(suffix[1]!));
    return (
      url: uri
          .replace(
            path: uri.path.replaceRange(
              suffix.start,
              suffix.end,
              '_320.${suffix[2]}',
            ),
          )
          .toString(),
      kbps: 320,
    );
  }

  Future<JsonMap> _get(String operation, Map<String, String> params) async {
    if (_closed) throw StateError('The JioSaavn client is closed.');
    final uri = Uri.https('www.jiosaavn.com', '/api.php', {
      '__call': operation,
      '_format': 'json',
      '_marker': '0',
      'api_version': '4',
      'ctx': 'android',
      ...params,
    });
    final key = uri.toString();
    final cached = _cache.remove(key);
    if (cached != null && cached.expires.isAfter(DateTime.now())) {
      _cache[key] = cached;
      return cached.value;
    }
    try {
      final response = await (() async {
        final request = http.Request('GET', uri)
          ..headers.addAll({
            'Accept': 'application/json',
            'Accept-Language': 'en-IN,en;q=0.9',
            'User-Agent': InnertubeClient.userAgent,
            'Cookie': 'explicit_content=1',
          });
        final streamed = await _http.send(request);
        if (streamed.statusCode != 200) {
          await streamed.stream.drain<void>();
          throw ServiceException(
            'JioSaavn returned HTTP ${streamed.statusCode}.',
            statusCode: streamed.statusCode,
            retryable: streamed.statusCode >= 500,
          );
        }
        final bytes = BytesBuilder(copy: false);
        await for (final chunk in streamed.stream) {
          if (bytes.length + chunk.length > 4 * 1024 * 1024) {
            throw const ServiceException(
              'JioSaavn returned an oversized response.',
            );
          }
          bytes.add(chunk);
        }
        return jsonDecode(utf8.decode(bytes.takeBytes()));
      })().timeout(const Duration(seconds: 15));
      if (response is! Map<String, dynamic>) {
        throw const ServiceException(
          'JioSaavn returned an unexpected response.',
        );
      }
      _cache[key] = (
        expires: DateTime.now().add(const Duration(minutes: 3)),
        value: response,
      );
      while (_cache.length > 32) {
        _cache.remove(_cache.keys.first);
      }
      return response;
    } on TimeoutException {
      throw const ServiceException('JioSaavn timed out.', retryable: true);
    } on http.ClientException {
      throw const ServiceException(
        'Could not reach JioSaavn.',
        retryable: true,
      );
    }
  }

  static String _entities(String text) => text
      .replaceAll('&quot;', '"')
      .replaceAll('&#039;', "'")
      .replaceAll('&#39;', "'")
      .replaceAll('&amp;', '&')
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>');
  void clearCache() => _cache.clear();
  void close() {
    _closed = true;
    clearCache();
    if (_ownsClient) _http.close();
  }
}
