import 'dart:async';
import 'package:bitchord/data/lyric_formats.dart';
import 'package:bitchord/domain/lyrics_session.dart';
import 'package:bitchord/domain/music.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('TTML keeps syllable pauses, answering vocals and alternate voices', () {
    final lines = parseTtml(
      '''<tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><head><metadata>
      <ttm:agent xml:id="v1" type="person"/><ttm:agent xml:id="v2" type="person"/>
      </metadata></head><body><div>
      <p begin="1.9" end="5" ttm:agent="v1"><span begin="2" end="2.2">e</span><span begin="2.8" end="3.8">nough</span>
      <span ttm:role="x-bg"><span begin="3.5" end="5.5">(enough)</span></span>
      <span ttm:role="x-translation">should not duplicate</span></p>
      <p begin="6" end="7" ttm:agent="v2"><span begin="6" end="7">Yes</span></p>
      </div></body></tt>''',
    );
    expect(lines.map((l) => l.text), ['enough', 'Yes']);
    final word = lines.first.words.single;
    expect(word.syllables.length, 2);
    expect(
      word.charsSungAt(2500),
      1,
    ); // A pause rests at the syllable boundary.
    expect(word.charsSungAt(3300), closeTo(3.5, .001));
    expect(lines.first.timeMs, 1900);
    expect(lines.first.background?.text, '(enough)');
    expect(lines.first.endMs, 5500); // Backing voice is still singing.
    expect(lines.last.alignment, LyricAlignment.end);
  });

  test('TTML line ends create actual instrumental gaps, long slots do not', () {
    final known = parseTtml(
      '<tt><body><p begin="5s" end="7s">Opening</p><p begin="12s">Next</p></body></tt>',
    );
    expect(known.map((l) => l.timeMs), [0, 5000, 7000, 12000]);
    final unknown = parseTtml(
      '<tt><body><p begin="1s">Opening</p><p begin="12s">Next</p></body></tt>',
    );
    expect(unknown.map((l) => l.timeMs), [1000, 12000]);
    expect(
      parseTtml('<!DOCTYPE tt SYSTEM "https://example.invalid/entity"><tt/>'),
      isEmpty,
    );
  });

  test('enhanced LRC joins explicit syllables and decodes entities once', () {
    final rows = parseEnhancedLrc(
      '[00:01.00]<00:01.00>e<00:01.20>nough <00:02.00>isn&#x27;t\n[00:03.00]End',
    );
    expect(rows.first.text, "enough isn't");
    expect(rows.first.words.first.syllables.length, 2);
    expect(rows.first.words.last.endMs, 3000);
    expect(decodeLyricEntities('&amp;#x27;'), '&#x27;');
    expect(parseEnhancedLrc('[00:01.00]Ordinary LRC'), isEmpty);
  });

  test(
    'LRC terminators and right alignment survive a downloaded-file round trip',
    () {
      final line = parseLrc(
        '[00:01.00]<R><00:01.00>e<00:01.20>nough <00:02.00>now<00:02.50>',
      ).single;
      expect(line.text, 'enough now');
      expect(line.alignment, LyricAlignment.end);
      expect(line.words.map((w) => w.text), ['enough', 'now']);
      expect(line.words.last.endMs, 2500);
      expect(line.words.first.syllables.length, 2);
    },
  );

  test(
    'QRC suffix stamps and YRC prefix stamps preserve original word ranges',
    () {
      final qrc = parseKaraoke(
        '[1000,2000]Hello (1000,500)world(1500,1500)',
      ).single;
      final yrc = parseKaraoke(
        '[1000,2000](1000,500,0)Hello (1500,1500,0)world',
      ).single;
      expect(qrc.text, 'Hello world');
      expect(yrc.text, qrc.text);
      expect(qrc.words.map((w) => (w.startMs, w.endMs)), [
        (1000, 1500),
        (1500, 3000),
      ]);
      expect(yrc.words.map((w) => (w.startMs, w.endMs)), [
        (1000, 1500),
        (1500, 3000),
      ]);
    },
  );

  test(
    'provider envelopes unwrap content while HTML/errors never become lyrics',
    () {
      expect(
        parseProviderLyrics(
          '{"data":{"lyrics":"[00:01.00]Real response"}}',
        ).single.text,
        'Real response',
      );
      expect(
        parseProviderLyrics('{"isError":true,"lyrics":"failure"}'),
        isEmpty,
      );
      expect(
        parseProviderLyrics('<html><body>Cloudflare challenge</body></html>'),
        isEmpty,
      );
      expect(parseProviderLyrics('Lyrics unavailable'), isEmpty);
    },
  );

  test('backing vocals retain their own clock and extend the singing end', () {
    final line = withBackgroundVocals([
      LyricLine(
        const Duration(seconds: 1),
        'Wait (wait now)',
        words: const [
          LyricWord(1000, 2000, 'Wait'),
          LyricWord(2000, 3000, '(wait'),
          LyricWord(3000, 5000, 'now)'),
        ],
      ),
    ]).single;
    expect(line.text, 'Wait');
    expect(line.background?.text, '(wait now)');
    expect(line.background?.timeMs, 2000);
    expect(line.endMs, 5000);
    final midword = LyricLine(
      Duration.zero,
      'wait(ing)',
      words: const [LyricWord(0, 1000, 'wait(ing)')],
    );
    expect(withBackgroundVocals([midword]).single.background, isNull);
  });

  test(
    'repeated words reveal their own occurrence, translated text follows timing',
    () {
      final line = LyricLine(
        Duration.zero,
        'go go',
        words: const [LyricWord(0, 1000, 'go'), LyricWord(2000, 3000, 'go')],
      );
      expect(line.revealedChars(1500), 2.5);
      expect(line.revealedChars(2500), 4);
      expect(line.wordSpans, [(0, 2), (3, 5)]);
      expect(
        LyricLine(
          Duration.zero,
          'abcdefghij',
          timingSource: line,
        ).revealedChars(2500),
        8,
      );
    },
  );

  test(
    'CJK runs and absent spacing do not turn the whole line into one word',
    () {
      expect(
        wordsFromRuns(const [
          TimedRun(0, 100, '你'),
          TimedRun(100, 200, '好'),
        ]).map((w) => w.text),
        ['你', '好'],
      );
      expect(
        wordsFromRuns(const [
          TimedRun(0, 100, 'hello'),
          TimedRun(100, 200, 'world'),
        ]).length,
        2,
      );
      expect(
        wordsFromRuns(const [
          TimedRun(0, 100, 'e'),
          TimedRun(100, 200, 'nough'),
        ], spacingIsExplicit: true).single.text,
        'enough',
      );
    },
  );

  test('held-word movement settles and avoids joined/block scripts', () {
    final line = LyricLine(
      Duration.zero,
      'love',
      words: const [LyricWord(1000, 4000, 'love')],
    );
    expect(line.growingWords.length, 1);
    final sample = CharGrowth();
    line.growingWords.single.sampleInto(0, 2000, sample);
    expect(sample.scale, greaterThan(1));
    line.growingWords.single.sampleInto(0, 20000, sample);
    expect(
      (sample.scale, sample.shift, sample.rise, sample.bloom),
      (1.0, 0.0, 1.0, 0.0),
    );
    expect(line.isGrowing(20000), isFalse);
    for (final text in ['hello-world', 'العربية', '日本語']) {
      expect(
        LyricLine(
          Duration.zero,
          text,
          words: [LyricWord(0, 10000, text)],
        ).growingWords,
        isEmpty,
      );
    }
  });

  test(
    'duet alternation ignores group voices and repairs predominantly right songs',
    () {
      expect(lineAlignments(['v1', 'v2', 'v1000', 'v3', 'v3'], {}), [
        LyricAlignment.start,
        LyricAlignment.end,
        LyricAlignment.start,
        LyricAlignment.start,
        LyricAlignment.start,
      ]);
      expect(lineAlignments(['v2000', 'v2000'], {}), [
        LyricAlignment.start,
        LyricAlignment.start,
      ]);
    },
  );

  test(
    'lyric session waits for selected-track duration and rejects stale answers',
    () async {
      final calls = <(String, Duration, Completer<List<LyricLine>>)>[];
      final session = LyricsSession((song, duration) {
        final result = Completer<List<LyricLine>>();
        calls.add((song.id, duration, result));
        return result.future;
      });
      session.select(const Song(id: 'local:first', title: 'First', artist: ''));
      expect(calls, isEmpty);
      session.reportDuration(const Duration(seconds: 20));
      expect(calls.single.$2.inSeconds, 20);
      session.select(
        const Song(id: 'local:second', title: 'Second', artist: ''),
      );
      calls.first.$3.complete([LyricLine(Duration.zero, 'Previous song')]);
      await Future<void>.delayed(Duration.zero);
      expect(session.lines, isEmpty);
      session.reportDuration(const Duration(seconds: 30));
      session.reportDuration(
        const Duration(seconds: 31),
      ); // A rendition change does not refetch.
      expect(calls.length, 2);
      calls.last.$3.complete([LyricLine(Duration.zero, 'Selected song')]);
      await Future<void>.delayed(Duration.zero);
      expect(session.lines.single.text, 'Selected song');
      session.select(
        const Song(id: 'local:disabled', title: '', artist: ''),
        enabled: false,
      );
      session.reportDuration(const Duration(seconds: 10));
      expect(calls.length, 2);
      expect(session.error, 'Synced lyrics are switched off');
      session.dispose();
    },
  );
}
