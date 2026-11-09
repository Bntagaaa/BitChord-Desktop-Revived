import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:xml/xml.dart';

import '../domain/models.dart';
import 'innertube_client.dart';
import 'innertube_parser.dart';

class LyricsService {
  LyricsService({required this.innertube, http.Client? httpClient})
    : _http = httpClient ?? http.Client(),
      _ownsClient = httpClient == null;
  final InnertubeClient innertube;
  final http.Client _http;
  final bool _ownsClient;
  final _cache = <String, LyricsDocument>{};
  static const offeredProviders = [
    'lrc.red',
    'BiniLyrics',
    'BetterLyrics',
    'BetterLyrics Portato',
    'LyricsPlus',
    'Unison',
    'YouTube captions',
    'YouTube Music',
    'LRCLIB',
  ];

  Future<LyricsDocument?> fetch(Song song, {List<String>? providers}) async {
    final selected = providers ?? offeredProviders;
    final key = '${song.videoId}:${song.durationText}:${selected.join(',')}';
    final cached = _cache.remove(key);
    if (cached != null) {
      _cache[key] = cached;
      return cached;
    }
    // Disabled and hidden providers are never contacted. All launched lookups
    // have bounded timeouts, while selection follows the requested priority.
    final jobs = selected.where(offeredProviders.contains).map((name) async {
      try {
        return await _provider(name, song).timeout(const Duration(seconds: 12));
      } on Exception {
        return null;
      }
    }).toList();
    for (final job in jobs) {
      final result = await job;
      if (result == null || (result.lines.isEmpty && !result.isInstrumental)) {
        continue;
      }
      _cache[key] = result;
      while (_cache.length > 20) {
        _cache.remove(_cache.keys.first);
      }
      return result;
    }
    return null;
  }

