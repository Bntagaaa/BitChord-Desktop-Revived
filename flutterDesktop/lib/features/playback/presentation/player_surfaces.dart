import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../core/domain/audio_contract.dart';
import '../../../design_system/palette.dart';
import '../../../design_system/theme.dart';
import '../../online/application/catalog_controller.dart';
import '../../online/application/catalog_playback.dart';
import '../../online/domain/catalog.dart';
import '../../online/presentation/catalog_components.dart';
import '../../shell/application/shell_controller.dart';

enum PlayerPane { lyrics, queue, details }

class PlayerLayout {
  const PlayerLayout({this.expanded = false, this.pane});
  final bool expanded;
  final PlayerPane? pane;
}

final playerLayoutProvider =
    NotifierProvider<PlayerLayoutController, PlayerLayout>(
      PlayerLayoutController.new,
    );

class PlayerLayoutController extends Notifier<PlayerLayout> {
  @override
  PlayerLayout build() => const PlayerLayout();
  void expand() => state = PlayerLayout(expanded: true, pane: state.pane);
  void collapse() => state = PlayerLayout(pane: state.pane);
  void pane(PlayerPane? value) =>
      state = PlayerLayout(expanded: state.expanded, pane: value);
  void toggle(PlayerPane value) => pane(state.pane == value ? null : value);
  bool back() {
    if (state.pane != null) {
      pane(null);
      return true;
    }
    if (state.expanded) {
      collapse();
      return true;
    }
    return false;
  }
}

CatalogEntry? _current(CatalogPlaybackView playback) =>
    playback.index >= 0 && playback.index < playback.entries.length
    ? playback.entries[playback.index]
    : null;

class PlayerButton extends StatelessWidget {
  const PlayerButton(
    this.glyph,
    this.label, {
    super.key,
    this.onPressed,
    this.size = 32,
    this.glyphSize = 18,
    this.icon,
  });
  final String glyph, label;
  final VoidCallback? onPressed;
  final double size, glyphSize;
  final IconData? icon;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: size,
    height: size,
    child: IconButton(
      tooltip: onPressed == null ? '$label — unavailable' : label,
      onPressed: onPressed,
      padding: EdgeInsets.zero,
      style: IconButton.styleFrom(
        minimumSize: Size.zero,
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
      ),
      icon: icon == null
          ? SvgPicture.asset(
              'assets/icons/player_transport_$glyph.svg',
              width: glyphSize,
              height: glyphSize,
              colorFilter: ColorFilter.mode(
                Theme.of(context).colorScheme.onSurface.withValues(
                  alpha: onPressed == null ? .35 : .95,
                ),
                BlendMode.srcIn,
              ),
            )
          : Icon(icon, size: glyphSize),
    ),
  );
}

