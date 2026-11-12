// Standalone real local playback/import diagnostic. Uses the same FFI service
// as the app; no Flutter widget, Java, audio plugin or Dart PCM callback.
import 'dart:io';

import '../lib/core/platform/audio/audio_service.dart';
import '../lib/core/platform/audio/native_api.dart';
import '../lib/features/local_data/data/sqlite_repository.dart';
import '../lib/features/local_data/data/kotlin_import.dart';
import '../lib/features/local_music/data/local_files.dart';
import '../lib/features/playback/application/local_playback.dart';

Future<void> main(List<String> args) async {
  if (args.length < 3)
    throw ArgumentError(
      'local_audio.dart <native-library> <data-directory> <audio-file | --import=export.json>',
    );
  final root = Directory(args[1]);
  await root.create(recursive: true);
  final repository = SqliteLocalRepository(
    path: '${root.path}/local-v1.sqlite3',
    libraryPath: args[0],
  );
  if (args[2].startsWith('--import=')) {
    final api = NativeApi(args[0]);
    final state = await KotlinImport.importFile(
      source: File(args[2].substring(9)),
      backupDirectory: Directory('${root.path}/import-backups'),
      repository: repository,
      digest: api.digest,
    );
    stdout.writeln(
      'Imported ${state.queue.entries.length} queue entries; source unchanged.',
    );
    return;
  }
  final playback = LocalPlayback(
    repository,
    await AudioService.start(libraryPath: args[0]),
  );
  try {
    await playback.initialize();
    await playback.playLocal(await LocalFiles.entry(args[2]));
    while (true) {
      final s = await playback.refresh();
      stdout.writeln(
        '${s.phase.name} ${s.positionUs}us device=${s.backend} error=${s.error}',
      );
      if (s.phase.name == 'ended') break;
      if (s.error != 0) throw StateError('Native audio failed (${s.error})');
      await Future<void>.delayed(const Duration(milliseconds: 250));
    }
  } finally {
    await playback.close();
  }
}
