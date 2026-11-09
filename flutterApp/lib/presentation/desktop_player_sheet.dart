import 'dart:math';
import 'package:flutter/material.dart';
import '../data/lyrics_client.dart';
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
  int _activeLyricIndex = -1;
  DesktopLyrics? _lyrics;
  bool _isLoadingLyrics = false;

  final ScrollController _lyricsScrollController = ScrollController();

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

    _loadLyrics(widget.song);
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

    if (widget.song?.videoId != oldWidget.song?.videoId) {
      _loadLyrics(widget.song);
    }
  }

  Future<void> _loadLyrics(Song? song) async {
    if (song == null) {
      setState(() {
        _lyrics = null;
        _isLoadingLyrics = false;
        _activeLyricIndex = -1;
      });
      return;
    }

    setState(() {
      _isLoadingLyrics = true;
      _activeLyricIndex = -1;
    });

    final res = await DesktopLyricsClient.lookup(song);
    if (mounted && widget.song?.videoId == song.videoId) {
      setState(() {
        _lyrics = res;
        _isLoadingLyrics = false;
      });
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
                            child: _buildLyricsColumn(totalSeconds),
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
                borderRadius: BorderRadius.circular(10.0),
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

  Widget _buildLyricsColumn(int totalSeconds) {
    if (_isLoadingLyrics) {
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
          const Expanded(
            child: Center(
              child: CircularProgressIndicator(
                strokeWidth: 2.5,
                color: Colors.white70,
              ),
            ),
          ),
        ],
      );
    }

    final lyrics = _lyrics;
    if (lyrics == null || lyrics.lines.isEmpty) {
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
            child: Center(
              child: Text(
                'Lyrics unavailable',
                style: DesktopTypography.bodyLarge(
                  Colors.white.withValues(alpha: 0.4),
                ).copyWith(fontSize: 16.0),
              ),
            ),
          ),
        ],
      );
    }

    final currentMs = (widget.progress * totalSeconds * 1000).round();
    int newActive = -1;
    for (int i = 0; i < lyrics.lines.length; i++) {
      if (currentMs >= lyrics.lines[i].timeMs) {
        newActive = i;
      } else {
        break;
      }
    }

    if (newActive != _activeLyricIndex && newActive >= 0) {
      _activeLyricIndex = newActive;
      if (_lyricsScrollController.hasClients) {
        final targetOffset = max(0.0, (newActive - 2) * 52.0);
        _lyricsScrollController.animateTo(
          targetOffset,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOutCubic,
        );
      }
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 16.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Lyrics',
                style: DesktopTypography.headlineLarge(Colors.white).copyWith(
                  fontSize: 26.0,
                  fontWeight: FontWeight.w700,
                ),
              ),
              Text(
                lyrics.source,
                style: DesktopTypography.caption(
                  Colors.white.withValues(alpha: 0.35),
                ).copyWith(fontSize: 11.0),
              ),
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            controller: _lyricsScrollController,
            itemCount: lyrics.lines.length,
            itemBuilder: (context, index) {
              final lyric = lyrics.lines[index];
              if (lyric.isGap) return const SizedBox(height: 20.0);
              final isActive = index == _activeLyricIndex;

              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 10.0),
                child: MouseRegion(
                  cursor: SystemMouseCursors.click,
                  child: GestureDetector(
                    onTap: () {
                      if (totalSeconds > 0) {
                        widget.onSeek((lyric.timeMs / 1000) / totalSeconds);
                      }
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
                      child: Text(lyric.text),
                    ),
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
