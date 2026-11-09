import 'package:flutter/material.dart';
import '../data/innertube_client.dart';
import '../domain/song.dart';
import 'desktop_theme.dart';
import 'desktop_typography.dart';

/// The desktop New page, ported 1:1 from Kotlin [DesktopNewPage.kt].
///
/// Features:
/// - Fetches live Explore feed from YouTube Music InnerTube WEB_REMIX API ([Innertube.kt]).
/// - Renders "New albums & singles" horizontal carousel (208dp cards, 12dp radius).
/// - Renders "Trending" responsive multi-column track list (48dp artwork, CompactTrackRow).
/// - Renders "New music videos" widescreen carousel (260dp cards, 16:9 ratio).
class DesktopNewPage extends StatefulWidget {
  final DesktopThemePalette palette;
  final ValueChanged<Song> onSongSelected;

  const DesktopNewPage({
    super.key,
    required this.palette,
    required this.onSongSelected,
  });

  @override
  State<DesktopNewPage> createState() => _DesktopNewPageState();
}

class _DesktopNewPageState extends State<DesktopNewPage> {
  late Future<List<HomeShelf>> _exploreFuture;

  @override
  void initState() {
    super.initState();
    _loadFeed();
  }

  void _loadFeed() {
    _exploreFuture = InnertubeClient.instance.fetchExplore();
  }

  @override
  Widget build(BuildContext context) {
    final palette = widget.palette;

    return FutureBuilder<List<HomeShelf>>(
      future: _exploreFuture,
      builder: (context, snapshot) {
        return ListView(
          padding: const EdgeInsets.only(top: 8.0, bottom: 24.0),
          children: [
            // Page title (DesktopNewPage.kt:79-91)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
              child: Text(
                'New',
                style: DesktopTypography.displayLarge(palette.foreground).copyWith(
                  fontSize: 34.0,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -0.5,
                ),
              ),
            ),

            if (snapshot.connectionState == ConnectionState.waiting) ...[
              // Loading indicator (DesktopNewPage.kt:94-109)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 48.0),
                child: Center(
                  child: Column(
                    children: [
                      CircularProgressIndicator(
                        strokeWidth: 2.5,
                        color: palette.accent,
                      ),
                      const SizedBox(height: 14.0),
                      Text(
                        'Finding new music...',
                        style: DesktopTypography.bodyMedium(palette.secondary),
                      ),
                    ],
                  ),
                ),
              ),
            ] else if (snapshot.hasError) ...[
              // Error state (DesktopNewPage.kt:111-126)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 48.0),
                child: Center(
                  child: Column(
                    children: [
                      Text(
                        snapshot.error.toString(),
                        style: DesktopTypography.bodyMedium(palette.secondary),
                      ),
                      const SizedBox(height: 12.0),
                      ElevatedButton(
                        onPressed: () => setState(_loadFeed),
                        child: const Text('Try again'),
                      ),
                    ],
                  ),
                ),
              ),
            ] else if (!snapshot.hasData || snapshot.data!.isEmpty) ...[
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 48.0),
                child: Center(
                  child: Text(
                    'Nothing new to show right now.',
                    style: DesktopTypography.bodyMedium(palette.secondary),
                  ),
                ),
              ),
            ] else ...[
              // Shelves mapping (DesktopNewPage.kt:150-176)
              for (final shelf in snapshot.data!) ...[
                if (shelf.title.toLowerCase().contains('trending') ||
                    shelf.moreBrowseId?.startsWith('VL') == true)
                  _NewTrendingShelf(
                    shelf: shelf,
                    palette: palette,
                    onItemClick: (item) => widget.onSongSelected(item.toSong()),
                  )
                else if (shelf.title.toLowerCase().contains('video'))
                  _NewVideoShelf(
                    shelf: shelf,
                    palette: palette,
                    onItemClick: (item) => widget.onSongSelected(item.toSong()),
                  )
                else
                  _NewAlbumShelf(
                    shelf: shelf,
                    palette: palette,
                    onItemClick: (item) => widget.onSongSelected(item.toSong()),
                  ),
                const SizedBox(height: 14.0),
              ],
            ],
          ],
        );
      },
    );
  }
}

class _NewSectionHeader extends StatelessWidget {
  final String title;
  final DesktopThemePalette palette;
  final VoidCallback onTapMore;

