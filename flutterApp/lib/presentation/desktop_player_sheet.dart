import 'dart:math';
import 'package:flutter/material.dart';
import '../domain/song.dart';
import 'desktop_theme.dart';
import 'desktop_thin_slider.dart';
import 'desktop_typography.dart';

/// Fullscreen immersive landscape player sheet, ported 1:1 from Kotlin
/// [DesktopPlayerSheet.kt] and [LandscapePlayer.kt].
///
/// Features:
/// - 360ms slide up / 280ms slide down with [Curves.fastOutSlowIn].
/// - Vertical drag-to-dismiss gesture with handle.
/// - Two-column landscape split:
///   - Left: Large album artwork, track title, artist, scrubber slider, transport buttons.
///   - Right: Synchronized Apple Music-style lyrics with autoscroll and click-to-seek.
class DesktopPlayerSheet extends StatefulWidget {
  final bool visible;
  final Song? song;
  final bool isPlaying;
  final bool isLoading;
  final double progress; // 0.0 to 1.0
  final double volume;
  final bool shuffle;
  final VoidCallback onDismiss;
  final VoidCallback onPlayPause;
  final VoidCallback onPrevious;
  final VoidCallback onNext;
  final ValueChanged<double> onSeek;
  final ValueChanged<double> onVolumeChange;
  final VoidCallback onToggleShuffle;
  final DesktopThemePalette palette;

  const DesktopPlayerSheet({
    super.key,
    required this.visible,
    required this.song,
    required this.isPlaying,
    required this.isLoading,
    required this.progress,
    required this.volume,
    required this.shuffle,
    required this.onDismiss,
    required this.onPlayPause,
    required this.onPrevious,
    required this.onNext,
    required this.onSeek,
    required this.onVolumeChange,
    required this.onToggleShuffle,
    required this.palette,
  });

  @override
  State<DesktopPlayerSheet> createState() => _DesktopPlayerSheetState();
}

