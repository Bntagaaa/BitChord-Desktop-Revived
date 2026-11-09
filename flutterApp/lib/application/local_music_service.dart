import 'dart:io';
import 'dart:isolate';

import 'package:audio_metadata_reader/audio_metadata_reader.dart';
import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;

import 'dart:convert';

import '../domain/models.dart';

/// Filesystem scans and tag/image extraction run outside the UI isolate.
class LocalMusicService {
  LocalMusicService(this.cacheDirectory);
  final Directory cacheDirectory;

  Future<List<Song>> scan(
    List<String> roots, {
    List<Song> downloads = const [],
    bool filterNonMusic = false,
  }) async {
    final cache = cacheDirectory.path;
    final downloadedArtwork = <String, String>{
      for (final song in downloads)
        if (song.localPath != null && song.thumbnailUrl != null)
          p.normalize(p.absolute(song.localPath!)): song.thumbnailUrl!,
    };
    return Isolate.run(
      () => _scan(roots, cache, downloadedArtwork, filterNonMusic),
    );
  }

  Future<String?> embeddedLyrics(Song song) async {
    final path = song.localPath;
    if (path == null) return null;
    return Isolate.run(() {
      try {
        return readMetadata(File(path), getImage: false).lyrics;
      } on Object {
        return null;
      }
    });
  }
}

const _extensions = {
  '.aac',
  '.flac',
  '.m4a',
  '.mp3',
  '.ogg',
  '.wav',
  '.webm',
  '.opus',
  '.aiff',
  '.aif',
};

List<Song> _scan(
  List<String> roots,
  String cachePath,
  Map<String, String> downloadedArtwork,
  bool filterNonMusic,
) {
  final cache = Directory(cachePath)..createSync(recursive: true);
  final seen = <String>{};
  final result = <Song>[];
  for (final root in roots) {
    final directory = Directory(root);
    if (!directory.existsSync()) continue;
    // Match the source's depth-four traversal and never follow symlink loops.
    final pending = <(Directory, int)>[(directory, 0)];
    while (pending.isNotEmpty) {
      final (folder, depth) = pending.removeLast();
      List<FileSystemEntity> entries;
      try {
        entries = folder.listSync(followLinks: false);
      } on FileSystemException {
        continue;
      }
      for (final entity in entries) {
        if (entity is Directory && depth < 3) {
          pending.add((entity, depth + 1));
          continue;
        }
        if (entity is! File ||
            !_extensions.contains(p.extension(entity.path).toLowerCase())) {
          continue;
        }
        final path = p.normalize(p.absolute(entity.path));
        if (!seen.add(path)) continue;
        try {
          final stat = entity.statSync();
          if (filterNonMusic && !_isMusic(path, stat.size)) continue;
          final name = p.basenameWithoutExtension(path);
          final split = name.indexOf(' - ');
          var title = split >= 0 ? name.substring(split + 3).trim() : name;
          var artist = split >= 0
              ? name.substring(0, split).trim()
              : 'On This Computer';
          String? album;
          String? artwork;
          String? duration;
          final key = sha256
              .convert(
                utf8.encode(
                  '$path:${stat.size}:${stat.modified.millisecondsSinceEpoch}',
                ),
              )
              .toString();
          final artFile = File(p.join(cache.path, '$key.art'));
          try {
            final tags = readMetadata(entity, getImage: !artFile.existsSync());
            if (tags.title?.trim().isNotEmpty ?? false) {
              title = tags.title!.trim();
            }
            if (tags.artist?.trim().isNotEmpty ?? false) {
              artist = tags.artist!.trim();
            }
            album = tags.album;
            if (tags.duration != null) {
              duration = formatDuration(tags.duration!);
            }
            if (artFile.existsSync()) {
              artwork = artFile.uri.toString();
            } else if (tags.pictures.isNotEmpty) {
              final bytes = tags.pictures.first.bytes;
              // Bound embedded artwork copies; Flutter separately downsamples decoding.
              if (bytes.isNotEmpty && bytes.length <= 12 * 1024 * 1024) {
                artFile.writeAsBytesSync(bytes, flush: true);
                artwork = artFile.uri.toString();
              }
            }
          } on Object {
            // Corrupt tags do not prevent an otherwise valid file from appearing.
          }
          artwork ??= _folderArtwork(entity.parent);
          artwork ??= downloadedArtwork[path];
          result.add(
            Song(
              videoId: 'local:$path',
              title: title,
              artist: artist,
              thumbnailUrl: artwork,
              durationText: duration,
              albumName: album,
              localUri: entity.absolute.uri.toString(),
              localPath: path,
              localDateAddedSeconds:
                  stat.changed.millisecondsSinceEpoch ~/ 1000,
              localDateModifiedSeconds:
                  stat.modified.millisecondsSinceEpoch ~/ 1000,
              sourceQuality: p.extension(path).substring(1).toUpperCase(),
            ),
          );
        } on FileSystemException {
          // Files can disappear or become inaccessible during a scan.
        }
      }
    }
  }
  result.sort((a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()));
  _trimArtworkCache(cache, 128 * 1024 * 1024);
  return result;
}

bool _isMusic(String path, int bytes) {
  final value = path.replaceAll('\\', '/').toLowerCase();
  if (value.endsWith('.wav') || bytes < 240 * 1024) return false;
  return !const [
    '/ringtones/',
    '/notifications/',
    '/alarms/',
    '/ui/',
    '/recordings/',
    '/voice recorder/',
    'voice note',
    'voicenote',
    'recording',
    'ringtone',
    'notification',
    'alarm',
  ].any(value.contains);
}

String? _folderArtwork(Directory folder) {
  for (final name in const [
    'cover.jpg',
    'cover.png',
    'folder.jpg',
    'folder.png',
    'front.jpg',
    'front.png',
    'album.jpg',
    'album.png',
  ]) {
    final file = File(p.join(folder.path, name));
    if (file.existsSync()) return file.uri.toString();
  }
  return null;
}

void _trimArtworkCache(Directory directory, int maxBytes) {
  final files = directory
      .listSync()
      .whereType<File>()
      .where((file) => file.path.endsWith('.art'))
      .toList();
  final entries = <(File, FileStat)>[];
  var total = 0;
  for (final file in files) {
    try {
      final stat = file.statSync();
      total += stat.size;
      entries.add((file, stat));
    } on FileSystemException {
      /* A concurrent cleanup already removed it. */
    }
  }
  entries.sort((a, b) => a.$2.modified.compareTo(b.$2.modified));
  for (final (file, stat) in entries) {
    if (total <= maxBytes) break;
    try {
      file.deleteSync();
      total -= stat.size;
    } on FileSystemException {
      continue;
    }
  }
}
