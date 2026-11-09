// Behavioral cases from original sharedUi LyricClockTest.kt.
import 'dart:math';
import 'package:bitchord/domain/lyric_clock.dart';
import 'package:bitchord/domain/lyrics.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final rate in [1.0,1.25]) {
    test('dated readings stay on audio at $rate speed through delayed frames', () {
      final random = Random(7), clock = LyricClock(44500);
      final pending = <(double,int,double)>[];
      var poll = 0.0, now = 0.0, stamp = 0.0;
      var report = 44500, previous = 0;
      while (now < 60000) {
        while (poll <= now) { pending.add((poll + random.nextDouble() * 800, (44500 + poll * rate).toInt(), poll)); poll += 500; }
        while (pending.isNotEmpty && pending.first.$1 <= now) {
          final reading = pending.removeAt(0); report = reading.$2; stamp = reading.$3;
        }
        final shown = clock.frame(nowMs: now, reportedMs: report, sampledAtMs: stamp, discontinuity: 0);
        expect(shown, greaterThanOrEqualTo(previous));
        if (now > 4000) expect((shown - (44500 + now * rate)).abs(), lessThan(40));
        previous = shown;
        now += 1000 / 60;
        if (random.nextDouble() < .02) now += random.nextDouble() * 600;
      }
    });
  }
  test('a far announced seek jumps to the dated reading immediately', () {
    final clock = LyricClock(30000);
    clock.frame(nowMs: 0, reportedMs: 30000, sampledAtMs: 0, discontinuity: 0);
    clock.frame(nowMs: 16, reportedMs: 30000, sampledAtMs: 0, discontinuity: 0);
    expect(clock.frame(nowMs: 33, reportedMs: 4000, sampledAtMs: 30, discontinuity: 1), 4003);
  });
  test('a nearby announced backward seek glides and lands without cutting', () {
    final clock = LyricClock(10000);
    var previous = 0, largestStep = 0, last = 0;
    for (var now = 0.0; now <= 5000; now += 1000 / 60) {
      final taken = (now / 500).floor() * 500.0;
      last = clock.frame(nowMs: now, reportedMs: 10000 + taken.toInt() - (taken >= 4000 ? 1000 : 0),
        sampledAtMs: taken, discontinuity: now >= 4000 ? 1 : 0);
      if (now > 4000) largestStep = max(largestStep, (last - previous).abs());
      previous = last;
    }
    expect(largestStep, lessThan(150));
    expect((last - 14000).abs(), lessThan(40));
  });
  test('hold and restart preserve a paused song instead of extrapolating pause time', () {
    final clock = LyricClock(10000);
    clock.frame(nowMs: 0, reportedMs: 10000, sampledAtMs: 0, discontinuity: 0);
    clock.hold(10000, 5); clock.restart();
    expect(clock.frame(nowMs: 60000, reportedMs: 10000, sampledAtMs: 0, discontinuity: 0), 10000);
    expect(clock.frame(nowMs: 60016, reportedMs: 10000, sampledAtMs: 0, discontinuity: 0), 10016);
  });
  test('overlapping lead and backing voices stay active beyond the latest row', () {
    final lines = [
      LyricLine(Duration.zero, 'Lead', words: const [LyricWord(0,3000,'Lead')],
        background: LyricLine(const Duration(seconds: 2), 'Answer', words: const [LyricWord(2000,7000,'Answer')])),
      LyricLine(const Duration(seconds: 4), 'Second'),
      LyricLine(const Duration(seconds: 5), 'Third'),
    ];
    expect(activeLyricRows(lines, 5500), [0,2]);
    expect(activeLyricRows(lines, 7500), [2]);
  });
}
