import 'dart:async';
import 'package:bitchord/data/lyric_formats.dart';
import 'package:bitchord/domain/lyric_clock.dart';
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
    'lyric session queries on select and refetches when duration arrives, rejecting stale answers',
    () async {
      final calls = <(String, Duration, Completer<List<LyricLine>>)>[];
      final session = LyricsSession((song, duration) {
        final result = Completer<List<LyricLine>>();
        calls.add((song.id, duration, result));
        return result.future;
      });
      session.select(const Song(id: 'local:first', title: 'First', artist: ''));
      expect(calls.length, 1);
      expect(calls.first.$2, Duration.zero);
      session.reportDuration(const Duration(seconds: 20));
      expect(calls.length, 2);
      expect(calls.last.$2.inSeconds, 20);
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
      expect(calls.length, 4);
      calls.last.$3.complete([LyricLine(Duration.zero, 'Selected song')]);
      await Future<void>.delayed(Duration.zero);
      expect(session.lines.single.text, 'Selected song');
      session.select(
        const Song(id: 'local:disabled', title: '', artist: ''),
        enabled: false,
      );
      session.reportDuration(const Duration(seconds: 10));
      expect(calls.length, 4);
      expect(session.error, 'Synced lyrics are switched off');
      session.dispose();
    },
  );

  test(
    'LyricsSession queries lyrics immediately when song duration is null or zero',
    () async {
      final calls = <(String, Duration, Completer<List<LyricLine>>)>[];
      final session = LyricsSession((song, duration) {
        final result = Completer<List<LyricLine>>();
        calls.add((song.id, duration, result));
        return result.future;
      });

      const onlineTrack = Song(
        id: 'yt:catalog_track',
        title: 'Online Song',
        artist: 'Artist',
        duration: null,
      );
      session.select(onlineTrack);

      expect(calls.length, 1);
      expect(calls.single.$1, 'yt:catalog_track');
      expect(calls.single.$2, Duration.zero);
      expect(session.loading, isTrue);

      final lyricsResult = [
        LyricLine(const Duration(seconds: 2), 'Line 1'),
        LyricLine(const Duration(seconds: 6), 'Line 2'),
      ];
      calls.single.$3.complete(lyricsResult);
      await Future<void>.delayed(Duration.zero);

      expect(session.loading, isFalse);
      expect(session.lines.length, 2);
      expect(session.lines.first.text, 'Line 1');
      expect(session.error, isNull);

      // Later native duration resolves
      session.reportDuration(const Duration(seconds: 210));
      await Future<void>.delayed(Duration.zero);

      expect(session.lines.length, 2);
      expect(session.lines.first.text, 'Line 1');

      session.dispose();
    },
  );

  test(
    'LyricsSession with enabled=false does not trigger lookup and records disabled status',
    () {
      var lookupCalled = false;
      final session = LyricsSession((song, duration) async {
        lookupCalled = true;
        return [];
      });

      session.select(
        const Song(id: 'yt:disabled', title: 'Disabled', artist: ''),
        enabled: false,
      );

      expect(lookupCalled, isFalse);
      expect(session.loading, isFalse);
      expect(session.lines, isEmpty);
      expect(session.error, 'Synced lyrics are switched off');

      session.dispose();
    },
  );

  test('lyric edge fade calculation follows the 28dp boundary', () {
    const fadePixels = 28.0;
    double topStop(double height) =>
        height > 0 ? (fadePixels / height).clamp(0.0, 0.5) : 0.0;
    expect(topStop(400), 28.0 / 400.0);
    expect(topStop(50), 0.5);
    expect(topStop(0), 0.0);
  });

  test(
    'LrcRed and TTML parsing yields word-synced lyrics with revealedChars',
    () {
      const rawTtml = '''
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" lrc:timing="Word" xml:lang="en">
<head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head>
<body><div begin="10.0" end="20.0"><p begin="10.0" end="15.0" ttm:agent="v1"><span begin="10.0" end="12.0">Hello</span> <span begin="12.0" end="15.0">World</span></p></div></body>
</tt>''';
      final lines = parseTtml(rawTtml);
      expect(lines.length, 2);
      expect(lines.first.isGap, isTrue);
      final line = lines.last;
      expect(line.text, 'Hello World');
      expect(line.words.length, 2);
      expect(line.words.first.text, 'Hello');
      expect(line.words.first.startMs, 10000);
      expect(line.words.first.endMs, 12000);
      expect(line.isWordSynced, isTrue);
      expect(line.revealedChars(11000), 2.5);
      expect(line.revealedChars(13500), 8.5);
    },
  );

  test(
    'LyricClock maintains synchronization within 250ms over 60s of continuous playback with 250ms updates',
    () {
      final clock = LyricClock(0);
      const frameMs = 1000.0 / 60.0;
      var nowMs = 0.0;
      var previousDisplayed = -1;

      while (nowMs <= 60000.0) {
        final reportedMs = (nowMs / 250.0).floor() * 250;
        final displayed = clock.frame(
          nowMs: nowMs,
          reportedMs: reportedMs,
          sampledAtMs: double.nan,
          discontinuity: 0,
        );

        expect(
          displayed,
          greaterThanOrEqualTo(previousDisplayed),
          reason:
              'Clock moved backwards at t=$nowMs ms: $displayed < $previousDisplayed',
        );
        previousDisplayed = displayed;

        final error = (displayed - nowMs).abs();
        expect(
          error,
          lessThanOrEqualTo(250.0),
          reason:
              'At t=$nowMs ms, displayed $displayed diverged from truth $nowMs by $error ms',
        );

        if (nowMs >= 2000.0) {
          expect(
            error,
            lessThanOrEqualTo(40.0),
            reason:
                'Steady-state error exceeded 40ms at t=$nowMs ms (error: $error ms)',
          );
        }

        nowMs += frameMs;
      }

      expect((previousDisplayed - 60000).abs(), lessThanOrEqualTo(40));
      expect(
        previousDisplayed,
        lessThan(61000),
        reason:
            'Clock accelerated forward: displayed=$previousDisplayed at t=60s',
      );
    },
  );

  test(
    'LyricClock backward seek from 60s to 30s settles cleanly at 30s without shooting forward',
    () {
      final clock = LyricClock(0);
      const frameMs = 1000.0 / 60.0;
      var nowMs = 0.0;

      // Phase 1: Play 60 seconds
      while (nowMs <= 60000.0) {
        final reportedMs = (nowMs / 250.0).floor() * 250;
        clock.frame(
          nowMs: nowMs,
          reportedMs: reportedMs,
          sampledAtMs: double.nan,
          discontinuity: 0,
        );
        nowMs += frameMs;
      }

      expect((clock.displayedMs - 60000).abs(), lessThanOrEqualTo(40));

      // Phase 2: Backward seek from 60s to 30s
      final seekNowMs = nowMs;
      final seekDisplayed = clock.frame(
        nowMs: seekNowMs,
        reportedMs: 30000,
        sampledAtMs: double.nan,
        discontinuity: 1,
      );

      expect(
        (seekDisplayed - 30000).abs(),
        lessThanOrEqualTo(250),
        reason:
            'Clock failed to settle near 30000ms after seek, was $seekDisplayed ms',
      );
      expect(
        seekDisplayed,
        lessThan(40000),
        reason:
            'Clock shot forward (observed: $seekDisplayed ms) instead of jumping back to 30s',
      );

      // Phase 3: Verify 2 seconds of smooth forward playback from 30s
      final postSeekStartMs = seekNowMs;
      var postNowMs = postSeekStartMs + frameMs;
      while (postNowMs <= postSeekStartMs + 2000.0) {
        final elapsedSinceSeek = postNowMs - postSeekStartMs;
        final reportedMs = 30000 + (elapsedSinceSeek / 250.0).floor() * 250;
        final displayed = clock.frame(
          nowMs: postNowMs,
          reportedMs: reportedMs,
          sampledAtMs: double.nan,
          discontinuity: 1,
        );

        final expected = 30000 + elapsedSinceSeek;
        final error = (displayed - expected).abs();
        expect(
          error,
          lessThanOrEqualTo(250.0),
          reason:
              'Post-seek playback diverged: displayed $displayed vs expected $expected',
        );
        postNowMs += frameMs;
      }
    },
  );

  testWidgets(
    'Ticker driving LyricClock continuously interpolates frames and handles backward seek over long playback',
    (tester) async {
      final clock = LyricClock(0);
      final displayedFrames = <int>[];
      var reportedPositionMs = 0;
      var discontinuity = 0;

      final ticker = tester.createTicker((elapsed) {
        final nowMs = elapsed.inMicroseconds / 1000.0;
        final displayed = clock.frame(
          nowMs: nowMs,
          reportedMs: reportedPositionMs,
          sampledAtMs: double.nan,
          discontinuity: discontinuity,
        );
        displayedFrames.add(displayed);
      });

      ticker.start();

      // Play 60 seconds (3600 frames)
      for (var frame = 0; frame < 3600; frame++) {
        final elapsedMs = frame * (1000.0 / 60.0);
        if (frame % 15 == 0) {
          reportedPositionMs = (elapsedMs / 250.0).floor() * 250;
        }
        await tester.pump(const Duration(milliseconds: 16, microseconds: 666));
      }

      expect(displayedFrames.length, 3600);

      // Check monotonicity
      for (var i = 1; i < displayedFrames.length; i++) {
        expect(
          displayedFrames[i],
          greaterThanOrEqualTo(displayedFrames[i - 1]),
          reason: 'Frame $i went backwards',
        );
      }

      // Check synchronization error <= 250ms across all frames
      for (var i = 0; i < displayedFrames.length; i++) {
        final nowMs = i * (1000.0 / 60.0);
        final error = (displayedFrames[i] - nowMs).abs();
        expect(
          error,
          lessThanOrEqualTo(250.0),
          reason: 'Frame $i drifted by $error ms',
        );
      }

      expect((displayedFrames.last - 60000).abs(), lessThanOrEqualTo(40));
      expect(
        displayedFrames.last,
        lessThan(61000),
        reason:
            'Clock accelerated forward to ${displayedFrames.last} at t=60s',
      );

      // Trigger backward seek to 30s
      discontinuity++;
      reportedPositionMs = 30000;

      // Pump immediate frame after seek
      await tester.pump(const Duration(milliseconds: 16, microseconds: 666));

      final seekDisplayed = displayedFrames.last;
      expect(
        (seekDisplayed - 30000).abs(),
        lessThanOrEqualTo(250),
        reason:
            'Clock failed to settle near 30000ms after seek, was: $seekDisplayed ms',
      );
      expect(
        seekDisplayed,
        lessThan(40000),
        reason:
            'Clock shot forward (observed: $seekDisplayed ms) instead of jumping back to 30s',
      );

      ticker.stop();
      ticker.dispose();
    },
  );
}
