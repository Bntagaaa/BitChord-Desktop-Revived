// Original 1.9.2: TtmlLyrics.kt, EnhancedLrc.kt, ProviderLyrics.kt, LrcLib.kt.
import 'dart:convert';
import 'dart:math' as math;
import 'package:xml/xml.dart';
import '../domain/lyrics.dart';

int _stamp(Match m) =>
    int.parse(m[1]!) * 60000 +
    int.parse(m[2]!) * 1000 +
    int.parse(m[3]!) * (m[3]!.length == 2 ? 10 : 1);
final _lineStamp = RegExp(r'\[(\d{1,2}):(\d{2})[.:](\d{2,3})\]');
final _wordStamp = RegExp(r'<(\d{1,3}):(\d{2})[.:](\d{2,3})>');

List<LyricLine> parseLrc(String source) {
  final lines = <LyricLine>[];
  for (final row in const LineSplitter().convert(source)) {
    final mark = _lineStamp.firstMatch(row);
    if (mark == null) continue;
    var body = row.substring(mark.end);
    final side = body.startsWith('<R>')
        ? LyricAlignment.end
        : LyricAlignment.start;
    if (side == LyricAlignment.end) body = body.substring(3);
    final marks = _wordStamp.allMatches(body).toList();
    final runs = [
      for (var i = 0; i < marks.length; i++)
        TimedRun(
          _stamp(marks[i]),
          i + 1 < marks.length ? _stamp(marks[i + 1]) : _stamp(marks[i]),
          body.substring(
            marks[i].end,
            i + 1 < marks.length ? marks[i + 1].start : body.length,
          ),
        ),
    ];
    lines.add(
      LyricLine(
        Duration(milliseconds: _stamp(mark)),
        body.replaceAll(_wordStamp, '').trim(),
        words: wordsFromRuns(runs),
        alignment: side,
      ),
    );
  }
  lines.sort((a, b) => a.time.compareTo(b.time));
  final kept = [
    for (var i = 0; i < lines.length; i++)
      if (!lines[i].isGap ||
          i == lines.length - 1 ||
          lines[i + 1].timeMs - lines[i].timeMs >= 4000)
        lines[i],
  ];
  if (kept.isNotEmpty && !kept.first.isGap && kept.first.timeMs >= 4000) {
    kept.insert(0, LyricLine(Duration.zero, ''));
  }
  return kept;
}

String decodeLyricEntities(String text) => text
    .replaceAllMapped(
      RegExp(r'&#x([0-9a-fA-F]+);'),
      (m) => String.fromCharCode(int.parse(m[1]!, radix: 16)),
    )
    .replaceAllMapped(
      RegExp(r'&#(\d+);'),
      (m) => String.fromCharCode(int.parse(m[1]!)),
    )
    .replaceAll('&apos;', "'")
    .replaceAll('&quot;', '"')
    .replaceAll('&nbsp;', ' ')
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&amp;', '&');

List<LyricLine> parseEnhancedLrc(String source) {
  final line = RegExp(r'^\[(\d{1,3}):(\d{2})[.:](\d{2,3})\](.*)$');
  final word = RegExp(r'<(\d{1,3}):(\d{2})[.:](\d{2,3})>([^<]*)');
  final rows = [
    for (final row in const LineSplitter().convert(source))
      if (line.firstMatch(row.trim()) case final match?)
        (_stamp(match), match[4]!.trim(), word.allMatches(match[4]!).toList()),
  ]..sort((a, b) => a.$1.compareTo(b.$1));
  if (!rows.any((r) => r.$3.isNotEmpty)) return [];
  final lines = <LyricLine>[];
  for (var i = 0; i < rows.length; i++) {
    final row = rows[i], marks = row.$3;
    if (marks.isEmpty) {
      final text = decodeLyricEntities(row.$2);
      if (text.isNotEmpty) {
        lines.add(LyricLine(Duration(milliseconds: row.$1), text));
      }
      continue;
    }
    final end = i + 1 < rows.length ? rows[i + 1].$1 : _stamp(marks.last) + 800;
    final words = wordsFromRuns([
      for (var j = 0; j < marks.length; j++)
        TimedRun(
          _stamp(marks[j]),
          j + 1 < marks.length ? _stamp(marks[j + 1]) : end,
          decodeLyricEntities(marks[j][4]!),
        ),
    ]);
    if (words.isNotEmpty) {
      lines.add(
        LyricLine(
          Duration(milliseconds: math.min(row.$1, words.first.startMs)),
          words.map((w) => w.text).join(' '),
          words: words,
        ),
      );
    }
  }
  return withInstrumentalGaps(lines);
}

int? ttmlTime(String? value) {
  final raw = value?.trim();
  if (raw == null || raw.isEmpty) return null;
  if (raw.endsWith('ms')) {
    return double.tryParse(raw.substring(0, raw.length - 2))?.toInt();
  }
  final stripped = raw.endsWith('s') ? raw.substring(0, raw.length - 1) : raw;
  final parts = stripped.split(':').map(double.tryParse).toList();
  if (parts.isEmpty || parts.length > 3 || parts.contains(null)) return null;
  var seconds = 0.0;
  for (final part in parts) {
    seconds = seconds * 60 + part!;
  }
  return (seconds * 1000).toInt();
}

