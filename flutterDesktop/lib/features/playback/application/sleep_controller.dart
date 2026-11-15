import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../domain/sleep_timer.dart';

final sleepPortProvider = Provider<SleepPort?>((ref) => null);
final sleepProvider = NotifierProvider<SleepController, SleepView>(
  SleepController.new,
);

final class SleepView {
  const SleepView({
    this.available = false,
    this.busy = false,
    this.status = const SleepStatus(),
    this.error,
  });
  final bool available, busy;
  final SleepStatus status;
  final String? error;
}

final class SleepController extends Notifier<SleepView> {
  int _revision = 0;
  bool _disposed = false, _polling = false;
  @override
  SleepView build() {
    final port = ref.read(sleepPortProvider);
    if (port == null || !port.supportsSleep) return const SleepView();
    final timer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => unawaited(_poll(port)),
    );
    ref.onDispose(() {
      _disposed = true;
      timer.cancel();
    });
    return const SleepView(available: true);
  }

  Future<void> _poll(SleepPort port) async {
    if (_polling || state.busy) return;
    _polling = true;
    final revision = _revision;
    try {
      final status = await port.sleepStatus();
      if (!_disposed && !state.busy && revision == _revision)
        state = SleepView(available: true, status: status, error: state.error);
    } catch (_) {
      if (!_disposed && revision == _revision)
        state = SleepView(
          available: true,
          status: state.status,
          error: 'Could not read sleep timer.',
        );
    } finally {
      _polling = false;
    }
  }

  Future<void> configure(SleepRequest request) async {
    if (!state.available || state.busy || _disposed) return;
    ++_revision;
    state = SleepView(available: true, busy: true, status: state.status);
    final port = ref.read(sleepPortProvider)!;
    try {
      await port.configureSleep(request);
      final status = await port.sleepStatus();
      if (!_disposed) state = SleepView(available: true, status: status);
    } catch (_) {
      if (!_disposed)
        state = SleepView(
          available: true,
          status: state.status,
          error: 'Could not apply sleep timer. Try again.',
        );
    }
  }
}
