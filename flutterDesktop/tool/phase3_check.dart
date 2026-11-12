import 'dart:convert';
import 'dart:io';

import '../lib/core/domain/audio_contract.dart';
import '../lib/core/platform/audio/audio_service.dart';
import '../lib/core/platform/audio/native_api.dart';
import '../lib/features/local_data/data/kotlin_import.dart';
import '../lib/features/local_data/data/sqlite_repository.dart';
import '../lib/features/local_data/domain/local_state.dart';
import '../lib/features/playback/domain/local_queue.dart';

void check(bool value, String message) {
  if (!value) throw StateError(message);
}

void queueCorpus() {
  final corpus =
      jsonDecode(
            File('test/fixtures/phase3/queue-oracle.json').readAsStringSync(),
          )
          as Map;
  for (final dynamic c in corpus['cases']) {
    var q = LocalQueue([
      for (final dynamic row in c['rows'])
        QueueEntry(
          entryId: row['id'] as String,
          catalogId: row['catalog'] as String,
          renditionId: row['catalog'] as String,
          sourceId: 'oracle',
          title: '',
          tier: QueueTier.values.byName(row['tier'] as String),
        ),
    ], cursor: c['cursor'] as int);
    for (var i = 0; i < (c['actions'] as List).length; i++) {
      final a = c['actions'][i] as List;
      q = switch (a[0]) {
        'next' => q.next(),
        'previous' => q.previous(),
        'jump' => q.jump(a[1] as int),
        'remove' => q.remove(a[1] as int),
        'move' => q.move(a[1] as int, a[2] as int),
        'clear' => q.clearManual(),
        'enqueue' => q.enqueue([
          QueueEntry(
            entryId: a[1] as String,
            catalogId: 'dup',
            renditionId: 'dup',
            sourceId: 'oracle',
            title: '',
          ),
        ], playNext: a[2] as bool),
        _ => throw StateError('Unknown fixture action'),
      };
      final expected = c['expected'][i] as Map;
      check(q.cursor == expected['cursor'], 'Kotlin cursor differential at $i');
      check(
        jsonEncode(q.entries.map((e) => e.entryId).toList()) ==
            jsonEncode(expected['ids']),
        'Kotlin queue differential at $i',
      );
      final names = {
        QueueTier.user: 'USER_QUEUE',
        QueueTier.context: 'CONTEXT',
        QueueTier.autoplay: 'AUTOPLAY',
      };
      check(
        jsonEncode(q.entries.map((e) => names[e.tier]).toList()) ==
            jsonEncode(expected['tiers']),
        'Kotlin tiers differential at $i',
      );
    }
  }
  final migration =
      jsonDecode(
            File(
              'test/fixtures/phase3/migration-oracle.json',
            ).readAsStringSync(),
          )
          as Map;
  final imported = KotlinImport.songs(
    (migration['songs'] as List).join('\n'),
    'oracle',
  );
  check(
    imported.single.catalogId == 'local:fixture' &&
        imported.single.title == 'A song | 🎵' &&
        imported.single.localPath == '/missing/song.flac' &&
        imported.single.metadata['kotlinFields'] is List,
    'Executed Kotlin encoder differential',
  );
  final rows = List.generate(
    8,
    (i) => QueueEntry(
      entryId: '$i',
      catalogId: 'duplicate',
      renditionId: 'recording',
      sourceId: 'local',
      title: '',
      tier: i < 3 ? QueueTier.user : QueueTier.context,
    ),
  );
  final q = LocalQueue(rows);
  final shuffled = q.shuffle(19);
  check(
    shuffled.entries[1].entryId == '1' && shuffled.entries[2].entryId == '2',
    'Manual queue pinned',
  );
  check(
    shuffled
            .restoreOrder(rows.map((e) => e.entryId).toList())
            .entries
            .map((e) => e.entryId)
            .join() ==
        rows.map((e) => e.entryId).join(),
    'Shuffle undo',
  );
  check(
    q.next(repeat: RepeatMode.one, automatic: true).current!.entryId == '0',
    'Repeat one EOF',
  );
  check(
    LocalQueue([rows[0]]).next(repeat: RepeatMode.all).current!.entryId == '0',
    'Repeat all wrap',
  );
  check(
    RepeatMode.off.next == RepeatMode.all &&
        RepeatMode.all.next == RepeatMode.one &&
        RepeatMode.one.next == RepeatMode.off,
    'Repeat cycle',
  );
  check(identical(q.afterHandoff('0'), q), 'Idempotent adoption');
  check(identical(q.afterHandoff('7'), q), 'Stale handoff');
}

