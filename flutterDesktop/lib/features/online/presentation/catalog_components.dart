import 'dart:math' as math;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../application/catalog_controller.dart';
import '../domain/catalog.dart';

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
        style: Theme.of(
          context,
        ).textTheme.titleMedium?.copyWith(color: white ? Colors.white : null),
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
