import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import '../domain/song.dart';
import 'desktop_theme.dart';
import 'desktop_thin_slider.dart';

enum DesktopRepeatMode {
  off('Off'),
  all('All'),
  one('One');

  final String label;
  const DesktopRepeatMode(this.label);

  DesktopRepeatMode next() {
    switch (this) {
      case DesktopRepeatMode.off:
        return DesktopRepeatMode.all;
      case DesktopRepeatMode.all:
        return DesktopRepeatMode.one;
      case DesktopRepeatMode.one:
        return DesktopRepeatMode.off;
    }
  }
}

/// Translated 1:1 from DesktopApp.kt lines 6210-6350 (DesktopMiniPlayer).
class DesktopMiniPlayer extends StatelessWidget {
  final Song? song;
  final bool isPlaying;
  final bool isLoading;
  final bool previousEnabled;
  final double progress; // 0.0 to 1.0
  final double volume; // 0.0 to 1.0
  final bool shuffle;
  final DesktopRepeatMode repeatMode;
  final bool lyricsOpen;
  final bool queueOpen;
  final VoidCallback onExpand;
  final VoidCallback onPlayPause;
  final VoidCallback onPrevious;
  final VoidCallback onNext;
  final ValueChanged<bool> onShuffleChange;
  final ValueChanged<DesktopRepeatMode> onRepeatModeChange;
  final ValueChanged<double> onVolumeChange;
  final VoidCallback onOpenLyrics;
  final VoidCallback onOpenQueue;
  final DesktopThemePalette palette;

  const DesktopMiniPlayer({
    super.key,
    required this.song,
    required this.isPlaying,
    required this.isLoading,
    required this.previousEnabled,
    required this.progress,
    required this.volume,
    required this.shuffle,
    required this.repeatMode,
    required this.lyricsOpen,
    required this.queueOpen,
    required this.onExpand,
    required this.onPlayPause,
    required this.onPrevious,
    required this.onNext,
    required this.onShuffleChange,
    required this.onRepeatModeChange,
    required this.onVolumeChange,
    required this.onOpenLyrics,
    required this.onOpenQueue,
    required this.palette,
  });

