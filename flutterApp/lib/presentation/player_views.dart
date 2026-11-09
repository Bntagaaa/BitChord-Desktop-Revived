import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:media_kit_video/media_kit_video.dart';

import '../application/app_controller.dart';
import '../domain/models.dart';
import '../playback/playback_controller.dart';
import 'desktop_theme.dart';
import 'menus.dart';
import 'settings_page.dart';
import 'smooth_lyrics.dart';
import 'widgets.dart';

enum PlayerPanel { lyrics, queue, details }

class PlayerDock extends StatelessWidget {
  const PlayerDock({
    super.key,
    required this.app,
    required this.onExpand,
    required this.onPanel,
    this.panel,
  });
  final AppController app;
  final VoidCallback onExpand;
  final ValueChanged<PlayerPanel> onPanel;
  final PlayerPanel? panel;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app.playback,
    builder: (context, _) {
      final player = app.playback;
      final song = player.currentSong;
      final p = DesktopPalette.of(context);
      return ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: GlassSurface(
          blur: !app.setting('reduce_dynamic_blur', false),
          radius: 16,
          color: Theme.of(context).brightness == Brightness.dark
              ? const Color(0xB324242A)
              : const Color(0xE8E7EBF1),
          child: SizedBox(
            height: 58,
            child: LayoutBuilder(
              builder: (context, constraints) {
                final wide = constraints.maxWidth >= 650;
                final medium = constraints.maxWidth >= 480;
                return Stack(
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 8,
                      ),
                      child: Row(
                        children: [
                          if (medium)
                            ToolButton(
                              icon: Icons.shuffle,
                              asset:
                                  'assets/icons/player_transport_shuffle.svg',
                              label: 'Shuffle',
                              size: 30,
                              selected: player.shuffle,
                              onPressed: () =>
                                  player.setShuffle(!player.shuffle),
                            ),
                          TransportButton(
                            'previous',
                            'Previous',
                            player.currentSong == null ? null : player.previous,
                            size: 32,
                            iconSize: 19,
                          ),
                          player.buffering
                              ? const SizedBox(
                                  width: 36,
                                  height: 36,
                                  child: Padding(
                                    padding: EdgeInsets.all(9),
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                    ),
                                  ),
                                )
                              : TransportButton(
                                  player.playing ? 'pause' : 'play',
                                  player.playing ? 'Pause' : 'Play',
                                  song == null ? null : player.playPause,
                                  size: 36,
                                  iconSize: 22,
                                ),
                          TransportButton(
                            'next',
                            'Next',
                            song == null ? null : player.next,
                            size: 32,
                            iconSize: 19,
                          ),
                          if (wide)
                            ToolButton(
                              icon: player.repeat == PlaybackRepeat.one
                                  ? Icons.repeat_one
                                  : Icons.repeat,
                              asset: player.repeat == PlaybackRepeat.one
                                  ? 'assets/icons/player_transport_repeat_one.svg'
                                  : 'assets/icons/player_transport_repeat.svg',
                              label: 'Repeat: ${player.repeat.name}',
                              size: 30,
                              selected: player.repeat != PlaybackRepeat.off,
                              onPressed: () => player.setRepeat(
                                PlaybackRepeat.values[(player.repeat.index +
                                        1) %
                                    PlaybackRepeat.values.length],
                              ),
                            ),
                          const SizedBox(width: 9),
                          Expanded(
                            child: song == null
                                ? const SizedBox.shrink()
                                : GestureDetector(
                                    behavior: HitTestBehavior.opaque,
                                    onTap: onExpand,
                                    onSecondaryTapDown: (_) =>
                                        showSongMenu(context, app, song),
                                    child: Row(
                                      children: [
                                        Artwork(
                                          song.thumbnailUrl,
                                          size: 42,
                                          radius: 7,
                                        ),
                                        const SizedBox(width: 10),
                                        Expanded(
                                          child: Column(
                                            mainAxisAlignment:
                                                MainAxisAlignment.center,
                                            crossAxisAlignment:
                                                CrossAxisAlignment.start,
                                            children: [
                                              Text(
                                                song.title,
                                                maxLines: 1,
                                                overflow: TextOverflow.ellipsis,
                                                style: const TextStyle(
                                                  fontSize: 13,
                                                  fontWeight: FontWeight.w600,
                                                ),
                                              ),
                                              Text(
                                                song.artist,
                                                maxLines: 1,
                                                overflow: TextOverflow.ellipsis,
                                                style: TextStyle(
                                                  fontSize: 11,
                                                  color: p.secondary,
                                                ),
                                              ),
                                            ],
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                          ),
                          const SizedBox(width: 5),
                          ToolButton(
                            icon: Icons.lyrics_outlined,
                            asset: 'assets/icons/player_transport_lyrics.svg',
                            label: 'Lyrics',
                            size: 30,
                            selected: panel == PlayerPanel.lyrics,
                            onPressed: () => onPanel(PlayerPanel.lyrics),
                          ),
                          if (wide)
                            ToolButton(
                              icon: Icons.picture_in_picture_alt,
                              label: 'Picture in picture',
                              size: 30,
                              onPressed: song == null ? null : app.openPiP,
                            ),
                          ToolButton(
                            icon: Icons.queue_music,
                            asset: 'assets/icons/player_transport_up_next.svg',
                            label: 'Queue',
                            size: 30,
                            selected: panel == PlayerPanel.queue,
                            onPressed: () => onPanel(PlayerPanel.queue),
                          ),
                          if (wide)
                            ToolButton(
                              icon: Icons.info_outline,
                              label: 'Now playing details',
                              size: 30,
                              selected: panel == PlayerPanel.details,
                              onPressed: () => onPanel(PlayerPanel.details),
                            ),
                          if (wide)
                            ToolButton(
                              icon: Icons.headphones,
                              label: 'Audio output',
                              size: 30,
                              onPressed: () => showAudioOutputs(context, app),
                            ),
                          if (medium && !app.setting('hide_volume_bar', false))
                            DesktopThinSlider(
                              value: player.volume.clamp(0, 100) / 100.0,
                              width: wide ? 72 : 54,
                              idleHeight: 4,
                              activeHeight: 7,
                              onChanged: (v) => player.setVolume(v * 100),
                            ),
                        ],
                      ),
                    ),
                    if (song == null)
                      Positioned.fill(
                        child: IgnorePointer(
                          child: Center(
                            child: SvgPicture.asset(
                              'assets/icons/no-music.svg',
                              width: 48,
                              height: 48,
                            ),
                          ),
                        ),
                      ),
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 0,
                      child: SizedBox(
                        height: 2,
                        child: CustomPaint(
                          painter: _DockProgressPainter(
                            progress: player.duration.inMilliseconds <= 0
                                ? 0.0
                                : (player.position.inMilliseconds /
                                        player.duration.inMilliseconds)
                                    .clamp(0.0, 1.0),
                            trackColor: p.foreground.withOpacity(0.12),
                            fillColor: p.accent.withOpacity(0.92),
                          ),
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      );
    },
  );
}

class _DockProgressPainter extends CustomPainter {
  const _DockProgressPainter({
    required this.progress,
    required this.trackColor,
    required this.fillColor,
  });

  final double progress;
  final Color trackColor;
  final Color fillColor;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(
      Rect.fromLTWH(0, 0, size.width, size.height),
      Paint()..color = trackColor,
    );
    if (progress > 0) {
      canvas.drawRect(
        Rect.fromLTWH(0, 0, size.width * progress, size.height),
        Paint()..color = fillColor,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _DockProgressPainter old) =>
      old.progress != progress ||
      old.trackColor != trackColor ||
      old.fillColor != fillColor;
}

class TransportButton extends StatelessWidget {
  const TransportButton(
    this.name,
    this.label,
    this.action, {
    super.key,
    this.size = 40,
    this.iconSize = 24,
    this.color,
  });
  final String name, label;
  final VoidCallback? action;
  final double size, iconSize;
  final Color? color;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: size,
    height: size,
    child: IconButton(
      tooltip: label,
      onPressed: action,
      padding: EdgeInsets.zero,
      icon: SvgPicture.asset(
        'assets/icons/player_transport_$name.svg',
        width: iconSize,
        height: iconSize,
        colorFilter: ColorFilter.mode(
          action == null
              ? DesktopPalette.of(context).secondary.withAlpha(90)
              : color ?? DesktopPalette.of(context).foreground,
          BlendMode.srcIn,
        ),
      ),
    ),
  );
}

class PlayerSidePanel extends StatelessWidget {
  const PlayerSidePanel({
    super.key,
    required this.app,
    required this.panel,
    required this.onClose,
  });
  final AppController app;
  final PlayerPanel panel;
  final VoidCallback onClose;
  @override
  Widget build(BuildContext context) {
    final title = switch (panel) {
      PlayerPanel.lyrics => 'Lyrics',
      PlayerPanel.queue => 'Queue',
      PlayerPanel.details => 'Now Playing',
    };
    final p = DesktopPalette.of(context);
    return GlassSurface(
      radius: 0,
      blur: !app.setting('reduce_dynamic_blur', false),
      color: p.lyricsSurface.withAlpha(225),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 12, 10, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: p.foreground,
                    ),
                  ),
                ),
                ToolButton(
                  icon: Icons.close,
                  label: 'Close $title',
                  onPressed: onClose,
                  size: 28,
                ),
              ],
            ),
          ),
          if (panel == PlayerPanel.lyrics) _LyricsTools(app: app),
          Expanded(
            child: switch (panel) {
              PlayerPanel.lyrics => LyricsView(app: app),
              PlayerPanel.queue => QueueView(app: app),
              PlayerPanel.details => _SongDetails(app: app),
            },
          ),
        ],
      ),
    );
  }
}

