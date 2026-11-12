import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/core/domain/audio_contract.dart';
import '../lib/features/online/application/catalog_controller.dart';
import '../lib/features/online/domain/catalog.dart';
import '../lib/features/online/domain/online_port.dart';
import '../lib/features/online/presentation/catalog_surface.dart';
import '../lib/features/online/presentation/catalog_components.dart';

// Test-only ports. Production bootstrap always injects OnlineRuntime.
final class ControlledCatalog implements OnlinePort, OnlineCapabilities {
  CatalogPage? browsePage;
  bool resolverConfigured = false;
  @override
  bool get youtubeAudioConfigured => resolverConfigured;
  SearchFilter? requestedFilter;

  final requests = <String, Completer<CatalogPage>>{};
  final scopes = <String, RequestScope>{};
  Completer<void>? offlineGate;
  RequestScope? offlineScope;
  @override
  List<String> get providers => ['youtube', 'addon', 'jiosaavn'];
  @override
  String get currentTitle => '';
  @override
  String get currentSubtitle => '';
  @override
  Future<CatalogPage> search(
    String provider,
    String query,
    RequestScope scope, {
    String? continuation,
    SearchFilter filter = SearchFilter.all,
  }) {
    requestedFilter = filter;
    scopes[query] = scope;
    return (requests[query] = Completer<CatalogPage>()).future;
  }

  @override
  Future<CatalogPage> browse(
    String id,
    RequestScope scope, {
    String? continuation,
    String? params,
  }) async =>
      browsePage ??
      CatalogPage([
        const CatalogEntry(
          provider: 'youtube',
          id: 'aaaaaaaaaaa',
          title: 'Fixture recording',
          subtitle: 'Fixture artist',
        ),
      ]);
  @override
  Future<void> connectAddon(String url, RequestScope scope) async {}
  @override
  Future<void> play(
    CatalogEntry entry,
    AudioQuality quality,
    RequestScope scope,
  ) async => throw const ProviderFailure(ProviderProblem.unsupported);
  @override
  Future<void> playOffline(CatalogEntry entry, RequestScope scope) async {
    offlineScope = scope;
    await offlineGate?.future;
    scope.check();
  }

  @override
  Future<void> pause() async {}
  @override
  Future<void> resume() async {}
  @override
  Future<void> seek(int positionUs) async {}
  @override
  Future<PlaybackSnapshot> snapshot() async =>
      throw StateError('No native engine in controller fixture');
  @override
  Future<void> download(
    CatalogEntry entry,
    AudioQuality quality,
    RequestScope scope,
    void Function(int, int) progress,
  ) async {}
  @override
  Future<List<DownloadedEntry>> downloads() async => [];
  @override
  Future<void> removeDownload(CatalogEntry entry) async {}
}

