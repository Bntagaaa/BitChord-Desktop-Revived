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
import 'package:bitchord_desktop/design_system/theme.dart';
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
      await tester.sendKeyDownEvent(LogicalKeyboardKey.altLeft);
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
      await tester.sendKeyUpEvent(LogicalKeyboardKey.altLeft);
      await tester.pumpAndSettle();
      expect(find.text('Music services are not connected.'), findsOneWidget);
      await tester.sendKeyEvent(LogicalKeyboardKey.f11);
      await tester.pumpAndSettle();
      expect(window.toggles, 1);
      expect(tester.takeException(), isNull);
    },
  );
  test(
    'Reference typography avoids inherited Material heading line heights',
    () {
      final text = bitChordTheme(
        BitChordPalette.light,
        Brightness.light,
      ).textTheme;
      expect(text.headlineLarge!.fontSize, 30);
      expect(text.headlineLarge!.fontWeight, FontWeight.w700);
      expect(text.headlineLarge!.letterSpacing, -.4);
      expect(text.headlineLarge!.height, ui.kTextHeightNone);
      expect(text.bodySmall!.fontSize, 12);
      expect(text.bodySmall!.height, 16 / 12);
      expect(text.labelLarge!.fontWeight, FontWeight.w400);
    },
  );
  testWidgets('Default page, sidebar and dock use measured reference bounds', (
    tester,
  ) async {
    await mount(tester, const Size(1220, 780));
    expect(
      tester.getTopLeft(find.byKey(const Key('page-heading'))),
      const Offset(236, 9),
    );
    expect(
      find.text('Home'),
      findsNWidgets(2),
    ); // Sidebar label and page heading only.
    expect(find.byTooltip('Music services are not connected'), findsNothing);
    expect(tester.getRect(find.byKey(const Key('nav-listen-now'))).top, 61);
    expect(tester.getRect(find.byKey(const Key('nav-history'))).top, 208);
    expect(tester.getRect(find.byKey(const Key('sidebar-account'))).top, 682);
    expect(
      tester.getRect(find.byKey(const Key('player-dock'))),
      const Rect.fromLTWH(340, 702, 760, 58),
    );
    expect(
      tester.getSize(find.byKey(const Key('empty-dock-mark'))),
      const Size(48, 48),
    );
    for (final action in ['play', 'next', 'pip', 'details', 'audio-output']) {
      expect(
        tester
            .widget<IconButton>(find.byKey(Key('transport-$action')))
            .onPressed,
        isNull,
      );
    }
    expect(tester.getSize(find.byKey(const Key('dock-volume'))).width, 72);
    expect(tester.takeException(), isNull);
  });
  testWidgets('Windows Home title matches the pinned glyph top', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;
    addTearDown(() => debugDefaultTargetPlatformOverride = null);
    await mount(tester, const Size(1220, 780));
    expect(
      tester.getTopLeft(find.byKey(const Key('page-heading'))),
      const Offset(236, 5),
    );
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'Account menu is honest, anchored and dismisses before fullscreen',
    (tester) async {
      final window = TestWindow();
      await mount(tester, const Size(1220, 780), window: window);
      expect(find.text('Sign in'), findsOneWidget);
      await tester.tap(find.byKey(const Key('sidebar-account')));
      await tester.pumpAndSettle();
      final panel = tester.getRect(find.byKey(const Key('account-panel')));
      expect(panel.left, 24);
      expect(panel.width, 292);
      expect(panel.bottom, 716);
      expect(find.text('Add account'), findsOneWidget);
      await tester.tap(find.text('Add account'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('account-panel')), findsOneWidget);
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('account-panel')), findsNothing);
      expect(window.exits, 0);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'Rail playlist affordance and account footer preserve the width policy',
    (tester) async {
      final container = await mount(tester, const Size(900, 600));
      expect(tester.getRect(find.byKey(const Key('nav-listen-now'))).top, 67);
      expect(tester.getRect(find.byKey(const Key('nav-history'))).top, 254);
      expect(tester.getRect(find.byKey(const Key('sidebar-account'))).top, 494);
      await tester.tap(find.byKey(const Key('browse-playlists')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('drawer-scrim')), findsOneWidget);
      await tester.tap(find.byKey(const Key('sidebar-account')).last);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('drawer-scrim')), findsNothing);
      expect(find.byKey(const Key('account-panel')), findsOneWidget);
      expect(container.read(shellProvider).sidebarCollapsed, isFalse);
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
