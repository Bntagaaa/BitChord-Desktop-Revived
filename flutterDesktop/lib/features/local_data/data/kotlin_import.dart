import 'dart:convert';
import 'dart:io';

import '../../playback/domain/local_queue.dart';
import '../domain/local_state.dart';

// Export contains only explicit non-secret keys. Unknown/auth keys are rejected,
// never copied to the database or a plaintext backup by a permissive fallback.
const kotlinImportKeys = {
  'queue',
  'queue_index',
  'history',
  'downloads',
  'playlists',
  'liked_ids',
  'disliked_ids',
  'original_versions',
  'repeat_mode',
  'shuffle',
  'volume',
  'playback_speed',
  'local_music_folder',
};

final class KotlinImport {
  static Map<String, String> decodeExport(List<int> bytes) {
    if (bytes.length > 16 * 1024 * 1024)
      throw const FormatException('Import too large');
    final root = jsonDecode(utf8.decode(bytes)) as Map;
    if (root['version'] != 1 ||
        root['reference'] != '348b613a0c3db1a54de56483ea6f8748d6465211') {
      throw const FormatException('Unsupported Kotlin export');
    }
    final values = (root['preferences'] as Map).cast<String, String>();
    for (final key in values.keys) {
      final base = key.replaceFirst(RegExp(r'\.\d+$'), '');
      if (!kotlinImportKeys.contains(base))
        throw const FormatException('Unsupported import key');
    }
    return values;
  }

  static String chunks(Map<String, String> values, String key) {
    if (!values.containsKey('$key.0')) return values[key] ?? '';
    final out = StringBuffer();
    var at = 0;
    while (values.containsKey('$key.$at')) {
      out.write(values['$key.$at']);
      at++;
    }
    if (values.keys.any(
      (k) =>
          k.startsWith('$key.') &&
          int.tryParse(k.substring(key.length + 1)) != null &&
          int.parse(k.substring(key.length + 1)) >= at,
    )) {
      throw const FormatException('Missing preference chunk');
    }
    return out.toString();
  }

  static List<QueueEntry> songs(String value, String section) {
    final rows = value.split('\n').where((s) => s.isNotEmpty).toList();
    if (rows.length > 10000)
      throw const FormatException('Too many imported songs');
    return [for (var i = 0; i < rows.length; i++) song(rows[i], '$section:$i')];
  }

  static QueueEntry song(String row, String identity) {
    final fields = row
        .split('|')
        .map((s) => utf8.decode(base64Url.decode(base64Url.normalize(s))))
        .toList();
    if (fields.length < 10 || fields.length > 18 || fields[0].isEmpty)
      throw const FormatException('Invalid Kotlin song');
    return QueueEntry(
      entryId: 'kotlin:$identity',
      catalogId: fields[0],
      renditionId: fields[0],
      sourceId: fields[9].isEmpty ? 'kotlin' : 'local',
      title: fields[1],
      artist: fields[2],
      localPath: fields[9].isEmpty ? null : fields[9],
      tier: fields.length > 13 && fields[13] == 'true'
          ? QueueTier.autoplay
          : QueueTier.context,
      metadata: {'kotlinFields': fields},
    );
  }

  static LocalState convert(
    Map<String, String> values, {
    required String checkpoint,
    int revision = 1,
  }) {
    String get(String key) => chunks(values, key);
    final rows = songs(get('queue'), 'queue');
    if (rows.length > 200)
      throw const FormatException('Kotlin queue exceeds saved bound');
    final cursor = int.tryParse(get('queue_index')) ?? 0;
    var repeat = RepeatMode.off;
    for (final mode in RepeatMode.values) {
      if (mode.name.toUpperCase() == get('repeat_mode')) repeat = mode;
    }
    double number(String key, double fallback, double min, double max) {
      final n = double.tryParse(get(key));
      return n == null || !n.isFinite ? fallback : n.clamp(min, max);
    }

    final playlists = <Map<String, Object?>>[];
    for (final line in get(
      'playlists',
    ).split('\n').where((s) => s.isNotEmpty)) {
      final p = line.split('#');
      if (p.length != 3) throw const FormatException('Invalid playlist');
      String decode(String s) =>
          utf8.decode(base64Url.decode(base64Url.normalize(s)));
      final id = decode(p[0]);
      if (id.isEmpty) throw const FormatException('Empty playlist id');
      playlists.add({
        'id': id,
        'title': decode(p[1]),
        'songs': [
          for (final (i, row)
              in p[2].split(';').where((s) => s.isNotEmpty).indexed)
            song(row, 'playlist:$id:$i').toJson(),
        ],
      });
    }
    return LocalState(
      queue: LocalQueue(
        rows,
        cursor: rows.isEmpty ? 0 : cursor.clamp(0, rows.length - 1),
      ).trimmed(),
      revision: revision,
      importCheckpoint: checkpoint,
      settings: LocalSettings(
        repeat: repeat,
        shuffle: get('shuffle') == 'true',
        volume: number('volume', 1, 0, 1),
        rate: number('playback_speed', 1, .25, 3),
        localFolder: get('local_music_folder'),
      ),
      imported: {
        'history': songs(
          get('history'),
          'history',
        ).map((s) => s.toJson()).toList(),
        'downloads': songs(
          get('downloads'),
          'downloads',
        ).map((s) => s.toJson()).toList(),
        'playlists': playlists,
        for (final key in ['liked_ids', 'disliked_ids', 'original_versions'])
          key: get(key).split('\n').where((s) => s.isNotEmpty).toSet().toList(),
        'limitations': [
          'Kotlin saved songs omit queue IDs/manual tiers; stable row IDs assigned.',
          'Credentials/accounts require reauthentication; no secret import.',
          'Missing media paths are retained without modifying or copying music.',
        ],
      },
    );
  }

  // Backup relevant exported bytes before the destination transaction. Source is
  // opened read-only and never renamed, truncated, or used as the destination.
  static Future<LocalState> importFile({
    required File source,
    required Directory backupDirectory,
    required LocalRepository repository,
    required String Function(List<int>) digest,
  }) async {
    final bytes = await source.readAsBytes();
    final values = decodeExport(bytes);
    final checkpoint = digest(bytes);
    final existing = await repository.read();
    if (existing.importCheckpoint == checkpoint) return existing;
    if (existing.revision != 0)
      throw StateError('Import requires a fresh destination');
    final converted = convert(values, checkpoint: checkpoint);
    await backupDirectory.create(recursive: true);
    final backup = File(
      '${backupDirectory.path}${Platform.pathSeparator}kotlin-$checkpoint.json',
    );
    if (await backup.exists()) {
      if (base64Encode(await backup.readAsBytes()) != base64Encode(bytes))
        throw StateError('Backup collision');
    } else {
      await backup.create(exclusive: true);
      if (!Platform.isWindows)
        await Process.run('chmod', ['600', backup.path]).then((r) {
          if (r.exitCode != 0)
            throw FileSystemException('Cannot protect backup', backup.path);
        });
      await backup.writeAsBytes(bytes, flush: true);
    }
    if (base64Encode(await backup.readAsBytes()) != base64Encode(bytes))
      throw StateError('Backup verification failed');
    await repository.write(converted, expectedRevision: 0);
    return await repository.read();
  }
}
