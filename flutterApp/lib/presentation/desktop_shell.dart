import 'package:flutter/material.dart';
import 'desktop_mini_player.dart';
import 'desktop_sidebar.dart';
import 'desktop_theme.dart';
import 'sidebar_policy.dart';
import '../domain/song.dart';

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
  bool _userSidebarCollapsed = false;
  bool _sidebarDrawerOpen = false;
  DesktopDestination _destination = DesktopDestination.listenNow;
  bool _settingsOpen = false;

  // Mock player state for initial demonstration
  final Song? _currentSong = const Song(
    videoId: 'sample',
    title: 'Welcome to BitChord',
    artist: 'Native Desktop Revived',
    thumbnailUrl: null,
    durationSeconds: 180,
  );
  bool _isPlaying = false;
  final bool _isLoading = false;
  final double _progress = 0.35;
  double _volume = 0.8;
  bool _shuffle = false;
  DesktopRepeatMode _repeatMode = DesktopRepeatMode.off;
  bool _lyricsOpen = false;
  bool _queueOpen = false;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.themeController,
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

                      // Main Content column
                      Expanded(
                        child: Column(
                          children: [
                            // Top Navigation / Window Title bar
                            _buildTopBar(palette),

                            // Page Content with bottom padding of 132.dp for dock clearance
                            Expanded(
                              child: Padding(
                                padding: const EdgeInsets.only(bottom: 132.0),
                                child: _buildPageContent(palette),
                              ),
                            ),
                          ],
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
                      song: _currentSong,
                      isPlaying: _isPlaying,
                      isLoading: _isLoading,
                      previousEnabled: true,
                      progress: _progress,
                      volume: _volume,
                      shuffle: _shuffle,
                      repeatMode: _repeatMode,
                      lyricsOpen: _lyricsOpen,
                      queueOpen: _queueOpen,
                      palette: palette,
                      onExpand: () {
                        // Open immersive player sheet
                      },
                      onPlayPause: () {
                        setState(() => _isPlaying = !_isPlaying);
                      },
                      onPrevious: () {},
                      onNext: () {},
                      onShuffleChange: (s) => setState(() => _shuffle = s),
                      onRepeatModeChange: (r) => setState(() => _repeatMode = r),
                      onVolumeChange: (v) => setState(() => _volume = v),
                      onOpenLyrics: () => setState(() => _lyricsOpen = !_lyricsOpen),
                      onOpenQueue: () => setState(() => _queueOpen = !_queueOpen),
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  Widget _buildTopBar(DesktopThemePalette palette) {
    return Container(
      height: 48.0,
      padding: const EdgeInsets.symmetric(horizontal: 20.0),
      decoration: BoxDecoration(
        color: palette.chromeSurface,
        border: Border(
          bottom: BorderSide(
            color: palette.divider,
            width: 0.5,
          ),
        ),
      ),
      child: Row(
        children: [
          Text(
            _settingsOpen ? 'Settings' : _destination.label,
            style: TextStyle(
              fontFamily: 'SFProDisplay',
              fontSize: 16.0,
              fontWeight: FontWeight.w600,
              color: palette.foreground,
            ),
          ),
          const Spacer(),
          // Theme mode toggle icon
          IconButton(
            iconSize: 18.0,
            tooltip: 'Toggle Dark / Light Mode',
            icon: Icon(
              widget.themeController.isDark
                  ? Icons.light_mode_rounded
                  : Icons.dark_mode_rounded,
              color: palette.secondary,
            ),
            onPressed: () {
              widget.themeController.setMode(
                widget.themeController.isDark
                    ? DesktopThemeMode.light
                    : DesktopThemeMode.dark,
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _buildPageContent(DesktopThemePalette palette) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.album_rounded,
            size: 64.0,
            color: palette.secondary.withValues(alpha: 0.4),
          ),
          const SizedBox(height: 16.0),
          Text(
            _settingsOpen ? 'Settings Page' : '${_destination.label} Feed',
            style: TextStyle(
              fontFamily: 'SFProDisplay',
              fontSize: 22.0,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.25,
              color: palette.foreground,
            ),
          ),
          const SizedBox(height: 8.0),
          Text(
            'Direct 1:1 Code Porting from BitChord Desktop 1.9.2',
            style: TextStyle(
              fontFamily: 'SFProDisplay',
              fontSize: 14.0,
              fontWeight: FontWeight.w400,
              color: palette.secondary,
            ),
          ),
        ],
      ),
    );
  }
}
