// Direct port of LyricLine.kt, SyllableRuns.kt, LyricGaps.kt,
// LyricAlignments.kt and BackgroundVocals.kt at original 1.9.2 (348b613a).
import 'dart:math' as math;

enum LyricAlignment { start, end }

class LyricSyllable {
  const LyricSyllable(this.startMs, this.endMs, this.startChar, this.endChar);
  final int startMs, endMs, startChar, endChar;

  static List<LyricSyllable> normalized(List<LyricSyllable> parts, int length) {
    final usable = parts.where((p) => p.endChar > p.startChar && p.startChar < length).toList()
      ..sort((a, b) => a.startChar.compareTo(b.startChar));
    if (usable.length < 2) return const [];
    return [for (var i = 0; i < usable.length; i++)
      LyricSyllable(usable[i].startMs, math.max(usable[i].startMs, usable[i].endMs),
        i == 0 ? 0 : usable[i].startChar,
        i + 1 < usable.length ? usable[i + 1].startChar : length)];
  }
}

class LyricWord {
  const LyricWord(this.startMs, this.endMs, this.text, {this.syllables = const []});
  final int startMs, endMs;
  final String text;
  final List<LyricSyllable> syllables;

  double charsSungAt(int positionMs) {
    if (positionMs <= startMs) return 0;
    if (positionMs >= endMs) return text.length.toDouble();
    if (syllables.isEmpty) return (positionMs - startMs) / math.max(1, endMs - startMs) * text.length;
    for (final part in syllables) {
      if (positionMs < part.startMs) return part.startChar.toDouble();
      if (positionMs < part.endMs) {
        return part.startChar + (positionMs - part.startMs) / math.max(1, part.endMs - part.startMs) * (part.endChar - part.startChar);
      }
    }
    return text.length.toDouble();
  }

  LyricWord? withoutChars(bool Function(String) drop) {
    if (!text.split('').any(drop)) return this;
    final moved = List<int>.filled(text.length + 1, 0);
    final kept = StringBuffer();
    for (var i = 0; i < text.length; i++) {
      moved[i] = kept.length;
      if (!drop(text[i])) kept.write(text[i]);
    }
    moved[text.length] = kept.length;
    final value = kept.toString();
    final trimmed = value.trim();
    if (trimmed.isEmpty) return null;
    final lead = value.indexOf(trimmed);
    return LyricWord(startMs, endMs, trimmed, syllables: LyricSyllable.normalized([
      for (final part in syllables) LyricSyllable(part.startMs, part.endMs,
        math.max(0, moved[part.startChar] - lead), math.min(trimmed.length, moved[part.endChar] - lead)),
    ], trimmed.length));
  }
}

class LyricLine {
  LyricLine(this.time, this.text, {this.words = const [], this.sungUntilMs,
    this.background, this.alignment = LyricAlignment.start, this.timingSource});
  final Duration time;
  final String text;
  final List<LyricWord> words;
  final int? sungUntilMs;
  final LyricLine? background, timingSource;
  final LyricAlignment alignment;
  int get timeMs => time.inMilliseconds;
  bool get isGap => text.isEmpty;
  bool get isWordSynced => words.isNotEmpty;
  bool get hasKnownEnd => words.isNotEmpty || sungUntilMs != null;
  int get endMs {
    final lead = words.lastOrNull?.endMs ?? sungUntilMs ?? timeMs;
    return math.max(lead, background?.endMs ?? lead);
  }

  LyricLine copyWith({String? text, List<LyricWord>? words, LyricLine? background,
    LyricAlignment? alignment, LyricLine? timingSource}) => LyricLine(time,
    text ?? this.text, words: words ?? this.words, sungUntilMs: sungUntilMs,
    background: background ?? this.background, alignment: alignment ?? this.alignment,
    timingSource: timingSource ?? this.timingSource);

  late final List<(int, int)> wordSpans = _wordSpans();
  List<(int, int)> _wordSpans() {
    var offset = 0;
    return [for (final word in words) (() {
      final found = text.indexOf(word.text, math.min(offset, text.length));
      final start = found < 0 ? offset : found;
      offset = start + word.text.length;
      return (start, offset);
    })()];
  }

  late final List<(int, int)> sweepSpans = [for (var i = 0; i < words.length; i++)
    if (words[i].syllables.isEmpty) wordSpans[i]
    else ...[for (final part in words[i].syllables)
      (wordSpans[i].$1 + part.startChar, wordSpans[i].$1 + part.endChar)]];
  late final List<GrowingWord> growingWords = [for (var i = 0; i < words.length; i++)
    if (_canGrow(words[i])) GrowingWord(i, words[i])];
  GrowingWord? growingAt(int index) => growingWords.where((w) => w.index == index).firstOrNull;
  bool isGrowing(int positionMs) => growingWords.any((w) => positionMs >= w.startMs && positionMs <= w.restsAtMs);
  bool isLifted(int positionMs) => words.isNotEmpty && positionMs > words.first.startMs &&
    (positionMs < words.last.endMs + 700 || isGrowing(positionMs));
  int get animatesFromMs => math.min(timeMs, words.firstOrNull?.startMs ?? timeMs);
  int get animatesUntilMs => math.max(endMs, math.max(words.isEmpty ? endMs : words.last.endMs + 700,
    growingWords.fold<int>(0, (v, w) => math.max(v, w.restsAtMs))));