  Future<LyricsDocument?> _provider(String name, Song song) async {
    final title = cleanTitle(song.title);
    final artist = song.artist;
    final seconds = song.duration.inSeconds;
    switch (name) {
      case 'LRCLIB':
        final exact = await _json(
          Uri.https('lrclib.net', '/api/get', {
            'track_name': title,
            'artist_name': artist,
            if (seconds > 0) 'duration': '$seconds',
            if (song.albumName?.isNotEmpty == true)
              'album_name': song.albumName!,
          }),
        );
        final direct = _lrclibDocument(InnertubeParser.object(exact));
        if (direct != null) return direct;
        final hits =
            InnertubeParser.array(
                  await _json(
                    Uri.https('lrclib.net', '/api/search', {
                      'track_name': title,
                      'artist_name': artist,
                    }),
                  ),
                )
                .map(InnertubeParser.object)
                .where(
                  (h) =>
                      h['syncedLyrics'] is String || h['plainLyrics'] is String,
                )
                .toList();
        hits.sort(
          (a, b) =>
              (((a['duration'] as num?) ?? seconds) - seconds).abs().compareTo(
                (((b['duration'] as num?) ?? seconds) - seconds).abs(),
              ),
        );
        return hits.isEmpty ? null : _lrclibDocument(hits.first);
      case 'BetterLyrics':
      case 'BetterLyrics Portato':
        final path = name == 'BetterLyrics' ? '/getLyrics' : '/qq/getLyrics';
        final raw = await _get(
          Uri.https('lyrics-api.boidu.dev', path, {
            's': title,
            'a': artist,
            if (seconds > 0) 'd': '$seconds',
            if (song.albumName != null) 'al': song.albumName!,
          }),
        );
        return raw == null ? null : parseProvider(raw, source: name);
      case 'BiniLyrics':
        final response = InnertubeParser.object(
          await _json(
            Uri.https('lyrics-api.binimum.org', '/', {
              'track': title,
              'artist': artist,
              if (seconds > 0) 'duration': '$seconds',
              if (song.albumName != null) 'album': song.albumName!,
            }),
          ),
        );
        final hit = InnertubeParser.object(
          InnertubeParser.array(response['results']).firstOrNull,
        );
        final url = Uri.tryParse(hit['lyricsUrl'] as String? ?? '');
        // This provider points to lrc.red documents; do not follow arbitrary
        // URLs injected into a third-party metadata response.
        if (url == null || url.scheme != 'https' || url.host != 'lrc.red') {
          return null;
        }
        final raw = await _get(url);
        return raw == null ? null : parseProvider(raw, source: name);
      case 'lrc.red':
        final response = InnertubeParser.object(
          await _json(
            Uri.https('lrc.red', '/search.json', {'q': '$title $artist'}),
          ),
        );
        final hits = InnertubeParser.array(response['hits'])
            .map(InnertubeParser.object)
            .where((h) {
              final duration = (h['duration'] as num?)?.toDouble();
              return _normalized(cleanTitle(h['title'] as String? ?? '')) ==
                      _normalized(title) &&
                  _sameVersion(h['title'] as String? ?? '', song.title) &&
                  _artistMatches(h['artist'] as String? ?? '', artist) &&
                  (seconds <= 0 ||
                      duration == null ||
                      (duration - seconds).abs() <= 3) &&
                  RegExp(
                    r'^[A-Z]{2}[A-Z0-9]{3}\d{7}$',
                  ).hasMatch(h['isrc'] as String? ?? '');
            })
            .toList();
        if (hits.isEmpty) return null;
        hits.sort(
          (a, b) =>
              (((a['duration'] as num?) ?? seconds) - seconds).abs().compareTo(
                (((b['duration'] as num?) ?? seconds) - seconds).abs(),
              ),
        );
        final raw = await _get(
          Uri.https('lrc.red', '/s/${hits.first['isrc']}.ttml'),
        );
        return raw == null ? null : parseProvider(raw, source: name);
      case 'Unison':
        final raw = await _get(
          Uri.https('unison.boidu.dev', '/lyrics', {
            'song': title,
            'artist': artist,
            if (seconds > 0) 'duration': '$seconds',
            if (song.albumName != null) 'album': song.albumName!,
          }),
        );
        return raw == null ? null : parseProvider(raw, source: name);
      case 'LyricsPlus':
        for (final host in [
          'lyricsplus.prjktla.my.id',
          'lyricsplus.atomix.one',
          'lyricsplus.binimum.org',
        ]) {
          final response = InnertubeParser.object(
            await _json(
              Uri.https(host, '/v2/lyrics/get', {
                'title': title,
                'artist': artist,
                if (seconds > 0) 'duration': '$seconds',
                if (song.albumName != null) 'album': song.albumName!,
              }),
            ),
          );
          final rows = InnertubeParser.array(response['lyrics']);
          final lines = <LyricLine>[];
          for (final row in rows) {
            final line = InnertubeParser.object(row);
            final start = (line['time'] as num?)?.toInt();
            if (start == null) continue;
            final syllables = InnertubeParser.array(line['syllabus']);
            final words = <LyricWord>[];
            for (final s in syllables.map(InnertubeParser.object)) {
              final time = (s['time'] as num?)?.toInt();
              final text = s['text'] as String?;
              if (time == null || text == null) continue;
              words.add(
                LyricWord(
                  text: text,
                  start: Duration(milliseconds: time),
                  end: Duration(
                    milliseconds:
                        time + ((s['duration'] as num?)?.toInt() ?? 0),
                  ),
                ),
              );
            }
            final text =
                line['text'] as String? ?? words.map((w) => w.text).join();
            if (text.trim().isEmpty) continue;
            lines.add(
              LyricLine(
                text: text.trim(),
                start: Duration(milliseconds: start),
                words: words,
                end: line['duration'] is num
                    ? Duration(
                        milliseconds: start + (line['duration'] as num).toInt(),
                      )
                    : null,
              ),
            );
          }
          if (lines.isNotEmpty) {
            lines.sort((a, b) => a.start.compareTo(b.start));
            return LyricsDocument(lines: lines, isSynced: true, source: name);
          }
        }
        return null;
      case 'YouTube Music':
        if (!song.isYouTube) return null;
        final next = await innertube.post('next', {
          'videoId': song.videoId,
          'isAudioOnly': true,
        });
        final tabs = InnertubeParser.collect(next, 'tabRenderer').toList();
        final lyricsTab =
            tabs
                .where(
                  (t) =>
                      InnertubeParser.text(t['title']).toLowerCase() ==
                      'lyrics',
                )
                .firstOrNull ??
            tabs
                .skip(1)
                .where((t) => InnertubeParser.findString(t, 'browseId') != null)
                .firstOrNull;
        final id = InnertubeParser.findString(lyricsTab, 'browseId');
        if (id == null) return null;
        final page = await innertube.browse(
          id,
          params: InnertubeParser.findString(lyricsTab, 'params'),
        );
        final shelf = InnertubeParser.collect(
          page,
          'musicDescriptionShelfRenderer',
        ).firstOrNull;
        final text = InnertubeParser.text(shelf?['description']);
        return text.isEmpty ? null : plain(text, source: name);
      case 'YouTube captions':
        if (!song.isYouTube) return null;
        final idBytes = utf8.encode(song.videoId);
        final raw = await innertube.post('get_transcript', {
          'params': base64Encode([10, idBytes.length, ...idBytes]),
        });
        final lines =
            InnertubeParser.collect(raw, 'transcriptCueRenderer')
                .map((cue) {
                  final start = int.tryParse('${cue['startOffsetMs']}');
                  final text = InnertubeParser.text(cue['cue']).trim();
                  if (start == null || text.isEmpty) return null;
                  return LyricLine(
                    text: text,
                    start: Duration(milliseconds: start),
                  );
                })
                .whereType<LyricLine>()
                .toList()
              ..sort((a, b) => a.start.compareTo(b.start));
        return lines.isEmpty
            ? null
            : LyricsDocument(lines: lines, isSynced: true, source: name);
      default:
        return null;
    }
  }

