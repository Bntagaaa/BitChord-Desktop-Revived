import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/playback/equalizer.dart';

void main() {
  test('flat curve has no filter graph and no attenuation', () {
    expect(EqualizerCurve.flat.preampDb, 0);
    expect(EqualizerCurve.flat.nativeFilter, isEmpty);
  });
  test('source seven-band frequencies and shelf endpoints are preserved', () {
    expect(EqualizerCurve.manualFrequencies, [
      60,
      150,
      400,
      1000,
      2500,
      6000,
      14000,
    ]);
    expect(EqualizerCurve.slots.first.kind, EqualizerFilterKind.lowShelf);
    expect(EqualizerCurve.slots[6].kind, EqualizerFilterKind.highShelf);
    expect(
      EqualizerCurve.manual(equalizerPresets['Bass Boost']!).nativeFilter,
      contains('bass=f=60.0:t=q:w=1.000:g=6.000'),
    );
  });
  test('overlapping positive bands receive sufficient preamp headroom', () {
    final curve = EqualizerCurve.manual(const [6, 6, 6, 6, 6, 6, 6]);
    expect(curve.preampDb, lessThan(-6));
    for (var point = 0; point < 96; point++) {
      final hz = 20.0 * math.pow(1000, point / 95);
      var gain = curve.preampDb;
      for (var i = 0; i < EqualizerCurve.slots.length; i++) {
        gain += sectionGainDb(
          EqualizerCurve.slots[i],
          curve.gainsDb[i],
          curve.qs[i],
          hz,
        );
      }
      expect(gain, lessThanOrEqualTo(.000001));
    }
  });
  test('invalid curves cannot inject NaN or malformed native filters', () {
    expect(
      () => EqualizerCurve.manual([double.nan, 0, 0, 0, 0, 0, 0]),
      throwsArgumentError,
    );
    expect(() => EqualizerCurve.manual([0]), throwsArgumentError);
    final bounded = EqualizerCurve.manual([100, -100, 0, 0, 0, 0, 0]);
    expect(bounded.gainsDb.take(2), [12, -12]);
  });
  test('tone pad retains source tilt and contour geometry', () {
    final tone = EqualizerCurve.tone(5, -5, focused: true);
    expect(tone.gainsDb.skip(7), [-6, -6, 6]);
    expect(tone.qs.skip(7), [.9, 2.2, .9]);
  });
  test('crossfade endpoints and equal-power envelope', () {
    expect(crossfadeGains(0).incoming, closeTo(0, 1e-10));
    expect(crossfadeGains(1).outgoing, closeTo(0, 1e-10));
    for (var i = 0; i <= 100; i++) {
      final gains = crossfadeGains(i / 100);
      expect(
        gains.incoming * gains.incoming + gains.outgoing * gains.outgoing,
        closeTo(1, 1e-10),
      );
    }
  });
}
