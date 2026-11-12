import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/core/domain/audio_contract.dart';
import '../lib/features/online/application/catalog_controller.dart';
import '../lib/features/online/domain/catalog.dart';
import '../lib/features/online/domain/online_port.dart';
import '../lib/features/online/presentation/catalog_surface.dart';

// Test-only ports. Production bootstrap always injects OnlineRuntime.
final class ControlledCatalog implements OnlinePort {
  final requests = <String, Completer<CatalogPage>>{};
  final scopes = <String, RequestScope>{};
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
  }) {
    scopes[query] = scope;
    return (requests[query] = Completer<CatalogPage>()).future;
  }

  @override
  Future<CatalogPage> browse(
    String id,
    RequestScope scope, {
    String? continuation,
    String? params,
  }) async => CatalogPage([
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
  Future<void> playOffline(CatalogEntry entry) async {}
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
        find.textContaining('Audio resolution and sign-in are unavailable'),
        findsOneWidget,
      );
      expect(find.byTooltip('Play'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
