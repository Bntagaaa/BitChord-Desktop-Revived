// Port source: LyricsQuery.kt and LrcLib.kt, including duration matching and gap policy.
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../domain/music.dart';
import 'renderer_parser.dart';

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

List<LyricLine> parseLrc(String source) {
  final stamp = RegExp(r'\[(\d{1,2}):(\d{2})[.:](\d{2,3})\]');
  final words = RegExp(r'<(\d{1,3}):(\d{2})[.:](\d{2,3})>');
  final lines = <LyricLine>[];
  for (final row in source.split('\n')) {
    final match = stamp.firstMatch(row);
    if (match == null) continue;
    final fraction = int.parse(match[3]!) * (match[3]!.length == 2 ? 10 : 1);
    final millis =
        int.parse(match[1]!) * 60000 + int.parse(match[2]!) * 1000 + fraction;
    lines.add(
      LyricLine(
        Duration(milliseconds: millis),
        row
            .substring(match.end)
            .replaceAll(words, '')
            .replaceFirst(RegExp(r'^<R>'), '')
            .trim(),
      ),
    );
  }
  lines.sort((a, b) => a.time.compareTo(b.time));
  final kept = <LyricLine>[];
  for (var index = 0; index < lines.length; index++) {
    final line = lines[index];
    if (line.text.isNotEmpty ||
        index == lines.length - 1 ||
        lines[index + 1].time - line.time >= const Duration(seconds: 4)) {
      kept.add(line);
    }
  }
  if (kept.isNotEmpty &&
      kept.first.text.isNotEmpty &&
      kept.first.time >= const Duration(seconds: 4)) {
    kept.insert(0, const LyricLine(Duration.zero, ''));
  }
  return kept;
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

    final exact = object(await get('get', query))['syncedLyrics'] as String?;
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