  Future<String?> _get(Uri uri) async {
    final response = await _http
        .get(
          uri,
          headers: {
            'User-Agent': 'BitChord-Desktop-Revived/1.9.2 Flutter',
            'Accept': 'application/json,text/plain,application/ttml+xml',
          },
        )
        .timeout(const Duration(seconds: 6));
    if (response.statusCode < 200 || response.statusCode >= 300) return null;
    if (response.bodyBytes.length > 2 * 1024 * 1024) {
      throw const ServiceException('Lyrics response exceeds the size limit.');
    }
    return utf8.decode(response.bodyBytes, allowMalformed: true);
  }

  Future<Object?> _json(Uri uri) async {
    final raw = await _get(uri);
    if (raw == null) return null;
    try {
      return jsonDecode(raw);
    } on FormatException {
      return null;
    }
  }

  static LyricsDocument? _lrclibDocument(JsonMap value) {
    if (value['instrumental'] == true) {
      return const LyricsDocument(isInstrumental: true, source: 'LRCLIB');
    }
    final synced = value['syncedLyrics'] as String?;
    if (synced?.isNotEmpty == true) return parseLrc(synced!, source: 'LRCLIB');
    final unsynced = value['plainLyrics'] as String?;
    return unsynced?.isNotEmpty == true
        ? plain(unsynced!, source: 'LRCLIB')
        : null;
  }

  static final _stamp = RegExp(r'\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]');
  static final _wordStamp = RegExp(r'<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>');
  static Duration _stampTime(RegExpMatch match) => Duration(
    minutes: int.parse(match[1]!),
    seconds: int.parse(match[2]!),
    milliseconds: int.tryParse((match[3] ?? '').padRight(3, '0')) ?? 0,
  );