  @override
  Widget build(BuildContext context) {
    // 1:1 from Kotlin: shape = RoundedCornerShape(16.dp), widthIn(max = 760.dp), height = 58.dp
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760.0),
        child: Container(
          height: 58.0,
          margin: const EdgeInsets.symmetric(horizontal: 16.0),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16.0),
            border: Border.all(
              color: palette.barEdge,
              width: 0.5,
            ),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(16.0),
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 20.0, sigmaY: 20.0),
              child: Container(
                color: palette.barGlass,
                child: Stack(
                  children: [
                    // Main controls Row
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12.0,
                        vertical: 8.0,
                      ),
                      child: Row(
                        children: [
                          // Shuffle button (size 34, icon 18)
                          _ToolbarButton(
                            size: 34.0,
                            iconAsset: 'assets/icons/player_transport_shuffle.svg',
                            iconSize: 18.0,
                            tint: shuffle ? palette.accent : palette.secondary,
                            onTap: () => onShuffleChange(!shuffle),
                          ),
                          const SizedBox(width: 4.0),

                          // Previous button (size 34, icon 20)
                          _ToolbarButton(
                            size: 34.0,
                            iconAsset: 'assets/icons/player_transport_previous.svg',
                            iconSize: 20.0,
                            tint: previousEnabled
                                ? palette.foreground
                                : palette.secondary.withValues(alpha: 0.4),
                            onTap: previousEnabled ? onPrevious : null,
                          ),
                          const SizedBox(width: 4.0),

                          // Play / Pause button (size 36, icon 23)
                          _ToolbarButton(
                            size: 36.0,
                            onTap: song != null ? onPlayPause : null,
                            child: isLoading
                                ? SizedBox(
                                    width: 18.0,
                                    height: 18.0,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2.0,
                                      valueColor: AlwaysStoppedAnimation<Color>(
                                        palette.foreground,
                                      ),
                                    ),
                                  )
                                : SvgPicture.asset(
                                    isPlaying
                                        ? 'assets/icons/player_transport_pause.svg'
                                        : 'assets/icons/player_transport_play.svg',
                                    width: 23.0,
                                    height: 23.0,
                                    colorFilter: ColorFilter.mode(
                                      palette.foreground,
                                      BlendMode.srcIn,
                                    ),
                                  ),
                          ),
                          const SizedBox(width: 4.0),

                          // Next button (size 34, icon 20)
                          _ToolbarButton(
                            size: 34.0,
                            iconAsset: 'assets/icons/player_transport_next.svg',
                            iconSize: 20.0,
                            tint: song != null
                                ? palette.foreground
                                : palette.secondary.withValues(alpha: 0.4),
                            onTap: song != null ? onNext : null,
                          ),
                          const SizedBox(width: 4.0),

                          // Repeat button (size 34, icon 18)
                          _ToolbarButton(
                            size: 34.0,
                            iconAsset: repeatMode == DesktopRepeatMode.one
                                ? 'assets/icons/player_transport_repeat_one.svg'
                                : 'assets/icons/player_transport_repeat.svg',
                            iconSize: 18.0,
                            tint: repeatMode == DesktopRepeatMode.off
                                ? palette.secondary
                                : palette.accent,
                            onTap: () => onRepeatModeChange(repeatMode.next()),
                          ),

                          // Middle: Artwork and title/artist (or spacer when empty)
                          if (song == null) ...[
                            const Spacer(),
                          ] else ...[
                            const SizedBox(width: 8.0),
                            // Artwork 42dp rounded 7dp
                            GestureDetector(
                              onTap: onExpand,
                              child: ClipRRect(
                                borderRadius: BorderRadius.circular(7.0),
                                child: Container(
                                  width: 42.0,
                                  height: 42.0,
                                  color: palette.surfaceRaised,
                                  child: song!.thumbnailUrl != null
                                      ? Image.network(
                                          song!.thumbnailUrl!,
                                          fit: BoxFit.cover,
                                          errorBuilder: (_, _, _) => Icon(
                                            Icons.music_note_rounded,
                                            color: palette.secondary,
                                          ),
                                        )
                                      : Icon(
                                          Icons.music_note_rounded,
                                          color: palette.secondary,
                                        ),
                                ),
                              ),
                            ),
                            const SizedBox(width: 8.0),
                            // Title & artist
                            Expanded(
                              child: GestureDetector(
                                onTap: onExpand,
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      song!.title,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        fontFamily: 'SFProDisplay',
                                        fontSize: 14.0,
                                        fontWeight: FontWeight.w600,
                                        color: palette.foreground,
                                      ),
                                    ),
                                    Text(
                                      song!.artist,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        fontFamily: 'SFProDisplay',
                                        fontSize: 12.0,
                                        fontWeight: FontWeight.w400,
                                        color: palette.secondary,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                            const SizedBox(width: 8.0),
                          ],

                          // Trailing actions: Lyrics, Queue, Volume slider
                          _ToolbarButton(
                            size: 32.0,
                            iconAsset: 'assets/icons/player_transport_lyrics.svg',
                            iconSize: 18.0,
                            tint: lyricsOpen
                                ? palette.foreground
                                : palette.secondary,
                            onTap: onOpenLyrics,
                          ),
                          const SizedBox(width: 2.0),
                          _ToolbarButton(
                            size: 32.0,
                            iconAsset: 'assets/icons/player_transport_up_next.svg',
                            iconSize: 18.0,
                            tint: queueOpen
                                ? palette.foreground
                                : palette.secondary,
                            onTap: onOpenQueue,
                          ),
                          const SizedBox(width: 6.0),

                          // Volume slider: Apple Music scrubber (DesktopThinSlider.kt, width 72.dp)
                          DesktopThinSlider(
                            value: volume,
                            onChanged: onVolumeChange,
                            width: 72.0,
                            idleHeight: 4.0,
                            activeHeight: 7.0,
                            palette: palette,
                          ),
                        ],
                      ),
                    ),

                    // Centered 48.dp logo when song is null (lines 6329-6338)
                    if (song == null)
                      Center(
                        child: SvgPicture.asset(
                          'assets/icons/no-music.svg',
                          width: 48.0,
                          height: 48.0,
                          colorFilter: ColorFilter.mode(
                            palette.secondary.withValues(alpha: 0.4),
                            BlendMode.srcIn,
                          ),
                        ),
                      ),

                    // Bottom progress line: height 2dp (lines 6339-6350)
                    if (song != null)
                      Align(
                        alignment: Alignment.bottomLeft,
                        child: Container(
                          height: 2.0,
                          color: palette.foreground.withValues(alpha: 0.12),
                          child: progress > 0.0
                              ? FractionallySizedBox(
                                  alignment: Alignment.centerLeft,
                                  widthFactor: progress.clamp(0.0, 1.0),
                                  child: Container(
                                    color:
                                        palette.accent.withValues(alpha: 0.92),
                                  ),
                                )
                              : null,
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ToolbarButton extends StatefulWidget {
  final double size;
  final String? iconAsset;
  final double? iconSize;
  final Color? tint;
  final Widget? child;
  final VoidCallback? onTap;

  const _ToolbarButton({
    required this.size,
    this.iconAsset,
    this.iconSize,
    this.tint,
    this.child,
    this.onTap,
  });

  @override
  State<_ToolbarButton> createState() => _ToolbarButtonState();
}

class _ToolbarButtonState extends State<_ToolbarButton> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      cursor: widget.onTap != null
          ? SystemMouseCursors.click
          : SystemMouseCursors.basic,
      child: GestureDetector(
        onTap: widget.onTap,
        child: Container(
          width: widget.size,
          height: widget.size,
          decoration: BoxDecoration(
            color: _isHovered && widget.onTap != null
                ? Colors.white.withValues(alpha: 0.08)
                : Colors.transparent,
            shape: BoxShape.circle,
          ),
          alignment: Alignment.center,
          child: widget.child ??
              (widget.iconAsset != null
                  ? SvgPicture.asset(
                      widget.iconAsset!,
                      width: widget.iconSize ?? 18.0,
                      height: widget.iconSize ?? 18.0,
                      colorFilter: ColorFilter.mode(
                        widget.tint ?? Colors.white,
                        BlendMode.srcIn,
                      ),
                    )
                  : const SizedBox()),
        ),
      ),
    );
  }
}