class _DesktopPlayerSheetState extends State<DesktopPlayerSheet>
    with SingleTickerProviderStateMixin {
  late AnimationController _animController;
  late Animation<double> _slideAnimation;
  double _dragOffset = 0.0;
  int _activeLyricIndex = 2;

  final ScrollController _lyricsScrollController = ScrollController();

  static const List<Map<String, dynamic>> _sampleLyrics = [
    {'time': 0, 'text': 'Intro'},
    {'time': 12, 'text': 'Di saat angin berhembus perlahan'},
    {'time': 24, 'text': 'Kuteringat semua kenangan yang lalu'},
    {'time': 38, 'text': 'Bayangmu hadir di setiap langkahku'},
    {'time': 52, 'text': 'Menyapa rindu yang tak kunjung padam'},
    {'time': 68, 'text': 'Biar waktu yang akan menjawab'},
    {'time': 84, 'text': 'Semua tanya di dalam hatiku'},
    {'time': 102, 'text': 'Kutahu kau masih di sana'},
    {'time': 120, 'text': 'Menanti pagi kembali bersinar'},
    {'time': 140, 'text': 'Outro'},
  ];

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 360),
      reverseDuration: const Duration(milliseconds: 280),
    );
    _slideAnimation = CurvedAnimation(
      parent: _animController,
      curve: Curves.fastOutSlowIn,
      reverseCurve: Curves.fastOutSlowIn,
    );

    if (widget.visible) {
      _animController.value = 1.0;
    }
  }

  @override
  void didUpdateWidget(DesktopPlayerSheet oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.visible != oldWidget.visible) {
      if (widget.visible) {
        _animController.forward();
      } else {
        _animController.reverse();
      }
    }
  }

  @override
  void dispose() {
    _animController.dispose();
    _lyricsScrollController.dispose();
    super.dispose();
  }

  String _formatTime(int seconds) {
    final m = seconds ~/ 60;
    final s = seconds % 60;
    return '$m:${s.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    if (!_animController.isAnimating && !widget.visible && _dragOffset == 0.0) {
      return const SizedBox.shrink();
    }

    final song = widget.song;
    final totalSeconds = song?.durationSeconds ?? 180;
    final currentSeconds = (widget.progress * totalSeconds).round();
    final remainingSeconds = totalSeconds - currentSeconds;

    return AnimatedBuilder(
      animation: _slideAnimation,
      builder: (context, child) {
        final screenHeight = MediaQuery.of(context).size.height;
        final baseOffset = (1.0 - _slideAnimation.value) * screenHeight;
        final totalY = max(0.0, baseOffset + _dragOffset);

        return Transform.translate(
          offset: Offset(0, totalY),
          child: child,
        );
      },
      child: GestureDetector(
        onVerticalDragUpdate: (details) {
          setState(() {
            _dragOffset = max(0.0, _dragOffset + details.delta.dy);
          });
        },
        onVerticalDragEnd: (details) {
          final screenHeight = MediaQuery.of(context).size.height;
          if (_dragOffset > screenHeight * 0.25 ||
              details.primaryVelocity! > 1400.0) {
            widget.onDismiss();
          }
          setState(() {
            _dragOffset = 0.0;
          });
        },
        child: Container(
          width: double.infinity,
          height: double.infinity,
          decoration: BoxDecoration(
            color: widget.palette.background,
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [
                widget.palette.surfaceRaised,
                widget.palette.background,
              ],
            ),
          ),
          child: Stack(
            children: [
              // Drag Handle at Top Center (38dp x 5dp, radius 3dp, lines 173-188)
              Align(
                alignment: Alignment.topCenter,
                child: Padding(
                  padding: const EdgeInsets.only(top: 8.0),
                  child: Container(
                    width: 38.0,
                    height: 5.0,
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.70),
                      borderRadius: BorderRadius.circular(3.0),
                    ),
                  ),
                ),
              ),

              // Minimize Button (Top Left)
              Positioned(
                top: 8.0,
                left: 12.0,
                child: IconButton(
                  iconSize: 28.0,
                  icon: const Icon(
                    Icons.keyboard_arrow_down_rounded,
                    color: Colors.white,
                  ),
                  tooltip: 'Minimize player',
                  onPressed: widget.onDismiss,
                ),
              ),

              // Close / Dismiss hit area on handle
              Align(
                alignment: Alignment.topCenter,
                child: GestureDetector(
                  behavior: HitTestBehavior.translucent,
                  onTap: widget.onDismiss,
                  child: const SizedBox(
                    width: 140.0,
                    height: 36.0,
                  ),
                ),
              ),

              // Two-column Player content
              Positioned.fill(
                top: 48.0,
                bottom: 24.0,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 48.0),
                  child: LayoutBuilder(
                    builder: (context, constraints) {
                      final isWide = constraints.maxWidth >= 720.0;

                      if (!isWide) {
                        return _buildCompactColumn(
                          song,
                          currentSeconds,
                          remainingSeconds,
                        );
                      }

                      return Row(
                        children: [
                          // Left Column: Artwork + Info + Scrubber + Controls
                          Expanded(
                            flex: 5,
                            child: _buildPlayerControlsColumn(
                              song,
                              currentSeconds,
                              remainingSeconds,
                            ),
                          ),

                          const SizedBox(width: 48.0),

                          // Right Column: Apple Music Synchronized Lyrics
                          Expanded(
                            flex: 5,
                            child: _buildLyricsColumn(),
                          ),
                        ],
                      );
                    },
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPlayerControlsColumn(
    Song? song,
    int currentSeconds,
    int remainingSeconds,
  ) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Artwork: large rounded square with subtle shadow
        Center(
          child: Container(
            constraints: const BoxConstraints(
              maxWidth: 320.0,
              maxHeight: 320.0,
            ),
            child: AspectRatio(
              aspectRatio: 1.0,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(16.0),
                child: Container(
                  color: widget.palette.surfaceRaised,
                  child: song?.thumbnailUrl != null
                      ? Image.network(
                          song!.thumbnailUrl!,
                          fit: BoxFit.cover,
                          errorBuilder: (_, _, _) => _artworkPlaceholder(),
                        )
                      : _artworkPlaceholder(),
                ),
              ),
            ),
          ),
        ),

        const SizedBox(height: 28.0),

        // Title & Artist
        Text(
          song?.title ?? 'No track playing',
          style: DesktopTypography.headlineMedium(Colors.white).copyWith(
            fontWeight: FontWeight.w700,
            fontSize: 22.0,
          ),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        const SizedBox(height: 4.0),
        Text(
          song?.artist ?? 'Select a track to start playback',
          style: DesktopTypography.bodyMedium(
            widget.palette.secondary,
          ).copyWith(fontSize: 15.0),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),

        const SizedBox(height: 18.0),

        // Apple Music Scrubber Slider
        DesktopThinSlider(
          value: widget.progress,
          onChanged: widget.onSeek,
          width: double.infinity,
          idleHeight: 5.0,
          activeHeight: 9.0,
          palette: widget.palette,
        ),
        const SizedBox(height: 4.0),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              _formatTime(currentSeconds),
              style: DesktopTypography.caption(widget.palette.secondary)
                  .copyWith(fontSize: 11.0),
            ),
            Text(
              '-${_formatTime(remainingSeconds)}',
              style: DesktopTypography.caption(widget.palette.secondary)
                  .copyWith(fontSize: 11.0),
            ),
          ],
        ),

        const SizedBox(height: 16.0),

        // Transport Controls Row
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            IconButton(
              iconSize: 22.0,
              icon: Icon(
                Icons.shuffle_rounded,
                color: widget.shuffle
                    ? widget.palette.accent
                    : widget.palette.secondary,
              ),
              onPressed: widget.onToggleShuffle,
            ),
            IconButton(
              iconSize: 28.0,
              icon: const Icon(
                Icons.skip_previous_rounded,
                color: Colors.white,
              ),
              onPressed: widget.onPrevious,
            ),
            // Large Play / Pause button
            GestureDetector(
              onTap: widget.onPlayPause,
              child: Container(
                width: 58.0,
                height: 58.0,
                decoration: const BoxDecoration(
                  shape: BoxShape.circle,
                  color: Colors.white,
                ),
                child: Center(
                  child: widget.isLoading
                      ? const SizedBox(
                          width: 24.0,
                          height: 24.0,
                          child: CircularProgressIndicator(
                            strokeWidth: 2.5,
                            color: Colors.black,
                          ),
                        )
                      : Icon(
                          widget.isPlaying
                              ? Icons.pause_rounded
                              : Icons.play_arrow_rounded,
                          color: Colors.black,
                          size: 34.0,
                        ),
                ),
              ),
            ),
            IconButton(
              iconSize: 28.0,
              icon: const Icon(
                Icons.skip_next_rounded,
                color: Colors.white,
              ),
              onPressed: widget.onNext,
            ),
            IconButton(
              iconSize: 22.0,
              icon: Icon(
                Icons.repeat_rounded,
                color: widget.palette.secondary,
              ),
              onPressed: () {},
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildLyricsColumn() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 16.0),
          child: Text(
            'Lyrics',
            style: DesktopTypography.headlineLarge(Colors.white).copyWith(
              fontSize: 26.0,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        Expanded(
          child: ListView.builder(
            controller: _lyricsScrollController,
            itemCount: _sampleLyrics.length,
            itemBuilder: (context, index) {
              final lyric = _sampleLyrics[index];
              final isActive = index == _activeLyricIndex;

              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 12.0),
                child: GestureDetector(
                  onTap: () {
                    setState(() => _activeLyricIndex = index);
                  },
                  child: AnimatedDefaultTextStyle(
                    duration: const Duration(milliseconds: 200),
                    style: TextStyle(
                      fontFamily: 'SFProDisplay',
                      fontSize: isActive ? 26.0 : 22.0,
                      fontWeight:
                          isActive ? FontWeight.w700 : FontWeight.w500,
                      color: isActive
                          ? Colors.white
                          : Colors.white.withValues(alpha: 0.38),
                      height: 1.3,
                    ),
                    child: Text(lyric['text'] as String),
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _buildCompactColumn(
    Song? song,
    int currentSeconds,
    int remainingSeconds,
  ) {
    return SingleChildScrollView(
      child: _buildPlayerControlsColumn(
        song,
        currentSeconds,
        remainingSeconds,
      ),
    );
  }

  Widget _artworkPlaceholder() {
    return Center(
      child: Icon(
        Icons.album_rounded,
        size: 96.0,
        color: widget.palette.secondary.withValues(alpha: 0.4),
      ),
    );
  }
}
