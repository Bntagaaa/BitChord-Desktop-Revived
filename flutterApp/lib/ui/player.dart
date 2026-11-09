// Port source: LandscapePlayer.kt, LandscapePlayerColumns.kt, LandscapeLyricsFrame.kt.
import 'dart:async';
import 'dart:math' as math;
import 'dart:ui';
import 'package:flutter/material.dart';
import '../domain/application.dart';
import 'theme.dart';
import 'widgets.dart';

enum PlayerPane { main, lyrics, queue }

class PlayerGeometry {
  const PlayerGeometry(this.size, this.top, this.actionsTop);
  final double size;
  final double top;
  final double actionsTop;
  static PlayerGeometry calculate(
    double width,
    double height,
    double actions,
    double gap,
  ) {
    final available = math.max(0.0, height - actions - gap);
    final size = math.min(math.max(0.0, width), available);
    final top = (available - size) / 2;
    return PlayerGeometry(
      size,
      top,
      top + size + (height - top - size - actions) / 2,
    );
  }
}

class ImmersivePlayer extends StatefulWidget {
  const ImmersivePlayer({super.key, required this.app});
  final MusicApplication app;
  @override
  State<ImmersivePlayer> createState() => _ImmersivePlayerState();
}

class _ImmersivePlayerState extends State<ImmersivePlayer> {
  PlayerPane _pane = PlayerPane.main;
  bool _hover = false;
  bool _intro = true;
  String? _track;
  Timer? _timer;
  final _lyricsScroll = ScrollController();
  final _lyricsKeys = <int, GlobalKey>{};
  int _active = -1;
  MusicApplication get app => widget.app;
  @override
  void initState() {
    super.initState();
    app.addListener(_changed);
    _changed();
  }

  void _changed() {
    if (_track != app.song?.id) {
      _track = app.song?.id;
      _lyricsKeys.clear();
      _active = -1;
      _reveal();
    }
    if (mounted) setState(() {});
  }

  void _reveal() {
    _timer?.cancel();
    _intro = true;
    _timer = Timer(const Duration(seconds: 2), () {
      if (mounted) setState(() => _intro = false);
    });
  }

