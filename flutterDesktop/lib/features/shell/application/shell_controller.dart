import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/domain/failure_reporter.dart';
import '../domain/shell_settings.dart';

final settingsStoreProvider = Provider<ShellSettingsStore>(
  (ref) => throw StateError('Bootstrap must provide settings storage'),
);
final initialSettingsProvider = Provider<ShellSettings>(
  (ref) => throw StateError('Bootstrap must load settings'),
);
final desktopWindowProvider = Provider<DesktopWindow>(
  (ref) => throw StateError('Bootstrap must provide a desktop host'),
);
final diagnosticsProvider = Provider<FailureReporter>(
  (ref) => throw StateError('Bootstrap must provide diagnostics'),
);
final shellProvider = NotifierProvider<ShellController, ShellSettings>(
  ShellController.new,
);

final class ShellController extends Notifier<ShellSettings> {
  Future<void> _writes = Future<void>.value();
  bool _saving = false;
  ShellSettings? _pending;
  @override
  ShellSettings build() => ref.read(initialSettingsProvider);

  void update(ShellSettings settings) {
    state = settings;
    // One active write and one latest snapshot, rather than an unbounded future chain.
    _pending = settings;
    if (!_saving) {
      _saving = true;
      _writes = _drain(
        ref.read(settingsStoreProvider),
        ref.read(diagnosticsProvider),
      );
    }
  }

  Future<void> _drain(
    ShellSettingsStore store,
    FailureReporter diagnostics,
  ) async {
    try {
      while (_pending != null) {
        final snapshot = _pending!;
        _pending = null;
        try {
          await store.write(snapshot);
        } catch (error) {
          diagnostics.failure('save settings', error);
        }
      }
    } finally {
      _saving = false;
    }
  }

  Future<void> flush() => _writes;
}
