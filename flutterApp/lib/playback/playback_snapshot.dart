import '../domain/models.dart';

enum PlaybackRepeat { off, all, one }

/// A durable resume record. It contains catalogue metadata, never signed URLs.
class PlaybackSnapshot {
  const PlaybackSnapshot({
    required this.queue,
    this.currentIndex = 0,
    this.position = Duration.zero,
    this.volume = 70,
    this.shuffle = false,
    this.repeat = PlaybackRepeat.off,
    this.autoplay = true,
    this.crossfade = Duration.zero,
    this.rate = 1,
    this.videoEnabled = true,
    this.equalizer = const [0, 0, 0, 0, 0, 0, 0],
    this.originalOrder = const [],
  });
  final List<Song> queue;
  final int currentIndex;
  final Duration position;
  final double volume;
  final bool shuffle;
  final PlaybackRepeat repeat;
  final bool autoplay;
  final Duration crossfade;
  final double rate;
  final bool videoEnabled;
  final List<double> equalizer;
  final List<String> originalOrder;

  Map<String, Object?> toJson() => {
    'schemaVersion': 1,
    'queue': queue.map((s) => s.toJson()).toList(),
    'currentIndex': currentIndex,
    'positionMs': position.inMilliseconds,
    'volume': volume,
    'shuffle': shuffle,
    'repeat': repeat.name,
    'autoplay': autoplay,
    'crossfadeMs': crossfade.inMilliseconds,
    'rate': rate,
    'videoEnabled': videoEnabled,
    'equalizer': equalizer,
    'originalOrder': originalOrder,
  };

  factory PlaybackSnapshot.fromJson(Map<String, dynamic> json) {
    final queue = (json['queue'] as List? ?? const [])
        .whereType<Map<String, dynamic>>()
        .map(Song.fromJson)
        .toList();
    final savedEq = (json['equalizer'] as List? ?? const [])
        .whereType<num>()
        .map((v) => v.toDouble())
        .toList();
    double finite(Object? value, double fallback, double low, double high) {
      final n = value is num ? value.toDouble() : fallback;
      return n.isFinite ? n.clamp(low, high) : fallback;
    }

    return PlaybackSnapshot(
      queue: queue,
      currentIndex: queue.isEmpty
          ? -1
          : ((json['currentIndex'] as num?)?.toInt() ?? 0).clamp(
              0,
              queue.length - 1,
            ),
      position: Duration(
        milliseconds: ((json['positionMs'] as num?)?.toInt() ?? 0).clamp(
          0,
          7 * 86400000,
        ),
      ),
      volume: finite(json['volume'], 70, 0, 100),
      shuffle: json['shuffle'] == true,
      repeat:
          PlaybackRepeat.values
              .where((r) => r.name == json['repeat'])
              .firstOrNull ??
          PlaybackRepeat.off,
      autoplay: json['autoplay'] != false,
      crossfade: Duration(
        milliseconds: ((json['crossfadeMs'] as num?)?.toInt() ?? 0).clamp(
          0,
          12000,
        ),
      ),
      rate: finite(json['rate'], 1, .25, 3),
      videoEnabled: json['videoEnabled'] != false,
      equalizer: savedEq.length == 7 && savedEq.every((g) => g.isFinite)
          ? savedEq.map((v) => v.clamp(-12.0, 12.0)).toList()
          : const [0, 0, 0, 0, 0, 0, 0],
      originalOrder: (json['originalOrder'] as List? ?? const [])
          .whereType<String>()
          .toList(),
    );
  }
}
