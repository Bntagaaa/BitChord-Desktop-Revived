import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../lib/features/online/application/catalog_controller.dart';
import '../lib/features/online/application/search_suggestions.dart';
import '../lib/features/online/domain/catalog.dart';
import '../lib/features/online/domain/online_port.dart';
import '../lib/features/online/presentation/catalog_search_field.dart';
import '../lib/features/online/presentation/download_manager.dart';
import '../lib/features/online/presentation/catalog_surface.dart';
import '../lib/features/online/application/download_queue.dart';
import '../lib/design_system/palette.dart';
import '../lib/design_system/theme.dart';
import 'download_queue_test.dart' show ControlledDownloads, track;
import 'online_controller_test.dart' show ControlledCatalog;

class SuggestionPort extends ControlledCatalog implements OnlineSuggestions {
  final lookups = <String, Completer<List<String>>>{};
  final suggestionScopes = <String, RequestScope>{};
  @override
  Future<List<String>> suggestions(String query, RequestScope scope) {
    suggestionScopes[query] = scope;
    return (lookups[query] = Completer<List<String>>()).future;
  }
}

void main() {
  testWidgets(
    'suggestions debounce, reject stale results, and dismiss on submit',
    (tester) async {
      final port = SuggestionPort();
      final container = ProviderContainer(
        overrides: [onlinePortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      final suggestions = container.read(searchSuggestionsProvider.notifier);
      suggestions.edit('a', youtube: true);
      await tester.pump(const Duration(milliseconds: 100));
      suggestions.edit('ab', youtube: true);
      await tester.pump(const Duration(milliseconds: 201));
      expect(port.lookups.keys, ['ab']);
      suggestions.edit('abc', youtube: true);
      expect(port.suggestionScopes['ab']!.cancelled, isTrue);
      await tester.pump(const Duration(milliseconds: 201));
      port.lookups['abc']!.complete(['abc', 'abc song']);
      await tester.pump();
      port.lookups['ab']!.complete(['Stale']);
      await tester.pump();
      expect(container.read(searchSuggestionsProvider), ['abc song']);
      suggestions.dismiss();
      expect(container.read(searchSuggestionsProvider), isEmpty);
      suggestions.edit('addon', youtube: false);
      await tester.pump(const Duration(milliseconds: 250));
      expect(port.lookups.containsKey('addon'), isFalse);
    },
  );

  testWidgets(
    'search pill keeps reference dimensions, mouse selection and fill semantics',
    (tester) async {
      final port = SuggestionPort();
      final input = TextEditingController();
      addTearDown(input.dispose);
      final submitted = <String>[];
      await tester.pumpWidget(
        ProviderScope(
          overrides: [onlinePortProvider.overrideWithValue(port)],
          child: MaterialApp(
            home: Scaffold(
              body: Column(
                children: [
                  CatalogSearchField(
                    controller: input,
                    youtube: true,
                    onSubmit: submitted.add,
                    onEdit: () {},
                  ),
                  const TextButton(onPressed: null, child: Text('Outside')),
                ],
              ),
            ),
          ),
        ),
      );
      expect(
        tester.getSize(find.byKey(const Key('catalog-search-pill'))),
        const Size(492, 42),
      );
      await tester.enterText(
        find.byKey(const Key('catalog-search-input')),
        'fixture',
      );
      await tester.pump(const Duration(milliseconds: 201));
      port.lookups['fixture']!.complete(['fixture artist', 'fixture song']);
      await tester.pump();
      expect(port.searchCalls, 0);
      await tester.tap(find.byTooltip('Fill search').first);
      await tester.pump();
      expect(input.text, 'fixture artist');
      expect(submitted, isEmpty);
      await tester.pump(const Duration(milliseconds: 201));
      port.lookups['fixture artist']!.complete(['fixture artist album']);
      await tester.pump();
      await tester.pump();
      final pointer = await tester.startGesture(
        tester.getCenter(find.text('fixture artist album')),
      );
      await tester.pump();
      expect(find.text('fixture artist album'), findsOneWidget);
      await pointer.up();
      await tester.pump();
      expect(submitted, ['fixture artist album']);
      expect(find.byKey(const ValueKey('suggestion-0')), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('arrow keys choose a suggestion and Escape dismisses it', (
    tester,
  ) async {
    final port = SuggestionPort();
    final input = TextEditingController();
    addTearDown(input.dispose);
    final submitted = <String>[];
    await tester.pumpWidget(
      ProviderScope(
        overrides: [onlinePortProvider.overrideWithValue(port)],
        child: MaterialApp(
          home: Scaffold(
            body: CatalogSearchField(
              controller: input,
              youtube: true,
              onSubmit: submitted.add,
              onEdit: () {},
            ),
          ),
        ),
      ),
    );
    await tester.enterText(find.byType(TextField), 'fixture');
    await tester.pump(const Duration(milliseconds: 201));
    port.lookups['fixture']!.complete(['fixture song']);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pump();
    expect(submitted, ['fixture song']);
    await tester.enterText(find.byType(TextField), 'again');
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pump(const Duration(milliseconds: 250));
    expect(find.byKey(const ValueKey('suggestion-0')), findsNothing);
    expect(port.lookups.containsKey('again'), isFalse);
  });

  for (final dark in [false, true]) {
    testWidgets(
      'Phase 4 ${dark ? 'dark' : 'light'} Search and download diagnostics',
      (tester) async {
        tester.view.physicalSize = const Size(900, 600);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final input = TextEditingController();
        addTearDown(input.dispose);
        final port = ControlledDownloads();
        final container = ProviderContainer(
          overrides: [onlinePortProvider.overrideWithValue(port)],
        );
        addTearDown(container.dispose);
        final boundary = GlobalKey();
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: RepaintBoundary(
              key: boundary,
              child: MaterialApp(
                theme: bitChordTheme(
                  dark ? BitChordPalette.dark : BitChordPalette.light,
                  dark ? Brightness.dark : Brightness.light,
                ),
                home: Scaffold(
                  body: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      children: [
                        CatalogSearchField(
                          controller: input,
                          youtube: true,
                          onSubmit: (_) {},
                          onEdit: () {},
                        ),
                        Builder(
                          builder: (context) => TextButton(
                            onPressed: () => showDialog<void>(
                              context: context,
                              builder: (_) => const DownloadManager(),
                            ),
                            child: const Text('Downloads'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        Future<void> capture(String name) async {
          if (Platform.environment['BITCHORD_CAPTURE'] != '1') return;
          await tester.runAsync(() async {
            final render =
                boundary.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary;
            final image = await render.toImage();
            try {
              final bytes = await image.toByteData(
                format: ui.ImageByteFormat.png,
              );
              final dir = Directory('verification/phase4-search-download');
              await dir.create(recursive: true);
              await File(
                '${dir.path}/${dark ? 'dark' : 'light'}-$name.png',
              ).writeAsBytes(bytes!.buffer.asUint8List());
            } finally {
              image.dispose();
            }
          });
        }

        await capture('search-widget-900x600');
        final queue = container.read(downloadQueueProvider.notifier);
        for (var i = 0; i < 6; i++) {
          queue.enqueue(track(i));
        }
        port.progress['0']!(35, 100);
        await tester.tap(find.text('Downloads'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 300));
        expect(find.text('6 tracks'), findsOneWidget);
        expect(find.text('Downloading · 35%'), findsOneWidget);
        expect(find.text('Queued'), findsWidgets);
        expect(tester.takeException(), isNull);
        await capture('download-manager-widget-900x600');
        await tester.tap(find.text('Cancel all'));
        await tester.pump();
        expect(find.text('Nothing downloading.'), findsOneWidget);
        await tester.tap(find.text('Done'));
        await tester.pumpAndSettle();
        port.browsePage = CatalogPage(
          [for (var i = 0; i < 3; i++) track(i)],
          header: const CatalogHeader(
            title: 'Fixture album',
            subtitle: 'Album • Artist • 2026',
          ),
        );
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: RepaintBoundary(
              key: boundary,
              child: MaterialApp(
                theme: bitChordTheme(
                  dark ? BitChordPalette.dark : BitChordPalette.light,
                  dark ? Brightness.dark : Brightness.light,
                ),
                home: const Scaffold(
                  body: Padding(
                    padding: EdgeInsets.all(16),
                    child: CatalogSurface(
                      destination: 'listen-now',
                      title: 'Fixture album',
                      browseId: 'MPREfixture',
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        await capture('album-widget-900x600');
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }
}
