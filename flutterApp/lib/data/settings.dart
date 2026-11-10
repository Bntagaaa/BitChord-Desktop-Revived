import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

class Settings extends ChangeNotifier {
  Settings.memory();
  File? _file;
  final Map<String, dynamic> _values = {};
  Future<void> _writes = Future.value();
  String? error;

  static Future<Settings> load() async {
    final settings = Settings.memory();
    final support = await getApplicationSupportDirectory();
    // A separate namespace preserves the original Java Preferences and accounts.
    settings._file = File('${support.path}/flutter-reworked/settings.json');
    if (await settings._file!.exists()) {
      final decoded = jsonDecode(await settings._file!.readAsString());
      if (decoded is Map<String, dynamic>) settings._values.addAll(decoded);
    }
    return settings;
  }

  T get<T>(String key, T fallback) {
    final value = _values[key];
    return value is T ? value : fallback;
  }

  Future<void> set(String key, Object value) {
    _values[key] = value;
    notifyListeners();
    final snapshot = jsonEncode(_values);
    _writes = _writes
        .then((_) async {
          final file = _file;
          if (file == null) return;
          await file.parent.create(recursive: true);
          final temporary = File('${file.path}.tmp');
          await temporary.writeAsString(snapshot, flush: true);
          await temporary.rename(file.path);
          error = null;
        })
        .catchError((Object failure) {
          error = 'Could not save settings';
          notifyListeners();
        });
    return _writes;
  }

  static const Set<String> secretKeys = {
    'spotify_spdc_token',
    'spotify_dc',
    'lastfm_session_key',
    'lastfm_api_key',
    'lastfm_secret',
    'listenbrainz_token',
    'paxsenix_api_key',
  };

  static const Set<String> deviceLocalKeys = {
    'downloaded_tracks',
    'downloaded_tracks_metadata',
    'downloaded_collections',
    'local_music_folder',
    'local_music_folder_uri',
    'last_version_code',
  };

  Map<String, dynamic> exportPrefs() {
    return Map<String, dynamic>.from(_values)
      ..removeWhere((k, _) => secretKeys.contains(k) || deviceLocalKeys.contains(k));
  }

  Future<void> importPrefs(Map<String, dynamic> incoming) {
    final kept = <String, dynamic>{};
    for (final entry in _values.entries) {
      if (secretKeys.contains(entry.key) || deviceLocalKeys.contains(entry.key)) {
        kept[entry.key] = entry.value;
      }
    }
    _values.clear();
    _values.addAll(kept);
    for (final entry in incoming.entries) {
      if (!secretKeys.contains(entry.key) && !deviceLocalKeys.contains(entry.key)) {
        _values[entry.key] = entry.value;
      }
    }
    notifyListeners();
    final snapshot = jsonEncode(_values);
    _writes = _writes
        .then((_) async {
          final file = _file;
          if (file == null) return;
          await file.parent.create(recursive: true);
          final temporary = File('${file.path}.tmp');
          await temporary.writeAsString(snapshot, flush: true);
          await temporary.rename(file.path);
          error = null;
        })
        .catchError((Object failure) {
          error = 'Could not save settings';
          notifyListeners();
        });
    return _writes;
  }
}
