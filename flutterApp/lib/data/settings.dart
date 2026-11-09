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
}