class OnlineTransport extends ConsumerWidget {
  const OnlineTransport({
    super.key,
    required this.empty,
    required this.palette,
    required this.reduceBlur,
  });
  final Widget empty;
  final BitChordPalette palette;
  final bool reduceBlur;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final snapshot = ref.watch(transportProvider);
    if (snapshot == null ||
        snapshot.entryId.isEmpty ||
        snapshot.phase == AudioPhase.idle)
      return empty;
    final playback = ref.watch(catalogPlaybackProvider);
    final entry = _current(playback);
    final port = ref.watch(onlinePortProvider);
    final layout = ref.read(playerLayoutProvider.notifier);
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 760),
      child: GlassSurface(
        palette: palette,
        reduceBlur: reduceBlur,
        child: SizedBox(
          key: const Key('playing-dock'),
          height: 58,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final wide = constraints.maxWidth >= 620;
              final compact = constraints.maxWidth < 500;
              return Stack(
                children: [
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    child: Row(
                      children: [
                        if (!compact)
                          const PlayerButton('shuffle', 'Shuffle', size: 34),
                        PlayerButton(
                          'previous',
                          'Previous',
                          size: 34,
                          glyphSize: 20,
                          onPressed: playback.hasPrevious
                              ? () => ref
                                    .read(catalogPlaybackProvider.notifier)
                                    .previous()
                              : null,
                        ),
                        PlayerButton(
                          snapshot.phase == AudioPhase.playing
                              ? 'pause'
                              : 'play',
                          snapshot.phase == AudioPhase.playing
                              ? 'Pause'
                              : 'Play',
                          size: 36,
                          glyphSize: 23,
                          onPressed: () =>
                              ref.read(transportProvider.notifier).toggle(),
                        ),
                        PlayerButton(
                          'next',
                          'Next',
                          size: 34,
                          glyphSize: 20,
                          onPressed: playback.hasNext
                              ? () => ref
                                    .read(catalogPlaybackProvider.notifier)
                                    .next()
                              : null,
                        ),
                        if (!compact)
                          const PlayerButton('repeat', 'Repeat', size: 34),
                        const SizedBox(width: 8),
                        if (wide)
                          GestureDetector(
                            onTap: layout.expand,
                            child: CatalogArtwork(
                              url: entry?.artwork,
                              size: 42,
                              radius: 7,
                            ),
                          ),
                        Expanded(
                          child: InkWell(
                            key: const Key('expand-player'),
                            onTap: layout.expand,
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8,
                              ),
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    port?.currentTitle ?? entry?.title ?? '',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: Theme.of(context)
                                        .textTheme
                                        .bodyMedium
                                        ?.copyWith(fontWeight: FontWeight.w600),
                                  ),
                                  Text(
                                    playback.error ??
                                        (snapshot.phase == AudioPhase.failed
                                            ? 'Playback failed (${snapshot.error})'
                                            : port?.currentSubtitle ?? ''),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: Theme.of(context).textTheme.bodySmall
                                        ?.copyWith(color: palette['secondary']),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                        PlayerButton(
                          'lyrics',
                          'Lyrics',
                          onPressed: () => layout.toggle(PlayerPane.lyrics),
                        ),
                        if (wide)
                          const PlayerButton(
                            '',
                            'Mini Player window',
                            icon: Icons.picture_in_picture_alt_rounded,
                          ),
                        PlayerButton(
                          'up_next',
                          'Queue',
                          onPressed: () => layout.toggle(PlayerPane.queue),
                        ),
                        if (wide) ...[
                          PlayerButton(
                            '',
                            'Now playing details',
                            icon: Icons.info_rounded,
                            onPressed: () => layout.toggle(PlayerPane.details),
                          ),
                          const PlayerButton(
                            '',
                            'Audio output',
                            icon: Icons.headphones_rounded,
                          ),
                          const SizedBox(width: 6),
                          Tooltip(
                            message: 'Volume adjustment is unavailable',
                            child: SizedBox(
                              width: 72,
                              child: Divider(
                                height: 4,
                                thickness: 4,
                                color: palette['secondary'],
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: 10,
                    child: PlayerProgress(thin: true, snapshot: snapshot),
                  ),
                  if (snapshot.awaitingAudio)
                    const Positioned(
                      top: 2,
                      right: 8,
                      width: 8,
                      height: 8,
                      child: CircularProgressIndicator(strokeWidth: 1.5),
                    ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

// Shared by the dock and expanded player. Pointer movement previews only;
// one seek on release retains Phase 4's native identity/revision fences.
class PlayerProgress extends ConsumerStatefulWidget {
  const PlayerProgress({super.key, required this.snapshot, this.thin = false});
  final PlaybackSnapshot snapshot;
  final bool thin;
  @override
  ConsumerState<PlayerProgress> createState() => _PlayerProgressState();
}

class _PlayerProgressState extends ConsumerState<PlayerProgress> {
  double? _preview;
  PlaybackSnapshot? _start;
  bool _same(PlaybackSnapshot snapshot) =>
      _start?.entryId == snapshot.entryId &&
      _start?.selection == snapshot.selection &&
      _start?.queueRevision == snapshot.queueRevision;
  String _time(int us) {
    final s = us ~/ 1000000;
    return '${s ~/ 60}:${(s % 60).toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = widget.snapshot;
    final duration = math.max(1, snapshot.durationUs).toDouble();
    final position =
        (_same(snapshot) ? _preview : null) ?? snapshot.positionUs.toDouble();
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          height: widget.thin ? 10 : 24,
          // Keep the interactive strip ten pixels tall while painting the
          // source's two-pixel progress line against the dock's bottom edge.
          child: Transform.translate(
            offset: Offset(0, widget.thin ? 4 : 0),
            child: SliderTheme(
              data: SliderTheme.of(context).copyWith(
                trackHeight: widget.thin ? 2 : 4,
                thumbShape: RoundSliderThumbShape(
                  enabledThumbRadius: widget.thin ? 0 : 5,
                ),
                overlayShape: SliderComponentShape.noOverlay,
                trackShape: const RectangularSliderTrackShape(),
                padding: EdgeInsets.zero,
              ),
              child: Slider(
                key: Key(widget.thin ? 'dock-seek' : 'player-seek'),
                semanticFormatterCallback: (value) => _time(value.round()),
                value: position.clamp(0, duration),
                max: duration,
                onChangeStart: snapshot.durationUs <= 0
                    ? null
                    : (value) => setState(() {
                        _preview = value;
                        _start = snapshot;
                      }),
                onChanged: snapshot.durationUs <= 0
                    ? null
                    : (value) {
                        if (_same(widget.snapshot))
                          setState(() => _preview = value);
                      },
                onChangeEnd: snapshot.durationUs <= 0
                    ? null
                    : (value) {
                        final start = _start;
                        setState(() {
                          _preview = null;
                          _start = null;
                        });
                        if (start != null)
                          ref
                              .read(transportProvider.notifier)
                              .seek(value.round(), start);
                      },
              ),
            ),
          ),
        ),
        if (!widget.thin)
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                _time(position.round()),
                style: Theme.of(context).textTheme.bodySmall,
              ),
              Text(
                '-${_time((snapshot.durationUs - position.round()).clamp(0, snapshot.durationUs))}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
      ],
    );
  }
}

class PlayerSidePanel extends ConsumerWidget {
  const PlayerSidePanel({super.key, required this.pane, this.embedded = false});
  final PlayerPane pane;
  final bool embedded;
  @override
  Widget build(BuildContext context, WidgetRef ref) => ColoredBox(
    color: Theme.of(context).colorScheme.surface,
    child: Column(
      children: [
        SizedBox(
          height: 52,
          child: Padding(
            padding: const EdgeInsets.only(left: 18, right: 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    switch (pane) {
                      PlayerPane.lyrics => 'Lyrics',
                      PlayerPane.queue => 'Queue',
                      PlayerPane.details => 'Now playing',
                    },
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                IconButton(
                  tooltip: 'Close panel',
                  onPressed: () =>
                      ref.read(playerLayoutProvider.notifier).pane(null),
                  icon: const Icon(Icons.close, size: 16),
                ),
              ],
            ),
          ),
        ),
        Expanded(
          child: switch (pane) {
            PlayerPane.queue => const _Queue(),
            PlayerPane.lyrics => const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.lyrics_outlined, size: 28),
                    SizedBox(height: 16),
                    Text('Lyrics unavailable'),
                    SizedBox(height: 8),
                    Text(
                      'Lyrics providers are not connected in this build.',
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
            ),
            PlayerPane.details => const _Details(),
          },
        ),
      ],
    ),
  );
}

class _Queue extends ConsumerWidget {
  const _Queue();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final playback = ref.watch(catalogPlaybackProvider);
    if (playback.entries.isEmpty)
      return const Center(child: Text('Your queue is empty'));
    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 0, 18, 28),
      children: [
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Text('NOW PLAYING'),
        ),
        if (_current(playback) case final entry?)
          _queueRow(
            context,
            ref,
            entry,
            playback.index,
            playback,
            current: true,
          ),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 18),
          child: Text('PLAYING NEXT'),
        ),
        for (var i = playback.index + 1; i < playback.entries.length; i++)
          _queueRow(context, ref, playback.entries[i], i, playback),
        if (!playback.hasNext) const Text('No more songs in this queue.'),
      ],
    );
  }

  Widget _queueRow(
    BuildContext context,
    WidgetRef ref,
    CatalogEntry entry,
    int index,
    CatalogPlaybackView playback, {
    bool current = false,
  }) => Material(
    color: Colors.transparent,
    child: InkWell(
      onTap: current
          ? null
          : () => ref
                .read(catalogPlaybackProvider.notifier)
                .select(
                  entry,
                  context: playback.entries,
                  index: index,
                  offline: playback.offline,
                ),
      borderRadius: BorderRadius.circular(7),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          children: [
            CatalogArtwork(url: entry.artwork, size: 44, radius: 6),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    entry.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: current
                          ? Theme.of(context).colorScheme.primary
                          : null,
                    ),
                  ),
                  Text(
                    entry.subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _Details extends ConsumerWidget {
  const _Details();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final snapshot = ref.watch(transportProvider);
    final entry = _current(ref.watch(catalogPlaybackProvider));
    final port = ref.watch(onlinePortProvider);
    return ListView(
      padding: const EdgeInsets.all(18),
      children: [
        Center(
          child: CatalogArtwork(url: entry?.artwork, size: 220, radius: 10),
        ),
        const SizedBox(height: 20),
        Text(
          port?.currentTitle ?? '',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        Text(port?.currentSubtitle ?? ''),
        const SizedBox(height: 24),
        if (snapshot != null) ...[
          Text('Playback · ${snapshot.phase.name}'),
          const SizedBox(height: 12),
          Text(
            '${snapshot.outputRate} Hz · ${snapshot.outputChannels} channels · ${snapshot.precisionBits}-bit',
          ),
          if (snapshot.error != 0) Text('Playback error ${snapshot.error}'),
        ],
      ],
    );
  }
}

/// LandscapePlayer.kt's two equal columns and shared state; no second audio
/// engine, media session or transport timer is created when this view opens.
class ExpandedPlayer extends ConsumerWidget {
  const ExpandedPlayer({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final snapshot = ref.watch(transportProvider);
    final playback = ref.watch(catalogPlaybackProvider);
    final entry = _current(playback);
    final port = ref.watch(onlinePortProvider);
    final state = ref.watch(playerLayoutProvider);
    final layout = ref.read(playerLayoutProvider.notifier);
    final colors = Theme.of(context).colorScheme;
    final image = entry?.artwork == null
        ? null
        : ref.watch(artworkProvider(entry!.artwork!)).asData?.value;
    return Semantics(
      scopesRoute: true,
      explicitChildNodes: true,
      namesRoute: true,
      label: 'Fullscreen Player',
      child: Material(
        color: colors.surface,
        child: Stack(
          children: [
            if (image != null)
              Positioned.fill(
                child: ExcludeSemantics(
                  child: Opacity(
                    opacity: .18,
                    child: Image.memory(
                      image,
                      fit: BoxFit.cover,
                      cacheWidth: 768,
                    ),
                  ),
                ),
              ),
            Positioned.fill(
              child: ColoredBox(color: colors.surface.withValues(alpha: .65)),
            ),
            Positioned.fill(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(32, 48, 32, 24),
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 1100),
                    child: LayoutBuilder(
                      builder: (context, constraints) {
                        final artSize = math.max(
                          80.0,
                          math.min(
                            (constraints.maxWidth - 48) / 2,
                            constraints.maxHeight - 62,
                          ),
                        );
                        final art = Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            CatalogArtwork(
                              url: entry?.artwork,
                              size: artSize,
                              radius: 10,
                            ),
                            const SizedBox(height: 16),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                PlayerButton(
                                  'lyrics',
                                  'Lyrics',
                                  onPressed: () =>
                                      layout.toggle(PlayerPane.lyrics),
                                ),
                                const SizedBox(width: 16),
                                const PlayerButton(
                                  '',
                                  'Audio output',
                                  icon: Icons.headphones_rounded,
                                ),
                                const SizedBox(width: 16),
                                PlayerButton(
                                  'up_next',
                                  'Queue',
                                  onPressed: () =>
                                      layout.toggle(PlayerPane.queue),
                                ),
                              ],
                            ),
                          ],
                        );
                        final controls = state.pane != null
                            ? PlayerSidePanel(pane: state.pane!, embedded: true)
                            : Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    port?.currentTitle ?? '',
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                    style: Theme.of(
                                      context,
                                    ).textTheme.headlineLarge,
                                  ),
                                  const SizedBox(height: 8),
                                  Text(
                                    port?.currentSubtitle ?? '',
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                    style: Theme.of(context)
                                        .textTheme
                                        .titleLarge
                                        ?.copyWith(
                                          color: colors.onSurfaceVariant,
                                        ),
                                  ),
                                  const SizedBox(height: 28),
                                  if (snapshot != null)
                                    PlayerProgress(snapshot: snapshot),
                                  const SizedBox(height: 24),
                                  Row(
                                    mainAxisAlignment:
                                        MainAxisAlignment.spaceEvenly,
                                    children: [
                                      const PlayerButton('shuffle', 'Shuffle'),
                                      PlayerButton(
                                        'previous',
                                        'Previous',
                                        size: 44,
                                        glyphSize: 28,
                                        onPressed: playback.hasPrevious
                                            ? () => ref
                                                  .read(
                                                    catalogPlaybackProvider
                                                        .notifier,
                                                  )
                                                  .previous()
                                            : null,
                                      ),
                                      PlayerButton(
                                        snapshot?.phase == AudioPhase.playing
                                            ? 'pause'
                                            : 'play',
                                        snapshot?.phase == AudioPhase.playing
                                            ? 'Pause'
                                            : 'Play',
                                        size: 64,
                                        glyphSize: 40,
                                        onPressed: snapshot == null
                                            ? null
                                            : () => ref
                                                  .read(
                                                    transportProvider.notifier,
                                                  )
                                                  .toggle(),
                                      ),
                                      PlayerButton(
                                        'next',
                                        'Next',
                                        size: 44,
                                        glyphSize: 28,
                                        onPressed: playback.hasNext
                                            ? () => ref
                                                  .read(
                                                    catalogPlaybackProvider
                                                        .notifier,
                                                  )
                                                  .next()
                                            : null,
                                      ),
                                      const PlayerButton('repeat', 'Repeat'),
                                    ],
                                  ),
                                  const SizedBox(height: 24),
                                  const Tooltip(
                                    message: 'Volume adjustment is unavailable',
                                    child: Slider(value: 1, onChanged: null),
                                  ),
                                  if (playback.error != null)
                                    Text(playback.error!),
                                ],
                              );
                        if (constraints.maxWidth < 650) {
                          return ListView(
                            children: [
                              SizedBox(height: artSize + 64, child: art),
                              const SizedBox(height: 24),
                              SizedBox(
                                height: math.max(
                                  350.0,
                                  constraints.maxHeight - artSize - 88,
                                ),
                                child: controls,
                              ),
                            ],
                          );
                        }
                        return Row(
                          children: [
                            Expanded(child: art),
                            const SizedBox(width: 48),
                            Expanded(child: controls),
                          ],
                        );
                      },
                    ),
                  ),
                ),
              ),
            ),
            Positioned(
              top: 8,
              left: 8,
              child: IconButton(
                tooltip: 'Minimize player',
                onPressed: layout.collapse,
                icon: const Icon(Icons.keyboard_arrow_down, size: 20),
              ),
            ),
            Positioned(
              top: 8,
              right: 8,
              child: Row(
                children: [
                  const PlayerButton(
                    '',
                    'Mini Player window',
                    icon: Icons.picture_in_picture_alt_rounded,
                  ),
                  IconButton(
                    tooltip: 'Toggle fullscreen',
                    onPressed: () async {
                      try {
                        await ref
                            .read(desktopWindowProvider)
                            .toggleFullscreen();
                      } catch (error) {
                        ref
                            .read(diagnosticsProvider)
                            .failure('window action', error);
                      }
                    },
                    icon: const Icon(Icons.fullscreen, size: 20),
                  ),
                ],
              ),
            ),
            Positioned(
              top: 8,
              left: 0,
              right: 0,
              child: Center(
                child: GestureDetector(
                  onTap: layout.collapse,
                  child: Container(
                    width: 38,
                    height: 5,
                    decoration: BoxDecoration(
                      color: colors.onSurface.withValues(alpha: .5),
                      borderRadius: BorderRadius.circular(3),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