String _qualified(XmlElement e, String name) {
  final local = name.split(':').last;
  for (final attribute in e.attributes) {
    if (attribute.name.qualified == name || attribute.name.local == local) {
      return attribute.value;
    }
  }
  return '';
}

class _Piece {
  _Piece(this.text, [this.start, this.end]);
  final String text;
  final int? start, end;
}

bool _hasTimedChild(XmlElement element) => element.childElements.any(
  (child) =>
      (child.getAttribute('begin')?.isNotEmpty ?? false) ||
      _hasTimedChild(child),
);
void _collect(XmlNode node, List<_Piece> out, List<_Piece> backing) {
  for (final child in node.children) {
    if (child is XmlElement) {
      final role = _qualified(child, 'ttm:role');
      if (role == 'x-translation' || role == 'x-roman') continue;
      final sink = role == 'x-bg' ? backing : out;
      final begin = ttmlTime(child.getAttribute('begin')),
          end = ttmlTime(child.getAttribute('end'));
      if (begin != null && end != null && !_hasTimedChild(child)) {
        sink.add(_Piece(child.innerText, begin, end));
      } else {
        _collect(child, sink, backing);
      }
    } else if (child is XmlText && child.value.isNotEmpty) {
      out.add(_Piece(child.value));
    }
  }
}

List<LyricWord> _mergePieces(List<_Piece> pieces) {
  final words = <LyricWord>[], syllables = <LyricSyllable>[];
  var current = '', start = 0, end = 0;
  var timed = false;
  void flush() {
    final text = current.trim();
    current = '';
    if (text.isNotEmpty && timed) {
      words.add(
        LyricWord(
          start,
          end,
          text,
          syllables: LyricSyllable.normalized(syllables, text.length),
        ),
      );
    }
    syllables.clear();
    timed = false;
  }

  for (final piece in pieces) {
    if (piece.start == null) {
      if (piece.text.trim().isEmpty) {
        flush();
      } else if (timed) {
        current += piece.text;
      }
    } else {
      if (piece.text.trim().isEmpty) continue;
      if (piece.text[0].trim().isEmpty) flush();
      if (current.isEmpty) start = piece.start!;
      final from = current.length;
      current += piece.text.trim();
      syllables.add(
        LyricSyllable(piece.start!, piece.end!, from, current.length),
      );
      end = piece.end!;
      timed = true;
      if (piece.text[piece.text.length - 1].trim().isEmpty) flush();
    }
  }
  flush();
  return words;
}

List<LyricLine> parseTtml(String source) {
  // Dart's XML reader does not fetch external entities. Match the original
  // document policy as well: external declarations are not lyric content.
  if (RegExp(r'<!DOCTYPE|<!ENTITY', caseSensitive: false).hasMatch(source)) {
    return [];
  }
  try {
    final doc = XmlDocument.parse(source), sung = <(LyricLine, String?)>[];
    final agents = <String, String>{};
    for (final e in doc.descendants.whereType<XmlElement>()) {
      if (e.name.local == 'agent') {
        final id = _qualified(e, 'xml:id'), type = e.getAttribute('type');
        if (id.isNotEmpty && type != null && type.isNotEmpty) agents[id] = type;
      }
      if (e.name.local != 'p') continue;
      final pieces = <_Piece>[], backingPieces = <_Piece>[];
      _collect(e, pieces, backingPieces);
      final words = _mergePieces(pieces),
          backingWords = _mergePieces(backingPieces);
      LyricLine? line;
      if (words.isEmpty) {
        final begin = ttmlTime(e.getAttribute('begin')),
            end = ttmlTime(e.getAttribute('end'));
        if (begin != null && e.innerText.trim().isNotEmpty) {
          line = LyricLine(
            Duration(milliseconds: begin),
            e.innerText.trim(),
            sungUntilMs: end != null && end > begin ? end : null,
          );
        }
      } else {
        final begin = ttmlTime(e.getAttribute('begin')) ?? words.first.startMs;
        line = LyricLine(
          Duration(milliseconds: math.min(begin, words.first.startMs)),
          words.map((w) => w.text).join(' '),
          words: words,
          background: backingWords.isEmpty
              ? null
              : LyricLine(
                  Duration(milliseconds: backingWords.first.startMs),
                  backingWords.map((w) => w.text).join(' '),
                  words: backingWords,
                ),
        );
      }
      if (line != null) sung.add((line, _qualified(e, 'ttm:agent')));
    }
    sung.sort((a, b) => a.$1.time.compareTo(b.$1.time));
    final sides = lineAlignments(sung.map((s) => s.$2).toList(), agents);
    return withInstrumentalGaps([
      for (var i = 0; i < sung.length; i++)
        sung[i].$1.copyWith(alignment: sides[i]),
    ]);
  } on Exception {
    return [];
  }
}

