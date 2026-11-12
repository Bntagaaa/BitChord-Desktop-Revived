import '../../playback/domain/local_queue.dart';

final class LocalSettings {
  const LocalSettings({
    this.repeat = RepeatMode.off,
    this.volume = 1,
    this.rate = 1,
    this.localFolder = '',
    this.shuffle = false,
  });
  final RepeatMode repeat;
  final double volume, rate;
  final String localFolder;
  final bool shuffle;
  Map<String, Object?> toJson() => {
    'version': 1,
    'repeat': repeat.name,
    'volume': volume,
    'rate': rate,
    'localFolder': localFolder,
    'shuffle': shuffle,
  };
  factory LocalSettings.fromJson(Map<String, Object?> j) {
    if (j['version'] != 1)
      throw const FormatException('Unsupported settings version');
    final volume = (j['volume'] as num).toDouble();
    final rate = (j['rate'] as num).toDouble();
    if (!volume.isFinite ||
        volume < 0 ||
        volume > 1 ||
        !rate.isFinite ||
        rate < .25 ||
        rate > 3) {
      throw const FormatException('Invalid audio settings');
    }
    return LocalSettings(
      repeat: RepeatMode.values.byName(j['repeat'] as String),
      volume: volume,
      rate: rate,
      localFolder: j['localFolder'] as String,
      shuffle: j['shuffle'] as bool,
    );
  }
}

final class LocalState {
  LocalState({
    required this.queue,
    this.revision = 0,
    this.positionUs = 0,
    this.settings = const LocalSettings(),
    this.importCheckpoint,
    Map<String, Object?> imported = const {},
  }) : imported = Map.unmodifiable(imported) {
    if (revision < 0 || positionUs < 0)
      throw ArgumentError('Negative revision/position');
  }
  final LocalQueue queue;
  final int revision, positionUs;
  final LocalSettings settings;
  final String? importCheckpoint;
  final Map<String, Object?> imported;
  Map<String, Object?> toJson() => {
    'version': 1,
    'revision': revision,
    'queue': queue.toJson(),
    'positionUs': positionUs,
    'settings': settings.toJson(),
    'importCheckpoint': importCheckpoint,
    'imported': imported,
  };
  factory LocalState.fromJson(Map<String, Object?> j) {
    if (j['version'] != 1)
      throw const FormatException('Unsupported local schema');
    return LocalState(
      queue: LocalQueue.fromJson((j['queue'] as Map).cast<String, Object?>()),
      revision: j['revision'] as int,
      positionUs: j['positionUs'] as int,
      settings: LocalSettings.fromJson(
        (j['settings'] as Map).cast<String, Object?>(),
      ),
      importCheckpoint: j['importCheckpoint'] as String?,
      imported: (j['imported'] as Map).cast<String, Object?>(),
    );
  }
}

abstract interface class LocalRepository {
  Future<LocalState> read();
  // Compare-and-swap revision makes queue, cursor, settings and import checkpoint atomic.
  Future<void> write(LocalState value, {required int expectedRevision});
}
