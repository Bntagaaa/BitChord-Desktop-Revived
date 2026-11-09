import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/data/local_store.dart';

import 'legacy_fixture.dart';

void main() {
  late Directory root;
  setUp(() async {
    root = await Directory.systemTemp.createTemp('bitchord-store-test-');
  });
  tearDown(() async {
    await root.delete(recursive: true);
  });

  test(
    'explicit migration never writes the legacy file and preserves playlists',
    () async {
      final store = await LocalStore.open(
        directory: Directory('${root.path}/new-profile'),
      );
      await store.setSetting('volume', 50.0);
      final encoded = legacySong();
      final legacy = File('${root.path}/prefs.xml');
      final contents = xml({
        'history': encoded,
        'queue': '$encoded\n$encoded',
        'downloads': encoded,
        'playlists':
            '${field('local-id')}#${field('My list')}#$encoded;$encoded',
        'liked_ids': 'recording',
        'volume': '0.9',
        'youtube_cookie': 'private',
      });
      await legacy.writeAsString(contents);
      final beforeBytes = await legacy.readAsBytes();
      final report = await store.importLegacy(legacy);
      expect(await legacy.readAsBytes(), beforeBytes);
      expect(report.playlists, 1);
      expect(store.playlists.single.songs, hasLength(2));
      expect(store.queue, hasLength(2));
      expect(store.likedSongs.single.title, '日本語 • Déjà vu');
      expect(store.likedIds, contains('recording'));
      expect(store.getSetting<double>('volume', 100), 50.0);
      expect(store.settings.containsKey('youtube_cookie'), isFalse);
      expect(
        await File(
          '${store.directory.path}/profile.before-migration.json',
        ).exists(),
        isTrue,
      );
      final reopened = await LocalStore.open(directory: store.directory);
      expect(reopened.playlists.single.title, 'My list');
      expect(reopened.downloads.single.localPath, '/music/one.flac');
    },
  );

  test(
    'serialized writes keep the active item when a long queue is persisted',
    () async {
      final store = await LocalStore.open(directory: root);
      final queue = List.generate(
        350,
        (i) => Song(videoId: '$i', title: 'Track $i', artist: 'Artist'),
      );
      await Future.wait([
        store.setSetting('volume', 30.0),
        store.setSetting('shuffle', true),
        store.saveQueue(
          queue,
          index: 290,
          position: const Duration(seconds: 17),
        ),
        store.setRating(queue[290], LikeStatus.like),
      ]);
      final reopened = await LocalStore.open(directory: root);
      expect(reopened.queue.length, lessThanOrEqualTo(LocalStore.maxQueue));
      expect(reopened.queue[reopened.queueIndex].videoId, '290');
      expect(reopened.queuePosition, const Duration(seconds: 17));
      expect(reopened.getSetting<double>('volume', 100), 30.0);
      expect(reopened.getSetting<bool>('shuffle', false), isTrue);
      expect(reopened.likedSongs.single.videoId, '290');
    },
  );

  test(
    'unreadable profile is never silently replaced with an empty profile',
    () async {
      final profile = File('${root.path}/profile.json');
      await profile.writeAsString('{ damaged');
      await expectLater(
        LocalStore.open(directory: root),
        throwsFormatException,
      );
      expect(await profile.readAsString(), '{ damaged');
    },
  );

  test('credentials cannot be written to ordinary settings', () async {
    final store = await LocalStore.open(directory: root);
    await expectLater(
      store.setSetting('youtube_cookie', 'private'),
      throwsArgumentError,
    );
    await expectLater(
      store.setSettings({'volume': 0.5, 'access_token': 'private'}),
      throwsArgumentError,
    );
    expect(store.settings, isEmpty);
  });
}
