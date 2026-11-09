import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../application/app_controller.dart';
import '../domain/models.dart';
import '../playback/playback_controller.dart';
import 'catalog_pages.dart';
import 'desktop_theme.dart';
import 'desktop_shortcuts.dart';
import 'menus.dart';
import 'player_views.dart';
import 'settings_page.dart';
import 'widgets.dart';

class DesktopShell extends StatefulWidget {
  const DesktopShell({super.key, required this.controller});
  final AppController controller;
  @override
  State<DesktopShell> createState() => _DesktopShellState();
}

class _DesktopShellState extends State<DesktopShell>
    with SingleTickerProviderStateMixin {
  PlayerPanel? panel;
  final nowPlayingKey = GlobalKey<NowPlayingViewState>();
  bool nowPlaying = false, drawer = false;
  bool sheetMounted = false;
  late final AnimationController sheetMotion = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 360),
    reverseDuration: const Duration(milliseconds: 280),
  );
  AppController get app => widget.controller;

  void openNowPlaying() {
    setState(() {
      sheetMounted = true;
      nowPlaying = true;
    });
    if (app.setting('reduce_animation', false)) {
      sheetMotion.value = 1;
    } else {
      sheetMotion.forward();
    }
  }

  void closeNowPlaying() {
    if (!nowPlaying) return;
    setState(() => nowPlaying = false);
    if (app.setting('reduce_animation', false)) {
      sheetMotion.value = 0;
      setState(() => sheetMounted = false);
    } else {
      sheetMotion.reverse().then((_) {
        if (mounted && !nowPlaying) {
          setState(() => sheetMounted = false);
        }
      });
    }
  }

  @override
  void dispose() {
    sheetMotion.dispose();
    super.dispose();
  }

  void navigate(AppRoute route) {
    setState(() => drawer = false);
    app.navigate(route);
  }

  void togglePanel(PlayerPanel value) =>
      setState(() => panel = panel == value ? null : value);
  KeyEventResult handleKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) return KeyEventResult.ignored;
    final shortcut = desktopShortcuts
        .where((item) => item.matches(event))
        .firstOrNull;
    final focusContext = FocusManager.instance.primaryFocus?.context;
    final editing =
        focusContext?.widget is EditableText ||
        focusContext?.findAncestorWidgetOfExactType<EditableText>() != null;
    if (shortcut != null) {
      if (editing &&
          shortcut.action != DesktopShortcutAction.quickSearch &&
          shortcut.action != DesktopShortcutAction.help) {
        return KeyEventResult.ignored;
      }
      final player = app.playback;
      switch (shortcut.action) {
        case DesktopShortcutAction.quickSearch:
          showQuickSearch(context, app);
        case DesktopShortcutAction.help:
          showDesktopShortcuts(context);
        case DesktopShortcutAction.playPause:
          if (player.currentSong != null) player.playPause();
        case DesktopShortcutAction.previous:
          player.previous();
        case DesktopShortcutAction.next:
          player.next();
        case DesktopShortcutAction.seekBackward:
          if (player.currentSong != null) {
            player.seek(player.position - const Duration(seconds: 5));
          }
        case DesktopShortcutAction.seekForward:
          if (player.currentSong != null) {
            player.seek(player.position + const Duration(seconds: 5));
          }
        case DesktopShortcutAction.volumeUp:
          player.setVolume(player.volume + 5);
        case DesktopShortcutAction.volumeDown:
          player.setVolume(player.volume - 5);
        case DesktopShortcutAction.shuffle:
          player.setShuffle(!player.shuffle);
        case DesktopShortcutAction.repeat:
          player.setRepeat(
            PlaybackRepeat.values[(player.repeat.index + 1) %
                PlaybackRepeat.values.length],
          );
        case DesktopShortcutAction.home:
          closeNowPlaying();
          navigate(AppRoute.home);
        case DesktopShortcutAction.search:
          closeNowPlaying();
          navigate(AppRoute.search);
        case DesktopShortcutAction.lyrics:
          if (nowPlaying) {
            nowPlayingKey.currentState?.toggleLyrics();
          } else {
            togglePanel(PlayerPanel.lyrics);
          }
      }
      return KeyEventResult.handled;
    }
    if (event.logicalKey == LogicalKeyboardKey.escape &&
        (nowPlaying || panel != null || drawer)) {
      if (nowPlaying) closeNowPlaying();
      setState(() {
        panel = null;
        drawer = false;
      });
      return KeyEventResult.handled;
    }
    if (editing) return KeyEventResult.ignored;
    // Keep native media keys and the common Search / Back aliases in addition to the source table.
    if (event.logicalKey == LogicalKeyboardKey.mediaPlayPause) {
      app.playback.playPause();
      return KeyEventResult.handled;
    }
    if (event.logicalKey == LogicalKeyboardKey.mediaTrackNext) {
      app.playback.next();
      return KeyEventResult.handled;
    }
    if (event.logicalKey == LogicalKeyboardKey.mediaTrackPrevious) {
      app.playback.previous();
      return KeyEventResult.handled;
    }
    if ((HardwareKeyboard.instance.isControlPressed ||
            HardwareKeyboard.instance.isMetaPressed) &&
        event.logicalKey == LogicalKeyboardKey.keyF) {
      closeNowPlaying();
      navigate(AppRoute.search);
      return KeyEventResult.handled;
    }
    if (HardwareKeyboard.instance.isAltPressed &&
        event.logicalKey == LogicalKeyboardKey.arrowLeft) {
      app.goBack();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app,
    builder: (context, _) {
      final p = DesktopPalette.of(context);
      return Focus(
        autofocus: true,
        onKeyEvent: handleKey,
        child: Listener(
          onPointerDown: (event) => MenuPointer.position = event.position,
          child: Scaffold(
            backgroundColor: p.background,
            body: LayoutBuilder(
              builder: (context, constraints) {
                final narrow = constraints.maxWidth < 980;
                final collapsed =
                    narrow || app.setting('sidebar_collapsed', false);
                final sideWidth = collapsed ? 72.0 : 220.0;
                final panelWidth = math.min(
                  360.0,
                  constraints.maxWidth - sideWidth - 360,
                );
                final overlayPanel = panelWidth < 280;
                final topPlayer = app.setting(
                  'enable_old_top_music_player_style',
                  false,
                );
                final dock = PlayerDock(
                  app: app,
                  panel: panel,
                  onPanel: togglePanel,
                  onExpand: openNowPlaying,
                );
                return Stack(
                  children: [
                    Row(
                      children: [
                        AnimatedContainer(
                          duration: app.setting('reduce_animation', false)
                              ? Duration.zero
                              : const Duration(milliseconds: 180),
                          width: sideWidth,
                          child: LayoutBuilder(
                            builder: (context, sidebarConstraints) => _Sidebar(
                              app: app,
                              // Expanded rows need the expanded width, including
                              // while the container is animating out of its rail.
                              collapsed:
                                  collapsed ||
                                  sidebarConstraints.maxWidth < sideWidth,
                              onNavigate: navigate,
                              onToggle: () {
                                if (narrow) {
                                  setState(() => drawer = true);
                                } else {
                                  app.setSetting(
                                    'sidebar_collapsed',
                                    !collapsed,
                                  );
                                }
                              },
                            ),
                          ),
                        ),
                        Expanded(
                          child: Column(
                            children: [
                              if (topPlayer)
                                Padding(
                                  padding: const EdgeInsets.fromLTRB(
                                    16,
                                    8,
                                    16,
                                    0,
                                  ),
                                  child: dock,
                                ),
                              // Back and quick search remain available for mouse users on every route.
                              SizedBox(
                                height: 34,
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 14,
                                  ),
                                  child: Row(
                                    children: [
                                      ToolButton(
                                        icon: Icons.chevron_left,
                                        label: 'Back',
                                        onPressed: app.canGoBack
                                            ? app.goBack
                                            : null,
                                        size: 28,
                                      ),
                                      const Spacer(),
                                      ToolButton(
                                        icon: Icons.search,
                                        label: 'Quick Search (Ctrl+K)',
                                        onPressed: () =>
                                            showQuickSearch(context, app),
                                        size: 28,
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                              if (app.error != null)
                                _ErrorBanner(
                                  message: app.error!,
                                  onRetry: app.refreshCurrent,
                                  onDismiss: app.clearError,
                                ),
                              if (app.error == null && app.notice != null)
                                Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 24,
                                    vertical: 4,
                                  ),
                                  child: Row(
                                    children: [
                                      const Icon(Icons.info_outline, size: 16),
                                      const SizedBox(width: 8),
                                      Expanded(
                                        child: Text(
                                          app.notice!,
                                          style: const TextStyle(fontSize: 12),
                                        ),
                                      ),
                                      ToolButton(
                                        icon: Icons.close,
                                        label: 'Dismiss notice',
                                        onPressed: app.clearError,
                                        size: 28,
                                      ),
                                    ],
                                  ),
                                ),
                              Expanded(
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: RepaintBoundary(
                                        child: CatalogPages(app: app),
                                      ),
                                    ),
                                    if (panel != null && !overlayPanel)
                                      SizedBox(
                                        width: panelWidth,
                                        child: PlayerSidePanel(
                                          app: app,
                                          panel: panel!,
                                          onClose: () =>
                                              setState(() => panel = null),
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    if (!topPlayer)
                      Positioned(
                        left: sideWidth + 10,
                        right:
                            10 +
                            (panel != null && !overlayPanel ? panelWidth : 0),
                        bottom: 20,
                        child: Center(child: dock),
                      ),
                    if (panel != null && overlayPanel)
                      Positioned(
                        right: 0,
                        top: 0,
                        bottom: 0,
                        width: math.min(360, constraints.maxWidth - sideWidth),
                        child: PlayerSidePanel(
                          app: app,
                          panel: panel!,
                          onClose: () => setState(() => panel = null),
                        ),
                      ),
                    if (drawer) ...[
                      Positioned.fill(
                        child: GestureDetector(
                          onTap: () => setState(() => drawer = false),
                          behavior: HitTestBehavior.opaque,
                          child: const ColoredBox(color: Colors.transparent),
                        ),
                      ),
                      Positioned(
                        left: 0,
                        top: 0,
                        bottom: 0,
                        width: 220,
                        child: DecoratedBox(
                          decoration: const BoxDecoration(
                            boxShadow: [
                              BoxShadow(color: Colors.black26, blurRadius: 18),
                            ],
                          ),
                          child: _Sidebar(
                            app: app,
                            collapsed: false,
                            onNavigate: navigate,
                            onToggle: () => setState(() => drawer = false),
                          ),
                        ),
                      ),
                    ],
                    if (sheetMounted)
                      Positioned.fill(
                        child: AnimatedBuilder(
                          animation: sheetMotion,
                          builder: (context, child) => Transform.translate(
                            offset: Offset(
                              0,
                              constraints.maxHeight * (1 - sheetMotion.value),
                            ),
                            child: IgnorePointer(
                              ignoring: !nowPlaying,
                              child: child,
                            ),
                          ),
                          child: NowPlayingView(
                            key: nowPlayingKey,
                            app: app,
                            onClose: closeNowPlaying,
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

class _Sidebar extends StatelessWidget {
  const _Sidebar({
    required this.app,
    required this.collapsed,
    required this.onNavigate,
    required this.onToggle,
  });
  final AppController app;
  final bool collapsed;
  final ValueChanged<AppRoute> onNavigate;
  final VoidCallback onToggle;
  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    return DecoratedBox(
      decoration: BoxDecoration(
        color: p.sidebar,
        border: Border(right: BorderSide(color: p.edge, width: .5)),
      ),
      child: LayoutBuilder(
        builder: (context, constraints) => SingleChildScrollView(
          child: SizedBox(
            height: math.max(680, constraints.maxHeight),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 16, 12, 16),
              child: Column(
                children: [
                  SizedBox(
                    height: 44,
                    child: collapsed
                        ? Center(
                            child: IconButton(
                              tooltip: 'Expand sidebar',
                              onPressed: onToggle,
                              icon: SvgPicture.asset(
                                'assets/icons/bitchord_logo_original.svg',
                                width: 28,
                                height: 22,
                                colorFilter: ColorFilter.mode(
                                  p.foreground,
                                  BlendMode.srcIn,
                                ),
                              ),
                            ),
                          )
                        : Row(
                            children: [
                              const SizedBox(width: 8),
                              SvgPicture.asset(
                                'assets/icons/bitchord_logo_original.svg',
                                width: 28,
                                height: 18,
                                colorFilter: ColorFilter.mode(
                                  p.foreground,
                                  BlendMode.srcIn,
                                ),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: SvgPicture.asset(
                                  'assets/icons/wordmark.svg',
                                  width: 84,
                                  height: 18,
                                  alignment: Alignment.centerLeft,
                                  colorFilter: ColorFilter.mode(
                                    p.foreground,
                                    BlendMode.srcIn,
                                  ),
                                ),
                              ),
                              ToolButton(
                                icon: Icons.chevron_left,
                                label: 'Collapse sidebar',
                                onPressed: onToggle,
                                size: 26,
                              ),
                            ],
                          ),
                  ),
                  const SizedBox(height: 10),
                  item(
                    context,
                    'Home',
                    Icons.home_rounded,
                    AppRoute.home,
                    svg: 'nav-home',
                  ),
                  item(
                    context,
                    'New',
                    Icons.grid_view_rounded,
                    AppRoute.explore,
                    svg: 'nav-explore',
                  ),
                  item(
                    context,
                    'Search',
                    Icons.search,
                    AppRoute.search,
                    svg: 'nav-search',
                  ),
                  item(
                    context,
                    'Library',
                    Icons.library_music,
                    AppRoute.library,
                    svg: 'nav-library',
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 10),
                    child: Divider(),
                  ),
                  item(
                    context,
                    'History',
                    Icons.history_rounded,
                    AppRoute.history,
                    svg: 'bit-clock',
                  ),
                  item(
                    context,
                    'Downloads',
                    Icons.download_outlined,
                    AppRoute.downloads,
                    svg: 'bit-download',
                  ),
                  item(
                    context,
                    'Local Music',
                    Icons.library_music_outlined,
                    AppRoute.localMusic,
                    svg: 'bit-library',
                  ),
                  item(
                    context,
                    'Spotify',
                    Icons.album_outlined,
                    AppRoute.spotify,
                    spotify: true,
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 10),
                    child: Divider(),
                  ),
                  item(
                    context,
                    'Liked Music',
                    Icons.star_outline_rounded,
                    AppRoute.liked,
                  ),
                  if (!collapsed)
                    Expanded(
                      child: ListView(
                        padding: const EdgeInsets.only(top: 4, bottom: 8),
                        children: [
                          for (final playlist in app.localPlaylists)
                            _NavItem(
                              label: playlist.title,
                              icon: Icons.playlist_play,
                              collapsed: false,
                              selected:
                                  app.route == AppRoute.detail &&
                                  app.detailPage?.browseId ==
                                      'local:${playlist.id}',
                              onTap: () => app.openPlaylist(playlist),
                            ),
                          if (app.localPlaylists.isEmpty)
                            Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 9,
                                vertical: 10,
                              ),
                              child: Text(
                                'Your playlists will appear here',
                                style: TextStyle(
                                  color: p.secondary,
                                  fontSize: 11,
                                  height: 1.4,
                                ),
                              ),
                            ),
                          _NavItem(
                            label: 'New playlist',
                            icon: Icons.add,
                            collapsed: false,
                            selected: false,
                            onTap: () => newPlaylist(context, app),
                          ),
                        ],
                      ),
                    )
                  else ...[
                    _NavItem(
                      label: 'New playlist',
                      icon: Icons.playlist_add,
                      collapsed: true,
                      selected: false,
                      onTap: () => newPlaylist(context, app),
                    ),
                    const Spacer(),
                  ],
                  const Divider(),
                  const SizedBox(height: 8),
                  Tooltip(
                    message: app.account?.name ?? 'Account',
                    child: HoverTile(
                      onTap: () => showAccounts(context, app),
                      padding: EdgeInsets.symmetric(
                        horizontal: collapsed ? 4 : 2,
                        vertical: 6,
                      ),
                      child: Row(
                        mainAxisAlignment: collapsed
                            ? MainAxisAlignment.center
                            : MainAxisAlignment.start,
                        children: [
                          Artwork(
                            app.account?.thumbnailUrl,
                            size: 30,
                            radius: 15,
                          ),
                          if (!collapsed) ...[
                            const SizedBox(width: 9),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    app.account?.name ?? 'Sign in',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontSize: 12,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                  Text(
                                    app.signedIn
                                        ? 'Account'
                                        : 'Connect account',
                                    style: TextStyle(
                                      color: p.secondary,
                                      fontSize: 10,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 4),
                  item(
                    context,
                    'Settings',
                    Icons.settings_rounded,
                    AppRoute.settings,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget item(
    BuildContext context,
    String label,
    IconData icon,
    AppRoute route, {
    String? svg,
    bool spotify = false,
  }) => _NavItem(
    label: label,
    icon: icon,
    svg: svg,
    spotify: spotify,
    collapsed: collapsed,
    selected: app.route == route,
    onTap: () => onNavigate(route),
  );
}

class _NavItem extends StatelessWidget {
  const _NavItem({
    required this.label,
    required this.icon,
    required this.collapsed,
    required this.selected,
    required this.onTap,
    this.svg,
    this.spotify = false,
  });
  final String label;
  final IconData icon;
  final String? svg;
  final bool spotify;
  final bool collapsed, selected;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    final color = selected ? p.navAccent : p.secondary;
    final glyph = spotify
        ? Image.asset(
            'assets/branding/spotify_logo.webp',
            width: collapsed ? 20 : 18,
            height: collapsed ? 20 : 18,
            color: color,
            colorBlendMode: BlendMode.srcIn,
          )
        : svg == null
        ? Icon(icon, size: collapsed ? 20 : 18, color: color)
        : SvgPicture.asset(
            'assets/icons/$svg.svg',
            width: collapsed ? 20 : 18,
            height: collapsed ? 20 : 18,
            colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
          );
    final tile = HoverTile(
      onTap: onTap,
      selected: selected,
      radius: 7,
      child: SizedBox(
        height: collapsed ? 42 : 34,
        child: Row(
          children: [
            Container(
              width: 3,
              height: 18,
              margin: EdgeInsets.only(left: collapsed ? 0 : 9),
              decoration: BoxDecoration(
                color: selected ? p.navAccent : Colors.transparent,
                borderRadius: BorderRadius.circular(3),
              ),
            ),
            if (collapsed)
              Expanded(child: Center(child: glyph))
            else ...[
              const SizedBox(width: 6),
              glyph,
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: selected ? p.foreground : p.secondary,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  ),
                ),
              ),
              const SizedBox(width: 7),
            ],
          ],
        ),
      ),
    );
    return collapsed ? Tooltip(message: label, child: tile) : tile;
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({
    required this.message,
    required this.onRetry,
    required this.onDismiss,
  });
  final String message;
  final VoidCallback onRetry, onDismiss;
  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.symmetric(horizontal: 24, vertical: 4),
    padding: const EdgeInsets.fromLTRB(12, 5, 4, 5),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.errorContainer.withAlpha(80),
      borderRadius: BorderRadius.circular(9),
    ),
    child: Row(
      children: [
        const Icon(Icons.info_outline, size: 17),
        const SizedBox(width: 10),
        Expanded(
          child: SelectableText(
            message,
            maxLines: 3,
            style: const TextStyle(fontSize: 12),
          ),
        ),
        TextButton(onPressed: onRetry, child: const Text('Retry')),
        ToolButton(
          icon: Icons.close,
          label: 'Dismiss error',
          onPressed: onDismiss,
          size: 28,
        ),
      ],
    ),
  );
}

Future<void> showQuickSearch(BuildContext context, AppController app) =>
    showGeneralDialog<void>(
      context: context,
      barrierDismissible: true,
      barrierLabel: 'Close Quick Search',
      barrierColor: Colors.transparent,
      transitionDuration: Duration.zero,
      pageBuilder: (context, _, _) => Align(
        alignment: const Alignment(0, -.65),
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: SizedBox(width: 530, child: _QuickSearch(app: app)),
        ),
      ),
    );

class _QuickSearch extends StatefulWidget {
  const _QuickSearch({required this.app});
  final AppController app;
  @override
  State<_QuickSearch> createState() => _QuickSearchState();
}

class _QuickSearchState extends State<_QuickSearch> {
  final input = TextEditingController();
  final focus = FocusNode();
  Timer? debounce;
  SearchPage? results;
  String? error;
  bool loading = false;
  int generation = 0, selected = 0;
  @override
  void dispose() {
    debounce?.cancel();
    input.dispose();
    focus.dispose();
    super.dispose();
  }

  void changed(String value) {
    debounce?.cancel();
    final ticket = ++generation;
    if (value.trim().isEmpty) {
      setState(() {
        results = null;
        error = null;
        loading = false;
      });
      return;
    }
    setState(() {
      loading = true;
      error = null;
    });
    debounce = Timer(const Duration(milliseconds: 240), () async {
      try {
        final page = await widget.app.quickSearch(value.trim());
        if (!mounted || generation != ticket) return;
        setState(() {
          results = page;
          loading = false;
          selected = 0;
        });
      } catch (failure) {
        if (!mounted || generation != ticket) return;
        setState(() {
          error = failure is ServiceException
              ? failure.message
              : 'Search is unavailable. Please try again.';
          loading = false;
        });
      }
    });
  }

  void openResult(SearchResult result) {
    Navigator.pop(context);
    if (result.song != null) {
      widget.app.playSong(
        result.song!,
        context: results?.songs,
        source: 'Quick Search',
      );
    } else if (result.item != null) {
      widget.app.openBrowse(result.item!);
    }
  }

  void fullSearch() {
    final value = input.text.trim();
    if (value.isEmpty) return;
    Navigator.pop(context);
    widget.app.navigate(AppRoute.search);
    widget.app.search(value);
  }

  @override
  Widget build(BuildContext context) {
    final entries = results?.results.take(7).toList() ?? <SearchResult>[];
    final p = DesktopPalette.of(context);
    return Material(
      color: Colors.transparent,
      child: GlassSurface(
        blur: !widget.app.setting('reduce_dynamic_blur', false),
        radius: 16,
        child: Focus(
          onKeyEvent: (node, event) {
            if (event is! KeyDownEvent) return KeyEventResult.ignored;
            if (event.logicalKey == LogicalKeyboardKey.arrowDown &&
                entries.isNotEmpty) {
              setState(() => selected = (selected + 1) % entries.length);
              return KeyEventResult.handled;
            }
            if (event.logicalKey == LogicalKeyboardKey.arrowUp &&
                entries.isNotEmpty) {
              setState(
                () =>
                    selected = (selected - 1 + entries.length) % entries.length,
              );
              return KeyEventResult.handled;
            }
            if (event.logicalKey == LogicalKeyboardKey.escape) {
              Navigator.pop(context);
              return KeyEventResult.handled;
            }
            return KeyEventResult.ignored;
          },
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.sizeOf(context).height * .8,
            ),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(8, 8, 8, 4),
                    child: TextField(
                      controller: input,
                      focusNode: focus,
                      autofocus: true,
                      onChanged: changed,
                      onSubmitted: (_) {
                        if (HardwareKeyboard.instance.isControlPressed ||
                            entries.isEmpty) {
                          fullSearch();
                        } else {
                          openResult(
                            entries[selected.clamp(0, entries.length - 1)],
                          );
                        }
                      },
                      decoration: InputDecoration(
                        hintText: 'Quick Search',
                        prefixIcon: const Icon(Icons.search, size: 19),
                        suffixIcon: loading
                            ? const Padding(
                                padding: EdgeInsets.all(14),
                                child: SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                ),
                              )
                            : IconButton(
                                tooltip: 'Close search',
                                onPressed: () => Navigator.pop(context),
                                icon: const Icon(Icons.close, size: 17),
                              ),
                      ),
                    ),
                  ),
                  for (var i = 0; i < entries.length; i++)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      child: HoverTile(
                        selected: i == selected,
                        onTap: () => openResult(entries[i]),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 7,
                        ),
                        child: Row(
                          children: [
                            Artwork(
                              entries[i].thumbnailUrl,
                              size: 38,
                              radius: entries[i].item?.type == BrowseType.artist
                                  ? 19
                                  : 5,
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    entries[i].title,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w600,
                                      fontSize: 12,
                                    ),
                                  ),
                                  Text(
                                    entries[i].subtitle,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      color: p.secondary,
                                      fontSize: 10,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              entries[i].song != null
                                  ? 'Song'
                                  : entries[i].item!.type.name,
                              style: TextStyle(
                                color: p.secondary,
                                fontSize: 10,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  if (error != null)
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(
                        error!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                          fontSize: 12,
                        ),
                      ),
                    ),
                  if (input.text.isEmpty)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(18, 16, 18, 20),
                      child: Text(
                        'Search songs, artists, albums and playlists',
                        style: TextStyle(color: p.secondary, fontSize: 12),
                      ),
                    ),
                  if (input.text.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.all(6),
                      child: HoverTile(
                        onTap: fullSearch,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 12,
                        ),
                        child: Row(
                          children: [
                            const Icon(Icons.search, size: 16),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                'Search “${input.text}”',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontSize: 12),
                              ),
                            ),
                            Text('↵', style: TextStyle(color: p.secondary)),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
