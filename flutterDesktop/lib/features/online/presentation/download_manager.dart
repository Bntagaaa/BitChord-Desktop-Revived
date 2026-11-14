import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../application/download_queue.dart';
import 'catalog_components.dart';
import '../../../design_system/palette.dart';

// DesktopDownloadManagerUi.kt: max 520dp, 40dp artwork/6dp radius,
// 20x8dp row padding, 2dp spacing, 3dp progress and 360dp bounded list.
class DownloadManager extends ConsumerWidget {
  const DownloadManager({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final rows = ref.watch(downloadQueueProvider);
    final palette = Theme.of(context).brightness == Brightness.dark
        ? BitChordPalette.dark
        : BitChordPalette.light;
    final queue = ref.read(downloadQueueProvider.notifier);
    final failed = rows
        .where((row) => row.phase == DownloadPhase.failed)
        .length;
    final busy = rows.any((row) => row.phase != DownloadPhase.failed);
    final summary = rows.isEmpty
        ? 'Nothing downloading'
        : failed > 0 && !busy
        ? '$failed download${failed == 1 ? '' : 's'} failed'
        : '${rows.length} track${rows.length == 1 ? '' : 's'}';
    return Dialog(
      backgroundColor: palette['cardFill'],
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: BorderSide(color: palette['cardEdge']),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Downloads',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        Text(
                          summary,
                          style: Theme.of(context).textTheme.bodyMedium
                              ?.copyWith(
                                color: failed > 0 && !busy
                                    ? palette['destructive']
                                    : palette['secondary'],
                              ),
                        ),
                      ],
                    ),
                  ),
                  if (rows.isNotEmpty)
                    TextButton(
                      style: TextButton.styleFrom(
                        foregroundColor: palette['secondary'],
                      ),
                      onPressed: busy ? queue.cancelAll : queue.clearFailed,
                      child: Text(busy ? 'Cancel all' : 'Clear'),
                    ),
                ],
              ),
            ),
            if (rows.isEmpty)
              const SizedBox(
                height: 96,
                child: Center(child: Text('Nothing downloading.')),
              )
            else
              Flexible(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 360),
                  child: ListView.separated(
                    shrinkWrap: true,
                    itemCount: rows.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 2),
                    itemBuilder: (context, index) {
                      final task = rows[index];
                      final fraction = task.total > 0
                          ? (task.done / task.total).clamp(0.0, 1.0)
                          : 0.0;
                      return Padding(
                        key: ValueKey('download-${task.entry.key}'),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 8,
                        ),
                        child: Row(
                          children: [
                            CatalogArtwork(
                              url: task.entry.artwork,
                              size: 40,
                              radius: 6,
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    task.entry.title,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: Theme.of(context)
                                        .textTheme
                                        .bodyMedium,
                                  ),
                                  Text(
                                    task.phase == DownloadPhase.queued
                                        ? 'Queued'
                                        : task.phase == DownloadPhase.failed
                                        ? task.error!
                                        : fraction > 0
                                        ? 'Downloading · ${(fraction * 100).floor()}%'
                                        : 'Downloading',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: Theme.of(context)
                                        .textTheme
                                        .bodySmall,
                                  ),
                                  if (task.phase == DownloadPhase.running) ...[
                                    const SizedBox(height: 4),
                                    LinearProgressIndicator(
                                      minHeight: 3,
                                      color: palette['accent'],
                                      backgroundColor: palette['foreground']
                                          .withValues(alpha: .15),
                                      value: fraction > 0 ? fraction : null,
                                    ),
                                  ],
                                ],
                              ),
                            ),
                            if (task.phase == DownloadPhase.failed)
                              IconButton(
                                tooltip: 'Retry',
                                color: palette['secondary'],
                                iconSize: 18,
                                icon: const Icon(Icons.refresh_rounded),
                                onPressed: () => queue.retry(task.entry.key),
                              ),
                            IconButton(
                              tooltip: 'Cancel',
                              color: palette['secondary'],
                              iconSize: 18,
                              icon: const Icon(Icons.close_rounded),
                              onPressed: () => queue.cancel(task.entry.key),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
                ),
              ),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              child: Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  style: TextButton.styleFrom(
                    foregroundColor: palette['foreground'],
                  ),
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Done'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
