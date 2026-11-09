import 'dart:collection';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../domain/models.dart';
import 'legacy_profile.dart';

export 'legacy_profile.dart';

/// A versioned Flutter profile, separate from the JVM's Preferences node.
/// Writes are serialized and replaced atomically. The legacy profile is read only.
class LocalStore {
  LocalStore._(this.directory, this._data);
  final Directory directory;
  final Map<String, dynamic> _data;
  Future<void> _pending = Future.value();
  static const schemaVersion = 1;
  static const maxHistory = 100;
  static const maxQueue = 200;

  static Future<LocalStore> open({Directory? directory}) async {
    final root =
        directory ??
        Directory(
          p.join(
            (await getApplicationSupportDirectory()).path,
            'flutter-profile-v1',
          ),
        );
    await root.create(recursive: true);
    final profile = File(p.join(root.path, 'profile.json'));
    Map<String, dynamic> state = {'version': schemaVersion};
    if (await profile.exists()) {
      if (await profile.length() > 32 * 1024 * 1024) {
        throw const FormatException('Profile is too large to load safely.');
      }
      // Never replace an unreadable profile with defaults.
      final decoded = jsonDecode(await profile.readAsString());
      if (decoded is! Map<String, dynamic> ||
          decoded['version'] != schemaVersion) {
        throw const FormatException('Unsupported or damaged Flutter profile.');
      }
      state = decoded;
    }
    return LocalStore._(root, state);
  }

  Map<String, dynamic> get settings => UnmodifiableMapView(_settings);
  Map<String, dynamic> get _settings =>
      (_data['settings'] as Map<String, dynamic>?) ?? <String, dynamic>{};
  T getSetting<T>(String key, T fallback) {
    final value = _settings[key];
    if (value is T) return value;
    if (fallback is double && value is num) return value.toDouble() as T;
    if (fallback is int && value is num) return value.toInt() as T;
    return fallback;
  }

  List<Song> _songs(String key) => List.unmodifiable(
    (_data[key] as List? ?? const []).cast<Map<String, dynamic>>().map(
      Song.fromJson,
    ),
  );
  List<Song> get history => _songs('history');
  List<Song> get queue => _songs('queue');
  List<Song> get downloads => _songs('downloads');
  List<Song> get likedSongs => _songs('likedSongs');
  Set<String> get likedIds =>
      Set.unmodifiable((_data['likedIds'] as List? ?? const []).cast<String>());
  Set<String> get dislikedIds => Set.unmodifiable(
    (_data['dislikedIds'] as List? ?? const []).cast<String>(),
  );
  int get queueIndex =>
      (_data['queueIndex'] as int? ?? 0).clamp(0, max(0, queue.length - 1));
  Duration get queuePosition =>
      Duration(milliseconds: _data['queuePositionMs'] as int? ?? 0);
  List<LocalPlaylist> get playlists => List.unmodifiable(
    (_data['playlists'] as List? ?? const []).cast<Map<String, dynamic>>().map(
      LocalPlaylist.fromJson,
    ),
  );

  Future<void> setSetting(String key, Object? value) async {
    if (_secretKey(key)) {
      throw ArgumentError('Credentials belong in secure storage.');
    }
    final next = Map<String, dynamic>.of(_settings);
    if (value == null) {
      next.remove(key);
    } else {
      next[key] = value;
    }
    _data['settings'] = next;
    await _persist();
  }

  Future<void> setSettings(Map<String, Object?> values) async {
    if (values.keys.any(_secretKey)) {
      throw ArgumentError('Credentials belong in secure storage.');
    }
    _data['settings'] = {..._settings, ...values};
    await _persist();
  }

  Future<void> saveQueue(
    List<Song> songs, {
    int index = 0,
    Duration position = Duration.zero,
  }) async {
    // Preserve the playing item when the live queue exceeds the persistence cap.
    final start = index >= maxQueue ? index - maxQueue ~/ 2 : 0;
    final snapshot = songs.skip(start).take(maxQueue).toList(growable: false);
    _data['queue'] = snapshot.map((s) => s.toJson()).toList();
    _data['queueIndex'] = (index - start).clamp(0, max(0, snapshot.length - 1));
    _data['queuePositionMs'] = max(0, position.inMilliseconds);
    await _persist();
  }

  Future<void> recordHistory(Song song) async {
    _data['history'] = [
      song,
      ...history.where((s) => s.videoId != song.videoId),
    ].take(maxHistory).map((s) => s.toJson()).toList();
    await _persist();
  }

  Future<void> removeHistory(String videoId) async {
    _data['history'] = history
        .where((s) => s.videoId != videoId)
        .map((s) => s.toJson())
        .toList();
    await _persist();
  }

  Future<void> clearHistory() async {
    _data['history'] = <Object?>[];
    await _persist();
  }

  Future<void> setRating(Song song, LikeStatus rating) async {
    final liked = {...likedIds}..remove(song.videoId);
    final disliked = {...dislikedIds}..remove(song.videoId);
    final songs = likedSongs.where((s) => s.videoId != song.videoId).toList();
    if (rating == LikeStatus.like) {
      liked.add(song.videoId);
      songs.insert(0, song);
    }
    if (rating == LikeStatus.dislike) disliked.add(song.videoId);
    _data['likedIds'] = liked.toList();
    _data['dislikedIds'] = disliked.toList();
    _data['likedSongs'] = songs.map((s) => s.toJson()).toList();
    await _persist();
  }

