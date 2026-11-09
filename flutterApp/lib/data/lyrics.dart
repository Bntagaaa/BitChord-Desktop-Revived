// Port source: LyricsQuery.kt and LrcLib.kt, including duration matching and gap policy.
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../domain/music.dart';
import 'renderer_parser.dart';
import 'lyric_formats.dart';
export 'lyric_formats.dart';

String lyricsTitle(String title) {
  var value = title;
  for (final pattern in [
    r'\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]',
    r'\s+(feat|ft|featuring)\.?\s+.*$',
    r'\s*[(\[]\s*(official\s*)?(music\s*)?(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)\s*[)\]]',
    r'\s*[(\[]\s*official\s*[)\]]',
  ]) {
    value = value.replaceAll(RegExp(pattern, caseSensitive: false), ' ');
  }
  value = value
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim()
      .replaceAll(RegExp(r'[,\-–—]+\s*$'), '')
      .trim();
  return value.isEmpty ? title.trim() : value;
}

class LyricsRepository {
  LyricsRepository({http.Client? client}) : _client = client ?? http.Client();
  final http.Client _client;
  Future<List<LyricLine>> fetch(Song song, Duration duration) async {
    final query = {
      'track_name': lyricsTitle(song.title),
      'artist_name': song.artist.replaceFirst(RegExp(r' - Topic$'), '').trim(),
      'duration': duration.inSeconds.toString(),
    };
    Future<dynamic> get(String path, Map<String, String> parameters) async {
      final response = await _client
          .get(
            Uri.https('lrclib.net', '/api/$path', parameters),
            headers: {'User-Agent': 'BitChord (https://github.com/bitchord)'},
          )
          .timeout(const Duration(seconds: 15));
      return response.statusCode == 200 ? jsonDecode(response.body) : null;
    }

    String? exact;
    try {
      exact = object(await get('get', query))['syncedLyrics'] as String?;
    } on Exception {
      /* An exact miss still gets the source's fuzzy fallback. */
    }
    if (exact != null && exact.isNotEmpty) return parseLrc(exact);
    query.remove('duration');
    final hits =
        array(await get('search', query))
            .map(object)
            .where(
              (hit) => (hit['syncedLyrics'] as String?)?.isNotEmpty ?? false,
            )
            .toList()
          ..sort(
            (a, b) => (((a['duration'] as num?) ?? 0) - duration.inSeconds)
                .abs()
                .compareTo(
                  (((b['duration'] as num?) ?? 0) - duration.inSeconds).abs(),
                ),
          );
    return hits.isEmpty
        ? const []
        : parseLrc(hits.first['syncedLyrics'] as String);
  }

  void close() => _client.close();
}
