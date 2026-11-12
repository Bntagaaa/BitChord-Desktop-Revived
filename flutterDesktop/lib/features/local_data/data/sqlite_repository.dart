import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import '../../../core/platform/audio/native_api.dart';
import '../../playback/domain/local_queue.dart';
import '../domain/local_state.dart';

final class SqliteLocalRepository implements LocalRepository {
  const SqliteLocalRepository({required this.path, required this.libraryPath});
  final String path, libraryPath;
  static String storageRoot({String? portableRoot}) {
    if (portableRoot != null) return Directory(portableRoot).absolute.path;
    final env = Platform.environment;
    final base = Platform.isWindows
        ? env['LOCALAPPDATA']
        : env['XDG_DATA_HOME'] ?? '${env['HOME']}/.local/share';
    if (base == null || base.isEmpty)
      throw StateError('No durable storage root');
    return '$base/BitChordFlutter'; // Deliberately separate from Kotlin preferences/media.
  }

  @override
  Future<LocalState> read() async {
    final file = path, library = libraryPath;
    return Isolate.run(() {
      final (text, revision) = NativeApi(library).readStore(file);
      if (text == null) return LocalState(queue: LocalQueue([]));
      final state = LocalState.fromJson(
        (jsonDecode(text) as Map).cast<String, Object?>(),
      );
      if (state.revision != revision)
        throw StateError('Stored revision mismatch');
      return state;
    });
  }

  @override
  Future<void> write(LocalState value, {required int expectedRevision}) async {
    if (value.revision != expectedRevision + 1)
      throw ArgumentError('Revision must advance once');
    final file = path, library = libraryPath, json = jsonEncode(value.toJson());
    await Isolate.run(
      () => NativeApi(library).writeStore(file, json, expectedRevision),
    );
  }
}
