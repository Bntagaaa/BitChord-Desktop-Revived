import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/features/online/application/catalog_controller.dart';
import '../lib/features/online/application/download_queue.dart';
import '../lib/features/online/domain/catalog.dart';
import 'online_controller_test.dart' show ControlledCatalog;

class ControlledDownloads extends ControlledCatalog {
  final jobs = <String, Completer<void>>{};
  final jobScopes = <String, RequestScope>{};
  final qualities = <String, AudioQuality>{};
  final progress = <String, void Function(int, int)>{};
  int running = 0, highWater = 0;
  @override
  Future<void> download(
    CatalogEntry entry,
    AudioQuality quality,
    RequestScope scope,
    void Function(int, int) onProgress,
  ) async {
    running++;
    if (running > highWater) highWater = running;
    jobScopes[entry.id] = scope;
    qualities[entry.id] = quality;
    progress[entry.id] = onProgress;
    try {
      await (jobs[entry.id] = Completer<void>()).future;
      scope.check();
    } finally {
      running--;
    }
  }
}

CatalogEntry track(int i) =>
    CatalogEntry(provider: 'youtube', id: '$i', title: 'Track $i');
Future<void> flush() async {
  for (var i = 0; i < 8; i++) {
    await Future<void>.value();
  }
}

void main() {
  test(
    'four workers, duplicate suppression, FIFO, quality and verified completion',
    () async {
      final port = ControlledDownloads();
      final container = ProviderContainer(
        overrides: [onlinePortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      final queue = container.read(downloadQueueProvider.notifier);
      for (var i = 0; i < 6; i++) {
        queue.enqueue(track(i), quality: AudioQuality.high);
      }
      queue.enqueue(track(0));
      expect(port.jobs.keys, ['0', '1', '2', '3']);
      expect(container.read(downloadQueueProvider).length, 6);
      port.progress['0']!(50, 100);
      expect(container.read(downloadQueueProvider).first.done, 50);
      port.jobs['0']!.complete();
      await flush();
      expect(port.jobs.keys, ['0', '1', '2', '3', '4']);
      expect(port.qualities['4'], AudioQuality.high);
      expect(container.read(downloadRevisionProvider), 1);
      expect(port.highWater, 4);
    },
  );

  test(
    'cancelled writer quiesces before same-key reenqueue and ignores late progress',
    () async {
      final port = ControlledDownloads();
      final container = ProviderContainer(
        overrides: [onlinePortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      final queue = container.read(downloadQueueProvider.notifier);
      queue.enqueue(track(0));
      final old = port.jobs['0']!;
      final late = port.progress['0']!;
      queue.cancel(track(0).key);
      expect(port.jobScopes['0']!.cancelled, isTrue);
      queue.enqueue(track(0), quality: AudioQuality.low);
      late(80, 100);
      expect(
        container.read(downloadQueueProvider).single.phase,
        DownloadPhase.queued,
      );
      old.complete();
      await flush();
      expect(port.jobs['0'], isNot(same(old)));
      expect(port.qualities['0'], AudioQuality.low);
      expect(container.read(downloadQueueProvider).single.done, 0);
      expect(container.read(downloadRevisionProvider), 0);
    },
  );

  test(
    'failed jobs remain retryable and cancel all cannot resurrect rows',
    () async {
      final port = ControlledDownloads();
      final container = ProviderContainer(
        overrides: [onlinePortProvider.overrideWithValue(port)],
      );
      addTearDown(container.dispose);
      final queue = container.read(downloadQueueProvider.notifier);
      queue.enqueue(track(0));
      port.jobs['0']!.completeError(
        const ProviderFailure(ProviderProblem.network),
      );
      await flush();
      expect(
        container.read(downloadQueueProvider).single.phase,
        DownloadPhase.failed,
      );
      queue.retry(track(0).key);
      expect(
        container.read(downloadQueueProvider).single.phase,
        DownloadPhase.running,
      );
      queue.cancelAll();
      port.progress['0']!(100, 100);
      port.jobs['0']!.complete();
      await flush();
      expect(container.read(downloadQueueProvider), isEmpty);
      expect(container.read(downloadRevisionProvider), 0);
    },
  );
}
