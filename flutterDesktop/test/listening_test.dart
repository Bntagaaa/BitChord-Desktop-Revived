// Port/controller/widget tests only; these do not assert real audio output.
// Native differential/decoder tests and phase5_check.dart provide DSP evidence.
import 'dart:async';
import 'package:bitchord_desktop/features/local_data/domain/local_state.dart';
import 'package:bitchord_desktop/features/playback/application/listening_controller.dart';
import 'package:bitchord_desktop/features/playback/domain/listening_settings.dart';
import 'package:bitchord_desktop/features/playback/domain/sleep_timer.dart';
import 'package:bitchord_desktop/features/playback/presentation/listening_panel.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Port implements ListeningPort {
  @override
  bool supportsListening = true;
  @override
  ListeningSettings listening = ListeningSettings();
  final writes = <ListeningSettings>[];
  Completer<void>? hold;
  bool fail = false;
  @override
  Future<void> configureListening(ListeningSettings settings) async {
    writes.add(settings);
    await hold?.future;
    if (fail) throw StateError('write failed');
    listening = settings;
  }
}

class _TempoPort extends _Port implements TempoPort {
  @override
  bool get supportsTempo => true;
}

void main() {
  test('typed payload, immutable gains and persisted older settings', () {
    final bands = List<double>.filled(7, 0);
    final settings = ListeningSettings(
      enabled: true,
      mode: EqualizerMode.manual,
      bands: bands,
      spatial: true,
    );
    bands[0] = 12;
    expect(settings.bands[0], 0);
    expect(
      settings.nativePayload,
      '1 1 0 0 0 0 0.0 1 0.0 0.0 0.0 0.0 0.0 0.0 0.0',
    );
    expect(
      ListeningSettings.fromJson(settings.toJson()).nativePayload,
      settings.nativePayload,
    );
    final legacy = const LocalSettings().toJson()..remove('listening');
    expect(LocalSettings.fromJson(legacy).listening, isNull);
    expect(
      LocalSettings.fromJson(
        const LocalSettings().withListening(settings).toJson(),
      ).listening!.spatial,
      isTrue,
    );
    expect(() => settings.bands[0] = 4, throwsUnsupportedError);
  });
  test('reject invalid settings before persistence or FFI', () {
    for (final value in [double.nan, double.infinity, 12.5]) {
      expect(
        () => ListeningSettings(bands: [value, 0, 0, 0, 0, 0, 0]),
        throwsArgumentError,
      );
    }
    expect(() => ListeningSettings(balance: 1.01), throwsArgumentError);
    expect(() => ListeningSettings(toneX: 6), throwsArgumentError);
    expect(() => ListeningSettings(bands: [0]), throwsArgumentError);
    for (final speed in [double.nan, double.infinity, .24, 3.01]) {
      expect(() => ListeningSettings(speed: speed), throwsArgumentError);
    }
    final legacy = ListeningSettings().toJson()
      ..remove('speed')
      ..remove('skipSilence');
    expect(ListeningSettings.fromJson(legacy).speed, 1);
    expect(ListeningSettings.fromJson(legacy).skipSilence, isFalse);
  });
  testWidgets(
    'speed uses the reference cycle and silence retains other settings',
    (tester) async {
      final port = _TempoPort();
      final container = ProviderContainer(
        overrides: [listeningPortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(home: Scaffold(body: ListeningPanel())),
        ),
      );
      var previous = 1.0;
      for (final speed in [1.25, 1.5, 2.0, .5, 1.0]) {
        await tester.tap(find.text('${previous.toStringAsFixed(2)}×'));
        await tester.pumpAndSettle();
        expect(port.listening.speed, speed);
        previous = speed;
      }
      await tester.tap(
        find.byWidgetPredicate(
          (widget) =>
              widget is Semantics &&
              widget.properties.label == 'Skip silence' &&
              widget.properties.toggled != null,
        ),
      );
      await tester.pumpAndSettle();
      expect(port.listening.skipSilence, isTrue);
      expect(port.listening.speed, 1);
    },
  );
  test(
    'gesture burst has one active update and one latest pending intent',
    () async {
      final port = _Port()..hold = Completer<void>();
      final container = ProviderContainer(
        overrides: [listeningPortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      final controller = container.read(listeningProvider.notifier);
      for (var i = 0; i < 100; i++)
        controller.update(ListeningSettings(balance: i / 100));
      expect(port.writes.length, 1);
      port.hold!.complete();
      await controller.flush();
      expect(port.writes.length, 2);
      expect(port.listening.balance, .99);
      expect(container.read(listeningProvider).saving, isFalse);
    },
  );
  test(
    'persistence/native failure is visible and retry can clear it',
    () async {
      final port = _Port()..fail = true;
      final container = ProviderContainer(
        overrides: [listeningPortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      final controller = container.read(listeningProvider.notifier);
      controller.update(ListeningSettings(enabled: true));
      await controller.flush();
      expect(container.read(listeningProvider).error, isNotNull);
      port.fail = false;
      controller.update(ListeningSettings(enabled: true));
      await controller.flush();
      expect(container.read(listeningProvider).error, isNull);
    },
  );
  testWidgets('no controls without negotiated native capability', (
    tester,
  ) async {
    await tester.pumpWidget(
      const ProviderScope(
        child: MaterialApp(home: Scaffold(body: ListeningPanel())),
      ),
    );
    expect(find.text('Equalizer'), findsNothing);
  });
  testWidgets('tone pointer/keyboard, disabled controls and manual preset', (
    tester,
  ) async {
    final port = _Port();
    final container = ProviderContainer(
      overrides: [listeningPortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(home: Scaffold(body: ListeningPanel())),
      ),
    );
    await tester.tap(find.text('Equalizer'));
    await tester.pumpAndSettle();
    await tester.tapAt(tester.getCenter(find.text('Manual')));
    await tester.pumpAndSettle();
    expect(
      container.read(listeningProvider).settings.mode,
      EqualizerMode.dynamic,
    );
    await tester.tap(find.bySemanticsLabel('Enable equalizer'));
    await tester.pumpAndSettle();
    final pad = find.bySemanticsLabel('Tone pad');
    await tester.tapAt(tester.getCenter(pad));
    await tester.pumpAndSettle();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pumpAndSettle();
    expect(container.read(listeningProvider).settings.toneX, 1);
    await tester.tap(find.text('Manual'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Acoustic'));
    await tester.pumpAndSettle();
    expect(container.read(listeningProvider).settings.bands, [
      3,
      1.5,
      0,
      1.5,
      2.5,
      2,
      1,
    ]);
    expect(tester.takeException(), isNull);
  });
}
