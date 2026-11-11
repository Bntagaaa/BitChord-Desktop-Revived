import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';
import '../../../app/router.dart';
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
}

class BitChordGlyph extends StatelessWidget {
  const BitChordGlyph(this.name, {super.key, required this.color, this.size = 20});
  final String name;
  final Color color;
  final double size;
  @override
  Widget build(BuildContext context) => SvgPicture.asset('assets/icons/$name.svg',
    width: size, height: size, colorFilter: ColorFilter.mode(color, BlendMode.srcIn));
}

class DesktopShell extends ConsumerStatefulWidget {
  const DesktopShell({super.key, required this.destination});
  final Destination destination;
  @override
  ConsumerState<DesktopShell> createState() => _DesktopShellState();
}

class _DesktopShellState extends ConsumerState<DesktopShell> {
  bool _drawerRequested = false;
  bool _narrow = false;
  final _rootFocus = FocusNode(debugLabel: 'Desktop shell');
  @override
  void dispose() { _rootFocus.dispose(); super.dispose(); }

  void _windowAction(Future<void> Function(DesktopWindow) action) {
    unawaited(action(ref.read(desktopWindowProvider)).catchError((Object error) {
      ref.read(diagnosticsProvider).failure('window action', error);
    }));
  }
  void _navigate(Destination destination) {
    setState(() => _drawerRequested = false);
    ref.read(historyProvider.notifier).visit('/${destination.id}');
    context.go('/${destination.id}');
  }

  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(shellProvider);
    final palette = BitChordPalette.resolve(settings, MediaQuery.platformBrightnessOf(context));
    return CallbackShortcuts(bindings: {
      const SingleActivator(LogicalKeyboardKey.f11, includeRepeats: false): () => _windowAction((host) => host.toggleFullscreen()),
      const SingleActivator(LogicalKeyboardKey.escape, includeRepeats: false): () {
        if (_drawerRequested) { setState(() => _drawerRequested = false); }
        else { _windowAction((host) => host.exitFullscreen()); }
      },
      const SingleActivator(LogicalKeyboardKey.keyH, alt: true, shift: true, includeRepeats: false): () => _navigate(Destination.home),
      const SingleActivator(LogicalKeyboardKey.keyL, control: true, shift: true, includeRepeats: false): () => _navigate(Destination.search),
    }, child: Focus(focusNode: _rootFocus, autofocus: true,
      child: Scaffold(body: LayoutBuilder(builder: (context, constraints) {
        final narrow = constraints.maxWidth < ShellMetrics.railBreakpoint;
        // A wide resize dismisses the transient drawer without changing the saved preference.
        if (!narrow && _narrow) _drawerRequested = false;
        _narrow = narrow;
        final collapsed = narrow || settings.sidebarCollapsed;
        void toggle() {
          if (narrow) { setState(() => _drawerRequested = !_drawerRequested); }
          else { ref.read(shellProvider.notifier).update(settings.copyWith(sidebarCollapsed: !settings.sidebarCollapsed)); }
        }
        final duration = settings.reduceMotion ? Duration.zero : ShellMetrics.sidebarDuration;
        return Stack(children: [
          Row(children: [
            AnimatedContainer(key: const Key('inline-sidebar'),
              width: collapsed ? ShellMetrics.railWidth : ShellMetrics.sidebarWidth,
              duration: duration, curve: Curves.fastOutSlowIn,
              child: LayoutBuilder(builder: (context, sidebarConstraints) => _Sidebar(
                collapsed: collapsed || sidebarConstraints.maxWidth < 200, palette: palette,
                selected: widget.destination, onToggle: toggle, onNavigate: _navigate))),
            Expanded(child: Stack(children: [
              Column(children: [
                _NavigationBar(palette: palette, title: widget.destination.label),
                Expanded(child: Padding(padding: const EdgeInsets.fromLTRB(16, 16, 16, 110),
                  child: Align(alignment: Alignment.topLeft, child: widget.destination == Destination.settings
                    ? _AppearancePanel(palette: palette) : _UnconnectedPage(destination: widget.destination, palette: palette)))),
              ]),
              Positioned(left: 10, right: 10, bottom: 20, child: Center(child: _EmptyDock(palette: palette, reduceBlur: settings.reduceBlur))),
            ])),
          ]),
          if (narrow && _drawerRequested) ...[
            Positioned.fill(left: ShellMetrics.railWidth, child: GestureDetector(
              key: const Key('drawer-scrim'), onTap: () => setState(() => _drawerRequested = false),
              child: ColoredBox(color: palette['scrim']))),
            Positioned(left: 0, top: 0, bottom: 0, width: ShellMetrics.sidebarWidth,
              child: _Sidebar(collapsed: false, palette: palette, selected: widget.destination,
                onToggle: toggle, onNavigate: _navigate)),
          ],
        ]);
      }))));
  }
}