class _LyricsTools extends StatelessWidget {
  const _LyricsTools({required this.app, this.white = false});
  final AppController app;
  final bool white;
  @override
  Widget build(BuildContext context) {
    if (!app.setting('synced_lyrics', true)) return const SizedBox.shrink();
    final enabled = app.lyrics != null && !app.lyricsTransforming;
    final colour = white ? Colors.white70 : null;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (app.lyricsTransforming)
              const SizedBox(
                width: 32,
                height: 32,
                child: Padding(
                  padding: EdgeInsets.all(9),
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              )
            else
              ToolButton(
                icon: Icons.translate_rounded,
                label: 'Translate lyrics',
                color: colour,
                onPressed: enabled ? () => app.translateLyrics() : null,
              ),
            ToolButton(
              icon: Icons.public,
              label: 'Romanize lyrics',
              color: colour,
              onPressed: enabled
                  ? () => app.translateLyrics(romanize: true)
                  : null,
            ),
            ToolButton(
              icon: Icons.restart_alt,
              label: 'Original lyrics',
              color: colour,
              onPressed: enabled ? app.resetLyrics : null,
            ),
            ToolButton(
              icon: Icons.tune,
              label: 'Lyrics sources',
              color: colour,
              onPressed: () => showLyricsSources(context, app),
            ),
          ],
        ),
        if (app.lyricsError != null && app.lyrics != null)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            child: Text(
              app.lyricsError!,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 11,
                color: white
                    ? Colors.white70
                    : Theme.of(context).colorScheme.error,
              ),
            ),
          ),
      ],
    );
  }
}

