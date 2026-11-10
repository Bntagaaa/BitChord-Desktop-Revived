import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import '../domain/application.dart';
import '../domain/audio.dart';
import '../domain/music.dart';
import 'theme.dart';
import 'widgets.dart';

const int recentTracksPerColumn = 4;
const double pageGutter = 16.0;
const double cardSpacing = 14.0;
const double shelfCardWidth = 158.0;
const double videoCardWidth = 248.0;

class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.app});

  final MusicApplication app;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final ScrollController _scrollController = ScrollController();
  String? _lastContinuationToken;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final pos = _scrollController.position;
    if (pos.extentAfter < 600.0) {
      _triggerContinuation();
    }
  }

  void _triggerContinuation() {
    final token = widget.app.page.continuation;
    if (token == null || widget.app.busy) return;
    if (_lastContinuationToken == token) return;
    _lastContinuationToken = token;
    unawaited(widget.app.more());
  }

  bool _isItemPlaying(CatalogItem item) {
    final currentSong = widget.app.song;
    final isPlaying = widget.app.status.value == PlayerStatus.playing;
    if (currentSong == null || !isPlaying) return false;
    if (item.song?.id == currentSong.id) return true;
    if (item.videoId != null && item.videoId == currentSong.id) return true;
    return false;
  }

  void _onTapItem(CatalogItem item, String shelfTitle) {
    if (item.song != null) {
      unawaited(widget.app.play(item.song!, origin: shelfTitle));
    } else if (item.videoId != null && item.browseId == null) {
      final s = Song(
        id: item.videoId!,
        title: item.title,
        artist: item.subtitle,
        artwork: item.artwork,
        durationText: item.durationText,
        isVideo: item.isVideo,
        isExplicit: item.isExplicit,
      );
      unawaited(widget.app.play(s, origin: shelfTitle));
    } else {
      unawaited(widget.app.browse(item));
    }
  }

  String _getRecentsViewMode() {
    return widget.app.settings.get(
      'home_recents_view_type',
      widget.app.settings.get(
        'home_recents_view',
        widget.app.settings.get('desktop_recent_tracks_view_mode', 'list'),
      ),
    );
  }

  void _toggleRecentsViewMode() {
    final current = _getRecentsViewMode();
    final next = current == 'grid' ? 'list' : 'grid';
    widget.app.settings.set('home_recents_view_type', next);
    widget.app.settings.set('home_recents_view', next);
    widget.app.settings.set('desktop_recent_tracks_view_mode', next);
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final app = widget.app;
    final colors = paletteOf(context);

    return ListenableBuilder(
      listenable: Listenable.merge([app, app.status]),
      builder: (context, _) {
        final page = app.page;
        final shelves = page.shelves;
        final isBusy = app.busy;
        final error = app.error;

        return DesktopScrollEdgeFade(
          controller: _scrollController,
          child: CustomScrollView(
            controller: _scrollController,
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(
                  pageGutter,
                  20,
                  pageGutter,
                  8,
                ),
                sliver: SliverToBoxAdapter(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (page.title.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: Text(
                            page.title,
                            style: Theme.of(context).textTheme.displayLarge,
                          ),
                        ),
                      if (error != null)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          child: SelectableText(
                            error,
                            style: TextStyle(color: colors.destructive),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              if (isBusy && shelves.isEmpty)
                const SliverToBoxAdapter(child: ShelfSkeleton()),
              if (!isBusy &&
                  shelves.isEmpty &&
                  page.songs.isEmpty &&
                  page.items.isEmpty &&
                  error == null)
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.all(40),
                    child: Center(
                      child: Text(
                        'No music returned',
                        style: TextStyle(color: colors.secondary),
                      ),
                    ),
                  ),
                ),
              for (int i = 0; i < shelves.length; i++)
                SliverToBoxAdapter(child: _buildShelf(shelves[i], i)),
              if (page.items.isNotEmpty)
                SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: pageGutter),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate((context, index) {
                      final item = page.items[index];
                      return ListTile(
                        leading: Artwork(item.artwork, size: 44, radius: 8),
                        title: Text(
                          item.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text(
                          item.subtitle,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        onTap: () => unawaited(app.browse(item)),
                      );
                    }, childCount: page.items.length),
                  ),
                ),
              if (page.songs.isNotEmpty)
                SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: pageGutter),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate((context, index) {
                      final song = page.songs[index];
                      return ListTile(
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 8,
                        ),
                        leading: Artwork(song.artwork, size: 44, radius: 8),
                        title: Text(
                          song.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text(
                          song.artist,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        trailing: Text(song.durationText ?? ''),
                        onTap: () => unawaited(
                          app.play(
                            song,
                            context: page.songs,
                            index: index,
                            origin: page.title.isNotEmpty ? page.title : 'Home',
                          ),
                        ),
                      );
                    }, childCount: page.songs.length),
                  ),
                ),
              if (isBusy && shelves.isNotEmpty)
                const SliverToBoxAdapter(child: ShelfSkeleton()),
              const SliverToBoxAdapter(child: SizedBox(height: 120)),
            ],
          ),
        );
      },
    );
  }

  Widget _buildShelf(Shelf shelf, int index) {
    final isRecents =
        shelf.layout == ShelfLayout.list ||
        (index == 0 && shelf.title.trim().toLowerCase() == 'recents') ||
        shelf.title.trim().toLowerCase() == 'listen again';

    if (isRecents) {
      return _buildRecentsShelf(shelf);
    }

    return switch (shelf.layout) {
      ShelfLayout.video => _buildVideoShelf(shelf),
      ShelfLayout.hero => _buildHeroShelf(shelf),
      _ => _buildCardsShelf(shelf),
    };
  }

  Widget _buildRecentsShelf(Shelf shelf) {
    final viewMode = _getRecentsViewMode();
    final isGrid = viewMode == 'grid';

    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          RecentSectionHeader(
            title: shelf.title,
            subtitle: shelf.subtitle ?? '',
            isGrid: isGrid,
            onToggleView: _toggleRecentsViewMode,
          ),
          if (isGrid)
            LayoutBuilder(
              builder: (context, constraints) {
                final cardWidth = min(constraints.maxWidth * 0.70, 320.0);
                return SizedBox(
                  height: cardWidth / 0.92,
                  child: DesktopShelfRowChrome(
                    builder: (context, controller) => ListView.separated(
                      controller: controller,
                      scrollDirection: Axis.horizontal,
                      padding: const EdgeInsets.symmetric(
                        horizontal: pageGutter,
                      ),
                      itemCount: shelf.items.length,
                      separatorBuilder: (_, _) =>
                          const SizedBox(width: cardSpacing),
                      itemBuilder: (context, index) {
                        final item = shelf.items[index];
                        if (item.isVideo) {
                          return SizedBox(
                            width: videoCardWidth,
                            child: VideoShelfCard(
                              item: item,
                              isPlaying: _isItemPlaying(item),
                              onTap: () => _onTapItem(item, shelf.title),
                            ),
                          );
                        }
                        return SizedBox(
                          width: cardWidth,
                          child: HeroCard(
                            item: item,
                            isPlaying: _isItemPlaying(item),
                            onTap: () => _onTapItem(item, shelf.title),
                          ),
                        );
                      },
                    ),
                  ),
                );
              },
            )
          else
            LayoutBuilder(
              builder: (context, constraints) {
                final columnWidth = min(constraints.maxWidth * 0.88, 400.0);
                final columns = <List<CatalogItem>>[];
                for (
                  var i = 0;
                  i < shelf.items.length;
                  i += recentTracksPerColumn
                ) {
                  columns.add(
                    shelf.items.sublist(
                      i,
                      min(i + recentTracksPerColumn, shelf.items.length),
                    ),
                  );
                }

                return SizedBox(
                  height: 256,
                  child: DesktopShelfRowChrome(
                    builder: (context, controller) => ListView.separated(
                      controller: controller,
                      scrollDirection: Axis.horizontal,
                      padding: const EdgeInsets.symmetric(
                        horizontal: pageGutter,
                      ),
                      itemCount: columns.length,
                      separatorBuilder: (_, _) => const SizedBox(width: 12),
                      itemBuilder: (context, colIndex) {
                        final columnItems = columns[colIndex];
                        return SizedBox(
                          width: columnWidth,
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              for (final item in columnItems)
                                CompactTrackRow(
                                  item: item,
                                  isPlaying: _isItemPlaying(item),
                                  onTap: () => _onTapItem(item, shelf.title),
                                ),
                            ],
                          ),
                        );
                      },
                    ),
                  ),
                );
              },
            ),
        ],
      ),
    );
  }

  Widget _buildCardsShelf(Shelf shelf) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionHeader(
            title: shelf.title,
            subtitle: shelf.subtitle ?? '',
            onOpen: shelf.moreBrowseId != null
                ? () => unawaited(
                    widget.app.browse(
                      CatalogItem(
                        title: shelf.title,
                        browseId: shelf.moreBrowseId,
                        params: shelf.moreParams,
                      ),
                    ),
                  )
                : null,
          ),
          SizedBox(
            height: 226,
            child: DesktopShelfRowChrome(
              builder: (context, controller) => ListView.separated(
                controller: controller,
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: pageGutter),
                itemCount: shelf.items.length,
                separatorBuilder: (_, _) => const SizedBox(width: cardSpacing),
                itemBuilder: (context, index) {
                  final item = shelf.items[index];
                  if (item.isVideo) {
                    return SizedBox(
                      width: videoCardWidth,
                      child: VideoShelfCard(
                        item: item,
                        isPlaying: _isItemPlaying(item),
                        onTap: () => _onTapItem(item, shelf.title),
                      ),
                    );
                  }
                  return SizedBox(
                    width: shelfCardWidth,
                    child: ShelfCard(
                      item: item,
                      isPlaying: _isItemPlaying(item),
                      onTap: () => _onTapItem(item, shelf.title),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildVideoShelf(Shelf shelf) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionHeader(
            title: shelf.title,
            subtitle: shelf.subtitle ?? '',
            onOpen: shelf.moreBrowseId != null
                ? () => unawaited(
                    widget.app.browse(
                      CatalogItem(
                        title: shelf.title,
                        browseId: shelf.moreBrowseId,
                        params: shelf.moreParams,
                      ),
                    ),
                  )
                : null,
          ),
          SizedBox(
            height: 216,
            child: DesktopShelfRowChrome(
              builder: (context, controller) => ListView.separated(
                controller: controller,
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: pageGutter),
                itemCount: shelf.items.length,
                separatorBuilder: (_, _) => const SizedBox(width: cardSpacing),
                itemBuilder: (context, index) {
                  final item = shelf.items[index];
                  return SizedBox(
                    width: videoCardWidth,
                    child: VideoShelfCard(
                      item: item,
                      isPlaying: _isItemPlaying(item),
                      onTap: () => _onTapItem(item, shelf.title),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHeroShelf(Shelf shelf) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionHeader(
            title: shelf.title,
            subtitle: shelf.subtitle ?? '',
            onOpen: shelf.moreBrowseId != null
                ? () => unawaited(
                    widget.app.browse(
                      CatalogItem(
                        title: shelf.title,
                        browseId: shelf.moreBrowseId,
                        params: shelf.moreParams,
                      ),
                    ),
                  )
                : null,
          ),
          LayoutBuilder(
            builder: (context, constraints) {
              final cardWidth = min(constraints.maxWidth * 0.70, 320.0);
              return SizedBox(
                height: cardWidth / 0.92,
                child: DesktopShelfRowChrome(
                  builder: (context, controller) => ListView.separated(
                    controller: controller,
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.symmetric(horizontal: pageGutter),
                    itemCount: shelf.items.length,
                    separatorBuilder: (_, _) =>
                        const SizedBox(width: cardSpacing),
                    itemBuilder: (context, index) {
                      final item = shelf.items[index];
                      return SizedBox(
                        width: cardWidth,
                        child: HeroCard(
                          item: item,
                          isPlaying: _isItemPlaying(item),
                          onTap: () => _onTapItem(item, shelf.title),
                        ),
                      );
                    },
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

class SectionHeader extends StatelessWidget {
  const SectionHeader({
    super.key,
    required this.title,
    this.subtitle = '',
    this.onOpen,
  });

  final String title;
  final String subtitle;
  final VoidCallback? onOpen;

  @override
  Widget build(BuildContext context) {
    final colors = paletteOf(context);
    final header = Padding(
      padding: const EdgeInsets.symmetric(horizontal: pageGutter, vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: Theme.of(
              context,
            ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          if (subtitle.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text(
                subtitle,
                style: TextStyle(fontSize: 13, color: colors.secondary),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
        ],
      ),
    );

    if (onOpen != null) {
      return InkWell(onTap: onOpen, child: header);
    }
    return header;
  }
}

class RecentSectionHeader extends StatelessWidget {
  const RecentSectionHeader({
    super.key,
    required this.title,
    this.subtitle = '',
    required this.isGrid,
    required this.onToggleView,
  });

  final String title;
  final String subtitle;
  final bool isGrid;
  final VoidCallback onToggleView;

  @override
  Widget build(BuildContext context) {
    final colors = paletteOf(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: pageGutter, vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                if (subtitle.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(
                      subtitle,
                      style: TextStyle(fontSize: 13, color: colors.secondary),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Material(
            key: const ValueKey('recents_view_toggle'),
            color: Colors.transparent,
            shape: const CircleBorder(),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: onToggleView,
              child: SizedBox(
                width: 32,
                height: 32,
                child: Tooltip(
                  message: isGrid
                      ? 'Switch to list view'
                      : 'Switch to grid view',
                  child: Icon(
                    isGrid ? Icons.view_list_rounded : Icons.grid_view_rounded,
                    size: 19,
                    color: colors.secondary,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class CompactTrackRow extends StatelessWidget {
  const CompactTrackRow({
    super.key,
    required this.item,
    required this.isPlaying,
    required this.onTap,
  });

  final CatalogItem item;
  final bool isPlaying;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = paletteOf(context);
    final borderColor = Theme.of(context).brightness == Brightness.dark
        ? Colors.white.withValues(alpha: 0.15)
        : Colors.black.withValues(alpha: 0.15);

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4, horizontal: 4),
        child: Row(
          children: [
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(7),
                border: Border.all(color: borderColor, width: 1),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(6),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Artwork(item.artwork, size: 48, radius: 6),
                    if (isPlaying) const Center(child: PlayingEqualizerBars()),
                    Positioned.fill(
                      child: ArtworkHover(
                        mode: ArtworkHoverMode.darkenAndPlay,
                        radius: 6,
                        child: const SizedBox.expand(),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      Flexible(
                        child: Text(
                          item.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.titleMedium
                              ?.copyWith(
                                fontSize: 14,
                                fontWeight: FontWeight.w500,
                              ),
                        ),
                      ),
                      if (item.isExplicit) ...[
                        const SizedBox(width: 4),
                        const ExplicitBadge(size: 13),
                      ],
                    ],
                  ),
                  const SizedBox(height: 2),
                  Text(
                    item.subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 12, color: colors.secondary),
                  ),
                ],
              ),
            ),
            if (item.durationText != null && item.durationText!.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(left: 8),
                child: Text(
                  item.durationText!,
                  style: TextStyle(fontSize: 12, color: colors.secondary),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class ShelfCard extends StatelessWidget {
  const ShelfCard({
    super.key,
    required this.item,
    required this.isPlaying,
    required this.onTap,
  });

  final CatalogItem item;
  final bool isPlaying;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = paletteOf(context);
    final borderColor = Theme.of(context).brightness == Brightness.dark
        ? Colors.white.withValues(alpha: 0.15)
        : Colors.black.withValues(alpha: 0.15);

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: shelfCardWidth,
            height: shelfCardWidth,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: borderColor, width: 1),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(11),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Artwork(item.artwork, size: shelfCardWidth, radius: 11),
                  if (isPlaying) const Center(child: PlayingEqualizerBars()),
                  Positioned.fill(
                    child: ArtworkHover(
                      mode: ArtworkHoverMode.darkenAndPlay,
                      radius: 11,
                      child: const SizedBox.expand(),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 10),
          Text(
            item.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.w500,
              fontSize: 14,
            ),
          ),
          if (item.subtitle.isNotEmpty)
            Text(
              item.subtitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: colors.secondary),
            ),
        ],
      ),
    );
  }
}

class VideoShelfCard extends StatelessWidget {
  const VideoShelfCard({
    super.key,
    required this.item,
    required this.isPlaying,
    required this.onTap,
  });

  final CatalogItem item;
  final bool isPlaying;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = paletteOf(context);
    final borderColor = Theme.of(context).brightness == Brightness.dark
        ? Colors.white.withValues(alpha: 0.15)
        : Colors.black.withValues(alpha: 0.15);

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          AspectRatio(
            aspectRatio: 16 / 9,
            child: Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: borderColor, width: 1),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(11),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Artwork(item.artwork, radius: 11),
                    if (isPlaying) const Center(child: PlayingEqualizerBars()),
                    Positioned.fill(
                      child: ArtworkHover(
                        mode: ArtworkHoverMode.darkenAndPlay,
                        radius: 11,
                        child: const SizedBox.expand(),
                      ),
                    ),
                    if (item.durationText != null &&
                        item.durationText!.isNotEmpty)
                      Positioned(
                        right: 6,
                        bottom: 6,
                        child: VideoDurationBadge(
                          durationText: item.durationText!,
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 10),
          Text(
            item.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.w500,
              fontSize: 14,
            ),
          ),
          if (item.subtitle.isNotEmpty)
            Text(
              item.subtitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: colors.secondary),
            ),
        ],
      ),
    );
  }
}

class HeroCard extends StatelessWidget {
  const HeroCard({
    super.key,
    required this.item,
    required this.isPlaying,
    required this.onTap,
  });

  final CatalogItem item;
  final bool isPlaying;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final borderColor = Theme.of(context).brightness == Brightness.dark
        ? Colors.white.withValues(alpha: 0.15)
        : Colors.black.withValues(alpha: 0.15);

    return AspectRatio(
      aspectRatio: 0.92,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(18),
        child: Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: borderColor, width: 1),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(17),
            child: Stack(
              fit: StackFit.expand,
              children: [
                Artwork(item.artwork, radius: 17),
                if (isPlaying) const Center(child: PlayingEqualizerBars()),
                Positioned.fill(
                  child: ArtworkHover(
                    mode: ArtworkHoverMode.darkenOnly,
                    radius: 17,
                    child: const SizedBox.expand(),
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 0,
                  child: Container(
                    padding: const EdgeInsets.fromLTRB(16, 34, 16, 14),
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: [
                          Colors.transparent,
                          Colors.black.withValues(alpha: 0.78),
                        ],
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          item.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        if (item.subtitle.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.only(top: 2),
                            child: Text(
                              item.subtitle,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: Colors.white.withValues(alpha: 0.72),
                                fontSize: 13,
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