Future<void> main(List<String> args) async {
  queueCorpus();
  final library = args.isNotEmpty
      ? args[0]
      : Platform.isWindows
      ? '${Directory.current.path}/build/native/Release/bitchord_audio.dll'
      : '${Directory.current.path}/build/native/libbitchord_audio.so';
  final api = NativeApi(library),
      baseline = NativeApi(library).bindings.bc_live_allocations();
  final temp = await Directory.systemTemp.createTemp('bitchord-ffi-');
  try {
    final repository = SqliteLocalRepository(
      path: '${temp.path}/state.sqlite3',
      libraryPath: library,
    );
    check((await repository.read()).revision == 0, 'Empty repository');
    String encode(String text) =>
        base64Url.encode(utf8.encode(text)).replaceAll('=', '');
    final fields = [
      'local:test',
      'A song | 🎵',
      'Artist',
      '',
      '',
      '',
      '',
      '',
      '',
      '/missing/music.flac',
      'false',
      'false',
      '',
      'false',
      '',
      '',
      'FLAC',
      '',
    ];
    final song = fields.map(encode).join('|');
    final raw = utf8.encode(
      jsonEncode({
        'version': 1,
        'reference': '348b613a0c3db1a54de56483ea6f8748d6465211',
        'preferences': {
          'queue.0': '$song\n',
          'queue.1': song,
          'queue_index': '1',
          'repeat_mode': 'ALL',
          'volume': '0.75',
          'local_music_folder': '/missing',
        },
      }),
    );
    final source = File('${temp.path}/export.json');
    await source.writeAsBytes(raw);
    final state = await KotlinImport.importFile(
      source: source,
      backupDirectory: Directory('${temp.path}/backups'),
      repository: repository,
      digest: api.digest,
    );
    check(
      state.queue.current!.entryId == 'kotlin:queue:1' &&
          state.queue.entries.length == 2,
      'Imported duplicate identity/cursor',
    );
    check(
      state.queue.current!.title == fields[1] &&
          state.settings.repeat == RepeatMode.all,
      'Imported Unicode/settings',
    );
    check(
      base64Encode(await source.readAsBytes()) == base64Encode(raw),
      'Read-only source',
    );
    check(
      (await KotlinImport.importFile(
            source: source,
            backupDirectory: Directory('${temp.path}/backups'),
            repository: repository,
            digest: api.digest,
          )).revision ==
          1,
      'Idempotent import',
    );
    var failed = false;
    try {
      await repository.write(
        LocalState(queue: LocalQueue([]), revision: 1),
        expectedRevision: 0,
      );
    } catch (_) {
      failed = true;
    }
    check(
      failed && (await repository.read()).queue.entries.length == 2,
      'Transactional CAS rollback',
    );
    failed = false;
    try {
      KotlinImport.chunks({'queue.0': 'a', 'queue.2': 'b'}, 'queue');
    } catch (_) {
      failed = true;
    }
    check(failed, 'Chunk gap rejected');
    failed = false;
    try {
      KotlinImport.decodeExport(
        utf8.encode(
          jsonEncode({
            'version': 1,
            'reference': '348b613a0c3db1a54de56483ea6f8748d6465211',
            'preferences': {'token': 'private'},
          }),
        ),
      );
    } catch (_) {
      failed = true;
    }
    check(failed, 'Unknown/secret fields rejected');
    final service = await AudioService.start(libraryPath: library);
    try {
      check(await service.monotonicNs() > 0, 'Native monotonic epoch');
      check(
        await service.submit(
              const AudioRequest(
                commandId: 1,
                selection: 1,
                queueRevision: 1,
                operation: AudioOperation.select,
                path: '/missing-local-file.wav',
                entryId: 'entry:1',
                renditionId: 'local:1',
              ),
            ) ==
            Admission.accepted,
        'FFI command admission',
      );
      var events = <AudioEvent>[];
      for (var i = 0; i < 100 && events.isEmpty; i++) {
        events = await service.events();
        await Future<void>.delayed(const Duration(milliseconds: 5));
      }
      check(
        events.length == 1 && events.single.commandId == 1,
        'Exactly one FFI terminal event',
      );
      check(
        (await service.events()).single.sequence == events.single.sequence,
        'Unacked journal retained',
      );
      await service.acknowledge(events.single.sequence);
      check((await service.events()).isEmpty, 'Acknowledgment releases event');
    } finally {
      await service.close();
      await service.close();
    }
    check(
      api.bindings.bc_live_allocations() == baseline,
      'Native caller buffers all freed',
    );
    stdout.writeln(
      jsonEncode({
        'queue_transitions': 450,
        'ffi': 'passed',
        'sqlite_import': 'passed',
        'physical_audio': 'BLOCKED: no physical listener/loopback/hotplug',
      }),
    );
  } finally {
    await temp.delete(recursive: true);
  }
}