class QueueView extends StatelessWidget {
  const QueueView({super.key, required this.app});
  final AppController app;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app.playback,
    builder: (context, _) {
      final player = app.playback;
      final p = DesktopPalette.of(context);
      if (player.queue.isEmpty) {
        return const EmptyState(
          title: 'Your queue is empty',
          message: 'Play a song or add music from a song menu.',
          icon: Icons.queue_music,
        );
      }
      return Column(
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 18),
            child: Row(
              children: [
                const Expanded(
                  child: Text('Autoplay', style: TextStyle(fontSize: 12)),
                ),
                Switch.adaptive(
                  value: player.autoplay,
                  onChanged: (value) {
                    player.setAutoplay(value);
                    app.setSetting('autoplay', value);
                  },
                ),
              ],
            ),
          ),
          Expanded(
            child: ReorderableListView.builder(
              buildDefaultDragHandles: false,
              padding: const EdgeInsets.fromLTRB(8, 0, 8, 90),
              itemCount: player.queue.length,
              onReorderItem: player.moveQueue,
              itemBuilder: (context, index) {
                final song = player.queue[index];
                final active = index == player.currentIndex;
                return ReorderableDragStartListener(
                  key: ValueKey(song.queueEntryId ?? '${song.videoId}:$index'),
                  index: index,
                  child: HoverTile(
                    selected: active,
                    onTap: () => player.playAt(index),
                    onSecondaryTapDown: (_) =>
                        showSongMenu(context, app, song, queueIndex: index),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 7,
                    ),
                    child: Row(
                      children: [
                        Artwork(song.thumbnailUrl, size: 40, radius: 5),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              if (active)
                                Text(
                                  'NOW PLAYING',
                                  style: TextStyle(
                                    color: p.navAccent,
                                    fontSize: 9,
                                    letterSpacing: .6,
                                  ),
                                ),
                              Text(
                                song.title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w600,
                                  color: active ? p.navAccent : p.foreground,
                                ),
                              ),
                              Text(
                                song.artist,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  color: p.secondary,
                                  fontSize: 10,
                                ),
                              ),
                              if (song.fromAutoplay)
                                Text(
                                  'Autoplay',
                                  style: TextStyle(
                                    color: p.secondary,
                                    fontSize: 9,
                                  ),
                                ),
                            ],
                          ),
                        ),
                        ToolButton(
                          icon: Icons.close,
                          label: 'Remove from queue',
                          size: 26,
                          iconSize: 14,
                          onPressed: () => player.removeAt(index),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      );
    },
  );
}