void main() {
  test('late query result cannot overwrite a newer query/provider', () async {
    final port = ControlledCatalog();
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    final controller = container.read(catalogProvider.notifier);
    final old = controller.load(query: 'old');
    final next = controller.load(provider: 'addon', query: 'new');
    expect(port.scopes['old']!.cancelled, isTrue);
    port.requests['new']!.complete(
      CatalogPage([
        const CatalogEntry(provider: 'addon', id: 'new', title: 'New result'),
      ]),
    );
    await next;
    port.requests['old']!.complete(
      CatalogPage([
        const CatalogEntry(
          provider: 'youtube',
          id: 'old',
          title: 'Stale result',
        ),
      ]),
    );
    await old;
    expect(container.read(catalogProvider).entries.single.id, 'new');
    expect(container.read(catalogProvider).provider, 'addon');
  });
  test('catalog bound is explicit and stops further continuation', () async {
    final port = ControlledCatalog();
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    final pending = container
        .read(catalogProvider.notifier)
        .load(query: 'large');
    port.requests['large']!.complete(
      CatalogPage(
        List.generate(
          801,
          (i) => CatalogEntry(provider: 'youtube', id: '$i', title: 'Track $i'),
        ),
        continuation: 'more',
      ),
    );
    await pending;
    expect(container.read(catalogProvider).entries.length, 800);
    expect(container.read(catalogProvider).error, contains('first 800'));
    expect(container.read(catalogProvider).continuation, isNull);
  });
  test('superseded offline selection is cancelled before adoption', () async {
    final port = ControlledCatalog()..offlineGate = Completer<void>();
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    final controller = container.read(catalogProvider.notifier);
    const track = CatalogEntry(provider: 'youtube', id: 'id', title: 'Track');
    final old = controller.play(track, offline: true);
    await controller.play(track);
    expect(port.offlineScope!.cancelled, isTrue);
    port.offlineGate!.complete();
    expect(await old, contains('cancelled'));
  });
  test(
    'missing bootstrap port cannot report successful provider actions',
    () async {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      final controller = container.read(catalogProvider.notifier);
      expect(
        await controller.connect('https://example.invalid'),
        contains('unavailable'),
      );
      expect(
        await controller.play(
          const CatalogEntry(provider: 'youtube', id: 'id', title: 'Track'),
        ),
        contains('unavailable'),
      );
    },
  );
  test('unsupported resolution stays an explicit failure', () async {
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(ControlledCatalog())],
    );
    addTearDown(container.dispose);
    final error = await container
        .read(catalogProvider.notifier)
        .play(
          const CatalogEntry(provider: 'youtube', id: 'id', title: 'Track'),
        );
    expect(error, contains('unsupported'));
  });
  test(
    'disposal cancels active HTTP scope and rejects late completion',
    () async {
      final port = ControlledCatalog();
      final container = ProviderContainer(
        overrides: [onlinePortProvider.overrideWithValue(port)],
      );
      final pending = container
          .read(catalogProvider.notifier)
          .load(query: 'pending');
      container.dispose();
      expect(port.scopes['pending']!.cancelled, isTrue);
      port.requests['pending']!.complete(CatalogPage([]));
      await pending;
    },
  );
  test('shelves and filters survive controller pagination', () async {
    final port = ControlledCatalog();
    final container = ProviderContainer(
      overrides: [onlinePortProvider.overrideWithValue(port)],
    );
    addTearDown(container.dispose);
    final controller = container.read(catalogProvider.notifier);
    final first = controller.load(query: 'album', filter: SearchFilter.albums);
    final shelf = CatalogShelf(
      title: 'Albums',
      moreId: 'more',
      params: 'params',
      entries: [
        const CatalogEntry(
          provider: 'youtube',
          id: 'MPRE1',
          title: 'Album',
          kind: CatalogKind.album,
        ),
      ],
    );
    port.requests['album']!.complete(
      CatalogPage(shelf.entries, shelves: [shelf], continuation: 'next'),
    );
    await first;
    final next = controller.more();
    expect(port.requestedFilter, SearchFilter.albums);
    port.requests['album']!.complete(CatalogPage([], shelves: []));
    await next;
    expect(container.read(catalogProvider).shelves.single.moreId, 'more');
    expect(
      container.read(catalogProvider).entries.single.kind,
      CatalogKind.album,
    );
  });
  testWidgets('shelf cards keep reference geometry and navigation targets', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1200, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    CatalogEntry? selected;
    String? opened;
    final shelf = CatalogShelf(
      title: 'Albums',
      moreId: 'more',
      params: 'opaque',
      entries: List.generate(
        12,
        (i) => CatalogEntry(
          provider: 'youtube',
          id: 'MPRE$i',
          title: 'Album $i',
          kind: CatalogKind.album,
        ),
      ),
    );
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: Align(
              alignment: Alignment.topLeft,
              child: SizedBox(
                width: 800,
                child: CatalogShelfView(
                  shelf: shelf,
                  onSelect: (e) => selected = e,
                  onOpen: (id, title, params) => opened = '$id:$params',
                ),
              ),
            ),
          ),
        ),
      ),
    );
    expect(
      tester.getSize(find.byKey(const ValueKey('card-youtube:MPRE0'))).width,
      150,
    );
    await tester.tap(find.text('Album 0'));
    expect(selected?.id, 'MPRE0');
    await tester.tap(find.byTooltip('Open Albums'));
    expect(opened, 'more:opaque');
    await tester.tap(find.byTooltip('Next in Albums'));
    await tester.pumpAndSettle();
    expect(find.text('Album 0').hitTestable(), findsNothing);
    expect(tester.takeException(), isNull);
  });
  testWidgets('Home preserves horizontal shelves and four-track columns', (
    tester,
  ) async {
    final tracks = List.generate(
      8,
      (i) => CatalogEntry(provider: 'youtube', id: '$i', title: 'Track $i'),
    );
    final port = ControlledCatalog()
      ..browsePage = CatalogPage(
        tracks,
        shelves: [
          CatalogShelf(
            title: 'Quick picks',
            entries: tracks,
            layout: ShelfLayout.tracks,
          ),
          CatalogShelf(
            title: 'Albums',
            entries: [
              const CatalogEntry(
                provider: 'youtube',
                id: 'MPRE1',
                title: 'Album',
                kind: CatalogKind.album,
              ),
            ],
          ),
        ],
      );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [onlinePortProvider.overrideWithValue(port)],
        child: const MaterialApp(
          home: Scaffold(
            body: CatalogSurface(destination: 'listen-now', title: 'Home'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Quick picks'), findsOneWidget);
    expect(find.text('Albums'), findsOneWidget);
    expect(find.byType(CatalogShelfView), findsNWidgets(2));
    expect(find.byType(CatalogArtwork), findsWidgets);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'guest catalog shows real adapter rows with truthful audio status',
    (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            onlinePortProvider.overrideWithValue(ControlledCatalog()),
          ],
          child: const MaterialApp(
            home: Scaffold(
              body: CatalogSurface(destination: 'listen-now', title: 'Home'),
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump();
      expect(find.text('Fixture recording'), findsOneWidget);
      expect(
        find.textContaining('Audio resolver is not configured'),
        findsOneWidget,
      );
      expect(find.byTooltip('Play'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