class _Sidebar extends StatelessWidget {
  const _Sidebar({required this.collapsed, required this.palette, required this.selected,
    required this.onToggle, required this.onNavigate});
  final bool collapsed;
  final BitChordPalette palette;
  final Destination selected;
  final VoidCallback onToggle;
  final ValueChanged<Destination> onNavigate;
  @override
  Widget build(BuildContext context) => ColoredBox(color: palette['sidebarSurface'],
    child: Padding(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 16),
      child: Column(children: [
        SizedBox(height: 51, child: Row(children: [
          Expanded(child: Semantics(label: collapsed ? 'Expand sidebar' : 'BitChord', button: collapsed,
            child: InkWell(key: const Key('sidebar-brand-toggle'), onTap: collapsed ? onToggle : null, child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              BitChordGlyph('bitchord_logo_original', color: palette['foreground'], size: 28),
              if (!collapsed) ...[const SizedBox(width: 13), Flexible(child: SizedBox(width: 84, height: 18,
                child: BitChordGlyph('bitchord_wordmark', color: palette['foreground'], size: 84)))],
            ])))),
          if (!collapsed) IconButton(tooltip: 'Collapse sidebar', onPressed: onToggle,
            icon: const Icon(Icons.chevron_left, size: 20)),
        ])),
        Expanded(child: SingleChildScrollView(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          for (final destination in Destination.values.where((d) => d != Destination.settings && d != Destination.explore)) ...[
            if (destination == Destination.history) const Padding(padding: EdgeInsets.symmetric(vertical: 9), child: Divider(height: 1)),
            _NavigationItem(destination: destination, collapsed: collapsed, palette: palette,
              selected: selected == destination || (destination == Destination.search && selected == Destination.explore),
              onPressed: () => onNavigate(destination)),
          ],
          const Padding(padding: EdgeInsets.symmetric(vertical: 9), child: Divider(height: 1)),
          if (!collapsed) Padding(padding: const EdgeInsets.all(9), child: Text('Your playlists will appear here',
            style: Theme.of(context).textTheme.labelMedium?.copyWith(color: palette['secondary']))),
        ]))),
        _NavigationItem(destination: Destination.settings, collapsed: collapsed, palette: palette,
          selected: selected == Destination.settings, onPressed: () => onNavigate(Destination.settings)),
      ])));
}

class _NavigationItem extends StatelessWidget {
  const _NavigationItem({required this.destination, required this.collapsed, required this.palette,
    required this.selected, required this.onPressed});
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
    return Padding(padding: const EdgeInsets.only(bottom: 2),
      child: Semantics(key: Key('nav-${destination.id}'), selected: selected, button: true, label: destination.label,
        child: Tooltip(message: collapsed ? destination.label : '', child: Material(
          color: selected ? palette['navSelectedSurface'] : Colors.transparent,
          borderRadius: BorderRadius.circular(7),
          child: InkWell(onTap: onPressed, borderRadius: BorderRadius.circular(7),
            hoverColor: palette['rowHover'], focusColor: palette['rowHover'],
            child: SizedBox(height: height, child: Stack(children: [
              Positioned.fill(child: Row(mainAxisAlignment: collapsed ? MainAxisAlignment.center : MainAxisAlignment.start, children: [
                if (!collapsed) const SizedBox(width: 18),
                if (destination == Destination.settings) Icon(Icons.settings_rounded, size: size, color: tint)
                else if (destination == Destination.spotify) Image.asset('assets/icons/spotify_logo.webp', width: size, height: size, color: tint)
                else BitChordGlyph(destination.glyph, color: tint, size: size),
                if (!collapsed) ...[const SizedBox(width: 10), Expanded(child: Text(destination.label,
                  maxLines: 1, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: palette[selected ? 'foreground' : 'secondary'], fontWeight: selected ? FontWeight.w500 : FontWeight.w400)))],
              ])),
              if (selected) Positioned(left: collapsed ? 0 : 9, top: (height - 18) / 2, width: 3, height: 18,
                child: ColoredBox(color: palette['navAccent'])),
            ])))))));
  }
}