  const _NewSectionHeader({
    required this.title,
    required this.palette,
    required this.onTapMore,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 10.0),
      child: InkWell(
        onTap: onTapMore,
        borderRadius: BorderRadius.circular(6.0),
        child: Row(
          children: [
            Expanded(
              child: Text(
                title,
                style: DesktopTypography.headlineMedium(palette.foreground).copyWith(
                  fontSize: 22.0,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.3,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            Icon(
              Icons.chevron_right_rounded,
              size: 20.0,
              color: palette.secondary,
            ),
          ],
        ),
      ),
    );
  }
}

/// Port of [NewAlbumShelf] in [DesktopNewPage.kt:225-292].
class _NewAlbumShelf extends StatelessWidget {
  final HomeShelf shelf;
  final DesktopThemePalette palette;
  final ValueChanged<ShelfItem> onItemClick;

  const _NewAlbumShelf({
    required this.shelf,
    required this.palette,
    required this.onItemClick,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _NewSectionHeader(
          title: shelf.title,
          palette: palette,
          onTapMore: () {},
        ),
        SizedBox(
          height: 272.0,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            itemCount: shelf.items.length,
            separatorBuilder: (_, _) => const SizedBox(width: 14.0),
            itemBuilder: (context, index) {
              final album = shelf.items[index];
              return _NewAlbumCard(
                album: album,
                palette: palette,
                onTap: () => onItemClick(album),
              );
            },
          ),
        ),
      ],
    );
  }
}

/// Port of [NewTrendingShelf] in [DesktopNewPage.kt:296-339].
class _NewTrendingShelf extends StatelessWidget {
  final HomeShelf shelf;
  final DesktopThemePalette palette;
  final ValueChanged<ShelfItem> onItemClick;

  const _NewTrendingShelf({
    required this.shelf,
    required this.palette,
    required this.onItemClick,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _NewSectionHeader(
          title: shelf.title,
          palette: palette,
          onTapMore: () {},
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16.0),
          child: LayoutBuilder(
            builder: (context, constraints) {
              // (maxWidth / 290.dp).toInt().coerceIn(1, 4) (line 313)
              final columnCount =
                  (constraints.maxWidth / 290.0).floor().clamp(1, 4);
              final rowsPerColumn =
                  ((shelf.items.length + columnCount - 1) / columnCount).ceil();

              final columns = <List<ShelfItem>>[];
              for (var i = 0; i < shelf.items.length; i += rowsPerColumn) {
                final end = (i + rowsPerColumn < shelf.items.length)
                    ? i + rowsPerColumn
                    : shelf.items.length;
                columns.add(shelf.items.sublist(i, end));
              }

              return Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (int c = 0; c < columns.length; c++) ...[
                    if (c > 0) const SizedBox(width: 16.0),
                    Expanded(
                      child: Column(
                        children: columns[c]
                            .map(
                              (track) => _CompactTrackRow(
                                title: track.title,
                                subtitle: track.subtitle,
                                thumbnailUrl: track.thumbnailUrl,
                                palette: palette,
                                onClick: () => onItemClick(track),
                              ),
                            )
                            .toList(),
                      ),
                    ),
                  ],
                ],
              );
            },
          ),
        ),
      ],
    );
  }
}

/// Port of [NewVideoShelf] in [DesktopNewPage.kt:342-411].
class _NewVideoShelf extends StatelessWidget {
  final HomeShelf shelf;
  final DesktopThemePalette palette;
  final ValueChanged<ShelfItem> onItemClick;

