// Initial direct-client port from InnerTubeX v0.7.0, the dependency pinned by 1.9.2.
// This is NOT the complete catalog/cipher/PoToken/NewPipe pipeline; see PORT_STATUS.md.
import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../domain/music.dart';
import 'innertube.dart';
import 'renderer_parser.dart';

class AudioStream {
  const AudioStream(this.uri, this.headers);
  final Uri uri;
  final Map<String, String> headers;
}

class StreamResolver {
  StreamResolver(this.api, {http.Client? client})
    : _client = client ?? http.Client();
  final InnerTube api;
  final http.Client _client;
  static const _agent =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15';

  Future<AudioStream> resolve(Song song) async {
    if (song.localUri != null) return AudioStream(song.localUri!, const {});
    await api.initialize();
    final response = await _client
        .post(
          Uri.parse('${InnerTube.origin}/youtubei/v1/player'),
          headers: {
            'Content-Type': 'application/json',
            'User-Agent': _agent,
            'Origin': InnerTube.origin,
            'X-Origin': InnerTube.origin,
            'Referer': '${InnerTube.origin}/',
            'X-YouTube-Client-Name': '101',
            'X-YouTube-Client-Version': '0.1',
            'X-Goog-Api-Format-Version': '1',
            if (api.visitorData != null) 'X-Goog-Visitor-Id': api.visitorData!,
          },
          body: jsonEncode({
            'context': {
              'client': {
                'clientName': 'VISIONOS',
                'clientVersion': '0.1',
                'osName': 'VISION_OS',
                'osVersion': '1.3',
                'deviceMake': 'Apple',
                'deviceModel': 'RealityDevice14,1',
                'platform': 'MOBILE',
                'hl': api.language,
                'gl': api.region,
                if (api.visitorData != null) 'visitorData': api.visitorData,
              },
              'user': {},
            },
            'videoId': song.id,
            'contentCheckOk': true,
            'racyCheckOk': true,
          }),
        )
        .timeout(const Duration(seconds: 20));
    if (response.statusCode != 200) {
      throw const MusicApiException('Could not resolve this track');
    }
    final root = object(jsonDecode(response.body));
    final formats =
        array(at(root, ['streamingData', 'adaptiveFormats']))
            .map(object)
            .where(
              (format) =>
                  (format['mimeType'] as String? ?? '').startsWith('audio/'),
            )
            .toList()
          ..sort(
            (a, b) => ((b['bitrate'] as num?) ?? 0).compareTo(
              (a['bitrate'] as num?) ?? 0,
            ),
          );
    for (final format in formats) {
      final raw = format['url'] as String?;
      if (raw == null) continue; // Never pretend a cipher was deciphered.
      final uri = Uri.tryParse(raw);
      if (uri == null ||
          uri.scheme != 'https' ||
          !uri.host.endsWith('.googlevideo.com')) {
        continue;
      }
      final headers = {'User-Agent': _agent};
      if (await _probe(uri, headers)) return AudioStream(uri, headers);
    }
    throw MusicApiException(
      at(root, ['playabilityStatus', 'reason']) as String? ??
          'No playable audio stream was returned for this track',
    );
  }

  /// Source StreamResolver probes beyond 1 MiB and requires real audio bytes.
  Future<bool> _probe(Uri uri, Map<String, String> headers) async {
    final length = int.tryParse(uri.queryParameters['clen'] ?? '');
    final start = length != null && length > 1048576 + 16384 ? 1048576 : 0;
    final client = http.Client();
    try {
      final request = http.Request(
        'GET',
        uri,
      )..headers.addAll({...headers, 'Range': 'bytes=$start-${start + 16383}'});
      final response = await client
          .send(request)
          .timeout(const Duration(seconds: 6));
      if (response.statusCode < 200 ||
          response.statusCode >= 300 ||
          !(response.headers['content-type'] ?? '').startsWith('audio/')) {
        return false;
      }
      var count = 0;
      await for (final chunk in response.stream.timeout(
        const Duration(seconds: 6),
      )) {
        count += chunk.length;
        if (count >= 16384) return true;
      }
      return false;
    } on Exception {
      return false;
    } finally {
      client.close();
    }
  }

  void close() => _client.close();
}
