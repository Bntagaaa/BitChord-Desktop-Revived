import 'dart:convert';
import 'package:http/http.dart' as http;
import '../domain/song.dart';

/// Single lyric line, 1:1 port of Kotlin's [LyricLine].
class LyricLine {
  final int timeMs;
  final String text;

  const LyricLine({
    required this.timeMs,
    required this.text,
  });

  bool get isGap => text.trim().isEmpty;
}

/// Parsed lyrics result, 1:1 port of Kotlin's [DesktopLyrics].
class DesktopLyrics {
  final String source;
  final List<LyricLine> lines;
  final bool isSynced;

  const DesktopLyrics({
    required this.source,
    required this.lines,
    this.isSynced = true,
  });

  String get plainText => lines.map((l) => l.text).join('\n');
}

/// Direct 1:1 port of [DesktopLyricsClient.kt] and [LrcLib.kt] from BitChord Compose Desktop.
class DesktopLyricsClient {
  DesktopLyricsClient._();

  static const String _lrclibBase = 'https://lrclib.net/api';
  static const String _userAgent = 'BitChord (https://github.com/bitchord)';
  static const int _minGapMs = 3000;

  static final RegExp _stampRegex =
      RegExp(r'\[(\d{1,2}):(\d{2})(?:\.(\d{2,3}))?\]');
  static final RegExp _noiseRegex = RegExp(
    r'\s*(\(|\[)(official\s*(music\s*)?video|video\s*clip|audio|lyric(s)?(\s*video)?|visualizer|remaster(ed)?|from\s+[^)\]]+)(\)|\])',
    caseSensitive: false,
  );

  static final Map<String, DesktopLyrics> _cache = {};

  /// Looks up real lyrics from LRCLIB first, with fallback to YouTube Music description.
  static Future<DesktopLyrics?> lookup(Song song) async {
    final cacheKey = '${song.videoId}|${song.title}';
    if (_cache.containsKey(cacheKey)) {
      return _cache[cacheKey];
    }

    // 1. Try LRCLIB exact match
    final cleanTitle = _clean(song.title);
    final cleanArtist = _clean(song.artist);

    final lrc = await _fetchLrcLib(cleanTitle, cleanArtist, song.durationSeconds);
    if (lrc != null && lrc.isNotEmpty) {
      final lines = parseLrc(lrc);
      if (lines.isNotEmpty) {
        final result = DesktopLyrics(
          source: 'LRCLIB',
          lines: lines,
          isSynced: true,
        );
        _cache[cacheKey] = result;
        return result;
      }
    }

    // 2. Try LRCLIB generic search with song query
    final searchLrc = await _searchLrcLib('$cleanTitle $cleanArtist');
    if (searchLrc != null && searchLrc.isNotEmpty) {
      final lines = parseLrc(searchLrc);
      if (lines.isNotEmpty) {
        final result = DesktopLyrics(
          source: 'LRCLIB',
          lines: lines,
          isSynced: true,
        );
        _cache[cacheKey] = result;
        return result;
      }
    }

    return null;
  }

  static Future<String?> _fetchLrcLib(
    String title,
    String artist,
    int durationSec,
  ) async {
    try {
      final uri = Uri.parse('$_lrclibBase/get').replace(queryParameters: {
        'track_name': title,
        'artist_name': artist,
        if (durationSec > 0) 'duration': durationSec.toString(),
      });

      final res = await http.get(uri, headers: {'User-Agent': _userAgent}).timeout(
        const Duration(seconds: 5),
      );
      if (res.statusCode == 200) {
        final json = jsonDecode(res.body) as Map<String, dynamic>;
        final synced = json['syncedLyrics'] as String?;
        if (synced != null && synced.trim().isNotEmpty) {
          return synced;
        }
        final plain = json['plainLyrics'] as String?;
        if (plain != null && plain.trim().isNotEmpty) {
          return plain;
        }
      }
    } catch (_) {}
    return null;
  }

  static Future<String?> _searchLrcLib(String query) async {
    try {
      final uri = Uri.parse('$_lrclibBase/search').replace(queryParameters: {
        'q': query,
      });

      final res = await http.get(uri, headers: {'User-Agent': _userAgent}).timeout(
        const Duration(seconds: 5),
      );
      if (res.statusCode == 200) {
        final list = jsonDecode(res.body) as List<dynamic>;
        for (final item in list) {
          if (item is Map<String, dynamic>) {
            final synced = item['syncedLyrics'] as String?;
            if (synced != null && synced.trim().isNotEmpty) {
              return synced;
            }
          }
        }
      }
    } catch (_) {}
    return null;
  }

  /// Parses LRC string format `[mm:ss.xx] words` 1:1 with Kotlin's [LrcLib.parseLrc].
  static List<LyricLine> parseLrc(String lrc) {
    final all = <LyricLine>[];

    for (final line in const LineSplitter().convert(lrc)) {
      final match = _stampRegex.firstMatch(line);
      if (match == null) continue;

      final minutes = int.tryParse(match.group(1) ?? '0') ?? 0;
      final seconds = int.tryParse(match.group(2) ?? '0') ?? 0;
      final fracStr = match.group(3) ?? '0';

      var fracMs = 0;
      if (fracStr.length == 2) {
        fracMs = (int.tryParse(fracStr) ?? 0) * 10;
      } else if (fracStr.length == 3) {
        fracMs = int.tryParse(fracStr) ?? 0;
      }

      final timeMs = minutes * 60000 + seconds * 1000 + fracMs;
      var text = line.substring(match.end).trim();

      // Clean inline word timestamps `<mm:ss.xx>` if present
      text = text.replaceAll(RegExp(r'<\d{1,2}:\d{2}(?:\.\d{2,3})?>'), '').trim();

      all.add(LyricLine(timeMs: timeMs, text: text));
    }

    all.sort((a, b) => a.timeMs.compareTo(b.timeMs));

    // Keep meaningful gap lines (LrcLib.kt:116)
    final kept = <LyricLine>[];
    for (var i = 0; i < all.length; i++) {
      final line = all[i];
      if (!line.isGap) {
        kept.add(line);
        continue;
      }
      final next = (i + 1 < all.length) ? all[i + 1] : null;
      if (next == null || (next.timeMs - line.timeMs) >= _minGapMs) {
        kept.add(line);
      }
    }

    if (kept.isEmpty) return const [];

    // Add intro gap if first line starts after _minGapMs (LrcLib.kt:126)
    final first = kept.first;
    if (!first.isGap && first.timeMs >= _minGapMs) {
      return [const LyricLine(timeMs: 0, text: ''), ...kept];
    }

    return kept;
  }

  /// Cleans noise like "(Official Music Video)", "Album •", etc. (LrcLib.kt:154).
  static String _clean(String s) {
    return s
        .replaceAll(_noiseRegex, ' ')
        .split(' | ')
        .first
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
  }
}