  double wordLift(int index, int positionMs) {
    if (index < 0 || index >= words.length) return 0;
    final word = words[index];
    final riseMs = (word.endMs - word.startMs).clamp(250, 700);
    final rising = ((positionMs - word.startMs) / riseMs).clamp(0.0, 1.0);
    final falling = (1 - (positionMs - word.endMs) / 700).clamp(0.0, 1.0);
    return _liftHeight(word) * _smooth(math.min(rising, falling));
  }
  double wordFall(int index, int positionMs) {
    if (index < 0 || index >= words.length) return 0;
    return _liftHeight(words[index]) * _smooth((1 - (positionMs - words[index].endMs) / 700).clamp(0.0, 1.0));
  }
  double revealedChars(int positionMs) {
    final source = timingSource;
    if (source != null) return source.text.isEmpty ? 0 :
      (source.revealedChars(positionMs) / source.text.length).clamp(0.0, 1.0) * text.length;
    if (words.isEmpty) return positionMs >= timeMs ? text.length.toDouble() : 0;
    for (var i = 0; i < words.length; i++) {
      final word = words[i]; final start = wordSpans[i].$1; final end = wordSpans[i].$2;
      if (positionMs < word.startMs) return start.toDouble();
      if (positionMs < word.endMs) return start + word.charsSungAt(positionMs);
      if (i + 1 < words.length && positionMs < words[i + 1].startMs) {
        return end + (positionMs - word.endMs) / math.max(1, words[i + 1].startMs - word.endMs) * (wordSpans[i + 1].$1 - end);
      }
    }
    return text.length.toDouble();
  }
}

class CharGrowth {
  double scale = 1, shift = 0, rise = 0, bloom = 0;
}

class GrowingWord {
  GrowingWord(this.index, LyricWord word) : startMs = word.startMs, endMs = word.endMs {
    final chars = word.text.length;
    final held = math.max(1, endMs - startMs).toDouble();
    final earned = math.pow(((held - 400) / 2600).clamp(0.0, 1.0), 3).toDouble();
    var decay = 0.0;
    if (chars > 5) decay += math.min((chars - 5) / 5, 1) * .4;
    if (held < 1200) decay += math.max(0, 1 - (held - 800) / 400) * (chars > 3 ? .3 : .1);
    decay = math.min(decay, .7);
    final bloomPace = math.min(1.1, held / 1500);
    final bloomSpread = chars <= 3 ? .85 : (chars >= 6 ? 1.1 : 1.0);
    final base = chars <= 3 ? .05 : .04;
    final liftPace = (held / 2000).clamp(.3, 1.0);
    for (var i = 0; i < chars; i++) {
      final place = chars > 1 ? i / (chars - 1) : 0.0;
      final reach = earned * (1 - place * decay);
      final scale = 1 + base + reach * .08;
      _scale.add(scale * .98);
      _bloom.add((.35 + reach * .45) * bloomPace * bloomSpread);
      _rise.add(((scale - 1) / .1) * liftPace);
      _shift.add(((i + .5) / chars - .5) * 2 * (scale - 1) * (25 / 34) * .98);
    }
    restsAtMs = startMs + (held * (math.max(0, chars - 1) * .09 + 1.5)).toInt();
  }
  final int index, startMs, endMs;
  late final int restsAtMs;
  final List<double> _scale = [], _shift = [], _rise = [], _bloom = [];
  void sampleInto(int charIndex, int positionMs, CharGrowth into) {
    final span = math.max(1, endMs - startMs);
    final elapsed = positionMs - startMs - charIndex * span * .09;
    final phase = (elapsed / (span * 1.5)).clamp(0.0, 1.0);
    final scale = _scale[charIndex];
    if (phase < .25) {
      final t = _smooth(phase / .25);
      into..scale = 1 + (scale - 1) * t..shift = _shift[charIndex] * t
        ..rise = _rise[charIndex] * t..bloom = _bloom[charIndex] * t;
    } else if (phase < .30) {
      into..scale = scale..shift = _shift[charIndex]..rise = _rise[charIndex]..bloom = _bloom[charIndex];
    } else if (phase < .75) {
      final t = _smooth((phase - .30) / .45);
      into..scale = scale + (1 - scale) * t..shift = _shift[charIndex] * (1 - t)
        ..rise = _rise[charIndex] + (1 - _rise[charIndex]) * t..bloom = _bloom[charIndex] * (1 - t);
    } else { into..scale = 1..shift = 0..rise = 1..bloom = 0; }
  }
}

