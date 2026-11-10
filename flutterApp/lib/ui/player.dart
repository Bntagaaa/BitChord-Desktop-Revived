// Port source: LandscapePlayer.kt, LandscapePlayerColumns.kt, LandscapeLyricsFrame.kt.
import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import '../domain/application.dart';
import '../domain/lyric_clock.dart';
import '../domain/lyrics.dart';
import 'theme.dart';
import 'widgets.dart';
import 'artwork_backdrop.dart';

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
  const ImmersivePlayer({super.key, required this.app, this.initialPane});
  final MusicApplication app;
  final PlayerPane? initialPane;
  @override
  State<ImmersivePlayer> createState() => _ImmersivePlayerState();
}

class _ImmersivePlayerState extends State<ImmersivePlayer>
    with TickerProviderStateMixin {
  late PlayerPane _pane;
  bool _hover = false;
  bool _intro = true;
  String? _track;
  Timer? _timer;
  final _lyricsScroll = ScrollController();
  final _lyricsKeys = <int, GlobalKey>{};
  int _active = -1;

  late final LyricClock _lyricClock;
  late final ValueNotifier<int> _clockNotifier;
  late final Ticker _ticker;
  int _discontinuity = 0;
  double _lastSampledAtMs = double.nan;
  double _lastReportedPositionMs = double.nan;

  MusicApplication get app => widget.app;

  @override
  void initState() {
    super.initState();
    _pane = widget.initialPane ?? PlayerPane.main;
    final initialPos = app.position.value.inMilliseconds;
    _lyricClock = LyricClock(initialPos);
    _clockNotifier = ValueNotifier<int>(initialPos);
    _ticker = createTicker(_onTick);
    _lastSampledAtMs = DateTime.now().microsecondsSinceEpoch / 1000.0;
    app.addListener(_changed);
    app.position.addListener(_onPositionChanged);
    _changed();
    _syncTicker();
  }

  void _onPositionChanged() {
    _lastSampledAtMs = DateTime.now().microsecondsSinceEpoch / 1000.0;
    if (!app.playing) {
      _lyricClock.hold(app.position.value.inMilliseconds, _lastSampledAtMs);
    }
  }

  void _syncTicker() {
    final shouldTick = _pane == PlayerPane.lyrics && app.playing;
    if (shouldTick && !_ticker.isActive) {
      _lyricClock.restart();
      _ticker.start();
    } else if (!shouldTick && _ticker.isActive) {
      _ticker.stop();
      final nowMs = DateTime.now().microsecondsSinceEpoch / 1000.0;
      _lyricClock.hold(app.position.value.inMilliseconds, nowMs);
    }
  }

  void _onTick(Duration elapsed) {
    if (!mounted || _pane != PlayerPane.lyrics) return;
    final nowMs = DateTime.now().microsecondsSinceEpoch / 1000.0;
    final reportedMs = app.position.value.inMilliseconds;
    if (reportedMs != _lastReportedPositionMs) {
      _lastReportedPositionMs = reportedMs.toDouble();
      if (_lastSampledAtMs.isNaN) {
        _lastSampledAtMs = nowMs;
      }
    }
    final displayedMs = _lyricClock.frame(
      nowMs: nowMs,
      reportedMs: reportedMs,
      sampledAtMs: _lastSampledAtMs.isNaN ? nowMs : _lastSampledAtMs,
      discontinuity: _discontinuity,
    );
    _clockNotifier.value = displayedMs;
  }

  void _changed() {
    if (_track != app.song?.id) {
      _track = app.song?.id;
      _discontinuity++;
      _lyricClock.restart();
      _lyricsKeys.clear();
      _active = -1;
      _reveal();
    }
    _lastSampledAtMs = DateTime.now().microsecondsSinceEpoch / 1000.0;
    if (!app.playing) {
      _clockNotifier.value = app.position.value.inMilliseconds;
    }
    _syncTicker();
    if (mounted) setState(() {});
  }

  void _reveal() {
    _timer?.cancel();
    _intro = true;
    _timer = Timer(const Duration(seconds: 2), () {
      if (mounted) setState(() => _intro = false);
    });
  }

  void _scrollToActive(int active) {
    if (!_lyricsScroll.hasClients || active < 0) return;
    final lineContext = _lyricsKeys[active]?.currentContext;
    if (lineContext == null) return;
    final box = lineContext.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize) return;
    final viewport = RenderAbstractViewport.of(box);
    final reveal = viewport.getOffsetToReveal(box, 0.5);
    final target = reveal.offset.clamp(
      0.0,
      _lyricsScroll.position.maxScrollExtent,
    );
    unawaited(
      _lyricsScroll.animateTo(
        target,
        duration: const Duration(milliseconds: 350),
        curve: Curves.easeInOut,
      ),
    );
  }

  @override
  void didUpdateWidget(ImmersivePlayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.app != widget.app) {
      oldWidget.app.removeListener(_changed);
      oldWidget.app.position.removeListener(_onPositionChanged);
      widget.app.addListener(_changed);
      widget.app.position.addListener(_onPositionChanged);
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    _clockNotifier.dispose();
    app.removeListener(_changed);
    app.position.removeListener(_onPositionChanged);
    _timer?.cancel();
    _lyricsScroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: paletteOf(context).lyricsSurface,
    body: Stack(
      children: [
        Positioned.fill(child: FullArtworkBackdrop(url: app.song?.artwork)),
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
          onChanged: (value) {
            _discontinuity++;
            final targetMs = value.round();
            _clockNotifier.value = targetMs;
            _lastReportedPositionMs = targetMs.toDouble();
            _lastSampledAtMs = DateTime.now().microsecondsSinceEpoch / 1000.0;
            if (!app.playing) {
              _lyricClock.hold(targetMs, _lastSampledAtMs);
            }
            unawaited(app.seek(Duration(milliseconds: targetMs)));
          },
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
              _syncTicker();
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
            () {
              setState(
                () => _pane = _pane == PlayerPane.queue
                    ? PlayerPane.main
                    : PlayerPane.queue,
              );
              _syncTicker();
            },
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

  Widget _lyrics() => ValueListenableBuilder<int>(
    valueListenable: _clockNotifier,
    builder: (context, clockMs, _) {
      final lines = app.lyrics;
      if (lines.isEmpty) {
        return Center(
          child: app.lyricsSession.loading
              ? const CircularProgressIndicator(strokeWidth: 2)
              : Text(app.lyricsSession.error ?? 'No lyrics available'),
        );
      }
      var active = -1;
      for (var index = 0; index < lines.length; index++) {
        if (lines[index].timeMs <= clockMs) {
          active = index;
        } else {
          break;
        }
      }
      if (_active != active) {
        _active = active;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted) return;
          _scrollToActive(active);
        });
      }
      return Column(
        children: [
          Expanded(
            child: ShaderMask(
              shaderCallback: (bounds) {
                const fadePixels = 28.0;
                final topFraction = bounds.height > 0
                    ? (fadePixels / bounds.height).clamp(0.0, 0.5)
                    : 0.0;
                final bottomFraction = (1.0 - topFraction).clamp(0.5, 1.0);
                return LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: const [
                    Colors.transparent,
                    Colors.white,
                    Colors.white,
                    Colors.transparent,
                  ],
                  stops: [0.0, topFraction, bottomFraction, 1.0],
                ).createShader(bounds);
              },
              blendMode: BlendMode.dstIn,
              child: SingleChildScrollView(
                controller: _lyricsScroll,
                padding: const EdgeInsets.fromLTRB(16, 20, 16, 140),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (var index = 0; index < lines.length; index++)
                      Padding(
                        key: _lyricsKeys.putIfAbsent(index, GlobalKey.new),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 4,
                        ),
                        child: Material(
                          color: Colors.transparent,
                          borderRadius: BorderRadius.circular(10),
                          child: InkWell(
                            borderRadius: BorderRadius.circular(10),
                            onTap: () {
                              final line = lines[index];
                              _discontinuity++;
                              _clockNotifier.value = line.timeMs;
                              _lastReportedPositionMs = line.timeMs.toDouble();
                              _lastSampledAtMs =
                                  DateTime.now().microsecondsSinceEpoch /
                                  1000.0;
                              if (!app.playing) {
                                _lyricClock.hold(line.timeMs, _lastSampledAtMs);
                              }
                              unawaited(
                                app.seek(Duration(milliseconds: line.timeMs)),
                              );
                            },
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 14,
                                vertical: 8,
                              ),
                              child: _SweptLyricText(
                                line: lines[index],
                                positionMs: clockMs,
                                isActive: index == active,
                                isPast: active >= 0 && index < active,
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

class _GapDotsPainter extends CustomPainter {
  const _GapDotsPainter({required this.progress, required this.color});
  final double progress;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const dots = 3;
    const radius = 6.0;
    const stride = 18.0;
    final totalWidth = (dots - 1) * stride + radius * 2;
    final startX = (size.width - totalWidth) / 2 + radius;
    final cy = size.height / 2;

    for (var i = 0; i < dots; i++) {
      final lit = (progress * dots - i).clamp(0.0, 1.0);
      final alpha = 0.25 + 0.75 * lit;
      final paint = Paint()
        ..color = color.withValues(alpha: alpha)
        ..style = PaintingStyle.fill;
      canvas.drawCircle(Offset(startX + i * stride, cy), radius, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _GapDotsPainter old) =>
      old.progress != progress || old.color != color;
}

class _SweptLyricText extends StatelessWidget {
  const _SweptLyricText({
    required this.line,
    required this.positionMs,
    required this.isActive,
    required this.isPast,
  });

  final LyricLine line;
  final int positionMs;
  final bool isActive;
  final bool isPast;

  @override
  Widget build(BuildContext context) {
    if (line.isGap) {
      final nextMs = line.endMs;
      final span = math.max(1, nextMs - line.timeMs);
      final through = ((positionMs - line.timeMs) / span).clamp(0.0, 1.0);
      return AnimatedContainer(
        duration: const Duration(milliseconds: 350),
        curve: Curves.easeInOutCubic,
        height: isActive ? 40.0 : 0.0,
        clipBehavior: Clip.hardEdge,
        child: CustomPaint(
          size: const Size(double.infinity, 40),
          painter: _GapDotsPainter(progress: through, color: Colors.white),
        ),
      );
    }

    const style = TextStyle(
      fontSize: 34,
      fontWeight: FontWeight.w700,
      color: Colors.white,
    );

    Widget mainText;
    if (isActive && line.isWordSynced) {
      final revealed = line.revealedChars(positionMs);
      final isGlow = line.isGrowing(positionMs);
      final progress = line.text.isEmpty
          ? 0.0
          : (revealed / line.text.length).clamp(0.0, 1.0);

      mainText = Stack(
        children: [
          Text(
            line.text,
            style: style.copyWith(color: Colors.white.withValues(alpha: 0.4)),
          ),
          if (isGlow)
            ImageFiltered(
              imageFilter: ui.ImageFilter.blur(sigmaX: 6.0, sigmaY: 6.0),
              child: Text(
                line.text,
                style: style.copyWith(
                  color: Colors.white.withValues(alpha: 0.62),
                ),
              ),
            ),
          ShaderMask(
            blendMode: BlendMode.dstIn,
            shaderCallback: (bounds) {
              const feather = 24.0;
              final lead = bounds.width > 0
                  ? (feather / bounds.width).clamp(0.01, 0.3)
                  : 0.05;
              final start = progress.clamp(0.0, 1.0);
              final end = (start + lead).clamp(0.0, 1.0);
              return ui.Gradient.linear(
                Offset.zero,
                Offset(bounds.width, 0),
                const [
                  Colors.white,
                  Colors.white,
                  Colors.transparent,
                  Colors.transparent,
                ],
                [0.0, start, end, 1.0],
              );
            },
            child: Text(line.text, style: style),
          ),
        ],
      );
    } else {
      final alpha = isActive ? 1.0 : (isPast ? 0.75 : 0.4);
      mainText = Text(
        line.text,
        style: style.copyWith(color: Colors.white.withValues(alpha: alpha)),
      );
    }

    if (line.background != null && line.background!.text.isNotEmpty) {
      return Column(
        crossAxisAlignment: line.alignment == LyricAlignment.end
            ? CrossAxisAlignment.end
            : CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          mainText,
          const SizedBox(height: 4),
          Text(
            line.background!.text,
            style: TextStyle(
              fontSize: 23,
              fontWeight: FontWeight.w600,
              color: Colors.white.withValues(alpha: 0.72),
            ),
          ),
        ],
      );
    }

    return mainText;
  }
}