  Future<void> saveDownloads(List<Song> songs) async {
    _data['downloads'] = songs.map((s) => s.toJson()).toList();
    await _persist();
  }

  Future<void> savePlaylists(List<LocalPlaylist> values) async {
    _data['playlists'] = values.map((p) => p.toJson()).toList();
    await _persist();
  }

  Future<LocalPlaylist> createPlaylist(
    String title, {
    List<Song> songs = const [],
  }) async {
    if (title.trim().isEmpty) throw ArgumentError('A playlist needs a name.');
    final random = Random.secure();
    final id = List.generate(
      16,
      (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
    ).join();
    final playlist = LocalPlaylist(id: id, title: title.trim(), songs: songs);
    await savePlaylists([...playlists, playlist]);
    return playlist;
  }

  Future<void> updatePlaylist(LocalPlaylist value) async {
    if (!playlists.any((p) => p.id == value.id)) {
      throw StateError('The playlist no longer exists.');
    }
    await savePlaylists(
      playlists.map((p) => p.id == value.id ? value : p).toList(),
    );
  }

  Future<void> deletePlaylist(String id) =>
      savePlaylists(playlists.where((p) => p.id != id).toList());

  Future<void> _persist() {
    _data['version'] = schemaVersion;
    final snapshot = jsonEncode(_data);
    final operation = _pending.then((_) async {
      final destination = File(p.join(directory.path, 'profile.json'));
      final temporary = File(p.join(directory.path, 'profile.pending.json'));
      await temporary.writeAsString(snapshot, flush: true);
      // Last known-good file remains available if a later read fails.
      if (await destination.exists()) {
        await destination.copy(p.join(directory.path, 'profile.backup.json'));
      }
      await temporary.rename(destination.path);
    });
    _pending = operation.catchError((Object _) {});
    return operation;
  }

  Future<void> flush() => _pending;

  /// Explicit import: reads a Java Preferences XML export or Linux prefs.xml.
  /// Credentials, protected source configurations and authentication are excluded.
  Future<LegacyImportResult> importLegacy(
    File source, {
    bool merge = true,
  }) async {
    if (await source.length() > 32 * 1024 * 1024) {
      throw const FormatException('Legacy preferences are too large.');
    }
    final snapshot = LegacyProfile.parseXml(await source.readAsString());
    final before = jsonEncode(_data);
    await File(
      p.join(directory.path, 'profile.before-migration.json'),
    ).writeAsString(before, flush: true);
    final allSongs = <Song>[
      ...snapshot.history,
      ...snapshot.queue,
      ...snapshot.downloads,
      ...snapshot.playlists.expand((p) => p.songs),
    ];
    final importedLiked = allSongs.where(
      (s) => snapshot.likedIds.contains(s.videoId),
    );
    List<Song> combine(List<Song> old, Iterable<Song> incoming, [int? limit]) {
      final byId = <String, Song>{};
      for (final song in [...(merge ? old : <Song>[]), ...incoming]) {
        byId.putIfAbsent(song.videoId, () => song);
      }
      return byId.values.take(limit ?? byId.length).toList();
    }

    _data['settings'] = merge
        ? {...snapshot.settings, ..._settings}
        : snapshot.settings;
    _data['history'] = combine(
      history,
      snapshot.history,
      maxHistory,
    ).map((s) => s.toJson()).toList();
    _data['downloads'] = combine(
      downloads,
      snapshot.downloads,
    ).map((s) => s.toJson()).toList();
    _data['likedSongs'] = combine(
      likedSongs,
      importedLiked,
    ).map((s) => s.toJson()).toList();
    _data['likedIds'] = {
      ...(merge ? likedIds : <String>{}),
      ...snapshot.likedIds,
    }.toList();
    _data['dislikedIds'] = {
      ...(merge ? dislikedIds : <String>{}),
      ...snapshot.dislikedIds,
    }.toList();
    if (!merge || queue.isEmpty) {
      _data['queue'] = snapshot.queue
          .take(maxQueue)
          .map((s) => s.toJson())
          .toList();
      _data['queueIndex'] = snapshot.queueIndex.clamp(
        0,
        max(0, snapshot.queue.length - 1),
      );
    }
    final mergedPlaylists = <String, LocalPlaylist>{
      for (final playlist in snapshot.playlists) playlist.id: playlist,
      if (merge)
        for (final playlist in playlists) playlist.id: playlist,
    };
    _data['playlists'] = mergedPlaylists.values.map((p) => p.toJson()).toList();
    _data['lastLegacyImport'] = DateTime.now().toUtc().toIso8601String();
    await _persist();
    return LegacyImportResult(
      history: snapshot.history.length,
      playlists: snapshot.playlists.length,
      downloads: snapshot.downloads.length,
      settings: snapshot.settings.length,
      skippedKeys: snapshot.skippedKeys,
    );
  }

  static bool _secretKey(String key) => isPrivatePreferenceKey(key);
}

class LegacyImportResult {
  const LegacyImportResult({
    required this.history,
    required this.playlists,
    required this.downloads,
    required this.settings,
    required this.skippedKeys,
  });
  final int history, playlists, downloads, settings;
  final List<String> skippedKeys;
}
