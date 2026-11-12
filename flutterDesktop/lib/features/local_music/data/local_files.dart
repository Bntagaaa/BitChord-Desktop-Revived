import 'dart:io';
import 'dart:math';

import '../../playback/domain/local_queue.dart';

final class LocalFiles {
  static const extensions = {'aac', 'flac', 'm4a', 'mp3', 'ogg', 'wav', 'webm'};
  static Future<QueueEntry> entry(String path) async {
    final file = File(path).absolute;
    if (!await file.exists())
      throw FileSystemException('Local file unavailable', path);
    final name = file.uri.pathSegments.last;
    final dot = name.lastIndexOf('.');
    final stem = dot < 0 ? name : name.substring(0, dot);
    final split = stem.indexOf(' - ');
    final random = Random.secure();
    final id = List.generate(
      16,
      (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
    ).join();
    return QueueEntry(
      entryId: id,
      catalogId: 'local:${file.path}',
      renditionId: file.uri.toString(),
      sourceId: 'local',
      localPath: file.path,
      title: split > 0 ? stem.substring(split + 3).trim() : stem,
      artist: split > 0 ? stem.substring(0, split).trim() : 'On This Computer',
    );
  }

  static Stream<QueueEntry> scan(Directory root, {int depth = 0}) async* {
    if (depth > 3)
      return; // Files.walk(root, 4) in Kotlin; do not follow symlinks.
    await for (final item in root.list(followLinks: false)) {
      if (item is Directory) {
        yield* scan(item, depth: depth + 1);
      }
      if (item is File &&
          extensions.contains(item.path.split('.').last.toLowerCase()))
        yield await entry(item.path);
    }
  }
}
