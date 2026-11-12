import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' as rendering;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/core/domain/failure_reporter.dart';
import 'package:bitchord_desktop/app/app.dart';
import 'package:bitchord_desktop/app/router.dart';
import 'package:bitchord_desktop/design_system/palette.dart';
import 'package:bitchord_desktop/features/shell/application/shell_controller.dart';
import 'package:bitchord_desktop/features/shell/domain/shell_settings.dart';

final class TestDiagnostics implements FailureReporter {
  final failures = <String>[];
  @override
  void failure(String operation, Object error) {
    failures.add(operation);
  }
}

// Test fixtures only: no simulated service is wired into the production app.
final class MemorySettings implements ShellSettingsStore {
  ShellSettings value = const ShellSettings();
  final writes = <ShellSettings>[];
  @override
  Future<ShellSettings> read() async => value;
  @override
  Future<void> write(ShellSettings settings) async {
    value = settings;
    writes.add(settings);
  }
}

final class TestWindow implements DesktopWindow {
  int toggles = 0;
  int exits = 0;
  @override
  Future<void> toggleFullscreen() async {
    toggles++;
  }

  @override
  Future<void> exitFullscreen() async {
    exits++;
  }

  @override
  Future<void> close() async {}
}

Future<ProviderContainer> mount(
  WidgetTester tester,
  Size size, {
  ShellSettings settings = const ShellSettings(),
  TestWindow? window,
  GlobalKey? capture,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(
    overrides: [
      diagnosticsProvider.overrideWithValue(TestDiagnostics()),
      initialSettingsProvider.overrideWithValue(settings),
      settingsStoreProvider.overrideWithValue(MemorySettings()),
      desktopWindowProvider.overrideWithValue(window ?? TestWindow()),
    ],
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: RepaintBoundary(key: capture, child: const BitChordApp()),
    ),
  );
  await tester.pumpAndSettle();
  return container;
}

void main() {
  test('Pitch black applies only to explicit dark, not system appearance', () {
    final system = BitChordPalette.resolve(
      const ShellSettings(pitchBlack: true),
      Brightness.dark,
    );
    expect(system['background'], const Color(0xff202020));
    final dark = BitChordPalette.resolve(
      const ShellSettings(appearance: Appearance.dark, pitchBlack: true),
      Brightness.light,
    );
    expect(dark['background'], Colors.black);
  });
  test(
    'Settings writes remain ordered and navigation history is bounded',
    () async {
      final store = MemorySettings();
      final container = ProviderContainer(
        overrides: [
          diagnosticsProvider.overrideWithValue(TestDiagnostics()),
          initialSettingsProvider.overrideWithValue(const ShellSettings()),
          settingsStoreProvider.overrideWithValue(store),
        ],
      );
      addTearDown(container.dispose);
      final shell = container.read(shellProvider.notifier);
      shell.update(const ShellSettings(sidebarCollapsed: true));
      shell.update(
        const ShellSettings(sidebarCollapsed: false, reduceBlur: true),
      );
      await shell.flush();
      expect(store.writes.length, 2);
      expect(store.value.reduceBlur, isTrue);
      final history = container.read(historyProvider.notifier);
      for (var i = 0; i < 100; i++) {
        history.visit('/route-$i');
      }
      expect(container.read(historyProvider).length, 32);
      expect(history.back(), '/route-98');
    },
  );
  testWidgets(
    '1220 shell preserves expanded sidebar, empty dock, disabled transport',
    (tester) async {
      await mount(tester, const Size(1220, 780));
      expect(
        tester.getSize(find.byKey(const Key('inline-sidebar'))).width,
        220,
      );
      expect(
        tester.getSize(find.byKey(const Key('player-dock'))),
        const Size(760, 58),
      );
      expect(
        tester
            .widget<IconButton>(find.byKey(const Key('transport-play')))
            .onPressed,
        isNull,
      );
      expect(find.text('Your playlists will appear here'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'Narrow drawer is transient and cannot overwrite collapse preference',
    (tester) async {
      final container = await mount(tester, const Size(900, 600));
      expect(tester.getSize(find.byKey(const Key('inline-sidebar'))).width, 72);
      await tester.tap(find.byKey(const Key('sidebar-brand-toggle')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('drawer-scrim')), findsOneWidget);
      expect(container.read(shellProvider).sidebarCollapsed, isFalse);
      tester.view.physicalSize = const Size(1220, 780);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('drawer-scrim')), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'Navigation, appearance changes, back and window shortcuts are real actions',
    (tester) async {
      final window = TestWindow();
      final container = await mount(
        tester,
        const Size(1220, 780),
        window: window,
      );
      await tester.tap(find.byKey(const Key('nav-settings')));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(ChoiceChip, 'dark'));
      await tester.pumpAndSettle();
      expect(container.read(shellProvider).appearance, Appearance.dark);
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();
      expect(find.text('Music services are not connected.'), findsOneWidget);
      await tester.sendKeyEvent(LogicalKeyboardKey.f11);
      await tester.pumpAndSettle();
      expect(window.toggles, 1);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('Capture shell renders at reference dimensions without overflow', (
    tester,
  ) async {
    for (final size in [const Size(1220, 780), const Size(900, 600)]) {
      final key = GlobalKey();
      await mount(
        tester,
        size,
        capture: key,
        settings: const ShellSettings(appearance: Appearance.dark),
      );
      expect(tester.takeException(), isNull);
      if (Platform.environment['BITCHORD_CAPTURE'] == '1') {
        // Image rasterization/PNG encoding uses engine threads, not fake time.
        await tester.runAsync(() async {
          final boundary =
              key.currentContext!.findRenderObject()!
                  as rendering.RenderRepaintBoundary;
          final image = await boundary.toImage(pixelRatio: 1);
          try {
            final bytes = await image.toByteData(
              format: ui.ImageByteFormat.png,
            );
            if (bytes == null)
              throw StateError('PNG encoding returned no data');
            final output = File(
              'verification/shell-dark-${size.width.toInt()}x${size.height.toInt()}.png',
            );
            output.parent.createSync(recursive: true);
            output.writeAsBytesSync(bytes.buffer.asUint8List());
          } finally {
            image.dispose();
          }
        });
        expect(tester.takeException(), isNull);
      }
      await tester.pumpWidget(const SizedBox());
    }
  });
}
