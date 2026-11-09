import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/application/local_music_service.dart';
import 'package:bitchord_desktop/domain/models.dart';

void main() {
  late Directory root;
  late LocalMusicService service;
  setUp(() async {
    root = await Directory.systemTemp.createTemp('bitchord-local-test-');
    service = LocalMusicService(Directory('${root.path}/cache'));
  });
  tearDown(() async {
    await root.delete(recursive: true);
  });

  test(
    'overlapping roots deduplicate files and tag failure uses filename fallback',
    () async {
      final music = await Directory('${root.path}/music').create();
      final sub = await Directory('${music.path}/album').create();
      final audio = File('${sub.path}/Artist - Song.mp3');
      await audio.writeAsBytes(
        List.filled(128, 0),
      ); // Deliberately corrupt tags.
      await File('${sub.path}/ignore.txt').writeAsString('Not music');
      final songs = await service.scan([
        music.path,
        sub.path,
        '${root.path}/missing',
      ]);
      expect(songs, hasLength(1));
      expect(songs.single.title, 'Song');
      expect(songs.single.artist, 'Artist');
      expect(songs.single.localPath, audio.absolute.path);
      expect(songs.single.localUri, audio.absolute.uri.toString());
    },
  );

  test(
    'scanner observes source depth limit and does not traverse symlink loops',
    () async {
      final music = await Directory('${root.path}/music').create();
      final within = await Directory(
        '${music.path}/a/b/c',
      ).create(recursive: true);
      final beyond = await Directory('${within.path}/d').create();
      await File('${within.path}/Allowed.mp3').writeAsBytes([0]);
      await File('${beyond.path}/Too deep.mp3').writeAsBytes([0]);
      if (!Platform.isWindows) {
        await Link('${music.path}/cycle').create(music.path);
      }
      final songs = await service.scan([music.path]);
      expect(songs.map((s) => s.title), ['Allowed']);
    },
  );

  test(
    'folder art takes priority, then downloaded artwork survives rescans',
    () async {
      final first = await Directory('${root.path}/first').create();
      final second = await Directory('${root.path}/second').create();
      final a = await File('${first.path}/One.mp3').writeAsBytes([0]);
      final b = await File('${second.path}/Two.mp3').writeAsBytes([0]);
      final art = await File(
        '${first.path}/cover.jpg',
      ).writeAsBytes([0xff, 0xd8]);
      final songs = await service.scan(
        [first.path, second.path],
        downloads: [
          Song(
            videoId: 'one',
            title: 'One',
            artist: 'Artist',
            localPath: a.path,
            thumbnailUrl: 'https://images.invalid/one',
          ),
          Song(
            videoId: 'two',
            title: 'Two',
            artist: 'Artist',
            localPath: b.path,
            thumbnailUrl: 'https://images.invalid/two',
          ),
        ],
      );
      expect(songs.first.thumbnailUrl, art.uri.toString());
      expect(songs.last.thumbnailUrl, 'https://images.invalid/two');
    },
  );

  test(
    'nonmusic filter excludes small files, recordings and WAV alerts',
    () async {
      final music = await Directory('${root.path}/music').create();
      await File('${music.path}/Small.mp3').writeAsBytes([0]);
      await File(
        '${music.path}/Song.flac',
      ).writeAsBytes(List.filled(250 * 1024, 0));
      await File(
        '${music.path}/Voice recording.mp3',
      ).writeAsBytes(List.filled(250 * 1024, 0));
      await File(
        '${music.path}/Alert.wav',
      ).writeAsBytes(List.filled(250 * 1024, 0));
      final songs = await service.scan([music.path], filterNonMusic: true);
      expect(songs.map((s) => s.title), ['Song']);
    },
  );
}
