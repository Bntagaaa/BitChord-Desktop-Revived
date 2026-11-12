import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' as foundation;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'app/app.dart';
import 'core/platform/desktop_host.dart';
import 'core/platform/diagnostics.dart';
import 'features/shell/application/shell_controller.dart';
import 'features/shell/data/preferences_store.dart';
import 'features/shell/domain/shell_settings.dart';

Future<void> main() async {
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
  final container = ProviderContainer(
    overrides: [
      settingsStoreProvider.overrideWithValue(store),
      initialSettingsProvider.overrideWithValue(settings),
      desktopWindowProvider.overrideWithValue(host),
      diagnosticsProvider.overrideWithValue(diagnostics),
    ],
  );
  host.beforeClose = () async {
    await container.read(shellProvider.notifier).flush();
    container.dispose();
  };
  runApp(
    UncontrolledProviderScope(container: container, child: const BitChordApp()),
  );
  await host.show();
}