  const _NewVideoShelf({
    required this.shelf,
    required this.palette,
    required this.onItemClick,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _NewSectionHeader(
          title: shelf.title,
          palette: palette,
          onTapMore: () {},
        ),
        SizedBox(
          height: 220.0,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            itemCount: shelf.items.length,
            separatorBuilder: (_, _) => const SizedBox(width: 16.0),
            itemBuilder: (context, index) {
              final video = shelf.items[index];
              return _NewVideoCard(
                video: video,
                palette: palette,
                onTap: () => onItemClick(video),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _NewAlbumCard extends StatefulWidget {
  final ShelfItem album;
  final DesktopThemePalette palette;
  final VoidCallback onTap;

  const _NewAlbumCard({
    required this.album,
    required this.palette,
    required this.onTap,
  });

  @override
  State<_NewAlbumCard> createState() => _NewAlbumCardState();
}

class _NewAlbumCardState extends State<_NewAlbumCard> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    final palette = widget.palette;

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: SizedBox(
          width: 208.0,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Artwork 208x208 with 12dp rounded corners (DesktopNewPage.kt:260)
              ClipRRect(
                borderRadius: BorderRadius.circular(12.0),
                child: Container(
                  width: 208.0,
                  height: 208.0,
                  color: palette.surfaceRaised,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      if (widget.album.thumbnailUrl != null)
                        Image.network(
                          widget.album.thumbnailUrl!,
                          fit: BoxFit.cover,
                          errorBuilder: (_, _, _) => _artPlaceholder(palette),
                        )
                      else
                        _artPlaceholder(palette),
                      if (_isHovered)
                        Container(
                          color: Colors.black.withValues(alpha: 0.25),
                          child: Center(
                            child: Container(
                              width: 44.0,
                              height: 44.0,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                color: Colors.black.withValues(alpha: 0.5),
                              ),
                              child: const Icon(
                                Icons.play_arrow_rounded,
                                color: Colors.white,
                                size: 28.0,
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 10.0),
              Text(
                widget.album.title,
                style: DesktopTypography.bodyMedium(palette.foreground).copyWith(
                  fontWeight: FontWeight.w600,
                  fontSize: 14.0,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 2.0),
              Text(
                widget.album.subtitle,
                style: DesktopTypography.bodySmall(palette.secondary).copyWith(
                  fontSize: 12.0,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _artPlaceholder(DesktopThemePalette palette) {
    return Container(
      color: palette.cardFill,
      child: Center(
        child: Icon(
          Icons.album_rounded,
          size: 56.0,
          color: palette.secondary.withValues(alpha: 0.4),
        ),
      ),
    );
  }
}

class _NewVideoCard extends StatefulWidget {
  final ShelfItem video;
  final DesktopThemePalette palette;
  final VoidCallback onTap;

  const _NewVideoCard({
    required this.video,
    required this.palette,
    required this.onTap,
  });

  @override
  State<_NewVideoCard> createState() => _NewVideoCardState();
}

class _NewVideoCardState extends State<_NewVideoCard> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    final palette = widget.palette;

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: SizedBox(
          width: 260.0,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Artwork 16:9 widescreen card with 12dp rounded corners (DesktopNewPage.kt:378)
              ClipRRect(
                borderRadius: BorderRadius.circular(12.0),
                child: AspectRatio(
                  aspectRatio: 16 / 9,
                  child: Container(
                    color: palette.surfaceRaised,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        if (widget.video.thumbnailUrl != null)
                          Image.network(
                            widget.video.thumbnailUrl!,
                            fit: BoxFit.cover,
                            errorBuilder: (_, _, _) => _artPlaceholder(palette),
                          )
                        else
                          _artPlaceholder(palette),
                        if (_isHovered)
                          Container(
                            color: Colors.black.withValues(alpha: 0.25),
                            child: Center(
                              child: Container(
                                width: 44.0,
                                height: 44.0,
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  color: Colors.black.withValues(alpha: 0.5),
                                ),
                                child: const Icon(
                                  Icons.play_arrow_rounded,
                                  color: Colors.white,
                                  size: 28.0,
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 10.0),
              Text(
                widget.video.title,
                style: DesktopTypography.bodyMedium(palette.foreground).copyWith(
                  fontWeight: FontWeight.w600,
                  fontSize: 14.0,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 2.0),
              Text(
                widget.video.subtitle,
                style: DesktopTypography.bodySmall(palette.secondary).copyWith(
                  fontSize: 12.0,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _artPlaceholder(DesktopThemePalette palette) {
    return Container(
      color: palette.cardFill,
      child: Center(
        child: Icon(
          Icons.smart_display_rounded,
          size: 48.0,
          color: palette.secondary.withValues(alpha: 0.4),
        ),
      ),
    );
  }
}

class _CompactTrackRow extends StatefulWidget {
  final String title;
  final String subtitle;
  final String? thumbnailUrl;
  final DesktopThemePalette palette;
  final VoidCallback onClick;

  const _CompactTrackRow({
    required this.title,
    required this.subtitle,
    required this.thumbnailUrl,
    required this.palette,
    required this.onClick,
  });

  @override
  State<_CompactTrackRow> createState() => _CompactTrackRowState();
}

class _CompactTrackRowState extends State<_CompactTrackRow> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    final palette = widget.palette;

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      child: GestureDetector(
        onTap: widget.onClick,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 4.0, horizontal: 6.0),
          decoration: BoxDecoration(
            color: _isHovered ? palette.rowHover : Colors.transparent,
            borderRadius: BorderRadius.circular(6.0),
          ),
          child: Row(
            children: [
              // 48.dp thumbnail with 7dp rounded corners
              ClipRRect(
                borderRadius: BorderRadius.circular(7.0),
                child: Container(
                  width: 48.0,
                  height: 48.0,
                  color: palette.surfaceRaised,
                  child: widget.thumbnailUrl != null
                      ? Image.network(
                          widget.thumbnailUrl!,
                          fit: BoxFit.cover,
                          errorBuilder: (_, _, _) => Icon(
                            Icons.music_note_rounded,
                            color: palette.secondary,
                            size: 24.0,
                          ),
                        )
                      : Icon(
                          Icons.music_note_rounded,
                          color: palette.secondary,
                          size: 24.0,
                        ),
                ),
              ),
              const SizedBox(width: 12.0),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      widget.title,
                      style: DesktopTypography.bodyMedium(palette.foreground)
                          .copyWith(
                        fontWeight: FontWeight.w600,
                        fontSize: 14.0,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 2.0),
                    Text(
                      widget.subtitle,
                      style: DesktopTypography.bodySmall(palette.secondary)
                          .copyWith(fontSize: 12.0),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
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