class _NavigationBar extends ConsumerWidget {
  const _NavigationBar({required this.palette, required this.title});
  final BitChordPalette palette;
  final String title;
  @override
  Widget build(BuildContext context, WidgetRef ref) => SizedBox(height: 48, child: Row(children: [
    IconButton(tooltip: 'Back', onPressed: ref.watch(historyProvider).length > 1 ? () {
      final path = ref.read(historyProvider.notifier).back();
      if (path != null) context.go(path);
    } : null, icon: const Icon(Icons.chevron_left)),
    Text(title, style: Theme.of(context).textTheme.titleMedium),
    const Spacer(),
    const Padding(padding: EdgeInsets.only(right: 16), child: Tooltip(message: 'Music services are not connected',
      child: Icon(Icons.account_circle_outlined, size: 22))),
  ]));
}

class _UnconnectedPage extends StatelessWidget {
  const _UnconnectedPage({required this.destination, required this.palette});
  final Destination destination;
  final BitChordPalette palette;
  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
    Text(destination.label, style: Theme.of(context).textTheme.displayLarge),
    const SizedBox(height: 24),
    Text('Music services are not connected.', style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: palette['secondary'])),
  ]);
}

class _EmptyDock extends StatelessWidget {
  const _EmptyDock({required this.palette, required this.reduceBlur});
  final BitChordPalette palette;
  final bool reduceBlur;
  @override
  Widget build(BuildContext context) => ConstrainedBox(constraints: const BoxConstraints(maxWidth: ShellMetrics.dockWidth),
    child: GlassSurface(palette: palette, reduceBlur: reduceBlur,
      child: SizedBox(key: const Key('player-dock'), height: ShellMetrics.dockHeight,
        child: LayoutBuilder(builder: (context, constraints) => Stack(alignment: Alignment.center, children: [
          Row(children: [
            const SizedBox(width: 12),
            for (final action in ['shuffle', 'previous', 'play', 'next', 'repeat'])
              _DisabledTransport(action, palette: palette),
            const Spacer(),
            for (final action in ['lyrics', 'up_next']) _DisabledTransport(action, palette: palette),
            const SizedBox(width: 12),
          ]),
          if (constraints.maxWidth >= 430) IgnorePointer(child: BitChordGlyph('bitchord_logo_original', color: palette['foreground'], size: 48)),
          Positioned(left: 0, right: 0, bottom: 0, height: 2, child: ColoredBox(color: palette['foreground'].withValues(alpha: .12))),
        ])))));
}

class _DisabledTransport extends StatelessWidget {
  const _DisabledTransport(this.action, {required this.palette});
  final String action;
  final BitChordPalette palette;
  @override
  Widget build(BuildContext context) => Tooltip(message: '$action — playback is unavailable',
    child: IconButton(key: Key('transport-$action'), onPressed: null,
      icon: BitChordGlyph('player_transport_$action', color: palette['secondary'].withValues(alpha: .4), size: action == 'play' ? 23 : 18)));
}

class _AppearancePanel extends ConsumerWidget {
  const _AppearancePanel({required this.palette});
  final BitChordPalette palette;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(shellProvider);
    void update(ShellSettings next) => ref.read(shellProvider.notifier).update(next);
    return SingleChildScrollView(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Settings', style: Theme.of(context).textTheme.displayLarge),
      const SizedBox(height: 24),
      Text('Appearance', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 12),
      Wrap(spacing: 8, children: [for (final mode in Appearance.values)
        ChoiceChip(label: Text(mode.name), selected: mode == settings.appearance,
          onSelected: (_) => update(settings.copyWith(appearance: mode)))]),
      if (settings.appearance == Appearance.dark) SwitchListTile(title: const Text('Pitch black'),
        value: settings.pitchBlack, onChanged: (value) => update(settings.copyWith(pitchBlack: value))),
      SwitchListTile(title: const Text('Reduce dynamic blur'), value: settings.reduceBlur,
        onChanged: (value) => update(settings.copyWith(reduceBlur: value))),
      SwitchListTile(title: const Text('Reduce animation'), value: settings.reduceMotion,
        onChanged: (value) => update(settings.copyWith(reduceMotion: value))),
    ]));
  }
}