  static LyricsDocument parseLrc(String raw, {String source = 'LRC'}) {
    final rows = <LyricLine>[];
    final offset =
        int.tryParse(
          RegExp(
                r'\[offset:([+-]?\d+)\]',
                caseSensitive: false,
              ).firstMatch(raw)?[1] ??
              '',
        ) ??
        0;
    for (final line in _decodeEntities(raw).split('\n')) {
      final stamps = _stamp.allMatches(line).toList();
      if (stamps.isEmpty) continue;
      final body = line.substring(stamps.last.end);
      final marks = _wordStamp.allMatches(body).toList();
      final words = <LyricWord>[];
      for (var i = 0; i < marks.length; i++) {
        final word = body.substring(
          marks[i].end,
          i + 1 < marks.length ? marks[i + 1].start : body.length,
        );
        if (word.trim().isEmpty) continue;
        final start = _stampTime(marks[i]);
        words.add(
          LyricWord(
            text: word,
            start: start,
            end: i + 1 < marks.length
                ? _stampTime(marks[i + 1])
                : start + const Duration(milliseconds: 800),
          ),
        );
      }
      final text = body
          .replaceAll(_wordStamp, '')
          .replaceFirst('<R>', '')
          .trim();
      for (final stamp in stamps) {
        rows.add(
          LyricLine(
            text: text,
            start: _stampTime(stamp),
            words: words,
            alignment: body.startsWith('<R>')
                ? LyricAlignment.end
                : LyricAlignment.start,
          ),
        );
      }
    }
    rows.sort((a, b) => a.start.compareTo(b.start));
    return LyricsDocument(
      lines: rows,
      isSynced: rows.isNotEmpty,
      source: source,
      offset: Duration(milliseconds: offset),
    );
  }

  static LyricsDocument plain(String text, {required String source}) =>
      LyricsDocument(
        lines: text
            .split('\n')
            .map((s) => s.trim())
            .where(
              (s) => s.isNotEmpty && !RegExp(r'^\[[A-Za-z]+:.*\]$').hasMatch(s),
            )
            .map((s) => LyricLine(text: s))
            .toList(),
        source: source,
      );

  static LyricsDocument? parseProvider(String raw, {required String source}) {
    String? extract(Object? value, [int depth = 0]) {
      if (depth > 8) return null;
      if (value is String) {
        final trimmed = value.trim();
        if (trimmed.startsWith('{') ||
            trimmed.startsWith('[') && !trimmed.startsWith('[0')) {
          try {
            return extract(jsonDecode(trimmed), depth + 1);
          } on FormatException {
            /* lyric text */
          }
        }
        return trimmed.isEmpty ? null : trimmed;
      }
      if (value is List) {
        final parts = value
            .map((v) => extract(v, depth + 1))
            .whereType<String>()
            .toList();
        return parts.isEmpty ? null : parts.join('\n');
      }
      // Missing fields are leaves. Recursing through an empty map for every
      // absent provider key creates an exponential walk of null values.
      if (value is! Map) return null;
      final map = InnertubeParser.object(value);
      if (map.isEmpty) return null;
      if (map['isError'] == true ||
          map['ok'] == false ||
          map['success'] == false ||
          (map['error'] != null &&
              map['error'] != false &&
              map['error'] != '')) {
        return null;
      }
      for (final key in [
        'ttml',
        'ttmlContent',
        'richSyncLyrics',
        'syncedLyrics',
        'lyrics',
        'lrc',
        'content',
        'text',
        'plainLyrics',
        'line',
        'lines',
        'lyric',
        'data',
        'result',
        'response',
      ]) {
        final result = extract(map[key], depth + 1);
        if (result != null) return result;
      }
      return null;
    }

    final unwrapped = extract(raw);
    if (unwrapped == null) return null;
    final text = _decodeEntities(unwrapped);
    if (text.contains('<tt') || text.contains('http://www.w3.org/ns/ttml')) {
      return parseTtml(text, source: source);
    }
    if (text.trimLeft().startsWith('<')) return null;
    final lrc = parseLrc(text, source: source);
    if (lrc.lines.any((l) => l.text.isNotEmpty)) return lrc;
    final karaoke = parseKaraoke(text, source: source);
    if (karaoke.lines.isNotEmpty) return karaoke;
    if (RegExp(
      r'lyrics? (not found|unavailable)|^error',
      caseSensitive: false,
    ).hasMatch(text)) {
      return null;
    }
    return plain(text, source: source);
  }

