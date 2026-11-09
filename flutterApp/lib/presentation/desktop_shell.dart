import 'package:flutter/material.dart';
import '../playback/audio_playback_controller.dart';
import 'desktop_mini_player.dart';
import 'desktop_new_page.dart';
import 'desktop_player_sheet.dart';
import 'desktop_sidebar.dart';
import 'desktop_theme.dart';
import 'desktop_typography.dart';
import 'sidebar_policy.dart';

/// Desktop application frame translated 1:1 from DesktopApp.kt lines 6470-6560.
class DesktopShell extends StatefulWidget {
  final DesktopThemeController themeController;

  const DesktopShell({
    super.key,
    required this.themeController,
  });

  @override
  State<DesktopShell> createState() => _DesktopShellState();
}

class _DesktopShellState extends State<DesktopShell> {
  final AudioPlaybackController _audio = AudioPlaybackController();

  bool _userSidebarCollapsed = false;
  bool _sidebarDrawerOpen = false;
  DesktopDestination _destination = DesktopDestination.listenNow;
  bool _settingsOpen = false;

  DesktopRepeatMode _repeatMode = DesktopRepeatMode.off;
  bool _lyricsOpen = false;
  bool _queueOpen = false;
  bool _playerSheetOpen = false;

  @override
  void dispose() {
    _audio.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([widget.themeController, _audio]),
      builder: (context, _) {
        final palette = widget.themeController.palette;

        return LayoutBuilder(
          builder: (context, constraints) {
            final windowWidth = constraints.maxWidth;
            final isNarrow = DesktopSidebarWidthPolicy.shouldUseRail(windowWidth);
            final inlineCollapsed = DesktopSidebarWidthPolicy.isInlineCollapsed(
              windowWidth,
              _userSidebarCollapsed,
            );

            return Scaffold(
              backgroundColor: palette.background,
              body: Stack(
                children: [
                  // Row: Sidebar on left, Content Area on right
                  Row(
                    children: [
                      DesktopSidebar(
                        collapsed: inlineCollapsed,
                        onToggleCollapse: () {
                          if (isNarrow) {
                            setState(() => _sidebarDrawerOpen = !_sidebarDrawerOpen);
                          } else {
                            setState(() => _userSidebarCollapsed = !_userSidebarCollapsed);
                          }
                        },
                        destination: _destination,
                        settingsOpen: _settingsOpen,
                        palette: palette,
                        onDestinationSelected: (dest) {
                          setState(() {
                            _destination = dest;
                            _settingsOpen = false;
                          });
                        },
                        onOpenSettings: () {
                          setState(() => _settingsOpen = true);
                        },
                      ),

                      // Main Content area (starts immediately at top, matching DesktopApp.kt)
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.only(bottom: 132.0),
                          child: _buildPageContent(palette),
                        ),
                      ),
                    ],
                  ),

                  // Floating Mini Player Dock (bottom clearance 24.dp)
                  Positioned(
                    left: inlineCollapsed ? 72.0 : 220.0,
                    right: 0.0,
                    bottom: 24.0,
                    child: DesktopMiniPlayer(
                      song: _audio.currentSong,
                      isPlaying: _audio.isPlaying,
                      isLoading: _audio.isLoading,
                      previousEnabled: true,
                      progress: _audio.progress,
                      volume: _audio.volume,
                      shuffle: _audio.shuffle,
                      repeatMode: _repeatMode,
                      lyricsOpen: _lyricsOpen,
                      queueOpen: _queueOpen,
                      palette: palette,
                      onExpand: () {
                        setState(() => _playerSheetOpen = true);
                      },
                      onPlayPause: () => _audio.togglePlayPause(),
                      onPrevious: () {},
                      onNext: () {},
                      onShuffleChange: (_) => _audio.toggleShuffle(),
                      onRepeatModeChange: (r) => setState(() => _repeatMode = r),
                      onVolumeChange: (v) => _audio.setVolume(v),
                      onOpenLyrics: () => setState(() => _lyricsOpen = !_lyricsOpen),
                      onOpenQueue: () => setState(() => _queueOpen = !_queueOpen),
                    ),
                  ),

                  // Fullscreen Immersive Player Sheet
                  DesktopPlayerSheet(
                    visible: _playerSheetOpen,
                    song: _audio.currentSong,
                    isPlaying: _audio.isPlaying,
                    isLoading: _audio.isLoading,
                    progress: _audio.progress,
                    volume: _audio.volume,
                    shuffle: _audio.shuffle,
                    palette: palette,
                    onDismiss: () => setState(() => _playerSheetOpen = false),
                    onPlayPause: () => _audio.togglePlayPause(),
                    onPrevious: () {},
                    onNext: () {},
                    onSeek: (p) => _audio.seek(p),
                    onVolumeChange: (v) => _audio.setVolume(v),
                    onToggleShuffle: () => _audio.toggleShuffle(),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  Widget _buildPageContent(DesktopThemePalette palette) {
    if (_settingsOpen) {
      return Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Settings',
              style: DesktopTypography.displayLarge(palette.foreground).copyWith(
                fontSize: 34.0,
                fontWeight: FontWeight.w800,
                letterSpacing: -0.5,
              ),
            ),
            const SizedBox(height: 24.0),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(
                'Theme Mode',
                style: DesktopTypography.bodyLarge(palette.foreground),
              ),
              subtitle: Text(
                widget.themeController.isDark ? 'Dark Mode' : 'Light Mode',
                style: DesktopTypography.bodySmall(palette.secondary),
              ),
              trailing: IconButton(
                icon: Icon(
                  widget.themeController.isDark
                      ? Icons.light_mode_rounded
                      : Icons.dark_mode_rounded,
                  color: palette.foreground,
                ),
                onPressed: () {
                  widget.themeController.setMode(
                    widget.themeController.isDark
                        ? DesktopThemeMode.light
                        : DesktopThemeMode.dark,
                  );
                },
              ),
            ),
          ],
        ),
      );
    }

    switch (_destination) {
      case DesktopDestination.listenNow:
        return DesktopNewPage(
          palette: palette,
          onSongSelected: (song) {
            _audio.playSong(song);
          },
        );
      default:
        return Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _destination.label,
                style: DesktopTypography.displayLarge(palette.foreground).copyWith(
                  fontSize: 34.0,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -0.5,
                ),
              ),
              const SizedBox(height: 32.0),
              Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.album_rounded,
                      size: 64.0,
                      color: palette.secondary.withValues(alpha: 0.4),
                    ),
                    const SizedBox(height: 16.0),
                    Text(
                      '${_destination.label} section',
                      style: DesktopTypography.headlineMedium(palette.foreground),
                    ),
                  ],
                ),
              ),
            ],
          ),
        );
    }
  }
}
