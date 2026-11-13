import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../application/catalog_controller.dart';
import '../domain/catalog.dart';
import '../../../design_system/palette.dart';

class CollectionHeader extends StatelessWidget {
  const CollectionHeader({
    super.key,
    required this.header,
    required this.album,
    required this.entries,
    required this.onPlay,
    required this.onShuffle,
    required this.onFind,
    required this.onDownload,
    required this.onShare,
    this.onRename,
    this.onDelete,
  });
  final CatalogHeader header;
  final bool album;
  final List<CatalogEntry> entries;
  final VoidCallback? onPlay, onShuffle;
  final VoidCallback onFind, onDownload, onShare;
  final VoidCallback? onRename, onDelete;
  @override
  Widget build(BuildContext context) {
    final palette = Theme.of(context).brightness == Brightness.dark
        ? BitChordPalette.dark
        : BitChordPalette.light;
    final parts = header.subtitle
        .split(RegExp(r' • | · | \| '))
        .map((part) => part.trim())
        .where((part) => part.isNotEmpty);
    final credit = parts
        .where(
          (part) =>
              ![
                'album',
                'single',
                'ep',
                'playlist',
                'artist',
              ].contains(part.toLowerCase()) &&
              !RegExp(
                r'^\d{4}$|^\d{1,2}:\d{2}(?::\d{2})?$|^[0-9,.]+\s*(songs?|tracks?)$',
                caseSensitive: false,
              ).hasMatch(part),
        )
        .firstOrNull;
    final year = parts
        .where((part) => RegExp(r'^\d{4}$').hasMatch(part))
        .firstOrNull;
    final seconds = entries.fold<int>(
      0,
      (sum, entry) => sum + (entry.durationSeconds ?? 0),
    );
    final minutes = seconds ~/ 60;
    final metadata = [
      album ? 'ALBUM' : 'PLAYLIST',
      if (year != null) year,
      if (entries.isNotEmpty) '${entries.length} songs',
      if (seconds > 0)
        minutes < 60
            ? '$minutes min'
            : '${minutes ~/ 60} hr ${minutes % 60} min',
    ].join(' • ').toUpperCase();
    Widget action(
      String tooltip,
      IconData icon,
      VoidCallback? onPressed, {
      bool primary = false,
    }) => SizedBox(
      width: 50,
      height: 50,
      child: IconButton(
        tooltip: tooltip,
        onPressed: onPressed,
        iconSize: 22,
        style: IconButton.styleFrom(
          backgroundColor: primary ? palette['accent'] : palette['subtleFill'],
          foregroundColor: primary
              ? palette['onAccent']
              : palette['foreground'],
        ),
        icon: Icon(icon),
      ),
    );
    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 36),
      child: Row(
        children: [
          CatalogArtwork(
            url: header.artwork ?? entries.firstOrNull?.artwork,
            size: 286,
            radius: 8,
          ),
          const SizedBox(width: 30),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  album ? 'ALBUM' : 'PLAYLIST',
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    color: palette['secondary'],
                    fontWeight: FontWeight.w600,
                    letterSpacing: 1.2,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  header.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.displaySmall
                      ?.copyWith(fontWeight: FontWeight.w700),
                ),
                if (credit != null) ...[
                  const SizedBox(height: 6),
                  Text(
                    credit,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium
                        ?.copyWith(color: palette['accent']),
                  ),
                ],
                const SizedBox(height: 8),
                Text(
                  metadata,
                  style: Theme.of(context).textTheme.bodySmall
                      ?.copyWith(color: palette['secondary']),
                ),
                const SizedBox(height: 24),
                Wrap(
                  spacing: 10,
                  runSpacing: 10,
                  children: [
                    action('Shuffle', Icons.shuffle_rounded, onShuffle),
                    action(
                      'Play',
                      Icons.play_arrow_rounded,
                      onPlay,
                      primary: true,
                    ),
                    action('Search this list', Icons.search_rounded, onFind),
                    SizedBox(
                      width: 50,
                      height: 50,
                      child: PopupMenuButton<String>(
                        tooltip: 'More',
                        style: IconButton.styleFrom(
                          backgroundColor: palette['subtleFill'],
                          foregroundColor: palette['foreground'],
                        ),
                        icon: const Icon(Icons.more_horiz_rounded, size: 22),
                        onSelected: (value) {
                          if (value == 'download') onDownload();
                          if (value == 'share') onShare();
                          if (value == 'rename') onRename?.call();
                          if (value == 'delete') onDelete?.call();
                        },
                        itemBuilder: (_) => [
                          PopupMenuItem(
                            value: 'download',
                            enabled: entries.isNotEmpty,
                            child: const Text('Download all'),
                          ),
                          const PopupMenuItem(
                            value: 'share',
                            child: Text('Share'),
                          ),
                          if (onRename != null)
                            const PopupMenuItem(
                              value: 'rename',
                              child: Text('Rename playlist'),
                            ),
                          if (onDelete != null)
                            const PopupMenuItem(
                              value: 'delete',
                              child: Text('Delete playlist'),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class CollectionTrackRow extends StatelessWidget {
  const CollectionTrackRow({
    super.key,
    required this.entry,
    required this.index,
    required this.album,
    required this.onPlay,
    required this.onDownload,
    this.onAddToPlaylist,
    this.onRemoveFromPlaylist,
  });
  final CatalogEntry entry;
  final int index;
  final bool album;
  final VoidCallback? onPlay, onDownload, onAddToPlaylist;
  final VoidCallback? onRemoveFromPlaylist;
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final seconds = entry.durationSeconds;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(6),
        onTap: onPlay,
        child: Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(6),
            color: index.isOdd ? colors.onSurface.withValues(alpha: .04) : null,
          ),
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
          child: Row(
            children: [
              SizedBox(
                width: 28,
                child: Text(
                  '${index + 1}',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.labelSmall,
                ),
              ),
              const SizedBox(width: 12),
              if (!album) ...[
                CatalogArtwork(url: entry.artwork, size: 44, radius: 6),
                const SizedBox(width: 12),
              ],
              Expanded(
                flex: 42,
                child: Text(
                  entry.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodyMedium
                      ?.copyWith(fontWeight: FontWeight.w500),
                ),
              ),
              Expanded(
                flex: 20,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  child: Text(
                    entry.subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              ),
              SizedBox(
                width: 58,
                child: Text(
                  seconds == null
                      ? '—'
                      : '${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, '0')}',
                  textAlign: TextAlign.end,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              SizedBox(
                width: 34,
                height: 34,
                child: IconButton(
                  tooltip: 'Download',
                  iconSize: 18,
                  padding: EdgeInsets.zero,
                  onPressed: onDownload,
                  icon: const Icon(Icons.download_outlined),
                ),
              ),
              if (onAddToPlaylist != null || onRemoveFromPlaylist != null)
                SizedBox(
                  width: 34,
                  height: 34,
                  child: PopupMenuButton<String>(
                    tooltip: 'Song options',
                    padding: EdgeInsets.zero,
                    icon: const Icon(Icons.more_horiz, size: 18),
                    onSelected: (action) {
                      if (action == 'add') onAddToPlaylist?.call();
                      if (action == 'remove') onRemoveFromPlaylist?.call();
                    },
                    itemBuilder: (_) => [
                      if (onAddToPlaylist != null)
                        const PopupMenuItem(
                          value: 'add',
                          child: Text('Add to playlist'),
                        ),
                      if (onRemoveFromPlaylist != null)
                        const PopupMenuItem(
                          value: 'remove',
                          child: Text('Remove from playlist'),
                        ),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class CatalogArtwork extends ConsumerWidget {
  const CatalogArtwork({
    super.key,
    required this.url,
    this.size = 150,
    this.round = false,
    this.radius = 12,
  });
  final Uri? url;
  final double size, radius;
  final bool round;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bytes = url == null
        ? null
        : ref.watch(artworkProvider(url!)).asData?.value;
    final placeholder = ColoredBox(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
    );
    final child = bytes == null
        ? placeholder
        : Image.memory(
            bytes,
            fit: BoxFit.cover,
            width: double.infinity,
            height: double.infinity,
            cacheWidth: (size * MediaQuery.devicePixelRatioOf(context))
                .round()
                .clamp(48, 768),
            errorBuilder: (_, _, _) => placeholder,
            excludeFromSemantics: true,
          );
    return SizedBox(
      width: size,
      height: size,
      child: round
          ? ClipOval(child: child)
          : ClipRRect(
              borderRadius: BorderRadius.circular(radius),
              child: child,
            ),
    );
  }
}

// Common.kt/HomeScreen.kt: 150dp cards, 14dp gaps, 320dp/.70 hero,
// .92 hero ratio, four 56dp rows per <=400dp/.88 track column.
class CatalogShelfView extends StatefulWidget {
  const CatalogShelfView({
    super.key,
    required this.shelf,
    required this.onSelect,
    required this.onOpen,
    this.hero = false,
  });
  final CatalogShelf shelf;
  final ValueChanged<CatalogEntry> onSelect;
  final void Function(String, String, String?) onOpen;
  final bool hero;
  @override
  State<CatalogShelfView> createState() => _CatalogShelfViewState();
}

class _CatalogShelfViewState extends State<CatalogShelfView> {
  final _scroll = ScrollController();
  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _page(int direction) {
    if (!_scroll.hasClients) return;
    _scroll.animateTo(
      (_scroll.offset + direction * _scroll.position.viewportDimension * .85)
          .clamp(0, _scroll.position.maxScrollExtent),
      duration: const Duration(milliseconds: 250),
      curve: Curves.easeOut,
    );
  }

  Widget _caption(CatalogEntry entry, {bool white = false}) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    mainAxisSize: MainAxisSize.min,
    children: [
      Text(
        entry.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.titleMedium
            ?.copyWith(color: white ? Colors.white : null),
      ),
      Text(
        entry.subtitle,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
          color: white
              ? Colors.white70
              : Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    ],
  );

  Widget _card(CatalogEntry entry, double width) {
    final hero = widget.hero;
    final artHeight = hero
        ? width / .92
        : entry.isVideo
        ? width * 9 / 16
        : width;
    return SizedBox(
      width: width,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          key: ValueKey('card-${entry.key}'),
          borderRadius: BorderRadius.circular(hero ? 18 : 12),
          onTap: () => widget.onSelect(entry),
          child: hero
              ? ClipRRect(
                  borderRadius: BorderRadius.circular(18),
                  child: SizedBox(
                    height: artHeight,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        FittedBox(
                          fit: BoxFit.cover,
                          child: CatalogArtwork(
                            url: entry.artwork,
                            size: width,
                            radius: 18,
                          ),
                        ),
                        Align(
                          alignment: Alignment.bottomCenter,
                          child: Container(
                            width: width,
                            decoration: const BoxDecoration(
                              gradient: LinearGradient(
                                begin: Alignment.topCenter,
                                end: Alignment.bottomCenter,
                                colors: [Colors.transparent, Color(0xc7000000)],
                              ),
                            ),
                            padding: const EdgeInsets.fromLTRB(16, 34, 16, 14),
                            child: _caption(entry, white: true),
                          ),
                        ),
                      ],
                    ),
                  ),
                )
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      height: artHeight,
                      child: FittedBox(
                        fit: BoxFit.cover,
                        clipBehavior: Clip.hardEdge,
                        child: CatalogArtwork(
                          url: entry.artwork,
                          size: width,
                          round: entry.kind == CatalogKind.artist,
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    _caption(entry),
                  ],
                ),
        ),
      ),
    );
  }

  Widget _track(CatalogEntry entry) => SizedBox(
    height: 56,
    child: Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => widget.onSelect(entry),
        borderRadius: BorderRadius.circular(7),
        child: Row(
          children: [
            CatalogArtwork(url: entry.artwork, size: 48, radius: 7),
            const SizedBox(width: 12),
            Expanded(child: _caption(entry)),
          ],
        ),
      ),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final shelf = widget.shelf;
    return Padding(
      padding: const EdgeInsets.only(bottom: 26),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: InkWell(
                  onTap: shelf.moreId == null
                      ? null
                      : () => widget.onOpen(
                          shelf.moreId!,
                          shelf.title,
                          shelf.params,
                        ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 10),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          shelf.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        if (shelf.subtitle.isNotEmpty)
                          Text(
                            shelf.subtitle,
                            style: Theme.of(context).textTheme.bodyMedium,
                          ),
                      ],
                    ),
                  ),
                ),
              ),
              if (shelf.moreId != null)
                IconButton(
                  tooltip: 'Open ${shelf.title}',
                  onPressed: () =>
                      widget.onOpen(shelf.moreId!, shelf.title, shelf.params),
                  icon: const Icon(Icons.chevron_right, size: 20),
                ),
              IconButton(
                tooltip: 'Previous in ${shelf.title}',
                onPressed: () => _page(-1),
                icon: const Icon(Icons.chevron_left, size: 20),
              ),
              IconButton(
                tooltip: 'Next in ${shelf.title}',
                onPressed: () => _page(1),
                icon: const Icon(Icons.chevron_right, size: 20),
              ),
            ],
          ),
          LayoutBuilder(
            builder: (context, constraints) {
              final tracks = shelf.layout == ShelfLayout.tracks;
              final width = tracks
                  ? math.min(constraints.maxWidth * .88, 400.0)
                  : widget.hero
                  ? math.min(constraints.maxWidth * .70, 320.0)
                  : 150.0;
              final hasVideos = shelf.entries.any((e) => e.isVideo);
              final height = tracks
                  ? 224.0
                  : widget.hero
                  ? width / .92
                  : 150.0 + 64;
              return SizedBox(
                height: height,
                child: Listener(
                  onPointerSignal: (event) {
                    if (event is PointerScrollEvent &&
                        event.scrollDelta.dx != 0 &&
                        _scroll.hasClients) {
                      _scroll.jumpTo(
                        (_scroll.offset + event.scrollDelta.dx).clamp(
                          0,
                          _scroll.position.maxScrollExtent,
                        ),
                      );
                    }
                  },
                  child: ScrollConfiguration(
                    behavior: ScrollConfiguration.of(context).copyWith(
                      dragDevices: {
                        PointerDeviceKind.mouse,
                        PointerDeviceKind.touch,
                        PointerDeviceKind.trackpad,
                      },
                    ),
                    child: ListView.separated(
                      controller: _scroll,
                      scrollDirection: Axis.horizontal,
                      itemCount: tracks
                          ? (shelf.entries.length / 4).ceil()
                          : shelf.entries.length,
                      separatorBuilder: (_, _) =>
                          SizedBox(width: tracks ? 12 : 14),
                      itemBuilder: (context, index) => tracks
                          ? SizedBox(
                              width: width,
                              child: Column(
                                children: [
                                  for (final entry
                                      in shelf.entries.skip(index * 4).take(4))
                                    _track(entry),
                                ],
                              ),
                            )
                          : _card(
                              shelf.entries[index],
                              !widget.hero &&
                                      hasVideos &&
                                      shelf.entries[index].isVideo
                                  ? 248
                                  : width,
                            ),
                    ),
                  ),
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}
