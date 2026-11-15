import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../domain/listening_settings.dart';
import '../domain/sleep_timer.dart';

final listeningPortProvider = Provider<ListeningPort?>((ref) => null);
final listeningProvider = NotifierProvider<ListeningController, ListeningView>(
  ListeningController.new,
);

final class ListeningView {
  const ListeningView(
    this.settings, {
    this.available = false,
    this.saving = false,
    this.tempoAvailable = false,
    this.error,
  });
  final ListeningSettings settings;
  final bool available, saving;
  final bool tempoAvailable;
  final String? error;
}

// One active durable/native update plus one coalesced intent. Slider gestures
// cannot create an unbounded command/CAS backlog or reorder saved settings.
final class ListeningController extends Notifier<ListeningView> {
  ListeningSettings? _pending;
  Future<void> _writes = Future.value();
  bool _saving = false, _disposed = false;
  @override
  ListeningView build() {
    ref.onDispose(() => _disposed = true);
    final port = ref.read(listeningPortProvider);
    return ListeningView(
      port?.listening ?? ListeningSettings(),
      available: port?.supportsListening ?? false,
      tempoAvailable: port is TempoPort && (port as TempoPort).supportsTempo,
    );
  }

  void update(ListeningSettings value) {
    if (!state.available || _disposed) return;
    state = ListeningView(
      value,
      available: true,
      saving: true,
      tempoAvailable: state.tempoAvailable,
    );
    _pending = value;
    if (!_saving) {
      _saving = true;
      _writes = _drain(ref.read(listeningPortProvider)!);
    }
  }

  Future<void> _drain(ListeningPort port) async {
    String? failure;
    try {
      while (_pending != null) {
        final value = _pending!;
        _pending = null;
        try {
          await port.configureListening(value);
          failure = null;
        } catch (_) {
          failure = 'Could not apply sound settings. Try again.';
        }
      }
    } finally {
      _saving = false;
      if (!_disposed)
        state = ListeningView(
          state.settings,
          available: true,
          error: failure,
          tempoAvailable: state.tempoAvailable,
        );
    }
  }

  Future<void> flush() => _writes;
}