  static LyricsDocument parseTtml(String text, {String source = 'TTML'}) {
    final document = XmlDocument.parse(text);
    final rows = <LyricLine>[];
    for (final paragraph in document.descendants.whereType<XmlElement>().where(
      (e) => e.name.local == 'p',
    )) {
      final spans = paragraph.descendants
          .whereType<XmlElement>()
          .where(
            (e) => e.name.local == 'span' && e.getAttribute('begin') != null,
          )
          .toList();
      final words = <LyricWord>[];
      for (final span in spans) {
        if (span.children.whereType<XmlElement>().any(
          (e) => e.getAttribute('begin') != null,
        )) {
          continue;
        }
        final begin = parseTtmlTime(span.getAttribute('begin'));
        final end = parseTtmlTime(span.getAttribute('end'));
        if (begin == null || span.innerText.isEmpty) continue;
        words.add(
          LyricWord(
            text: span.innerText,
            start: begin,
            end: end ?? begin + const Duration(milliseconds: 800),
          ),
        );
      }
      final start =
          parseTtmlTime(paragraph.getAttribute('begin')) ??
          words.firstOrNull?.start;
      if (start == null || paragraph.innerText.trim().isEmpty) continue;
      final agent = paragraph.attributes
          .where((a) => a.name.local == 'agent')
          .firstOrNull
          ?.value;
      rows.add(
        LyricLine(
          text: paragraph.innerText.trim(),
          start: start,
          end: parseTtmlTime(paragraph.getAttribute('end')),
          words: words,
          alignment: agent == 'v2' ? LyricAlignment.end : LyricAlignment.start,
        ),
      );
    }
    rows.sort((a, b) => a.start.compareTo(b.start));
    return LyricsDocument(lines: rows, isSynced: true, source: source);
  }

  static Duration? parseTtmlTime(String? input) {
    if (input == null) return null;
    if (input.endsWith('ms')) {
      final n = double.tryParse(input.substring(0, input.length - 2));
      return n == null ? null : Duration(microseconds: (n * 1000).round());
    }
    if (input.endsWith('s')) {
      final n = double.tryParse(input.substring(0, input.length - 1));
      return n == null ? null : Duration(microseconds: (n * 1000000).round());
    }
    var seconds = 0.0;
    for (final part in input.split(':')) {
      final n = double.tryParse(part);
      if (n == null) return null;
      seconds = seconds * 60 + n;
    }
    return Duration(microseconds: (seconds * 1000000).round());
  }

  static LyricsDocument parseKaraoke(
    String input, {
    String source = 'Karaoke',
  }) {
    final pattern = RegExp(r'^\[(\d+),(\d+)\](.*)$');
    final timing = RegExp(r'\((\d+),(\d+)(?:,\d+)?\)');
    final rows = <LyricLine>[];
    for (final raw in input.split('\n')) {
      final line = pattern.firstMatch(raw.trim());
      if (line == null) continue;
      final body = line[3]!;
      final marks = timing.allMatches(body).toList();
      final prefix = body.startsWith('(');
      final words = <LyricWord>[];
      for (var i = 0; i < marks.length; i++) {
        final mark = marks[i];
        final text = prefix
            ? body.substring(
                mark.end,
                i + 1 < marks.length ? marks[i + 1].start : body.length,
              )
            : body.substring(i == 0 ? 0 : marks[i - 1].end, mark.start);
        if (text.isEmpty) continue;
        final start = int.parse(mark[1]!);
        words.add(
          LyricWord(
            text: text,
            start: Duration(milliseconds: start),
            end: Duration(milliseconds: start + int.parse(mark[2]!)),
          ),
        );
      }
      if (words.isEmpty) continue;
      rows.add(
        LyricLine(
          text: body.replaceAll(timing, '').trim(),
          start: Duration(milliseconds: int.parse(line[1]!)),
          end: Duration(
            milliseconds: int.parse(line[1]!) + int.parse(line[2]!),
          ),
          words: words,
        ),
      );
    }
    return LyricsDocument(lines: rows, isSynced: true, source: source);
  }

