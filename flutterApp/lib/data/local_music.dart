// Port source: DesktopLocalMusic.kt @ 348b613a.
import 'dart:io';
import '../domain/music.dart';
import 'local_artwork.dart';

class LocalMusicScanner {
  const LocalMusicScanner({this.artworkCache});
  final LocalArtworkCache? artworkCache;
  static const extensions = {'aac', 'flac', 'm4a', 'mp3', 'ogg', 'wav', 'webm'};
  static const _nonMusic = [
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
  ];
  static ({String title, String artist}) parseName(String name) {
    final separator = name.indexOf(' - ');
    if (separator >= 0 &&
        name.substring(0, separator).trim().isNotEmpty &&
        name.substring(separator + 3).trim().isNotEmpty) {
      return (
        title: name.substring(separator + 3).trim(),
        artist: name.substring(0, separator).trim(),
      );
    }
    return (title: name, artist: 'On This Computer');
  }

  static Future<bool> isMusic(File file) async {
    if (file.path.split('.').last.toLowerCase() == 'wav') return false;
    if (await file.length() < 240 * 1024) return false;
    final normalized = file.path.toLowerCase().replaceAll(r'\', '/');
    return !_nonMusic.any(normalized.contains);
  }

  Future<List<Song>> scan(
    List<String> roots, {
    bool filterNonMusic = false,
    bool Function()? cancelled,
  }) async {
    final found = <String, Song>{};
    for (final root in roots) {
      final pending = <(Directory, int)>[(Directory(root), 0)];
      while (pending.isNotEmpty) {
        if (cancelled?.call() ?? false) return const [];
        final (directory, depth) = pending.removeLast();
        if (!await directory.exists()) continue;
        await for (final item in directory.list(followLinks: false)) {
          if (cancelled?.call() ?? false) return const [];
          if (item is Directory && depth + 1 < 4) {
            pending.add((item, depth + 1));
            continue;
          }
          if (item is! File ||
              !extensions.contains(item.path.split('.').last.toLowerCase())) {
            continue;
          }
          if (filterNonMusic && !await isMusic(item)) continue;
          final absolute = File.fromUri(item.absolute.uri.normalizePath());
          final filename = absolute.uri.pathSegments.last;
          final name = parseName(
            filename.replaceFirst(RegExp(r'\.[^.]+$'), ''),
          );
          final image = await artworkCache?.artwork(absolute.path);
          found[absolute.path] = Song(
            id: 'local:${absolute.path}',
            title: name.title,
            artist: name.artist,
            artwork: image,
            localUri: absolute.uri,
          );
        }
      }
    }
    return found.values.toList()
      ..sort((a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()));
  }
}
