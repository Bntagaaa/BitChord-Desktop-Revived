import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';

import '../../../app/router.dart';
import '../../online/application/catalog_controller.dart';
import '../../online/presentation/catalog_surface.dart';
import '../../online/presentation/account_session.dart';
import '../../../l10n/shell_strings.dart';
import '../../../design_system/palette.dart';
import '../../../design_system/theme.dart';
import '../application/shell_controller.dart';
import '../domain/shell_settings.dart';

enum Destination {
  home('listen-now', 'Home', 'Home'),
  newMusic('new', 'New', 'TabExplore'),
  search('search', 'Search', 'TabSearch'),
  library('library', 'Library', 'TabLibrary'),
  history('history', 'History', 'Clock'),
  downloads('downloads', 'Downloads', 'Download'),
  localMusic('local-music', 'Local Music', 'Library'),
  spotify('spotify', 'Spotify', 'TabLibrary'),
  explore('explore', 'Explore', 'TabExplore'),
  settings('settings', 'Settings', '');

  const Destination(this.id, this.label, this.glyph);
  final String id;
  final String label;
  final String glyph;

  String localizedLabel(BuildContext context) =>
      shellString(context, this == Destination.home ? 'home' : id, label);
}

class BitChordGlyph extends StatelessWidget {
  const BitChordGlyph(
    this.name, {
    super.key,
    required this.color,
    this.size = 20,
    this.width,
    this.height,
  });
  final String name;
  final Color color;
  final double size;
  final double? width;
  final double? height;
  @override
  Widget build(BuildContext context) => SvgPicture.asset(
    'assets/icons/$name.svg',
    width: width ?? size,
    height: height ?? size,
    colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
  );
}

class DesktopShell extends ConsumerStatefulWidget {
  const DesktopShell({
    super.key,
    required this.destination,
    this.browseId,
    this.detailTitle,
    this.browseParams,
  });
  final String? browseId, detailTitle, browseParams;
  final Destination destination;
  @override
  ConsumerState<DesktopShell> createState() => _DesktopShellState();
}

class _DesktopShellState extends ConsumerState<DesktopShell> {
  bool _drawerRequested = false;
  bool _accountsOpen = false;
  bool _narrow = false;
  final _rootFocus = FocusNode(debugLabel: 'Desktop shell');
  @override
  void dispose() {
    _rootFocus.dispose();
    super.dispose();
  }

  void _windowAction(Future<void> Function(DesktopWindow) action) {
    unawaited(
      action(ref.read(desktopWindowProvider)).catchError((Object error) {
        ref.read(diagnosticsProvider).failure('window action', error);
      }),
    );
  }

  void _navigate(Destination destination) {
    setState(() {
      _drawerRequested = false;
      _accountsOpen = false;
    });
    ref.read(historyProvider.notifier).visit('/${destination.id}');
    context.go('/${destination.id}');
  }

  void _goBack() {
    if (_drawerRequested) {
      _dismissOverlays();
      return;
    }
    if (_accountsOpen) {
      _dismissOverlays();
      return;
    }
    final path = ref.read(historyProvider.notifier).back();
    if (path != null) context.go(path);
  }

  void _dismissOverlays() {
    setState(() {
      _drawerRequested = false;
      _accountsOpen = false;
    });
    _rootFocus.requestFocus();
  }