double _smooth(double v) => v * v * (3 - 2 * v);
double _liftHeight(LyricWord word) => .25 + .75 * _smooth(((word.endMs - word.startMs - 150) / 750).clamp(0.0, 1.0));
bool _canGrow(LyricWord word) {
  final text = word.text, length = text.length;
  if (length == 0 || length > 7 || text.contains('-')) return false;
  if (text.codeUnits.any((c) => (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3040 && c <= 0x30ff) ||
      (c >= 0xac00 && c <= 0xd7af) || (c >= 0x0590 && c <= 0x08ff))) return false;
  final held = word.endMs - word.startMs;
  if (length == 1) return held >= 1100;
  if (length <= 3) return held >= 1360 + (length - 2) * 140;
  if (length == 4) return held >= 1050;
  return held >= 900 && held >= length * 200;
}

class TimedRun {
  const TimedRun(this.startMs, this.endMs, this.raw);
  final int startMs, endMs;
  final String raw;
}
List<LyricWord> wordsFromRuns(List<TimedRun> runs, {bool spacingIsExplicit = false}) {
  final glue = spacingIsExplicit || runs.any((r) => RegExp(r'\s').hasMatch(r.raw));
  final words = <LyricWord>[]; var current = ''; final parts = <LyricSyllable>[];
  var start = 0, end = 0;
  void flush() {
    if (current.isNotEmpty) words.add(LyricWord(start, math.max(start, end), current,
      syllables: LyricSyllable.normalized(parts, current.length)));
    current = ''; parts.clear();
  }
  for (final run in runs) {
    final content = run.raw.trim();
    if (content.isEmpty) { flush(); continue; }
    final joins = glue && current.isNotEmpty && run.raw[0].trim().isNotEmpty &&
      !_unspaced(current.codeUnitAt(current.length - 1)) && !_unspaced(content.codeUnitAt(0));
    if (!joins) flush();
    if (current.isEmpty) { start = run.startMs; end = run.startMs; }
    final from = current.length; current += content;
    final until = math.max(run.startMs, run.endMs);
    parts.add(LyricSyllable(run.startMs, until, from, current.length)); end = math.max(end, until);
    if (!glue || run.raw[run.raw.length - 1].trim().isEmpty) flush();
  }
  flush(); return words;
}
bool _unspaced(int c) => (c >= 0x3400 && c <= 0x9fff) || (c >= 0x3040 && c <= 0x30ff) ||
  (c >= 0x3000 && c <= 0x303f) || (c >= 0xff00 && c <= 0xffef) || (c >= 0x0e00 && c <= 0x0e7f);

List<LyricAlignment> lineAlignments(List<String?> singers, Map<String, String> types) {
  var left = true; String? lastVoice; var placed = 0, rightward = 0;
  final sides = singers.map((singer) {
    if (singer == null || singer.isEmpty) return LyricAlignment.start;
    final type = types[singer] ?? (singer == 'v1000' ? 'group' : singer == 'v2000' ? 'other' : 'person');
    placed++;
    if (type == 'group') return LyricAlignment.start;
    if (lastVoice == null) { left = type != 'other'; } else if (singer != lastVoice) { left = !left; }
    lastVoice = singer;
    if (!left) rightward++;
    return left ? LyricAlignment.start : LyricAlignment.end;
  }).toList();
  if (placed == 0 || rightward / placed < .85) return sides;
  return sides.map((s) => s == LyricAlignment.start ? LyricAlignment.end : LyricAlignment.start).toList();
}

List<LyricLine> withInstrumentalGaps(List<LyricLine> lines) {
  if (lines.isEmpty) return lines;
  final result = <LyricLine>[];
  if (lines.first.timeMs >= 4000) result.add(LyricLine(Duration.zero, ''));
  for (var i = 0; i < lines.length; i++) {
    final line = lines[i]; result.add(line);
    if (i + 1 < lines.length && line.hasKnownEnd && lines[i + 1].timeMs - line.endMs >= 4000 && line.endMs > line.timeMs) {
      result.add(LyricLine(Duration(milliseconds: line.endMs), ''));
    }
  }
  return result;
}

List<LyricLine> withBackgroundVocals(List<LyricLine> lines) => lines.map((line) {
  if (line.background != null || line.isGap || !line.text.endsWith(')')) return line;
  var depth = 0; int? open;
  for (var i = line.text.length - 1; i >= 0; i--) {
    if (line.text[i] == ')') depth++;
    if (line.text[i] == '(') { depth--; if (depth == 0) { if (i > 0) open = i; break; } }
  }
  if (open == null) return line;
  final lead = line.text.substring(0, open).trimRight(), backing = line.text.substring(open).trim();
  if (lead.isEmpty || !RegExp(r'[\p{L}\p{N}]', unicode: true).hasMatch(backing)) return line;
  if (line.words.isEmpty) return line.copyWith(text: lead,
    background: LyricLine(line.time, backing, sungUntilMs: line.sungUntilMs));
  var at = 0; int? split;
  for (var i = 0; i < line.words.length; i++) {
    if (at == open) { split = i; break; }
    if (at > open) break;
    at += line.words[i].text.length + 1;
  }
  if (split == null || split <= 0) return line;
  final background = line.words.sublist(split);
  return line.copyWith(text: lead, words: line.words.sublist(0, split),
    background: LyricLine(Duration(milliseconds: background.first.startMs), backing, words: background));
}).toList();
