import 'package:bitchord_desktop/app/app.dart';
import 'package:bitchord_desktop/design_system/palette.dart';
import 'package:bitchord_desktop/features/online/application/catalog_controller.dart';
import 'package:bitchord_desktop/features/online/application/catalog_playback.dart';
import 'package:bitchord_desktop/features/online/domain/catalog.dart';
import 'package:bitchord_desktop/features/online/presentation/catalog_components.dart';
import 'package:bitchord_desktop/features/playback/presentation/player_surfaces.dart';
import 'package:bitchord_desktop/features/shell/application/shell_controller.dart';
import 'package:bitchord_desktop/features/shell/domain/shell_settings.dart';
import 'package:bitchord_desktop/features/shell/presentation/settings_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart' hide TestWindow;

import 'catalog_playback_test.dart' show ContextPort;
import 'online_controller_test.dart' show transportFixture;
import 'shell_test.dart' show MemorySettings, TestDiagnostics, TestWindow;

void main() {
  testWidgets(
    'Home Listen again uses four compact rows and a real grid switch',
    (tester) async {
      tester.view.physicalSize = const Size(900, 600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: CatalogShelfView(
                shelf: CatalogShelf(
                  title: 'Listen again',
                  entries: List.generate(
                    8,
                    (i) => CatalogEntry(
                      provider: 'youtube',
                      id: '$i',
                      title: 'Song $i',
                    ),
                  ),
                ),
                hero: true,
                onSelect: (_) {},
                onOpen: (_, _, _) {},
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        tester.getSize(find.byType(CatalogArtwork).first),
        const Size(48, 48),
      );
      expect(find.byTooltip('Options for Song 0'), findsOneWidget);
      await tester.tap(find.byTooltip('Show as grid'));
      await tester.pumpAndSettle();
      expect(
        tester.getSize(find.byType(CatalogArtwork).first),
        const Size(320, 320),
      );
      await tester.tap(find.byTooltip('Show as list'));
      await tester.pumpAndSettle();
      expect(
        tester.getSize(find.byType(CatalogArtwork).first),
        const Size(48, 48),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Settings filters sections, preserves query on back and disables missing capabilities',
    (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            initialSettingsProvider.overrideWithValue(const ShellSettings()),
            settingsStoreProvider.overrideWithValue(MemorySettings()),
            diagnosticsProvider.overrideWithValue(TestDiagnostics()),
          ],
          child: const MaterialApp(
            home: Scaffold(body: SettingsPage(palette: BitChordPalette.dark)),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Account & integrations'), findsOneWidget);
      expect(find.text('LISTEN TOGETHER'), findsOneWidget);
      await tester.enterText(
        find.byKey(const Key('settings-search')),
        'Lyrics',
      );
      await tester.pumpAndSettle();
      expect(find.text('ACCOUNT'), findsNothing);
      expect(
        tester.widget<Switch>(find.byType(Switch).first).onChanged,
        isNull,
      );
      await tester.tap(find.text('Lyrics sources'));
      await tester.pumpAndSettle();
      expect(
        find.text('Lyrics providers are not connected in this build.'),
        findsOneWidget,
      );
      await tester.tap(find.byTooltip('Back to Settings'));
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('settings-search')))
            .controller!
            .text,
        'Lyrics',
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Native appearance smoke keyboard sequence changes real settings',
    (tester) async {
      final container = ProviderContainer(
        overrides: [
          initialSettingsProvider.overrideWithValue(const ShellSettings()),
          settingsStoreProvider.overrideWithValue(MemorySettings()),
          diagnosticsProvider.overrideWithValue(TestDiagnostics()),
        ],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(
            home: Scaffold(body: SettingsPage(palette: BitChordPalette.dark)),
          ),
        ),
      );
      await tester.pumpAndSettle();
      Future<void> choose(String query, int tabs) async {
        await tester.tap(find.byKey(const Key('settings-search')));
        await tester.enterText(find.byKey(const Key('settings-search')), query);
        await tester.pumpAndSettle();
        for (var i = 0; i < tabs; i++)
          await tester.sendKeyEvent(LogicalKeyboardKey.tab);
        await tester.sendKeyEvent(LogicalKeyboardKey.space);
        await tester.pumpAndSettle();
      }

      await choose('Theme', 4);
      expect(container.read(shellProvider).appearance, Appearance.dark);
      await choose('Pitch black', 2);
      expect(container.read(shellProvider).pitchBlack, isTrue);
      await choose('Theme', 3);
      expect(container.read(shellProvider).appearance, Appearance.light);
      expect(tester.takeException(), isNull);
    },
  );

  for (final size in [const Size(1220, 780), const Size(900, 600)]) {
    testWidgets(
      'Playing dock expands, preserves audio identity and opens queue at $size',
      (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final port = ContextPort()..currentSnapshot = transportFixture();
        final container = ProviderContainer(
          overrides: [
            onlinePortProvider.overrideWithValue(port),
            initialSettingsProvider.overrideWithValue(const ShellSettings()),
            settingsStoreProvider.overrideWithValue(MemorySettings()),
            diagnosticsProvider.overrideWithValue(TestDiagnostics()),
            desktopWindowProvider.overrideWithValue(TestWindow()),
          ],
        );
        addTearDown(container.dispose);
        const first = CatalogEntry(
          provider: 'youtube',
          id: 'first',
          title: 'Current song',
        );
        const second = CatalogEntry(
          provider: 'youtube',
          id: 'second',
          title: 'Next song',
        );
        await container
            .read(catalogPlaybackProvider.notifier)
            .select(first, context: [first, second], index: 0);
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: const BitChordApp(),
          ),
        );
        await tester.pump(const Duration(milliseconds: 501));
        await tester.pump();
        expect(
          tester.getSize(find.byKey(const Key('playing-dock'))).height,
          58,
        );
        await tester.tap(find.byKey(const Key('expand-player')));
        await tester.pump();
        expect(find.byType(ExpandedPlayer), findsOneWidget);
        expect(port.played, ['first']);
        await tester.tap(find.byTooltip('Queue').last);
        await tester.pump();
        expect(find.text('Next song'), findsOneWidget);
        await tester.tap(find.text('Next song'));
        await tester.pump();
        expect(port.played, ['first', 'second']);
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pump();
        expect(container.read(playerLayoutProvider).pane, isNull);
        expect(container.read(playerLayoutProvider).expanded, isTrue);
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pump();
        expect(find.byType(ExpandedPlayer), findsNothing);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }
}
