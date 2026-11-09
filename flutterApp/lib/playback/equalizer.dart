import 'dart:math' as math;

enum EqualizerFilterKind { bell, lowShelf, highShelf }

class EqualizerSlot {
  const EqualizerSlot(this.kind, this.hz);
  final EqualizerFilterKind kind;
  final double hz;
}

/// Same seven centres, shelf endpoints, tone-pad geometry and presets as 1.9.2.
class EqualizerCurve {
  EqualizerCurve._(this.gainsDb, this.qs, this.preampDb);
  static const manualFrequencies = <double>[
    60,
    150,
    400,
    1000,
    2500,
    6000,
    14000,
  ];
  static const slots = [
    EqualizerSlot(EqualizerFilterKind.lowShelf, 60),
    EqualizerSlot(EqualizerFilterKind.bell, 150),
    EqualizerSlot(EqualizerFilterKind.bell, 400),
    EqualizerSlot(EqualizerFilterKind.bell, 1000),
    EqualizerSlot(EqualizerFilterKind.bell, 2500),
    EqualizerSlot(EqualizerFilterKind.bell, 6000),
    EqualizerSlot(EqualizerFilterKind.highShelf, 14000),
    EqualizerSlot(EqualizerFilterKind.lowShelf, 250),
    EqualizerSlot(EqualizerFilterKind.bell, 1000),
    EqualizerSlot(EqualizerFilterKind.highShelf, 4000),
  ];
  final List<double> gainsDb;
  final List<double> qs;
  final double preampDb;
  static final flat = EqualizerCurve.manual(const [0, 0, 0, 0, 0, 0, 0]);

  factory EqualizerCurve.manual(List<double> bands) {
    if (bands.length != 7 || bands.any((value) => !value.isFinite)) {
      throw ArgumentError('Equalizer needs seven finite gain values.');
    }
    return EqualizerCurve._build(
      [...bands.map((v) => v.clamp(-12.0, 12.0)), 0, 0, 0],
      const [1, 1, 1, 1, 1, 1, 1, .707, .707, .707],
    );
  }

  factory EqualizerCurve.tone(int x, int y, {bool focused = false}) {
    final tilt = x.clamp(-5, 5) * 1.2;
    final contour = y.clamp(-5, 5) * 1.2;
    final shelfQ = focused ? .9 : .5;
    return EqualizerCurve._build(
      [0, 0, 0, 0, 0, 0, 0, -tilt, contour, tilt],
      [
        .707,
        .707,
        .707,
        .707,
        .707,
        .707,
        .707,
        shelfQ,
        focused ? 2.2 : .7,
        shelfQ,
      ],
    );
  }

  factory EqualizerCurve._build(List<double> gains, List<double> qs) {
    var peak = 0.0;
    for (var point = 0; point < 96; point++) {
      final hz = 20.0 * math.pow(1000, point / 95);
      var gain = 0.0;
      for (var i = 0; i < slots.length; i++) {
        gain += sectionGainDb(slots[i], gains[i], qs[i], hz);
      }
      peak = math.max(peak, gain);
    }
    return EqualizerCurve._(
      List.unmodifiable(gains),
      List.unmodifiable(qs),
      -peak,
    );
  }

  /// libmpv/FFmpeg executes this filter graph natively, outside Dart's UI isolate.
  /// Values come only from bounded numeric controls, never user filter strings.
  String get nativeFilter {
    if (gainsDb.every((g) => g.abs() < .01)) return '';
    final filters = <String>['volume=${preampDb.toStringAsFixed(4)}dB'];
    for (var i = 0; i < slots.length; i++) {
      if (gainsDb[i].abs() < .01) continue;
      final name = switch (slots[i].kind) {
        EqualizerFilterKind.lowShelf => 'bass',
        EqualizerFilterKind.highShelf => 'treble',
        EqualizerFilterKind.bell => 'equalizer',
      };
      filters.add(
        '$name=f=${slots[i].hz.toStringAsFixed(1)}:'
        't=q:w=${qs[i].toStringAsFixed(3)}:g=${gainsDb[i].toStringAsFixed(3)}',
      );
    }
    return 'lavfi=[${filters.join(',')}]';
  }
}

double sectionGainDb(EqualizerSlot slot, double gainDb, double q, double hz) {
  if (gainDb.abs() < .01) return 0;
  final a = math.pow(10, gainDb / 40).toDouble();
  final a2 = a * a;
  final x = hz / slot.hz;
  final x2 = x * x;
  final qq = q * q;
  final common = x2 * a / qq;
  final flat = (1 - x2) * (1 - x2);
  final magnitude = switch (slot.kind) {
    EqualizerFilterKind.bell => math.sqrt(
      (flat + x2 * a2 / qq) / (flat + x2 / (a2 * qq)),
    ),
    EqualizerFilterKind.lowShelf =>
      a *
          math.sqrt(
            ((a - x2) * (a - x2) + common) /
                ((1 - a * x2) * (1 - a * x2) + common),
          ),
    EqualizerFilterKind.highShelf =>
      a *
          math.sqrt(
            ((1 - a * x2) * (1 - a * x2) + common) /
                ((a - x2) * (a - x2) + common),
          ),
  };
  return 20 * math.log(magnitude) / math.ln10;
}

const equalizerPresets = <String, List<double>>{
  'Flat': [0, 0, 0, 0, 0, 0, 0],
  'Acoustic': [3, 1.5, 0, 1.5, 2.5, 2, 1],
  'Bass Boost': [6, 4, 1.5, 0, 0, 0, 0],
  'Bass Cut': [-6, -4, -1.5, 0, 0, 0, 0],
  'Vocal': [-3, -1.5, 1, 3.5, 3, 1, -1],
  'Treble Boost': [0, 0, 0, 0, 1.5, 3.5, 5],
  'Treble Cut': [0, 0, 0, 0, -1.5, -3.5, -5],
  'Loudness': [6, 3.5, 0, -1.5, -1, 2, 5],
  'Spoken Word': [-5, -2.5, 1.5, 4, 3.5, 1.5, -2],
  'Electronic': [5, 3, -1, 0, 1, 3, 4],
  'Rock': [4, 2.5, -1, -1.5, 1, 3, 3.5],
  'Hip-Hop': [6, 4, .5, -1, .5, 2, 2.5],
  'Jazz': [3, 1.5, 0, 1, 1.5, 2, 2.5],
  'Classical': [3, 2, 0, 0, 1, 2.5, 3],
  'Small Speakers': [5, 4, 2, .5, 0, -1, -2],
  'Late Night': [3, 1, 0, 1.5, 1, -1, -3],
};

/// Energy-preserving gains; a transition timer only controls the native volume.
({double outgoing, double incoming}) crossfadeGains(double progress) {
  final t = progress.clamp(0.0, 1.0) * math.pi / 2;
  return (outgoing: math.cos(t), incoming: math.sin(t));
}