  void _openAccounts() {
    setState(() {
      _drawerRequested = false;
      _accountsOpen = true;
    });
    // A modal excludes its opener from focus traversal. Keep shortcuts on the
    // shell owner, rather than leaving primary focus on the excluded opener.
    _rootFocus.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(shellProvider);
    final palette = BitChordPalette.resolve(
      settings,
      MediaQuery.platformBrightnessOf(context),
    );
    return CallbackShortcuts(
      bindings: {
        const SingleActivator(
          LogicalKeyboardKey.arrowLeft,
          alt: true,
          includeRepeats: false,
        ): _goBack,
        const SingleActivator(
          LogicalKeyboardKey.f11,
          includeRepeats: false,
        ): () =>
            _windowAction((host) => host.toggleFullscreen()),
        const SingleActivator(
          LogicalKeyboardKey.escape,
          includeRepeats: false,
        ): () {
          if (_accountsOpen) {
            _dismissOverlays();
          } else if (_drawerRequested) {
            _dismissOverlays();
          } else {
            _windowAction((host) => host.exitFullscreen());
          }
        },
        const SingleActivator(
          LogicalKeyboardKey.keyH,
          alt: true,
          shift: true,
          includeRepeats: false,
        ): () =>
            _navigate(Destination.home),
        const SingleActivator(
          LogicalKeyboardKey.keyL,
          control: true,
          shift: true,
          includeRepeats: false,
        ): () =>
            _navigate(Destination.search),
      },
      child: Focus(
        focusNode: _rootFocus,
        autofocus: true,
        child: Scaffold(
          body: LayoutBuilder(
            builder: (context, constraints) {
              final narrow = constraints.maxWidth < ShellMetrics.railBreakpoint;
              // A wide resize dismisses the transient drawer without changing the saved preference.
              if (!narrow && _narrow) _drawerRequested = false;
              _narrow = narrow;
              final collapsed = narrow || settings.sidebarCollapsed;
              void toggle() {
                if (narrow) {
                  setState(() => _drawerRequested = !_drawerRequested);
                  _rootFocus.requestFocus();
                } else {
                  ref
                      .read(shellProvider.notifier)
                      .update(
                        settings.copyWith(
                          sidebarCollapsed: !settings.sidebarCollapsed,
                        ),
                      );
                }
              }

              final duration = settings.reduceMotion
                  ? Duration.zero
                  : ShellMetrics.sidebarDuration;
              return Stack(
                children: [
                  ExcludeFocus(
                    excluding: _accountsOpen || (narrow && _drawerRequested),
                    child: ExcludeSemantics(
                      excluding: _accountsOpen || (narrow && _drawerRequested),
                      child: Row(
                        children: [
                          AnimatedContainer(
                            key: const Key('inline-sidebar'),
                            width: collapsed
                                ? ShellMetrics.railWidth
                                : ShellMetrics.sidebarWidth,
                            duration: duration,
                            curve: Curves.fastOutSlowIn,
                            child: LayoutBuilder(
                              builder: (context, sidebarConstraints) =>
                                  _Sidebar(
                                    collapsed:
                                        collapsed ||
                                        sidebarConstraints.maxWidth < 200,
                                    palette: palette,
                                    selected: widget.destination,
                                    onToggle: toggle,
                                    onNavigate: _navigate,
                                    onOpenAccounts: _openAccounts,
                                  ),
                            ),
                          ),
                          Expanded(
                            child: Stack(
                              children: [
                                Column(
                                  children: [
                                    Expanded(
                                      child: Padding(
                                        padding: EdgeInsets.fromLTRB(
                                          16,
                                          widget.destination == Destination.home
                                              // Fallback font ink metrics differ by
                                              // desktop OS; these settle HomeTitle's
                                              // first visible baseline to Kotlin.
                                              ? desktopHomeHeadingTopPadding(
                                                  Theme.of(context).platform,
                                                )
                                              : 16,
                                          16,
                                          ShellMetrics.contentBottomReserve,
                                        ),
                                        child: Align(
                                          alignment: Alignment.topLeft,
                                          child:
                                              widget.destination ==
                                                  Destination.settings
                                              ? _AppearancePanel(
                                                  palette: palette,
                                                )
                                              : ref.watch(onlinePortProvider) !=
                                                    null
                                              ? CatalogSurface(
                                                  key: ValueKey(
                                                    '${widget.destination.id}:${widget.browseId}',
                                                  ),
                                                  destination:
                                                      widget.destination.id,
                                                  title:
                                                      widget.detailTitle ??
                                                      widget.destination
                                                          .localizedLabel(
                                                            context,
                                                          ),
                                                  browseId: widget.browseId,
                                                  params: widget.browseParams,
                                                )
                                              : _UnconnectedPage(
                                                  destination:
                                                      widget.destination,
                                                  palette: palette,
                                                ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                                Positioned(
                                  left: 10,
                                  right: 10,
                                  bottom: 20,
                                  child: Center(
                                    child: OnlineTransport(
                                      palette: palette,
                                      reduceBlur: settings.reduceBlur,
                                      empty: _EmptyDock(
                                        palette: palette,
                                        reduceBlur: settings.reduceBlur,
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  if (narrow && _drawerRequested) ...[
                    Positioned.fill(
                      child: GestureDetector(
                        key: const Key('drawer-scrim'),
                        onTap: _dismissOverlays,
                        child: ColoredBox(
                          color: Colors.black.withValues(alpha: .26),
                        ),
                      ),
                    ),
                    Positioned(
                      left: 0,
                      top: 0,
                      bottom: 0,
                      width: ShellMetrics.sidebarWidth,
                      child: PhysicalModel(
                        color: palette['sidebarSurface'],
                        elevation: 18,
                        shadowColor: Colors.black,
                        clipBehavior: Clip.antiAlias,
                        borderRadius: const BorderRadius.horizontal(
                          right: Radius.circular(12),
                        ),
                        child: _Sidebar(
                          collapsed: false,
                          palette: palette,
                          selected: widget.destination,
                          onToggle: toggle,
                          onNavigate: _navigate,
                          onOpenAccounts: _openAccounts,
                        ),
                      ),
                    ),
                  ],
                  if (_accountsOpen) ...[
                    Positioned.fill(
                      child: GestureDetector(
                        key: const Key('account-scrim'),
                        behavior: HitTestBehavior.opaque,
                        onTap: _dismissOverlays,
                      ),
                    ),
                    Positioned(
                      left: 24,
                      bottom: 64,
                      width: 292,
                      child: _AccountPanel(
                        palette: palette,
                        reduceBlur: settings.reduceBlur,
                      ),
                    ),
                  ],
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

class _Sidebar extends StatelessWidget {
  const _Sidebar({
    required this.collapsed,
    required this.palette,
    required this.selected,
    required this.onToggle,
    required this.onNavigate,
    required this.onOpenAccounts,
  });
  final bool collapsed;
  final BitChordPalette palette;
  final Destination selected;
  final VoidCallback onToggle;
  final ValueChanged<Destination> onNavigate;
  final VoidCallback onOpenAccounts;

  Widget _divider() => Column(
    children: [
      const SizedBox(height: 6),
      ColoredBox(
        color: palette['divider'],
        child: const SizedBox(height: 1, width: double.infinity),
      ),
      const SizedBox(height: 12),
    ],
  );

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: palette['sidebarSurface'],
      border: Border(right: BorderSide(color: palette['divider'])),
    ),
    child: Padding(
      padding: const EdgeInsets.fromLTRB(12, 16, 12, 16),
      child: Column(
        children: [
          if (collapsed) ...[
            SizedBox(
              height: 35,
              width: double.infinity,
              child: Tooltip(
                message: 'Expand sidebar',
                child: InkWell(
                  key: const Key('sidebar-brand-toggle'),
                  onTap: onToggle,
                  child: Center(
                    child: BitChordGlyph(
                      'bitchord_logo_original',
                      color: palette['foreground'],
                      width: 28,
                      height: 18,
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),
          ] else
            Padding(
              padding: const EdgeInsets.only(left: 9, top: 1, bottom: 16),
              child: SizedBox(
                height: 28,
                child: Row(
                  children: [
                    BitChordGlyph(
                      'bitchord_logo_original',
                      color: palette['foreground'],
                      width: 28,
                      height: 18,
                    ),
                    const SizedBox(width: 13),
                    BitChordGlyph(
                      'bitchord_wordmark',
                      color: palette['foreground'],
                      width: 84,
                      height: 18,
                    ),
                    const Spacer(),
                    SizedBox(
                      width: 28,
                      height: 28,
                      child: IconButton(
                        key: const Key('sidebar-brand-toggle'),
                        tooltip: 'Collapse sidebar',
                        padding: EdgeInsets.zero,
                        onPressed: onToggle,
                        icon: Icon(
                          Icons.chevron_left,
                          size: 20,
                          color: palette['secondary'],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          Expanded(
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (final destination in Destination.values.where(
                    (d) =>
                        d != Destination.settings && d != Destination.explore,
                  )) ...[
                    if (destination == Destination.history) _divider(),
                    _NavigationItem(
                      destination: destination,
                      collapsed: collapsed,
                      palette: palette,
                      selected:
                          selected == destination ||
                          (destination == Destination.search &&
                              selected == Destination.explore),
                      onPressed: () => onNavigate(destination),
                    ),
                  ],
                  _divider(),
                  if (collapsed)
                    Tooltip(
                      message: 'Browse playlists',
                      child: InkWell(
                        key: const Key('browse-playlists'),
                        onTap: onToggle,
                        borderRadius: BorderRadius.circular(7),
                        child: SizedBox(
                          height: 42,
                          child: Icon(
                            Icons.playlist_play_rounded,
                            size: 20,
                            color: palette['secondary'],
                          ),
                        ),
                      ),
                    )
                  else
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 9,
                        vertical: 8,
                      ),
                      child: Text(
                        'Your playlists will appear here',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: palette['secondary'].withValues(alpha: .68),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
          ColoredBox(
            color: palette['divider'],
            child: const SizedBox(height: 1, width: double.infinity),
          ),
          const SizedBox(height: 8),
          Tooltip(
            message: collapsed ? 'Accounts' : '',
            child: InkWell(
              key: const Key('sidebar-account'),
              onTap: onOpenAccounts,
              borderRadius: BorderRadius.circular(8),
              child: collapsed
                  ? SizedBox(
                      height: 44,
                      child: Center(child: _AccountAvatar(palette: palette)),
                    )
                  : Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 4,
                        vertical: 5,
                      ),
                      child: Row(
                        children: [
                          _AccountAvatar(palette: palette),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'Sign in',
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: Theme.of(context).textTheme.bodyMedium
                                      ?.copyWith(fontWeight: FontWeight.w500),
                                ),
                                Text(
                                  'Connect account',
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: Theme.of(context).textTheme.bodySmall
                                      ?.copyWith(color: palette['secondary']),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
            ),
          ),
          const SizedBox(height: 4),
          _NavigationItem(
            destination: Destination.settings,
            collapsed: collapsed,
            palette: palette,
            selected: selected == Destination.settings,
            onPressed: () => onNavigate(Destination.settings),
          ),
        ],
      ),
    ),
  );
}

class _AccountAvatar extends StatelessWidget {
  const _AccountAvatar({required this.palette});
  final BitChordPalette palette;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: 36,
    height: 36,
    child: Center(
      child: Container(
        width: 26,
        height: 26,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: palette['foreground'].withValues(alpha: .14),
          border: Border.all(
            color: palette['foreground'].withValues(alpha: .18),
          ),
        ),
        child: Icon(
          Icons.person_rounded,
          size: 16,
          color: palette['foreground'].withValues(alpha: .85),
        ),
      ),
    ),
  );
}

class _AccountPanel extends ConsumerWidget {
  const _AccountPanel({required this.palette, required this.reduceBlur});
  final BitChordPalette palette;
  final bool reduceBlur;
  @override
  Widget build(BuildContext context, WidgetRef ref) => Semantics(
    key: const Key('account-panel'),
    scopesRoute: true,
    explicitChildNodes: true,
    namesRoute: true,
    label: 'Accounts',
    child: GlassSurface(
      palette: palette,
      reduceBlur: reduceBlur,
      tintAlpha: .47,
      blurRadius: 35,
      solidToken: 'barGlass',
      child: Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 13, 16, 5),
              child: Text(
                'Accounts',
                style: Theme.of(
                  context,
                ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w500),
              ),
            ),
            if (ref.watch(onlinePortProvider) != null)
              const AccountSession()
            else ...[
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 8,
                ),
                child: Text(
                  'Nobody is signed in. Your library, playlists and history need a YouTube Music account.',
                  style: Theme.of(
                    context,
                  ).textTheme.bodySmall?.copyWith(color: palette['secondary']),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 4,
                ),
                child: Divider(
                  height: .5,
                  thickness: .5,
                  color: palette['cardEdge'],
                ),
              ),
              Tooltip(
                message: 'Sign-in is unavailable.',
                child: Semantics(
                  enabled: false,
                  button: true,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 10,
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.add, size: 18, color: palette['secondary']),
                        const SizedBox(width: 10),
                        Text(
                          'Add account',
                          style: Theme.of(context).textTheme.bodyMedium
                              ?.copyWith(color: palette['secondary']),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    ),
  );
}

class _NavigationItem extends StatelessWidget {
  const _NavigationItem({
    required this.destination,
    required this.collapsed,
    required this.palette,
    required this.selected,
    required this.onPressed,
  });
  final Destination destination;
  final bool collapsed;
  final BitChordPalette palette;
  final bool selected;
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) {
    final tint = palette[selected ? 'navAccent' : 'secondary'];
    final size = collapsed ? 20.0 : 18.0;
    final height = collapsed ? 42.0 : 32.0;
    return Semantics(
      key: Key('nav-${destination.id}'),
      selected: selected,
      button: true,
      label: destination.localizedLabel(context),
      child: Tooltip(
        message: collapsed ? destination.localizedLabel(context) : '',
        child: Material(
          color: selected ? palette['navSelectedSurface'] : Colors.transparent,
          borderRadius: BorderRadius.circular(7),
          child: InkWell(
            onTap: onPressed,
            borderRadius: BorderRadius.circular(7),
            hoverColor: palette['rowHover'],
            focusColor: palette['rowHover'],
            child: SizedBox(
              height: height,
              child: Stack(
                children: [
                  Positioned.fill(
                    child: Row(
                      mainAxisAlignment: collapsed
                          ? MainAxisAlignment.center
                          : MainAxisAlignment.start,
                      children: [
                        if (!collapsed) const SizedBox(width: 18),
                        if (destination == Destination.settings)
                          Icon(Icons.settings_rounded, size: size, color: tint)
                        else if (destination == Destination.spotify)
                          Image.asset(
                            'assets/icons/spotify_logo.webp',
                            width: size,
                            height: size,
                            color: tint,
                          )
                        else
                          BitChordGlyph(
                            destination.glyph,
                            color: tint,
                            size: size,
                          ),
                        if (!collapsed) ...[
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              destination.localizedLabel(context),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: Theme.of(context).textTheme.bodyMedium
                                  ?.copyWith(
                                    color:
                                        palette[selected
                                            ? 'foreground'
                                            : 'secondary'],
                                    // Kotlin uses logical SemiBold / Medium,
                                    // remapped to medium / regular SF Pro cuts
                                    // in its default desktop font profile.
                                    fontWeight: selected
                                        ? FontWeight.w500
                                        : FontWeight.w400,
                                  ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  if (selected)
                    Positioned(
                      left: collapsed ? 0 : 9,
                      top: (height - 18) / 2,
                      width: 3,
                      height: 18,
                      child: ColoredBox(color: palette['navAccent']),
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

class _UnconnectedPage extends StatelessWidget {
  const _UnconnectedPage({required this.destination, required this.palette});
  final Destination destination;
  final BitChordPalette palette;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        destination.localizedLabel(context),
        key: const Key('page-heading'),
        style: Theme.of(context).textTheme.headlineLarge,
      ),
      const SizedBox(height: 24),
      Text(
        'Music services are not connected.',
        style: Theme.of(
          context,
        ).textTheme.bodyMedium?.copyWith(color: palette['secondary']),
      ),
    ],
  );
}

class _EmptyDock extends StatelessWidget {
  const _EmptyDock({required this.palette, required this.reduceBlur});
  final BitChordPalette palette;
  final bool reduceBlur;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      // Keep the full Kotlin-style dock at normal desktop widths. A smaller
      // viewport cannot fit all controls and the centered artwork safely.
      final compact = constraints.maxWidth < 520;
      return ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: ShellMetrics.dockWidth),
        child: GlassSurface(
          palette: palette,
          reduceBlur: reduceBlur,
          child: SizedBox(
            key: const Key('player-dock'),
            height: ShellMetrics.dockHeight,
            child: Stack(
              alignment: Alignment.center,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 8,
                  ),
                  child: Row(
                    children: [
                      if (!compact) ...[
                        _DockButton(
                          'shuffle',
                          label: 'Shuffle',
                          palette: palette,
                          width: 34,
                          glyphSize: 18,
                        ),
                        const SizedBox(width: 4),
                      ],
                      _DockButton(
                        'previous',
                        label: 'Previous',
                        palette: palette,
                        width: 34,
                        glyphSize: 20,
                        tint: palette['secondary'].withValues(alpha: .4),
                      ),
                      const SizedBox(width: 4),
                      _DockButton(
                        'play',
                        label: 'Play',
                        palette: palette,
                        width: 36,
                        glyphSize: 23,
                        tint: palette['foreground'],
                      ),
                      const SizedBox(width: 4),
                      _DockButton(
                        'next',
                        label: 'Next',
                        palette: palette,
                        width: 34,
                        glyphSize: 20,
                        tint: palette['foreground'],
                      ),
                      const SizedBox(width: 4),
                      if (!compact) ...[
                        _DockButton(
                          'repeat',
                          label: 'Repeat',
                          palette: palette,
                          width: 34,
                          glyphSize: 18,
                        ),
                        const SizedBox(width: 4),
                      ],
                      const Spacer(),
                      Row(
                        spacing: 1,
                        children: [
                          _DockButton(
                            'lyrics',
                            label: 'Lyrics',
                            palette: palette,
                          ),
                          if (!compact)
                            _DockButton(
                              'pip',
                              label: 'Picture in picture',
                              palette: palette,
                              icon: Icons.picture_in_picture_alt_rounded,
                            ),
                          _DockButton(
                            'up_next',
                            label: 'Queue',
                            palette: palette,
                          ),
                          if (!compact) ...[
                            _DockButton(
                              'details',
                              label: 'Now playing details',
                              palette: palette,
                              icon: Icons.info_rounded,
                            ),
                            _DockButton(
                              'audio-output',
                              label: 'Audio output',
                              palette: palette,
                              icon: Icons.headphones_rounded,
                            ),
                          ],
                          if (!compact)
                            Tooltip(
                              message: 'Volume — playback is unavailable',
                              child: Semantics(
                                key: const Key('dock-volume'),
                                label: 'Volume',
                                enabled: false,
                                child: SizedBox(
                                  width: 72,
                                  height: 29,
                                  child: Center(
                                    child: DecoratedBox(
                                      decoration: BoxDecoration(
                                        color: palette['foreground'].withValues(
                                          alpha: .30,
                                        ),
                                        borderRadius: BorderRadius.circular(2),
                                      ),
                                      child: const SizedBox(
                                        width: 72,
                                        height: 4,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                        ],
                      ),
                    ],
                  ),
                ),
                IgnorePointer(
                  child: SvgPicture.asset(
                    'assets/icons/bitchord_no_music.svg',
                    key: const Key('empty-dock-mark'),
                    semanticsLabel: 'BitChord',
                    width: 48,
                    height: 48,
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 0,
                  height: 2,
                  child: ColoredBox(
                    color: palette['foreground'].withValues(alpha: .12),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

class _DockButton extends StatelessWidget {
  const _DockButton(
    this.action, {
    required this.label,
    required this.palette,
    this.width = 32,
    this.glyphSize = 18,
    this.tint,
    this.icon,
  });
  final String action;
  final String label;
  final BitChordPalette palette;
  final double width;
  final double glyphSize;
  final Color? tint;
  final IconData? icon;
  @override
  Widget build(BuildContext context) => Tooltip(
    message: '$label — playback is unavailable',
    child: SizedBox(
      width: width,
      height: width,
      child: IconButton(
        key: Key('transport-$action'),
        onPressed: null,
        padding: EdgeInsets.zero,
        style: IconButton.styleFrom(
          minimumSize: Size.zero,
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
        icon: icon != null
            ? Icon(icon, size: glyphSize, color: tint ?? palette['secondary'])
            : BitChordGlyph(
                'player_transport_$action',
                color: tint ?? palette['secondary'],
                size: glyphSize,
              ),
      ),
    ),
  );
}

class _AppearancePanel extends ConsumerWidget {
  const _AppearancePanel({required this.palette});
  final BitChordPalette palette;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(shellProvider);
    void update(ShellSettings next) =>
        ref.read(shellProvider.notifier).update(next);
    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            shellString(context, 'settings', 'Settings'),
            key: const Key('page-heading'),
            style: Theme.of(context).textTheme.headlineLarge,
          ),
          const SizedBox(height: 24),
          Text(
            shellString(context, 'appearance', 'Appearance'),
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            children: [
              for (final mode in Appearance.values)
                ChoiceChip(
                  label: Text(mode.name),
                  selected: mode == settings.appearance,
                  onSelected: (_) =>
                      update(settings.copyWith(appearance: mode)),
                ),
            ],
          ),
          if (settings.appearance == Appearance.dark)
            SwitchListTile(
              title: const Text('Pitch black'),
              value: settings.pitchBlack,
              onChanged: (value) =>
                  update(settings.copyWith(pitchBlack: value)),
            ),
          SwitchListTile(
            title: Text(
              shellString(
                context,
                'reduce_dynamic_blur',
                'Reduce dynamic blur',
              ),
            ),
            value: settings.reduceBlur,
            onChanged: (value) => update(settings.copyWith(reduceBlur: value)),
          ),
          SwitchListTile(
            title: Text(
              shellString(context, 'reduce_animation', 'Reduce animation'),
            ),
            value: settings.reduceMotion,
            onChanged: (value) =>
                update(settings.copyWith(reduceMotion: value)),
          ),
          Wrap(
            spacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              const Text('UI Scale'),
              for (final percent in uiScaleOptions)
                ChoiceChip(
                  key: Key('ui-scale-$percent'),
                  label: Text('$percent%'),
                  selected: settings.uiScalePercent == percent,
                  onSelected: (_) =>
                      update(settings.copyWith(uiScalePercent: percent)),
                ),
            ],
          ),
          const SizedBox(height: 24),
          Text('About BitChord', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          Text(
            'BitChord Desktop 2.0.0+1 (Flutter Desktop)\n'
            'Phase 4: In-App YouTube Music Sign-In (WebKitGTK 4.1 / WebView2)\n'
            'Audio Engine: C++17 Native PCM Pipeline',
            style: Theme.of(
              context,
            ).textTheme.bodySmall?.copyWith(color: palette['secondary']),
          ),
          const SizedBox(height: 12),
        ],
      ),
    );
  }
}

double desktopHomeHeadingTopPadding(TargetPlatform platform) =>
    platform == TargetPlatform.windows ? 5 : 9;
