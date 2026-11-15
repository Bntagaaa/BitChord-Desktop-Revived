// Wire parameters only. Native code owns curves, coefficients and audible DSP.
enum EqualizerMode { manual, dynamic }

abstract interface class ListeningPort {
  bool get supportsListening;
  ListeningSettings get listening;
  Future<void> configureListening(ListeningSettings settings);
}

final class ListeningSettings {
  ListeningSettings({
    this.enabled = false,
    this.mode = EqualizerMode.dynamic,
    this.toneX = 0,
    this.toneY = 0,
    this.focused = false,
    this.balance = 0,
    this.spatial = false,
    this.speed = 1,
    this.skipSilence = false,
    List<double> bands = const [0, 0, 0, 0, 0, 0, 0],
  }) : bands = List.unmodifiable(bands) {
    if (!speed.isFinite ||
        speed < .25 ||
        speed > 3 ||
        toneX < -5 ||
        toneX > 5 ||
        toneY < -5 ||
        toneY > 5 ||
        !balance.isFinite ||
        balance.abs() > 1 ||
        bands.length != 7 ||
        bands.any((value) => !value.isFinite || value.abs() > 12)) {
      throw ArgumentError('Invalid listening settings');
    }
  }
  final bool enabled, focused, spatial, skipSilence;
  final double speed;
  final EqualizerMode mode;
  final int toneX, toneY;
  final double balance;
  final List<double> bands;

  String get nativePayload => [
    1,
    enabled ? 1 : 0,
    mode.index,
    toneX,
    toneY,
    focused ? 1 : 0,
    balance,
    spatial ? 1 : 0,
    ...bands,
  ].join(' ');

  Map<String, Object?> toJson() => {
    'version': 1,
    'enabled': enabled,
    'mode': mode.name,
    'toneX': toneX,
    'toneY': toneY,
    'focused': focused,
    'balance': balance,
    'spatial': spatial,
    'speed': speed,
    'skipSilence': skipSilence,
    'bands': bands,
  };
  factory ListeningSettings.fromJson(Map<String, Object?> json) {
    if (json['version'] != 1)
      throw const FormatException('Listening settings version');
    return ListeningSettings(
      enabled: json['enabled'] as bool,
      mode: EqualizerMode.values.byName(json['mode'] as String),
      toneX: json['toneX'] as int,
      toneY: json['toneY'] as int,
      focused: json['focused'] as bool,
      balance: (json['balance'] as num).toDouble(),
      spatial: json['spatial'] as bool,
      speed: (json['speed'] as num?)?.toDouble() ?? 1,
      skipSilence: json['skipSilence'] as bool? ?? false,
      bands: (json['bands'] as List)
          .map((value) => (value as num).toDouble())
          .toList(),
    );
  }
  ListeningSettings copyWith({
    bool? enabled,
    EqualizerMode? mode,
    int? toneX,
    int? toneY,
    bool? focused,
    double? balance,
    bool? spatial,
    double? speed,
    bool? skipSilence,
    List<double>? bands,
  }) => ListeningSettings(
    enabled: enabled ?? this.enabled,
    mode: mode ?? this.mode,
    toneX: toneX ?? this.toneX,
    toneY: toneY ?? this.toneY,
    focused: focused ?? this.focused,
    balance: balance ?? this.balance,
    spatial: spatial ?? this.spatial,
    speed: speed ?? this.speed,
    skipSilence: skipSilence ?? this.skipSilence,
    bands: bands ?? this.bands,
  );
}