class LyricsView extends StatelessWidget {
  const LyricsView({super.key, required this.app, this.immersive = false});
  final AppController app;
  final bool immersive;

  @override
  Widget build(BuildContext context) {
    return SmoothLyricsPanel(app: app, immersive: immersive, centerFocus: true);
  }
}

class NowPlayingView extends StatefulWidget {
  const NowPlayingView({super.key, required this.app, required this.onClose});
  final AppController app;
  final VoidCallback onClose;
  @override
  State<NowPlayingView> createState() => NowPlayingViewState();
}

class NowPlayingViewState extends State<NowPlayingView> {
  bool lyrics = false, queue = false;
  void toggleLyrics() => setState(() {
    lyrics = !lyrics;
    if (lyrics) queue = false;
  });
  void toggleQueue() => setState(() {
    queue = !queue;
    if (queue) lyrics = false;
  });
  AppController get app => widget.app;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app.playback,
    builder: (context, _) {
      final player = app.playback;
      final song = player.currentSong;
      if (song == null) {
        return ColoredBox(
          color: Colors.black,
          child: Center(
            child: TextButton(
              onPressed: widget.onClose,
              child: const Text('Back to music'),
            ),
          ),
        );
      }
      final localTheme = Theme.of(context).copyWith(
        brightness: Brightness.dark,
        iconTheme: const IconThemeData(color: Colors.white),
        colorScheme: Theme.of(context).colorScheme.copyWith(
          brightness: Brightness.dark,
          onSurface: Colors.white,
          primary: Colors.white,
          onPrimary: Colors.black,
        ),
        extensions: const <ThemeExtension<dynamic>>[DesktopPalette.dark],
      );
      return Theme(
        data: localTheme,
        child: Builder(
          builder: (context) => Material(
            color: Colors.black,
            child: Stack(
              fit: StackFit.expand,
              children: [
                _ArtworkBackdrop(
                  url: song.thumbnailUrl,
                  simple: app.setting('player_theme', 'DEFAULT') == 'SIMPLE',
                  legacy: app.setting('player_theme', 'DEFAULT') == 'LEGACY',
                  fullBleed: app.setting('full_screen_cover_art', false),
                  reducedBlur: app.setting('reduce_dynamic_blur', false),
                ),
                SafeArea(
                  child: Column(
                    children: [
                      Padding(
                        padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
                        child: Row(
                          children: [
                            ToolButton(
                              icon: Icons.keyboard_arrow_down,
                              label: 'Minimize player',
                              onPressed: widget.onClose,
                              color: Colors.white,
                              size: 32,
                              iconSize: 20,
                            ),
                            const Spacer(),
                            GestureDetector(
                              onTap: widget.onClose,
                              child: SizedBox(
                                width: 68,
                                height: 24,
                                child: Center(
                                  child: Container(
                                    width: 38,
                                    height: 5,
                                    decoration: BoxDecoration(
                                      color: Colors.white70,
                                      borderRadius: BorderRadius.circular(3),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                            const Spacer(),
                            ToolButton(
                              icon: Icons.picture_in_picture_alt,
                              label: 'Picture in picture',
                              onPressed: app.openPiP,
                              color: Colors.white,
                              size: 32,
                              iconSize: 20,
                            ),
                            ToolButton(
                              icon: Icons.fullscreen,
                              label: 'Toggle fullscreen',
                              onPressed: app.toggleFullscreen,
                              color: Colors.white,
                              size: 32,
                              iconSize: 20,
                            ),
                          ],
                        ),
                      ),
                      Expanded(
                        child: LayoutBuilder(
                          builder: (context, constraints) {
                            final compact = constraints.maxWidth < 700;
                            final shortWindow = constraints.maxHeight < 440;
                            final gutter = shortWindow ? 20.0 : 30.0;
                            final width = math.min(
                              1100.0,
                              math.max(0.0, constraints.maxWidth - 12),
                            );
                            final art = compact
                                ? math.max(
                                    0.0,
                                    math.min(
                                      constraints.maxWidth - 48,
                                      math.min(
                                        270.0,
                                        constraints.maxHeight * .38,
                                      ),
                                    ),
                                  )
                                : math.max(
                                    0.0,
                                    math.min(
                                      width / 2 - gutter * 2,
                                      constraints.maxHeight -
                                          (shortWindow ? 102 : 128),
                                    ),
                                  );
                            final cover =
                                player.videoEnabled &&
                                    player.videoController != null
                                ? ClipRRect(
                                    borderRadius: BorderRadius.circular(10),
                                    child: SizedBox(
                                      width: art,
                                      height: art * 9 / 16,
                                      child: Video(
                                        controller: player.videoController!,
                                        controls: NoVideoControls,
                                      ),
                                    ),
                                  )
                                : DecoratedBox(
                                    decoration: const BoxDecoration(
                                      boxShadow: [
                                        BoxShadow(
                                          color: Colors.black45,
                                          blurRadius: 28,
                                          offset: Offset(0, 12),
                                        ),
                                      ],
                                    ),
                                    child: Artwork(
                                      song.thumbnailUrl,
                                      size: art,
                                      radius: 10,
                                    ),
                                  );
                            final pane = queue
                                ? 'queue'
                                : lyrics
                                ? 'lyrics'
                                : 'main';
                            final Widget content;
                            if (queue) {
                              content = QueueView(app: app);
                            } else if (lyrics) {
                              content = Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  _trackHeader(context, song),
                                  const SizedBox(height: 12),
                                  Expanded(
                                    child: LyricsView(
                                      app: app,
                                      immersive: true,
                                    ),
                                  ),
                                  _LyricsTools(app: app, white: true),
                                ],
                              );
                            } else {
                              content = Center(
                                child: SingleChildScrollView(
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      _trackHeader(context, song),
                                      const SizedBox(height: 12),
                                      _controls(
                                        context,
                                        song,
                                        showMetadata: false,
                                        compact: shortWindow,
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            }
                            final right = AnimatedSwitcher(
                              duration: app.setting('reduce_animation', false)
                                  ? Duration.zero
                                  : const Duration(milliseconds: 180),
                              switchInCurve: Curves.easeOutCubic,
                              switchOutCurve: Curves.easeInCubic,
                              layoutBuilder: (current, previous) => Stack(
                                fit: StackFit.expand,
                                children: [...previous, ?current],
                              ),
                              child: KeyedSubtree(
                                key: ValueKey(pane),
                                child: content,
                              ),
                            );
                            if (compact) {
                              return Center(
                                child: SizedBox(
                                  width: math.min(width, 560),
                                  child: Column(
                                    children: [
                                      Padding(
                                        padding: const EdgeInsets.only(top: 10),
                                        child: cover,
                                      ),
                                      const SizedBox(height: 12),
                                      _playerActions(
                                        context,
                                        song,
                                        compact: true,
                                      ),
                                      const SizedBox(height: 8),
                                      Expanded(
                                        child: Padding(
                                          padding: const EdgeInsets.symmetric(
                                            horizontal: 20,
                                          ),
                                          child: right,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            }
                            // Original Kotlin landscape player keeps the sleeve
                            // and action row in an equal-width left column,
                            // while lyrics and queue replace only the right pane.
                            return Center(
                              child: SizedBox(
                                width: width,
                                child: Row(
                                  crossAxisAlignment:
                                      CrossAxisAlignment.stretch,
                                  children: [
                                    Expanded(
                                      child: Padding(
                                        padding: EdgeInsets.symmetric(
                                          horizontal: gutter,
                                        ),
                                        child: Column(
                                          children: [
                                            Expanded(
                                              child: Center(child: cover),
                                            ),
                                            _playerActions(context, song),
                                            SizedBox(
                                              height: shortWindow ? 8 : 20,
                                            ),
                                          ],
                                        ),
                                      ),
                                    ),
                                    Expanded(
                                      child: Padding(
                                        padding: EdgeInsets.fromLTRB(
                                          gutter,
                                          12,
                                          gutter,
                                          shortWindow ? 8 : 20,
                                        ),
                                        child: right,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );

  Widget _trackHeader(BuildContext context, Song song) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    mainAxisSize: MainAxisSize.min,
    children: [
      Text(
        'Playing from ${song.playbackSource ?? 'Queue'}',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          color: Colors.white70,
          fontSize: 11,
          fontWeight: FontWeight.w500,
        ),
      ),
      const SizedBox(height: 9),
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Metadata changes immediately, matching the desktop removal of sliding/fading titles.
                Text(
                  song.title,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 25,
                    fontWeight: FontWeight.w700,
                    height: 1.15,
                  ),
                ),
                const SizedBox(height: 6),
                GestureDetector(
                  onTap: song.artistId == null
                      ? null
                      : () {
                          widget.onClose();
                          app.openBrowse(
                            BrowseItem(
                              browseId: song.artistId!,
                              title: song.artist,
                              type: BrowseType.artist,
                            ),
                          );
                        },
                  child: Text(
                    song.artist,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white70, fontSize: 20),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          ToolButton(
            icon: app.isLiked(song)
                ? Icons.star_rounded
                : Icons.star_outline_rounded,
            asset: app.isLiked(song)
                ? 'assets/icons/bit-star-filled.svg'
                : 'assets/icons/bit-star.svg',
            label: 'Like',
            onPressed: () => app.toggleLike(song),
            selected: app.isLiked(song),
            color: Colors.white,
            size: 36,
          ),
          ToolButton(
            icon: Icons.more_horiz,
            label: 'Song actions',
            onPressed: () => showSongMenu(context, app, song),
            color: Colors.white,
            size: 36,
          ),
        ],
      ),
    ],
  );

  Widget _playerActions(
    BuildContext context,
    Song song, {
    bool compact = false,
  }) {
    final player = app.playback;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            ToolButton(
              icon: Icons.lyrics_outlined,
              asset: 'assets/icons/bit-lyrics-quote.svg',
              label: lyrics ? 'Close lyrics' : 'Open lyrics',
              onPressed: toggleLyrics,
              selected: lyrics,
              color: Colors.white,
              size: compact ? 36 : 42,
              iconSize: 22,
            ),
            GlassSurface(
              blur: false,
              radius: 24,
              color: Colors.white.withAlpha(30),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: queue
                    ? [
                        ToolButton(
                          icon: Icons.shuffle,
                          asset: 'assets/icons/bit-shuffle.svg',
                          label: 'Shuffle',
                          selected: player.shuffle,
                          onPressed: () => player.setShuffle(!player.shuffle),
                          color: Colors.white,
                          size: 38,
                        ),
                        ToolButton(
                          icon: Icons.repeat,
                          asset: 'assets/icons/bit-repeat.svg',
                          label: 'Repeat',
                          selected: player.repeat != PlaybackRepeat.off,
                          onPressed: () => player.setRepeat(
                            PlaybackRepeat.values[(player.repeat.index + 1) %
                                PlaybackRepeat.values.length],
                          ),
                          color: Colors.white,
                          size: 38,
                        ),
                        ToolButton(
                          icon: Icons.all_inclusive,
                          label: 'Autoplay',
                          selected: player.autoplay,
                          onPressed: () {
                            player.setAutoplay(!player.autoplay);
                            app.setSetting('autoplay', player.autoplay);
                          },
                          color: Colors.white,
                          size: 38,
                        ),
                      ]
                    : [
                        if (!song.isLocal)
                          ToolButton(
                            icon: Icons.music_note,
                            label: 'Audio',
                            selected: !player.videoEnabled,
                            onPressed: () => player.setVideoEnabled(false),
                            color: Colors.white,
                            size: 38,
                          ),
                        if (!song.isLocal)
                          ToolButton(
                            icon: Icons.music_video_rounded,
                            label: 'Video',
                            selected: player.videoEnabled,
                            onPressed: () => player.setVideoEnabled(true),
                            color: Colors.white,
                            size: 38,
                          ),
                        ToolButton(
                          icon: Icons.headphones,
                          label: 'Audio output',
                          onPressed: () => showAudioOutputs(context, app),
                          color: Colors.white,
                          size: 38,
                        ),
                      ],
              ),
            ),
            ToolButton(
              icon: Icons.queue_music,
              asset: 'assets/icons/bit-queue.svg',
              label: 'Up next',
              onPressed: toggleQueue,
              selected: queue,
              color: Colors.white,
              size: compact ? 36 : 42,
              iconSize: 22,
            ),
          ],
        ),
        const SizedBox(height: 14),
        TextButton(
          onPressed: () => showAudioOutputs(context, app),
          child: const Text(
            'Audio output',
            style: TextStyle(
              fontSize: 11,
              color: Colors.white70,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      ],
    );
  }

  Widget _controls(
    BuildContext context,
    Song song, {
    bool showMetadata = true,
    bool compact = false,
  }) {
    final player = app.playback;
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (showMetadata) ...[
          _trackHeader(context, song),
          const SizedBox(height: 16),
        ],
        if (player.error != null)
          Row(
            children: [
              Expanded(
                child: Text(
                  player.error!,
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ),
              TextButton(
                onPressed: player.retry,
                child: const Text(
                  'Retry',
                  style: TextStyle(color: Colors.white),
                ),
              ),
            ],
          ),
        if (player.videoError != null)
          Row(
            children: [
              Expanded(
                child: Text(
                  player.videoError!,
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ),
              TextButton(
                onPressed: () => player.setVideoEnabled(false),
                child: const Text(
                  'Audio only',
                  style: TextStyle(color: Colors.white),
                ),
              ),
            ],
          ),
        if (player.streamFormat != null)
          Text(
            player.streamFormat!.summary,
            style: const TextStyle(color: Colors.white38, fontSize: 10),
          ),
        const SizedBox(height: 9),
        if (app.lyrics != null && app.lyrics!.lines.isNotEmpty)
          GestureDetector(
            onTap: () => setState(() {
              lyrics = true;
              queue = false;
            }),
            child: Padding(
              padding: const EdgeInsets.only(bottom: 7),
              child: Text(
                app
                    .lyrics!
                    .lines[math.max(
                      0,
                      app.lyrics!.activeIndex(player.position),
                    )]
                    .text,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Colors.white54, fontSize: 14),
              ),
            ),
          ),
        PlaybackTimeline(app: app, white: true),
        SizedBox(height: compact ? 6 : 20),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            ToolButton(
              icon: Icons.shuffle,
              asset: 'assets/icons/player_transport_shuffle.svg',
              label: 'Shuffle',
              selected: player.shuffle,
              onPressed: () => player.setShuffle(!player.shuffle),
              color: player.shuffle ? Colors.white : Colors.white38,
            ),
            TransportButton(
              'previous',
              'Previous',
              player.previous,
              size: 50,
              iconSize: 34,
              color: Colors.white,
            ),
            player.buffering
                ? const SizedBox(
                    width: 66,
                    height: 66,
                    child: Padding(
                      padding: EdgeInsets.all(20),
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    ),
                  )
                : TransportButton(
                    player.playing ? 'pause' : 'play',
                    player.playing ? 'Pause' : 'Play',
                    player.playPause,
                    size: 66,
                    iconSize: 46,
                    color: Colors.white,
                  ),
            TransportButton(
              'next',
              'Next',
              player.next,
              size: 50,
              iconSize: 34,
              color: Colors.white,
            ),
            ToolButton(
              icon: player.repeat == PlaybackRepeat.one
                  ? Icons.repeat_one
                  : Icons.repeat,
              asset: player.repeat == PlaybackRepeat.one
                  ? 'assets/icons/player_transport_repeat_one.svg'
                  : 'assets/icons/player_transport_repeat.svg',
              label: 'Repeat: ${player.repeat.name}',
              selected: player.repeat != PlaybackRepeat.off,
              onPressed: () => player.setRepeat(
                PlaybackRepeat.values[(player.repeat.index + 1) %
                    PlaybackRepeat.values.length],
              ),
              color: player.repeat == PlaybackRepeat.off
                  ? Colors.white38
                  : Colors.white,
            ),
          ],
        ),
        SizedBox(height: compact ? 4 : 22),
        if (!app.setting('hide_volume_bar', false))
          Row(
            children: [
              const Icon(Icons.volume_down, color: Colors.white38, size: 18),
              Expanded(
                child: Slider(
                  value: player.volume.clamp(0, 100),
                  max: 100,
                  onChanged: player.setVolume,
                  activeColor: Colors.white60,
                  inactiveColor: Colors.white12,
                ),
              ),
              const Icon(Icons.volume_up, color: Colors.white38, size: 18),
            ],
          ),
        if (showMetadata) const SizedBox(height: 8),
        if (showMetadata)
          Center(
            child: TextButton.icon(
              onPressed: () => showAudioOutputs(context, app),
              icon: const Icon(
                Icons.speaker_group_outlined,
                size: 15,
                color: Colors.white54,
              ),
              label: const Text(
                'Audio output',
                style: TextStyle(color: Colors.white54, fontSize: 11),
              ),
            ),
          ),
      ],
    );
  }
}

class PlaybackTimeline extends StatelessWidget {
  const PlaybackTimeline({super.key, required this.app, this.white = false});
  final AppController app;
  final bool white;
  @override
  Widget build(BuildContext context) {
    final player = app.playback;
    final duration = math.max(1, player.duration.inMilliseconds).toDouble();
    final colour = white
        ? Colors.white54
        : DesktopPalette.of(context).secondary;
    return Column(
      children: [
        SizedBox(
          height: 18,
          child: SliderTheme(
            data: SliderTheme.of(context).copyWith(
              trackShape: const RectangularSliderTrackShape(),
              activeTrackColor: white ? Colors.white : null,
              inactiveTrackColor: white ? Colors.white24 : null,
              thumbColor: white ? Colors.white : null,
            ),
            child: Slider(
              value: player.position.inMilliseconds.toDouble().clamp(
                0,
                duration,
              ),
              max: duration,
              onChanged: (value) =>
                  player.seek(Duration(milliseconds: value.round())),
            ),
          ),
        ),
        Row(
          children: [
            Text(
              formatDuration(player.position),
              style: TextStyle(fontSize: 10, color: colour),
            ),
            const Spacer(),
            Text(
              formatDuration(player.duration),
              style: TextStyle(fontSize: 10, color: colour),
            ),
          ],
        ),
      ],
    );
  }
}

class _ArtworkBackdrop extends StatelessWidget {
  const _ArtworkBackdrop({
    required this.url,
    required this.simple,
    required this.legacy,
    required this.fullBleed,
    required this.reducedBlur,
  });
  final String? url;
  final bool simple, legacy, fullBleed, reducedBlur;
  @override
  Widget build(BuildContext context) => RepaintBoundary(
    child: LayoutBuilder(
      builder: (context, constraints) => Stack(
        fit: StackFit.expand,
        children: [
          ClipRect(
            child: ImageFiltered(
              imageFilter: ImageFilter.blur(
                sigmaX: fullBleed
                    ? 0
                    : reducedBlur
                    ? 0
                    : 75,
                sigmaY: fullBleed
                    ? 0
                    : reducedBlur
                    ? 0
                    : 75,
              ),
              child: Transform.scale(
                scale: 1.14,
                child: Artwork(
                  url,
                  width: constraints.maxWidth,
                  height: constraints.maxHeight,
                  radius: 0,
                ),
              ),
            ),
          ),
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: legacy
                  ? RadialGradient(
                      center: const Alignment(-.3, -.5),
                      radius: 1.3,
                      colors: [
                        Colors.black.withAlpha(35),
                        Colors.black.withAlpha(190),
                      ],
                    )
                  : LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: simple
                          ? [
                              Colors.black.withAlpha(85),
                              Colors.black.withAlpha(135),
                            ]
                          : [
                              Colors.black.withAlpha(60),
                              Colors.black.withAlpha(145),
                              Colors.black,
                            ],
                      stops: simple ? null : const [0, .5, 1],
                    ),
            ),
          ),
        ],
      ),
    ),
  );
}

class _SongDetails extends StatelessWidget {
  const _SongDetails({required this.app});
  final AppController app;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app.playback,
    builder: (context, _) {
      final song = app.playback.currentSong;
      if (song == null) {
        return const EmptyState(
          title: 'Nothing playing',
          message: 'Choose a song to see its details.',
        );
      }
      return ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 100),
        children: [
          Artwork(song.thumbnailUrl, width: 300, height: 300),
          const SizedBox(height: 20),
          Text(song.title, style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 6),
          Text(
            song.artist,
            style: TextStyle(
              color: DesktopPalette.of(context).secondary,
              fontSize: 16,
            ),
          ),
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: () => app.toggleLike(song),
            icon: Icon(app.isLiked(song) ? Icons.star : Icons.star_outline),
            label: Text(app.isLiked(song) ? 'Liked' : 'Like'),
          ),
          const SizedBox(height: 15),
          for (final item in <String, String?>{
            'Album': song.albumName,
            'Duration': song.durationText,
            'Source': song.isLocal
                ? 'Local music'
                : song.playbackSource ?? 'YouTube Music',
            'Quality': app.playback.streamFormat?.summary ?? song.sourceQuality,
          }.entries)
            if (item.value != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 7),
                child: Text(
                  '${item.key}\n${item.value}',
                  style: const TextStyle(height: 1.5),
                ),
              ),
          const SizedBox(height: 16),
          TextButton(
            onPressed: () => showSongMenu(context, app, song),
            child: const Text('More actions'),
          ),
        ],
      );
    },
  );
}