  @override
  void dispose() {
    app.removeListener(_changed);
    _timer?.cancel();
    _lyricsScroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: paletteOf(context).lyricsSurface,
    body: Stack(
      children: [
        if (app.song?.artwork != null)
          Positioned.fill(
            child: ImageFiltered(
              imageFilter: ImageFilter.blur(sigmaX: 60, sigmaY: 60),
              child: Opacity(
                opacity: .45,
                child: Image.network(
                  app.song!.artwork!,
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => const SizedBox.shrink(),
                ),
              ),
            ),
          ),
        SafeArea(
          child: LayoutBuilder(
            builder: (context, bounds) {
              if (bounds.maxWidth <= bounds.maxHeight ||
                  bounds.maxWidth < 560) {
                return _portrait();
              }
              final width = math.min(bounds.maxWidth, 1100.0);
              final height = math.max(0.0, bounds.maxHeight - 44);
              final gutter = bounds.maxHeight < 440 ? 20.0 : 30.0;
              final column = width / 2 - gutter * 2;
              final geometry = PlayerGeometry.calculate(
                column,
                height,
                88,
                bounds.maxHeight < 440 ? 12 : 24,
              );
              return Align(
                alignment: Alignment.topCenter,
                child: Padding(
                  padding: const EdgeInsets.only(top: 24, bottom: 20),
                  child: SizedBox(
                    width: width,
                    height: height,
                    child: Stack(
                      children: [
                        Positioned(
                          left: gutter,
                          top: geometry.top,
                          width: geometry.size,
                          height: geometry.size,
                          child: AnimatedScale(
                            scale: app.playing ? 1 : .88,
                            duration: const Duration(milliseconds: 500),
                            curve: const Cubic(.215, .61, .355, 1),
                            child: Artwork(app.song?.artwork, radius: 10),
                          ),
                        ),
                        Positioned(
                          left: width / 2 + gutter,
                          right: gutter,
                          top: _pane == PlayerPane.main
                              ? math.max(geometry.top, height / 2 - 120)
                              : geometry.top,
                          height: _pane == PlayerPane.queue
                              ? math.max(0, geometry.actionsTop - geometry.top)
                              : geometry.size,
                          child: MouseRegion(
                            onEnter: (_) => setState(() => _hover = true),
                            onExit: (_) => setState(() => _hover = false),
                            child: Stack(
                              children: [
                                if (_pane == PlayerPane.lyrics)
                                  Positioned.fill(child: _lyrics()),
                                if (_pane == PlayerPane.queue)
                                  Positioned.fill(child: _queue()),
                                if (_pane != PlayerPane.queue)
                                  AnimatedSlide(
                                    offset:
                                        _pane != PlayerPane.lyrics ||
                                            _intro ||
                                            _hover
                                        ? Offset.zero
                                        : const Offset(0, -1),
                                    duration: const Duration(milliseconds: 200),
                                    child: AnimatedOpacity(
                                      opacity:
                                          _pane != PlayerPane.lyrics ||
                                              _intro ||
                                              _hover
                                          ? 1
                                          : 0,
                                      duration: const Duration(
                                        milliseconds: 200,
                                      ),
                                      child: _metadata(),
                                    ),
                                  ),
                                if (_pane == PlayerPane.main)
                                  Positioned(
                                    top: 110,
                                    left: 0,
                                    right: 0,
                                    child: _transport(),
                                  ),
                              ],
                            ),
                          ),
                        ),
                        Positioned(
                          left: 0,
                          right: 0,
                          top: geometry.actionsTop,
                          child: _actions(),
                        ),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
        Align(
          alignment: Alignment.topCenter,
          child: Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Container(
              width: 38,
              height: 5,
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: .7),
                borderRadius: BorderRadius.circular(3),
              ),
            ),
          ),
        ),
        Positioned(
          top: 8,
          left: 8,
          child: IconButton(
            tooltip: 'Close player',
            onPressed: () => Navigator.pop(context),
            icon: const Icon(Icons.keyboard_arrow_down),
          ),
        ),
      ],
    ),
  );

  Widget _portrait() => Padding(
    padding: const EdgeInsets.fromLTRB(30, 50, 30, 24),
    child: Column(
      children: [
        Expanded(
          child: _pane == PlayerPane.lyrics
              ? _lyrics()
              : _pane == PlayerPane.queue
              ? _queue()
              : Center(
                  child: AspectRatio(
                    aspectRatio: 1,
                    child: Artwork(app.song?.artwork),
                  ),
                ),
        ),
        const SizedBox(height: 20),
        _metadata(),
        const SizedBox(height: 20),
        _transport(),
        const SizedBox(height: 24),
        _actions(),
      ],
    ),
  );

  Widget _metadata() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    mainAxisSize: MainAxisSize.min,
    children: [
      Text(
        'Playing from ${app.queue.source}',
        style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
      ),
      const SizedBox(height: 12),
      Text(
        app.song?.title ?? '',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
      ),
      Text(
        app.song?.artist ?? '',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 16, color: Color(0x99FFFFFF)),
      ),
    ],
  );

  Widget _transport() => Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      ValueListenableBuilder(
        valueListenable: app.position,
        builder: (context, position, _) => Slider(
          value: position.inMilliseconds.toDouble().clamp(
            0,
            math.max(1, app.duration.value.inMilliseconds).toDouble(),
          ),
          max: math.max(1, app.duration.value.inMilliseconds).toDouble(),
          onChanged: (value) =>
              unawaited(app.audio.seek(Duration(milliseconds: value.round()))),
        ),
      ),
      Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          TransportButton(
            'player_transport_previous',
            'Previous',
            () => unawaited(app.previous()),
            size: 44,
          ),
          const SizedBox(width: 30),
          TransportButton(
            app.playing ? 'player_transport_pause' : 'player_transport_play',
            app.playing ? 'Pause' : 'Play',
            () => unawaited(app.audio.playPause()),
            size: 56,
          ),
          const SizedBox(width: 30),
          TransportButton(
            'player_transport_next',
            'Next',
            () => unawaited(app.next()),
            size: 44,
          ),
        ],
      ),
    ],
  );

  Widget _actions() => Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          TransportButton(
            'player_transport_lyrics',
            'Lyrics',
            () {
              setState(() {
                _pane = _pane == PlayerPane.lyrics
                    ? PlayerPane.main
                    : PlayerPane.lyrics;
                _reveal();
              });
            },
            size: 44,
            active: _pane == PlayerPane.lyrics,
          ),
          const SizedBox(width: 52),
          ClipRRect(
            borderRadius: BorderRadius.circular(25),
            child: ColoredBox(
              color: Colors.white.withValues(alpha: .15),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 4,
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    TransportButton(
                      'player_transport_shuffle',
                      'Shuffle',
                      app.toggleShuffle,
                      active: app.shuffle,
                    ),
                    const SizedBox(width: 16),
                    TransportButton(
                      app.repeat == RepeatMode.one
                          ? 'player_transport_repeat_one'
                          : 'player_transport_repeat',
                      'Repeat',
                      () => app.cycleRepeat(),
                      active: app.repeat != RepeatMode.off,
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(width: 52),
          TransportButton(
            'player_transport_up_next',
            'Queue',
            () => setState(
              () => _pane = _pane == PlayerPane.queue
                  ? PlayerPane.main
                  : PlayerPane.queue,
            ),
            size: 44,
            active: _pane == PlayerPane.queue,
          ),
        ],
      ),
      const SizedBox(height: 16),
      const Text(
        'System default',
        style: TextStyle(fontSize: 11, color: Color(0x99FFFFFF)),
      ),
    ],
  );

  Widget _lyrics() => ValueListenableBuilder(
    valueListenable: app.position,
    builder: (context, position, _) {
      final lines = app.lyrics;
      if (lines.isEmpty) {
        return const Center(child: Text('No lyrics available'));
      }
      var active = -1;
      for (var index = 0; index < lines.length; index++) {
        if (lines[index].time <= position) {
          active = index;
        } else {
          break;
        }
      }
      if (_active != active) {
        _active = active;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted) return;
          final lineContext = _lyricsKeys[active]?.currentContext;
          if (lineContext != null) {
            unawaited(
              Scrollable.ensureVisible(
                lineContext,
                alignment: .5,
                duration: const Duration(milliseconds: 350),
                curve: Curves.easeInOut,
              ),
            );
          }
        });
      }
      return Column(
        children: [
          Expanded(
            child: ShaderMask(
              shaderCallback: (bounds) => const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  Colors.transparent,
                  Colors.white,
                  Colors.white,
                  Colors.transparent,
                ],
                stops: [0, .06, .92, 1],
              ).createShader(bounds),
              blendMode: BlendMode.dstIn,
              child: SingleChildScrollView(
                controller: _lyricsScroll,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (var index = 0; index < lines.length; index++)
                      InkWell(
                        key: _lyricsKeys.putIfAbsent(index, GlobalKey.new),
                        borderRadius: BorderRadius.circular(10),
                        onTap: () =>
                            unawaited(app.audio.seek(lines[index].time)),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Text(
                            lines[index].text,
                            style: TextStyle(
                              fontSize: 34,
                              fontWeight: FontWeight.w700,
                              color: Colors.white.withValues(
                                alpha: index == active ? 1 : .4,
                              ),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
          AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            height: _hover ? 44 : 0,
            clipBehavior: Clip.hardEdge,
            decoration: const BoxDecoration(),
            child: const Center(
              child: Text('Lyrics by LRCLIB', style: TextStyle(fontSize: 14)),
            ),
          ),
        ],
      );
    },
  );

  Widget _queue() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text('Queue', style: Theme.of(context).textTheme.headlineMedium),
      const SizedBox(height: 18),
      Expanded(
        child: ListView.builder(
          itemCount: app.queue.entries.length,
          itemBuilder: (context, index) {
            final track = app.queue.entries[index].song;
            return ListTile(
              leading: Artwork(track.artwork, size: 44, radius: 6),
              title: Text(
                track.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Text(
                track.artist,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              selected: index == app.queue.index,
              onTap: () => unawaited(app.jumpTo(index)),
            );
          },
        ),
      ),
    ],
  );
}
