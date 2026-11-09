import 'dart:convert';

import 'package:xml/xml.dart';

import '../domain/models.dart';
import 'lyrics_service.dart';

bool isPrivatePreferenceKey(String key) => RegExp(
  r'(cookie|password|secret|credential|token|session[_-]?key|api[_-]?key|authorization|source_configs|module_index_url|sp_dc)',
  caseSensitive: false,
).hasMatch(key);

/// Pure parser so migrations can be tested without touching actual user profiles.
class LegacyProfile {
  LegacyProfile({
    required this.settings,
    required this.history,
    required this.queue,
    required this.downloads,
    required this.playlists,
    required this.likedIds,
    required this.dislikedIds,
    required this.queueIndex,
    required this.skippedKeys,
  });
  final Map<String, dynamic> settings;
  final List<Song> history, queue, downloads;
  final List<LocalPlaylist> playlists;
  final Set<String> likedIds, dislikedIds;
  final int queueIndex;
  final List<String> skippedKeys;

  factory LegacyProfile.parseXml(String xml) {
    final document = XmlDocument.parse(xml);
    final nodes = document
        .findAllElements('node')
        .where(
          (node) => node.getAttribute('name') == 'com.music.bitchord.desktop',
        );
    if (document.findAllElements('node').isNotEmpty && nodes.isEmpty) {
      throw const FormatException(
        'This export does not contain the BitChord desktop preferences node.',
      );
    }
    final XmlNode selected = nodes.firstOrNull ?? document;
    final entries = <String, String>{};
    for (final element in selected.findAllElements('entry')) {
      final key = element.getAttribute('key');
      final value = element.getAttribute('value');
      if (key != null && value != null) entries[key] = value;
    }
    if (entries.isEmpty) {
      throw const FormatException('No Java preferences found.');
    }
    String value(String key) {
      if (!entries.containsKey('$key.0')) return entries[key] ?? '';
      final chunks = <String>[];
      for (var i = 0; entries.containsKey('$key.$i'); i++) {
        chunks.add(entries['$key.$i']!);
      }
      final suffixes = entries.keys
          .where((k) => k.startsWith('$key.'))
          .map((k) => int.tryParse(k.substring(key.length + 1)))
          .whereType<int>();
      if (suffixes.any((i) => i >= chunks.length)) {
        throw FormatException('Missing legacy preference chunk for $key.');
      }
      return chunks.join();
    }

    List<String> lines(String key) =>
        value(key).split('\n').where((s) => s.isNotEmpty).toList();
    List<Song> songs(String key) =>
        lines(key).map(decodeSong).whereType<Song>().toList();
    final playlists = <LocalPlaylist>[];
    for (final line in lines('playlists')) {
      final fields = line.split('#');
      if (fields.length < 3) continue;
      final id = _decode(fields[0]);
      final title = _decode(fields[1]);
      if (id.isEmpty || title.isEmpty) continue;
      playlists.add(
        LocalPlaylist(
          id: id,
          title: title,
          songs: fields[2]
              .split(';')
              .map(decodeSong)
              .whereType<Song>()
              .toList(),
        ),
      );
    }
    final collectionKeys = {
      'history',
      'queue',
      'downloads',
      'playlists',
      'liked_ids',
      'disliked_ids',
      'queue_index',
    };
    final keys = entries.keys
        .map((k) => k.replaceFirst(RegExp(r'\.\d+$'), ''))
        .toSet();
    final settings = <String, dynamic>{};
    final skipped = <String>[];
    for (final key in keys) {
      if (collectionKeys.contains(key)) continue;
      if (isPrivatePreferenceKey(key)) {
        skipped.add(key);
        continue;
      }
      final raw = value(key);
      settings[key] = raw == 'true'
          ? true
          : raw == 'false'
          ? false
          : int.tryParse(raw) ?? double.tryParse(raw) ?? raw;
    }
    if (keys.contains('lyrics_sources') ||
        keys.contains('lyrics_source_order')) {
      final ordered = <String>{
        ...value('lyrics_source_order').split(',').map((s) => s.trim()),
        ...LyricsService.offeredProviders,
      };
      final enabled = keys.contains('lyrics_sources')
          ? value('lyrics_sources').split(',').map((s) => s.trim()).toSet()
          : LyricsService.offeredProviders.toSet();
      settings['lyrics_providers'] = ordered
          .where(
            (provider) =>
                enabled.contains(provider) &&
                LyricsService.offeredProviders.contains(provider),
          )
          .toList();
    }
    // JVM volume is a 0..1 fraction; Flutter/media_kit use percent 0..100.
    final volume = settings['volume'];
    if (volume is num) {
      settings['volume'] = (volume * 100).clamp(0, 100).toDouble();
    }
    // Source builds before the four-rung ladder used HIGH for today's LOSSLESS.
    final quality = settings['audio_quality'];
    if (quality == 'HIGH' && settings['audio_quality_rungs'] != true) {
      settings['audio_quality'] = 'LOSSLESS';
    } else if (quality == 'STANDARD') {
      settings['audio_quality'] = 'MEDIUM';
    }
    return LegacyProfile(
      settings: settings,
      history: songs('history'),
      queue: songs('queue'),
      downloads: songs('downloads'),
      playlists: playlists,
      likedIds: lines('liked_ids').toSet(),
      dislikedIds: lines('disliked_ids').toSet(),
      queueIndex: int.tryParse(value('queue_index')) ?? 0,
      skippedKeys: skipped,
    );
  }

  static String _decode(String value) {
    try {
      return utf8.decode(base64Url.decode(base64Url.normalize(value)));
    } on FormatException {
      return '';
    }
  }

  static Song? decodeSong(String line) {
    final f = line.split('|').map(_decode).toList();
    if (f.length < 10 || f.first.isEmpty) return null;
    String? field(int index) =>
        index < f.length && f[index].isNotEmpty ? f[index] : null;
    final video = field(10) == 'true';
    return Song(
      videoId: f[0],
      title: f[1],
      artist: f[2],
      thumbnailUrl: field(3),
      durationText: field(4),
      artistId: field(5),
      albumId: field(6),
      albumName: field(7),
      localUri: field(8),
      localPath: field(9),
      isVideo: video,
      isVideoOrigin: field(11) == null ? video : field(11) == 'true',
      setVideoId: field(12),
      queueTier: field(13) == 'true' ? QueueTier.autoplay : QueueTier.context,
      localDateAddedSeconds: int.tryParse(field(14) ?? ''),
      localDateModifiedSeconds: int.tryParse(field(15) ?? ''),
      sourceQuality: field(16),
      isExplicit: field(17) == null ? null : field(17) == 'true',
    );
  }
}
