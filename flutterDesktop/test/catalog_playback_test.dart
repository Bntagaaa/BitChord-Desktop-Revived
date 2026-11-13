import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/features/online/application/catalog_controller.dart';
import '../lib/features/online/application/catalog_playback.dart';
import '../lib/features/online/domain/catalog.dart';
import '../lib/features/online/domain/online_port.dart';
import '../lib/core/domain/audio_contract.dart';
import '../lib/features/online/presentation/catalog_surface.dart';
import '../lib/features/online/presentation/catalog_components.dart';
import 'online_controller_test.dart' show ControlledCatalog, transportFixture;

class ContextPort extends ControlledCatalog implements OnlinePlaybackIdentity {
  final played = <String>[];
  final modes = <bool>[];
  final gates = <String, Completer<void>>{};
  int _serial = 0;
  @override
  String currentEntryId = '';
  @override
  Future<void> play(
    CatalogEntry entry,
    AudioQuality quality,
    RequestScope scope,
  ) async {
    final identity = 'native:${++_serial}';
    played.add(entry.id);
    modes.add(false);
    await gates[entry.id]?.future;
    scope.check();
    currentEntryId = identity;
  }

  @override
  Future<void> playOffline(CatalogEntry entry, RequestScope scope) async {
    await play(entry, AudioQuality.lossless, scope);
    modes[modes.length - 1] = true;
  }
}

void main() {
  testWidgets('large offline library keeps individual playback available', (
    tester,
  ) async {
    final port = ContextPort()
      ..downloadedEntries = List.generate(
        801,
        (i) => DownloadedEntry(
          CatalogEntry(provider: 'youtube', id: '$i', title: 'Offline $i'),
          1024,
        ),
      );
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(
          home: Scaffold(
            body: CatalogSurface(destination: 'downloads', title: 'Downloads'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Play offline').first);
    await tester.pump();
    expect(port.played, ['0']);
    expect(port.modes, [true]);
    expect(container.read(catalogPlaybackProvider).entries.length, 1);
    expect(tester.takeException(), isNull);
  });
  const first = CatalogEntry(provider: 'youtube', id: 'first', title: 'First');
  const second = CatalogEntry(
    provider: 'youtube',
    id: 'second',
    title: 'Second',
  );
  testWidgets(
    'album header, local find and row play keep original context indices',
    (tester) async {
      final port = ContextPort()
        ..resolverConfigured = true
        ..browsePage = CatalogPage(
          [first, second, first],
          header: const CatalogHeader(
            title: 'Fixture album',
            subtitle: 'Album • Artist • 2026',
          ),
        );
      final container = ProviderContainer(
        overrides: [onlinePortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(
            home: Scaffold(
              body: CatalogSurface(
                destination: 'listen-now',
                title: 'Fixture album',
                browseId: 'MPREtest',
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        tester.getSize(find.byType(CatalogArtwork).first),
        const Size(286, 286),
      );
      // Albums do not repeat the same sleeve beside every song.
      expect(find.byType(CatalogArtwork), findsOneWidget);
      await tester.tap(find.byTooltip('Search this list'));
      await tester.pump();
      await tester.enterText(
        find.byKey(const Key('collection-find')),
        'second',
      );
      await tester.pump();
      expect(find.text('First'), findsNothing);
      await tester.tap(find.text('Second'));
      await tester.pump();
      expect(port.played, ['second']);
      expect(container.read(catalogPlaybackProvider).index, 1);
      expect(container.read(catalogPlaybackProvider).entries.length, 3);
      expect(tester.takeException(), isNull);
    },
  );
  test(
    'context preserves duplicate positions, previous/next and offline mode',
    () async {
      final port = ContextPort();
      final container = ProviderContainer(
        overrides: [onlinePortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      final controller = container.read(catalogPlaybackProvider.notifier);
      await controller.select(
        first,
        context: [first, second, first],
        index: 2,
        offline: true,
      );
      expect(container.read(catalogPlaybackProvider).index, 2);
      expect(container.read(catalogPlaybackProvider).hasNext, isFalse);
      await controller.previous();
      expect(port.played, ['first', 'second']);
      expect(port.modes, [true, true]);
      await controller.next();
      expect(container.read(catalogPlaybackProvider).index, 2);
      expect(await controller.next(), isNull);
      expect(port.played.length, 3);
    },
  );
  test('EOF advances once only for the adopted native entry', () async {
    final port = ContextPort();
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    final controller = container.read(catalogPlaybackProvider.notifier);
    await controller.select(first, context: [first, second]);
    final old = port.currentEntryId;
    controller.observe(
      transportFixture(entryId: 'unrelated', phase: AudioPhase.ended),
    );
    expect(port.played, ['first']);
    controller.observe(transportFixture(entryId: old, phase: AudioPhase.ended));
    controller.observe(transportFixture(entryId: old, phase: AudioPhase.ended));
    await Future<void>.delayed(Duration.zero);
    expect(port.played, ['first', 'second']);
    controller.observe(transportFixture(entryId: old, phase: AudioPhase.ended));
    expect(port.played.length, 2);
  });
  test(
    'superseded resolution cannot restore context or advance on an old EOF',
    () async {
      final port = ContextPort()..gates['first'] = Completer<void>();
      final container = ProviderContainer(
        overrides: [onlinePortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      final controller = container.read(catalogPlaybackProvider.notifier);
      final pending = controller.select(first, context: [first, second]);
      await controller.next();
      final expected = port.currentEntryId;
      port.gates['first']!.complete();
      expect(await pending, contains('cancelled'));
      expect(port.currentEntryId, expected);
      expect(container.read(catalogPlaybackProvider).index, 1);
      controller.cancel();
      controller.observe(
        transportFixture(entryId: expected, phase: AudioPhase.ended),
      );
      expect(container.read(catalogPlaybackProvider).entries, isEmpty);
    },
  );
}