  Future<LyricsDocument> translate(
    LyricsDocument document,
    String language, {
    bool romanize = false,
  }) async {
    if (!RegExp(r'^[a-z]{2,3}(?:-[A-Za-z]{2,4})?$').hasMatch(language)) {
      throw ArgumentError('Invalid translation language.');
    }
    final transformed = <int, String>{};
    final lines = document.lines;
    var index = 0;
    while (index < lines.length) {
      final selected = <int>[];
      var payload = '';
      while (index < lines.length) {
        final text = lines[index].text;
        final piece = '\uE000${index.toString().padLeft(4, '0')}\uE001$text\n';
        if (payload.isNotEmpty && payload.length + piece.length > 3000) break;
        if (text.isNotEmpty) {
          selected.add(index);
          payload += piece;
        }
        index++;
      }
      if (selected.isEmpty) continue;
      final response = await _http
          .post(
            Uri.https('translate.googleapis.com', '/translate_a/single'),
            headers: {'User-Agent': 'BitChord-Desktop-Revived/1.9.2'},
            body: {
              'client': 'dict-chrome-ex',
              'sl': 'auto',
              'tl': language,
              'dt': romanize ? 'rm' : 't',
              'q': payload,
            },
          )
          .timeout(const Duration(seconds: 15));
      if (response.statusCode != 200) {
        throw const ServiceException(
          'The lyrics translation service is unavailable.',
        );
      }
      final root = InnertubeParser.array(jsonDecode(response.body));
      final translated = InnertubeParser.array(root.firstOrNull).map((segment) {
        final parts = InnertubeParser.array(segment);
        final offset = romanize ? 3 : 0;
        return parts.length > offset ? parts[offset] as String? ?? '' : '';
      }).join();
      final marker = RegExp('\uE000(\\d{4,})\uE001');
      final matches = marker.allMatches(translated).toList();
      for (var i = 0; i < matches.length; i++) {
        final id = int.parse(matches[i][1]!);
        if (!selected.contains(id)) continue;
        transformed[id] = translated
            .substring(
              matches[i].end,
              i + 1 < matches.length ? matches[i + 1].start : translated.length,
            )
            .trim();
      }
      if (selected.any((i) => transformed[i]?.isNotEmpty != true)) {
        throw const ServiceException(
          'Translation could not preserve lyric line alignment.',
        );
      }
    }
    return LyricsDocument(
      source: document.source,
      isSynced: document.isSynced,
      isInstrumental: document.isInstrumental,
      offset: document.offset,
      lines: List.generate(
        lines.length,
        (i) => LyricLine(
          text: lines[i].text,
          start: lines[i].start,
          end: lines[i].end,
          words: lines[i].words,
          alignment: lines[i].alignment,
          translation: romanize ? lines[i].translation : transformed[i],
          romanization: romanize ? transformed[i] : lines[i].romanization,
        ),
      ),
    );
  }

  static String cleanTitle(String title) => title
      .replaceAll(
        RegExp(
          r'\((?:from|feat\.?|official|lyrical|video|audio)[^)]*\)|\[[^\]]*\]|\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b',
          caseSensitive: false,
        ),
        ' ',
      )
      .split(' | ')
      .first
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
  static String _normalized(String input) => input
      .toLowerCase()
      .replaceAll(RegExp(r'[^\p{L}\p{N}]+', unicode: true), ' ')
      .trim();
  static bool _artistMatches(String found, String wanted) {
    final parts = wanted
        .split(RegExp(r',|&|\bfeat\.?|\bwith\b', caseSensitive: false))
        .map(_normalized)
        .where((s) => s.isNotEmpty);
    final actual = _normalized(found);
    return parts.isEmpty || parts.any(actual.contains);
  }

  static bool _sameVersion(String a, String b) {
    Set<String> versions(String value) => RegExp(
      r'\b(live|remix|acoustic|instrumental|karaoke|sped|slowed|demo)\b',
      caseSensitive: false,
    ).allMatches(value).map((m) => m[1]!.toLowerCase()).toSet();
    final first = versions(a), second = versions(b);
    return first.length == second.length && first.containsAll(second);
  }

  static String _decodeEntities(String value) => value
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&quot;', '"')
      .replaceAll('&#39;', "'")
      .replaceAll('&apos;', "'")
      .replaceAll('&amp;', '&');
  void clearCache() => _cache.clear();
  void close() {
    _cache.clear();
    if (_ownsClient) _http.close();
  }
}