final _karaokeLine = RegExp(r'^\[(\d{1,8}),(\d{1,8})\](.*)$');
final _prefixWord = RegExp(r'\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)');
final _suffixWord = RegExp(r'([^()]*)\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)');
String _karaokeContent(String raw) {
  final match = RegExp(
    r'LyricContent\s*=\s*"([^"]*)"',
    caseSensitive: false,
  ).firstMatch(raw);
  return match == null ? raw : decodeLyricEntities(match[1]!);
}

bool looksLikeKaraoke(String raw) =>
    const LineSplitter().convert(_karaokeContent(raw)).any((row) {
      final match = _karaokeLine.firstMatch(row.trim());
      return match != null &&
          (_prefixWord.hasMatch(match[3]!) || _suffixWord.hasMatch(match[3]!));
    });
List<LyricLine> parseKaraoke(String raw) {
  final rows = <LyricLine>[];
  for (final source in const LineSplitter().convert(_karaokeContent(raw))) {
    final match = _karaokeLine.firstMatch(source.trim());
    if (match == null) continue;
    final start = int.parse(match[1]!),
        duration = int.parse(match[2]!),
        body = match[3]!;
    TimedRun run(String text, String start, String duration) => TimedRun(
      int.parse(start),
      int.parse(start) + int.parse(duration),
      decodeLyricEntities(text),
    );
    final prefixed = wordsFromRuns(
      _prefixWord
          .allMatches(body)
          .map((m) => run(m[3]!, m[1]!, m[2]!))
          .toList(),
    );
    final suffixed = wordsFromRuns(
      _suffixWord
          .allMatches(body)
          .map((m) => run(m[1]!, m[2]!, m[3]!))
          .toList(),
    );
    final words =
        prefixed.fold<int>(0, (v, w) => v + w.text.length) >=
            suffixed.fold<int>(0, (v, w) => v + w.text.length)
        ? prefixed
        : suffixed;
    final text = decodeLyricEntities(
      body.replaceAll(RegExp(r'\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)'), ''),
    ).trim();
    if (words.isEmpty || text.isEmpty) continue;
    rows.add(
      LyricLine(
        Duration(milliseconds: math.min(start, words.first.startMs)),
        text,
        words: words,
        sungUntilMs: duration > 0 ? start + duration : null,
      ),
    );
  }
  rows.sort((a, b) => a.time.compareTo(b.time));
  return withInstrumentalGaps(rows);
}

String? _extract(dynamic value) {
  if (value is String) {
    final text = value.trim();
    try {
      final nested = jsonDecode(text);
      if (nested is Map || nested is List) return _extract(nested);
    } on FormatException {
      /* Already lyric text. */
    }
    return text;
  }
  if (value is List) {
    final text = value.map(_extract).whereType<String>().join('\n');
    return text.trim().isEmpty ? null : text;
  }
  if (value is Map) {
    if (value['isError'] == true ||
        value['ok'] == false ||
        (value['error'] != null &&
            value['error'] != false &&
            value['error'] != '')) {
      return null;
    }
    for (final key in [
      'ttml',
      'ttmlContent',
      'lyrics',
      'lrc',
      'content',
      'text',
      'plainLyrics',
      'syncedLyrics',
      'line',
      'lines',
      'lyric',
      'data',
      'result',
      'response',
      'metadata',
      'words',
    ]) {
      final text = _extract(value[key]);
      if (text != null) return text;
    }
  }
  return null;
}

String? unwrapLyrics(String raw) {
  var value = raw.replaceAll('\uFEFF', '').trim();
  if (value.startsWith('```')) {
    final lines = const LineSplitter().convert(value).skip(1).toList();
    if (lines.lastOrNull?.trim() == '```') lines.removeLast();
    value = lines.join('\n').trim();
  }
  if (value.isEmpty) return null;
  try {
    return _extract(jsonDecode(value))?.trim();
  } on FormatException {
    return value;
  }
}

List<LyricLine> parseProviderLyrics(String raw) {
  var content = unwrapLyrics(raw);
  if (content == null || content.isEmpty) return [];
  if (content.toLowerCase().contains('&lt;tt')) {
    content = decodeLyricEntities(content);
  }
  if (RegExp(
    r'<tt(?:\s|>)|http://www.w3.org/ns/ttml',
    caseSensitive: false,
  ).hasMatch(content)) {
    return parseTtml(content);
  }
  if (looksLikeKaraoke(content)) return parseKaraoke(content);
  if (content.trimLeft().startsWith('<')) return [];
  final enhanced = parseEnhancedLrc(content);
  if (enhanced.isNotEmpty) return enhanced;
  final lrc = parseLrc(content);
  if (lrc.isNotEmpty) return lrc;
  if (RegExp(
    r'\b(?:lyrics? (?:not found|unavailable)|error)\b',
    caseSensitive: false,
  ).hasMatch(content)) {
    return [];
  }
  return [
    for (final row in const LineSplitter().convert(content))
      if (row.trim().isNotEmpty &&
          !RegExp(r'^\[[A-Za-z]+:.*\]$').hasMatch(row.trim()))
        LyricLine(Duration.zero, row.trim()),
  ];
}
