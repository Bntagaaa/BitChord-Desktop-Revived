import 'dart:ui' as ui;
import 'dart:io';

import 'core/platform/audio/audio_service.dart';
import 'core/platform/audio/native_api.dart';
import 'features/local_data/data/sqlite_repository.dart';
import 'features/local_music/data/local_files.dart';
import 'features/playback/application/local_playback.dart';

import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' as foundation;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app/app.dart';
import 'features/online/application/catalog_controller.dart';
import 'features/online/data/online_runtime.dart';
import 'core/platform/desktop_host.dart';
import 'core/platform/diagnostics.dart';
import 'features/shell/application/shell_controller.dart';
import 'features/shell/data/preferences_store.dart';
import 'features/shell/domain/shell_settings.dart';

Future<void> main(List<String> arguments) async {
  WidgetsFlutterBinding.ensureInitialized();
  foundation.LicenseRegistry.addLicense(() async* {
    yield foundation.LicenseEntryWithLineBreaks([
      'BitChord reference assets',
    ], await rootBundle.loadString('assets/LICENSE_BitChord.txt'));
    yield foundation.LicenseEntryWithLineBreaks([
      'BitChord and Cupertino glyphs',
    ], await rootBundle.loadString('assets/NOTICE.md'));
  });
  final diagnostics = Diagnostics();
  FlutterError.onError = (details) =>
      diagnostics.failure('flutter', details.exception);
  ui.PlatformDispatcher.instance.onError = (error, stack) {
    diagnostics.failure('unhandled asynchronous error', error);
    return true;
  };
  final store = PreferencesStore(SharedPreferencesAsync());
  ShellSettings settings;
  try {
    settings = await store.read();
  } catch (error) {
    diagnostics.failure('read settings', error);
    settings = const ShellSettings();
  }
  final host = DesktopHost(diagnostics);
  try {
    await host.initialize();
  } catch (error) {
    diagnostics.failure('initialize desktop', error);
    rethrow;
  }
  final audio = await AudioService.start();
  final dataRoots = arguments.where(
    (value) => value.startsWith('--data-root='),
  );
  final root = Directory(
    SqliteLocalRepository.storageRoot(
      portableRoot: dataRoots.isEmpty ? null : dataRoots.single.substring(12),
    ),
  );
  await root.create(recursive: true);
  final playback = LocalPlayback(
    SqliteLocalRepository(
      path: '${root.path}/local-v1.sqlite3',
      libraryPath: NativeApi.bundledLibrary,
    ),
    audio,
  );
  try {
    await playback.initialize();
    final files = arguments.where((value) => value.startsWith('--local-file='));
    if (files.isNotEmpty)
      await playback.playLocal(
        await LocalFiles.entry(files.single.substring(13)),
      );
  } catch (_) {
    await audio.close();
    rethrow;
  }
  final online = OnlineRuntime(
    playback,
    Directory('${root.path}/downloads-v1'),
  );
  final container = ProviderContainer(
    overrides: [
      onlinePortProvider.overrideWithValue(online),
      settingsStoreProvider.overrideWithValue(store),
      initialSettingsProvider.overrideWithValue(settings),
      desktopWindowProvider.overrideWithValue(host),
      diagnosticsProvider.overrideWithValue(diagnostics),
    ],
  );
  host.beforeClose = () async {
    await container.read(shellProvider.notifier).flush();
    container.read(catalogProvider.notifier).cancel();
    await online.close();
    container.dispose();
  };
  runApp(
    UncontrolledProviderScope(container: container, child: const BitChordApp()),
  );
  await host.show();
}
