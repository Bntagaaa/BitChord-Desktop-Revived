import 'package:flutter/material.dart';
import '../domain/song.dart';
import 'desktop_theme.dart';
import 'desktop_typography.dart';

/// The desktop New page, ported 1:1 from Kotlin [DesktopNewPage.kt].
///
/// Layout:
/// - Page title: "New" (displayLarge 34sp W800, padding 16dp)
/// - "New albums & singles" horizontal carousel (208dp cards, 12dp radius)
/// - "Trending" responsive multi-column track list (48dp artwork, CompactTrackRow)
class DesktopNewPage extends StatelessWidget {
  final DesktopThemePalette palette;
  final ValueChanged<Song> onSongSelected;

  const DesktopNewPage({
    super.key,
    required this.palette,
    required this.onSongSelected,
  });

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.only(top: 8.0, bottom: 24.0),
      children: [
        // Page title (lines 79-91 in DesktopNewPage.kt)
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

        // Section 1: "New albums & singles"
        _NewSectionHeader(
          title: 'New albums & singles',
          palette: palette,
          onTapMore: () {},
        ),
        SizedBox(
          height: 272.0,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            itemCount: _sampleAlbums.length,
            separatorBuilder: (_, _) => const SizedBox(width: 14.0),
            itemBuilder: (context, index) {
              final album = _sampleAlbums[index];
              return _NewAlbumCard(
                album: album,
                palette: palette,
                onTap: () {
                  onSongSelected(
                    Song(
                      videoId: 'album-$index',
                      title: album.title,
                      artist: album.subtitle,
                      thumbnailUrl: album.thumbnailUrl,
                      durationSeconds: 210,
                    ),
                  );
                },
              );
            },
          ),
        ),

        const SizedBox(height: 12.0),

        // Section 2: "Trending"
        _NewSectionHeader(
          title: 'Trending',
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
                  ((_sampleTrending.length + columnCount - 1) / columnCount)
                      .ceil();

              final columns = <List<_ShelfItem>>[];
              for (var i = 0; i < _sampleTrending.length; i += rowsPerColumn) {
                final end = (i + rowsPerColumn < _sampleTrending.length)
                    ? i + rowsPerColumn
                    : _sampleTrending.length;
                columns.add(_sampleTrending.sublist(i, end));
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
                                onClick: () {
                                  onSongSelected(
                                    Song(
                                      videoId: track.videoId ?? 'track-$c',
                                      title: track.title,
                                      artist: track.subtitle,
                                      thumbnailUrl: track.thumbnailUrl,
                                      durationSeconds: 195,
                                    ),
                                  );
                                },
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

class _ShelfItem {
  final String title;
  final String subtitle;
  final String? thumbnailUrl;
  final String? videoId;

  const _ShelfItem({
    required this.title,
    required this.subtitle,
    this.thumbnailUrl,
    this.videoId,
  });
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
                style:
                    DesktopTypography.headlineMedium(palette.foreground).copyWith(
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

class _NewAlbumCard extends StatefulWidget {
  final _ShelfItem album;
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
              // Artwork 208x208 with 12dp rounded corners
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
                style:
                    DesktopTypography.bodyMedium(palette.foreground).copyWith(
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
              IconButton(
                iconSize: 18.0,
                icon: Icon(
                  Icons.more_vert_rounded,
                  color: palette.secondary,
                ),
                onPressed: () {},
              ),
            ],
          ),
        ),
      ),
    );
  }
}

const List<_ShelfItem> _sampleAlbums = [
  _ShelfItem(
    title: 'Dicari Onty Onty',
    subtitle: 'Single • Maman Fvndy',
  ),
  _ShelfItem(
    title: 'DJ Bukan Sombong (GANDENG DUA)',
    subtitle: 'Single • Maman Fvndy & WG LHC',
  ),
  _ShelfItem(
    title: 'Olala',
    subtitle: 'Single • Maman Fvndy',
  ),
  _ShelfItem(
    title: 'Dahlia',
    subtitle: 'Single • Denny Caknan',
  ),
  _ShelfItem(
    title: 'Sigar',
    subtitle: 'Single • Denny Caknan',
  ),
];

const List<_ShelfItem> _sampleTrending = [
  _ShelfItem(
    title: 'DIRANTAI DIGELANGI RINDU',
    subtitle: 'Jeyma Monica',
  ),
  _ShelfItem(
    title: 'LAUT KIDUL',
    subtitle: 'Jeyma Monica Official',
  ),
  _ShelfItem(
    title: 'DENNY CAKNAN - DAHLIA',
    subtitle: 'DC. PRODUCTION',
  ),
  _ShelfItem(
    title: 'TERBELAH JADI DUA',
    subtitle: 'Jeyma Monica',
  ),
  _ShelfItem(
    title: 'Kita Lewati Berdua',
    subtitle: 'Overnight',
  ),
  _ShelfItem(
    title: 'GHEA PUTRI MAHESA FT GERRY MAHESA',
    subtitle: 'MAHESA Official',
  ),
  _ShelfItem(
    title: 'SALAH APA',
    subtitle: 'Fira Cantika X Irwan Krisdiyanto',
  ),
  _ShelfItem(
    title: 'Perceraian Lara',
    subtitle: 'Fira Cantika, Irwan Krisdiyanto',
  ),
  _ShelfItem(
    title: 'SHOCK',
    subtitle: 'TREASURE',
  ),
  _ShelfItem(
    title: 'ROSSA - TAK SANGGUP LAGI',
    subtitle: 'POP BOOST',
  ),
  _ShelfItem(
    title: 'Cobalah Untuk Mengerti',
    subtitle: 'Stevano muhaling',
  ),
  _ShelfItem(
    title: 'Cinta Sudah Lewat',
    subtitle: 'Wijaya 80, yovie widianto',
  ),
];
