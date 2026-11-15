import 'dart:async';
import 'package:bitchord_desktop/features/playback/application/sleep_controller.dart';
import 'package:bitchord_desktop/features/playback/domain/sleep_timer.dart';
import 'package:bitchord_desktop/features/playback/presentation/sleep_panel.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Port implements SleepPort {
  @override
  bool supportsSleep = true;
  SleepStatus status = const SleepStatus();
  final requests = <SleepRequest>[];
  Completer<SleepStatus>? pending;
  bool fail = false;
  @override
  Future<SleepStatus> sleepStatus() => pending?.future ?? Future.value(status);
  @override
  Future<void> configureSleep(SleepRequest request) async {
    if (fail) throw StateError('unavailable');
    requests.add(request);
    status = SleepStatus(
      afterTrack: request.afterTrack,
      remainingMs: request.minutes == null ? null : request.minutes! * 60000,
    );
  }
}

void main() {
  test('reference custom bounds and exclusive wire requests', () {
    for (final invalid in [-1, 0, 4, 721]) {
      expect(() => SleepRequest.minutes(invalid), throwsArgumentError);
    }
    expect(SleepRequest.minutes(5).nativeValue, 300000);
    expect(SleepRequest.minutes(720).nativeValue, 43200000);
    expect(const SleepRequest.afterTrack().nativeValue, -1);
    expect(const SleepRequest.cancel().nativeValue, 0);
  });
  testWidgets('sleep capability, presets, validation, after-track and cancel', (
    tester,
  ) async {
    await tester.pumpWidget(
      const ProviderScope(
        child: MaterialApp(home: Scaffold(body: SleepPanel())),
      ),
    );
    expect(find.text('Sleep timer'), findsNothing);
    final port = _Port();
    final container = ProviderContainer(
      overrides: [sleepPortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(home: Scaffold(body: SleepPanel())),
      ),
    );
    await tester.tap(find.text('15 min'));
    await tester.pumpAndSettle();
    expect(port.requests.single.minutes, 15);
    expect(find.text('15 minutes remaining'), findsOneWidget);
    await tester.tap(find.text('After this track'));
    await tester.pumpAndSettle();
    expect(port.requests.last.afterTrack, isTrue);
    await tester.tap(find.text('Cancel timer'));
    await tester.pumpAndSettle();
    expect(container.read(sleepProvider).status.active, isFalse);
    await tester.tap(find.text('Custom'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '4');
    await tester.tap(find.text('Start'));
    await tester.pump();
    expect(find.text('Enter 5 to 720 minutes.'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '720');
    await tester.tap(find.text('Start'));
    await tester.pumpAndSettle();
    expect(port.requests.last.minutes, 720);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
  });
  testWidgets(
    'failed configuration remains retryable and disposal cancels polling',
    (tester) async {
      final port = _Port()..fail = true;
      final container = ProviderContainer(
        overrides: [sleepPortProvider.overrideWithValue(port)],
      );
      final controller = container.read(sleepProvider.notifier);
      await controller.configure(SleepRequest.minutes(15));
      expect(container.read(sleepProvider).error, isNotNull);
      port.fail = false;
      await controller.configure(const SleepRequest.afterTrack());
      expect(container.read(sleepProvider).error, isNull);
      port.pending = Completer<SleepStatus>();
      await tester.pump(const Duration(seconds: 1));
      container.dispose();
      port.pending!.complete(const SleepStatus());
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('a stale status poll cannot undo a later configuration', (
    tester,
  ) async {
    final port = _Port();
    final container = ProviderContainer(
      overrides: [sleepPortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    final controller = container.read(sleepProvider.notifier);
    final old = port.pending = Completer<SleepStatus>();
    await tester.pump(const Duration(seconds: 1));
    port.pending = null;
    await controller.configure(const SleepRequest.afterTrack());
    old.complete(const SleepStatus());
    await tester.pump();
    expect(container.read(sleepProvider).status.afterTrack, isTrue);
    container.dispose();
  });
}
