import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

import '../application/app_controller.dart';
import '../domain/models.dart';
import 'desktop_theme.dart';

/// Exact easing and timing curves ported from PlayerLyrics.kt in BitChord 1.9.2
const Curve kLyricEasing = Cubic(0.41, 0.0, 0.12, 0.99);
const Duration kLyricSettleDuration = Duration(milliseconds: 400);

const int kScrollLeadMinMs = 350;
const int kScrollLeadMaxMs = 500;

const int kStaggerSteps = 3;
const double kStaggerFraction = 0.06;

// Visual metrics ported directly from PlayerLyrics.kt
const double kUnsungAlpha = 0.45;
const double kGlowAlpha = 0.62;
const double kGlowRadius = 6.0;
const double kGlowRoom = 10.0;
const double kBackingFontSize = 23.0;
const double kBackingLineHeight = 29.0;
const double kBackingAlpha = 0.72;
const double kSubLyricFontSize = 20.0;
const double kSubLyricLineHeight = 25.0;
const double kSubLyricAlpha = 0.85;
const double kWipeFeather = 30.0;
const double kWordRise = 2.0;

const double kGapRowHeight = 40.0;
const double kGapRowSpacing = 16.0;
const int kGapDots = 3;
const double kGapDotSize = 13.0;
const double kGapDotGap = 5.0;
const double kGapDotRest = 0.25;
const double kGapRestScale = 0.76;

const List<double> kLineFalloffAlpha = [1.0, 0.8, 0.7, 0.58, 0.46];
const List<double> kLineFalloffBlur = [0.0, 1.0, 1.0, 1.7, 2.4];

const double kBrowsingAlpha = 0.72;
const double kInactiveScale = 0.98;
const double kPressedScale = 0.96;

/// Calculate early run-up silence between lines (350ms - 500ms ahead)
int calculateScrollLeadMs(List<LyricLine> lines, int positionMs) {
  final current = lines.lastIndexWhere((l) => l.start.inMilliseconds <= positionMs);
  if (current < 0) return kScrollLeadMinMs;
  if (current + 1 >= lines.length) return kScrollLeadMinMs;
  final currLine = lines[current];
  final nextLine = lines[current + 1];
  final currEndMs = currLine.end?.inMilliseconds ??
      (currLine.words.isNotEmpty
          ? currLine.words.last.end.inMilliseconds
          : currLine.start.inMilliseconds + 4000);
  final gap = nextLine.start.inMilliseconds - currEndMs;
  return gap.clamp(kScrollLeadMinMs, kScrollLeadMaxMs);
}

/// One handover tracking run ported from ScrollRun in PlayerLyrics.kt
class _ScrollRun {
  const _ScrollRun(this.id, this.delta, this.durationMs);
  final int id;
  final double delta;
  final int durationMs;
  double get spanMs => durationMs * (1.0 + kStaggerFraction * kStaggerSteps);
}

/// High-fidelity Apple Music-style smooth lyric panel ported from BitChord 1.9.2
class SmoothLyricsPanel extends StatefulWidget {
  const SmoothLyricsPanel({
    super.key,
    required this.app,
    this.immersive = false,
    this.centerFocus = true,
  });

  final AppController app;
  final bool immersive;
  final bool centerFocus;

  @override
  State<SmoothLyricsPanel> createState() => _SmoothLyricsPanelState();
}

class _SmoothLyricsPanelState extends State<SmoothLyricsPanel>
    with WidgetsBindingObserver, SingleTickerProviderStateMixin {
  final ScrollController _scroll = ScrollController();
  List<GlobalKey> _lineKeys = [];
  LyricsDocument? _doc;
  int _activeLine = -1;
  int _focusLine = -1;
  DateTime _manualBrowsingUntil = DateTime.fromMillisecondsSinceEpoch(0);
  bool _isWindowFocused = true;

  int _runIdCounter = 0;
  _ScrollRun _currentRun = const _ScrollRun(0, 0.0, 400);
  double _staggerElapsedMs = 0.0;
  AnimationController? _staggerAnim;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.app.playback.addListener(_onPositionTick);
    _syncDoc();
  }

  @override
  void didUpdateWidget(covariant SmoothLyricsPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.app.playback != widget.app.playback) {
      oldWidget.app.playback.removeListener(_onPositionTick);
      widget.app.playback.addListener(_onPositionTick);
    }
    _syncDoc();
  }

  void _syncDoc() {
    if (identical(_doc, widget.app.lyrics)) return;
    _doc = widget.app.lyrics;
    _lineKeys = List.generate(_doc?.lines.length ?? 0, (_) => GlobalKey());
    _activeLine = _resolveActiveLine();
    _focusLine = _resolveFocusLine();
    WidgetsBinding.instance.addPostFrameCallback(
      (_) => _scrollToFocus(jump: true),
    );
  }

  Duration _adjustedPos() {
    final offsetMs = widget.app.setting<num>('lyrics_offset_ms', 0).round();
    return widget.app.playback.position + Duration(milliseconds: offsetMs);
  }

  int _resolveActiveLine() {
    return _doc?.activeIndex(_adjustedPos()) ?? -1;
  }

  int _resolveFocusLine() {
    if (_doc == null || _doc!.lines.isEmpty) return -1;
    final now = _adjustedPos();
    final leadMs = calculateScrollLeadMs(_doc!.lines, now.inMilliseconds);
    final leadIndex = _doc!.activeIndex(now + Duration(milliseconds: leadMs));
    final activeIndex = _resolveActiveLine();
    return leadIndex >= 0 ? leadIndex : activeIndex;
  }

  void _onPositionTick() {
    if (!mounted || _doc == null || _doc!.lines.isEmpty) return;
    final nextActive = _resolveActiveLine();
    final nextFocus = _resolveFocusLine();

    final activeChanged = nextActive != _activeLine;
    final focusChanged = nextFocus != _focusLine;

    if (activeChanged || focusChanged) {
      setState(() {
        _activeLine = nextActive;
        _focusLine = nextFocus;
      });

      if (focusChanged) {
        if (_isWindowFocused ||
            widget.app.setting('animate_lyrics_while_unfocused', false)) {
          WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToFocus());
        }
      }
    }
  }

  void _scrollToFocus({bool jump = false}) {
    if (!mounted ||
        _focusLine < 0 ||
        _focusLine >= _lineKeys.length ||
        DateTime.now().isBefore(_manualBrowsingUntil) ||
        !_scroll.hasClients) {
      return;
    }

    final targetContext = _lineKeys[_focusLine].currentContext;
    if (targetContext == null) return;

    final renderBox = targetContext.findRenderObject() as RenderBox?;
    if (renderBox == null || !renderBox.hasSize) return;

    final viewport = RenderAbstractViewport.of(renderBox);
    final revealed = viewport.getOffsetToReveal(
      renderBox,
      widget.centerFocus ? 0.38 : 0.25,
    );

    final currentOffset = _scroll.offset;
    final targetOffset = revealed.offset.clamp(
      0.0,
      _scroll.position.maxScrollExtent,
    );
    final delta = targetOffset - currentOffset;

    if (jump || widget.app.setting('reduce_animation', false)) {
      _scroll.jumpTo(targetOffset);
      return;
    }

    if (delta.abs() > 0.5) {
      final leadMs = calculateScrollLeadMs(
        _doc!.lines,
        _adjustedPos().inMilliseconds,
      );

      _runIdCounter++;
      _currentRun = _ScrollRun(_runIdCounter, delta, leadMs);

      _staggerAnim?.dispose();
      _staggerAnim = AnimationController(
        vsync: this,
        duration: Duration(milliseconds: _currentRun.spanMs.round()),
      );
      _staggerAnim!.addListener(() {
        if (mounted) {
          setState(() {
            _staggerElapsedMs =
                _staggerAnim!.value * _currentRun.spanMs;
          });
        }
      });
      unawaited(_staggerAnim!.forward(from: 0.0));

      unawaited(
        _scroll.animateTo(
          targetOffset,
          duration: Duration(milliseconds: leadMs),
          curve: kLyricEasing,
        ),
      );
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _isWindowFocused = state == AppLifecycleState.resumed;
    if (_isWindowFocused) _scrollToFocus(jump: true);
  }

  @override
  void dispose() {
    widget.app.playback.removeListener(_onPositionTick);
    WidgetsBinding.instance.removeObserver(this);
    _staggerAnim?.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    _syncDoc();
    final p = DesktopPalette.of(context);
    final isLight = Theme.of(context).brightness == Brightness.light;

    if (!widget.app.setting('synced_lyrics', true)) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Synced lyrics are off',
              style: TextStyle(
                color: widget.immersive ? Colors.white70 : p.secondary,
                fontSize: 15,
                fontWeight: FontWeight.w500,
              ),
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: () => widget.app.setSetting('synced_lyrics', true),
              child: const Text('Enable lyrics'),
            ),
          ],
        ),
      );
    }

    if (widget.app.lyricsLoading) {
      return Center(
        child: Text(
          'Lyrics are coming together...',
          style: TextStyle(
            color: widget.immersive ? Colors.white54 : p.secondary,
            fontSize: 14,
            fontWeight: FontWeight.w500,
          ),
        ),
      );
    }

    if (_doc?.isInstrumental == true) {
      return Center(
        child: Text(
          'Instrumental',
          style: TextStyle(
            color: widget.immersive ? Colors.white : p.foreground,
            fontSize: 32,
            fontWeight: FontWeight.w800,
            letterSpacing: -0.5,
          ),
        ),
      );
    }

    if (_doc == null || _doc!.lines.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            widget.app.lyricsError ?? 'No lyrics available for this song.',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: widget.immersive ? Colors.white54 : p.secondary,
              fontSize: 14,
            ),
          ),
        ),
      );
    }

    final baseFontSize =
        widget.app.setting<num>('lyrics_text_size', 30).toDouble() +
        (widget.immersive ? 4 : 0);
    final reduceBlur =
        widget.app.setting('reduce_dynamic_blur', false) ||
        !widget.app.setting('lyrics_blur', true);

    final isBrowsing = DateTime.now().isBefore(_manualBrowsingUntil);

    return LayoutBuilder(
      builder: (context, constraints) => NotificationListener<ScrollNotification>(
        onNotification: (notice) {
          if (notice is ScrollStartNotification && notice.dragDetails != null) {
            setState(() {
              _manualBrowsingUntil =
                  DateTime.now().add(const Duration(seconds: 5));
            });
          }
          if (notice is UserScrollNotification &&
              notice.direction != ScrollDirection.idle) {
            setState(() {
              _manualBrowsingUntil =
                  DateTime.now().add(const Duration(seconds: 5));
            });
          }
          return false;
        },
        child: ShaderMask(
          blendMode: BlendMode.dstIn,
          shaderCallback: (bounds) {
            if (bounds.height <= 0) {
              return const LinearGradient(
                colors: [Colors.white, Colors.white],
              ).createShader(bounds);
            }
            const fadeH = 28.0;
            final topFrac = (fadeH / bounds.height).clamp(0.0, 0.35);
            final bottomFrac = (1.0 - topFrac).clamp(0.65, 1.0);
            return ui.Gradient.linear(
              Offset.zero,
              Offset(0, bounds.height),
              [
                Colors.transparent,
                Colors.white,
                Colors.white,
                Colors.transparent,
              ],
              [0.0, topFrac, bottomFrac, 1.0],
            );
          },
          child: Scrollbar(
            controller: _scroll,
            child: SingleChildScrollView(
              controller: _scroll,
              padding: EdgeInsets.fromLTRB(
                widget.immersive ? 20 : 28,
                constraints.maxHeight * 0.35,
                widget.immersive ? 20 : 28,
                constraints.maxHeight * 0.52,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (var i = 0; i < _doc!.lines.length; i++)
                    _SmoothLyricRow(
                      key: _lineKeys[i],
                      line: _doc!.lines[i],
                      nextLine: i + 1 < _doc!.lines.length ? _doc!.lines[i + 1] : null,
                      index: i,
                      activeLine: _activeLine,
                      focusLine: _focusLine,
                      isSynced: _doc!.isSynced,
                      docOffset: _doc!.offset,
                      fontSize: baseFontSize,
                      app: widget.app,
                      immersive: widget.immersive,
                      isLight: isLight,
                      reduceBlur: reduceBlur,
                      browsing: isBrowsing,
                      currentRun: _currentRun,
                      staggerElapsedMs: _staggerElapsedMs,
                      onSeek: (target) {
                        setState(() {
                          _manualBrowsingUntil =
                              DateTime.fromMillisecondsSinceEpoch(0);
                        });
                        widget.app.playback.seek(target);
                      },
                    ),
                  const SizedBox(height: 32),
                  Text(
                    _doc!.source.isNotEmpty ? 'Lyrics by ${_doc!.source}' : '',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w500,
                      color: widget.immersive ? Colors.white38 : p.secondary,
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

class _SmoothLyricRow extends StatefulWidget {
  const _SmoothLyricRow({
    super.key,
    required this.line,
    required this.nextLine,
    required this.index,
    required this.activeLine,
    required this.focusLine,
    required this.isSynced,
    required this.docOffset,
    required this.fontSize,
    required this.app,
    required this.immersive,
    required this.isLight,
    required this.reduceBlur,
    required this.browsing,
    required this.currentRun,
    required this.staggerElapsedMs,
    required this.onSeek,
  });

  final LyricLine line;
  final LyricLine? nextLine;
  final int index;
  final int activeLine;
  final int focusLine;
  final bool isSynced;
  final Duration docOffset;
  final double fontSize;
  final AppController app;
  final bool immersive;
  final bool isLight;
  final bool reduceBlur;
  final bool browsing;
  final _ScrollRun currentRun;
  final double staggerElapsedMs;
  final ValueChanged<Duration> onSeek;

  @override
  State<_SmoothLyricRow> createState() => _SmoothLyricRowState();
}

class _SmoothLyricRowState extends State<_SmoothLyricRow> {
  bool _pressed = false;

  bool get _isGapLine =>
      widget.line.text.trim().isEmpty || widget.line.text.trim() == '♪';

  double _calculateStaggerY() {
    if (widget.currentRun.id == 0 || widget.currentRun.durationMs <= 0) {
      return 0.0;
    }
    final behind = widget.currentRun.delta >= 0.0
        ? (widget.index - widget.focusLine)
        : (widget.focusLine - widget.index);
    if (behind < 0) return 0.0;
    final staggerSteps = behind.clamp(0, kStaggerSteps);
    final staggerDelay =
        staggerSteps * kStaggerFraction * widget.currentRun.durationMs;
    if (staggerDelay <= 0) return 0.0;

    final easeNow = kLyricEasing.transform(
      (widget.staggerElapsedMs / widget.currentRun.durationMs).clamp(0.0, 1.0),
    );
    final easeDelayed = kLyricEasing.transform(
      ((widget.staggerElapsedMs - staggerDelay) / widget.currentRun.durationMs)
          .clamp(0.0, 1.0),
    );
    return widget.currentRun.delta * (easeNow - easeDelayed);
  }

  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    final isActive = widget.index == widget.activeLine;
    final distance = (widget.index - widget.focusLine).abs();
    final step = distance.clamp(0, kLineFalloffAlpha.length - 1);

    final double targetAlpha;
    if (!widget.isSynced) {
      targetAlpha = 0.95;
    } else if (isActive) {
      targetAlpha = 1.0;
    } else if (widget.browsing) {
      targetAlpha = kBrowsingAlpha;
    } else {
      final baseFalloff = kLineFalloffAlpha[step];
      targetAlpha = widget.isLight ? math.max(baseFalloff, 0.72) : baseFalloff;
    }

    final double blurRadius;
    if (!widget.isSynced ||
        widget.reduceBlur ||
        widget.browsing ||
        isActive) {
      blurRadius = 0.0;
    } else {
      blurRadius = kLineFalloffBlur[step];
    }

    final double targetScale;
    if (_pressed) {
      targetScale = kPressedScale;
    } else if (isActive) {
      targetScale = 1.0;
    } else {
      targetScale = kInactiveScale;
    }

    final textColor = widget.immersive
        ? Colors.white
        : (widget.isLight ? p.foreground : p.foreground);

    final isDuetEnd = widget.line.alignment == LyricAlignment.end;
    final staggerY = _calculateStaggerY();

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: GestureDetector(
        onTapDown: (_) => setState(() => _pressed = true),
        onTapCancel: () => setState(() => _pressed = false),
        onTapUp: (_) => setState(() => _pressed = false),
        onTap: widget.isSynced
            ? () {
                final offsetMs =
                    widget.app.setting<num>('lyrics_offset_ms', 0).round();
                final target = widget.line.start -
                    widget.docOffset -
                    Duration(milliseconds: offsetMs);
                widget.onSeek(target);
              }
            : null,
        child: Transform.translate(
          offset: Offset(0, staggerY),
          child: TweenAnimationBuilder<double>(
            tween: Tween<double>(begin: targetScale, end: targetScale),
            duration: _pressed
                ? const Duration(milliseconds: 120)
                : kLyricSettleDuration,
            curve: kLyricEasing,
            builder: (context, scale, child) => Transform.scale(
              scale: scale,
              alignment:
                  isDuetEnd ? Alignment.centerRight : Alignment.centerLeft,
              child: child,
            ),
            child: TweenAnimationBuilder<double>(
              tween: Tween<double>(begin: targetAlpha, end: targetAlpha),
              duration: kLyricSettleDuration,
              curve: kLyricEasing,
              builder: (context, alpha, child) =>
                  Opacity(opacity: alpha.clamp(0.0, 1.0), child: child),
              child: blurRadius > 0.05
                  ? ImageFiltered(
                      imageFilter: ui.ImageFilter.blur(
                        sigmaX: blurRadius,
                        sigmaY: blurRadius,
                      ),
                      child: _buildRowContent(textColor, isActive, isDuetEnd),
                    )
                  : _buildRowContent(textColor, isActive, isDuetEnd),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildRowContent(Color color, bool isActive, bool isDuetEnd) {
    if (_isGapLine) {
      return _GapCountdownRow(
        app: widget.app,
        line: widget.line,
        nextLine: widget.nextLine,
        docOffset: widget.docOffset,
        color: color,
        isActive: isActive,
        onSeek: widget.onSeek,
      );
    }

    final textAlign = isDuetEnd ? TextAlign.end : TextAlign.start;
    final crossAlign =
        isDuetEnd ? CrossAxisAlignment.end : CrossAxisAlignment.start;

    final lineStyle = TextStyle(
      fontSize: widget.fontSize,
      fontWeight: FontWeight.w800,
      height: 1.18,
      letterSpacing: -0.4,
      color: color,
    );

    return Column(
      crossAxisAlignment: crossAlign,
      children: [
        if (isActive && (widget.line.isWordSynced || widget.isSynced))
          _SweptLyricText(
            app: widget.app,
            line: widget.line,
            docOffset: widget.docOffset,
            style: lineStyle,
            color: color,
            isActive: isActive,
            textAlign: textAlign,
          )
        else
          Text(
            widget.line.text.isEmpty ? '♪' : widget.line.text,
            textAlign: textAlign,
            style: lineStyle,
          ),
        if (widget.line.translation != null &&
            widget.line.translation!.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              widget.line.translation!,
              textAlign: textAlign,
              style: TextStyle(
                fontSize: kSubLyricFontSize,
                height: 1.25,
                fontWeight: FontWeight.w600,
                color: color.withAlpha((255 * kSubLyricAlpha).round()),
              ),
            ),
          ),
        if (widget.line.romanization != null &&
            widget.line.romanization!.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              widget.line.romanization!,
              textAlign: textAlign,
              style: TextStyle(
                fontSize: kSubLyricFontSize * 0.88,
                height: 1.2,
                fontWeight: FontWeight.w500,
                color: color.withAlpha((255 * (kSubLyricAlpha * 0.9)).round()),
              ),
            ),
          ),
      ],
    );
  }
}

/// 3-dot instrumental interlude countdown widget ported from PlayerLyrics.kt
class _GapCountdownRow extends StatelessWidget {
  const _GapCountdownRow({
    required this.app,
    required this.line,
    required this.nextLine,
    required this.docOffset,
    required this.color,
    required this.isActive,
    required this.onSeek,
  });

  final AppController app;
  final LyricLine line;
  final LyricLine? nextLine;
  final Duration docOffset;
  final Color color;
  final bool isActive;
  final ValueChanged<Duration> onSeek;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: app.playback,
      builder: (context, _) {
        final offsetMs = app.setting<num>('lyrics_offset_ms', 0).round();
        final currentPos = app.playback.position +
            docOffset +
            Duration(milliseconds: offsetMs);

        final startMs = line.start.inMilliseconds;
        final endMs = nextLine?.start.inMilliseconds ??
            (line.end?.inMilliseconds ?? startMs + 6000);
        final spanMs = math.max(1, endMs - startMs);
        final currentMs = currentPos.inMilliseconds;

        final through = ((currentMs - startMs) / spanMs).clamp(0.0, 1.0);
        final isRunning = currentMs >= startMs && currentMs <= endMs;
        final swell = isRunning || isActive ? 1.0 : kGapRestScale;

        return SizedBox(
          height: (kGapRowHeight + kGapRowSpacing) * swell,
          child: Align(
            alignment: Alignment.centerLeft,
            child: InkWell(
              borderRadius: BorderRadius.circular(10),
              onTap: () => onSeek(line.start),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                child: CustomPaint(
                  size: const Size(
                    kGapDotSize * kGapDots + kGapDotGap * (kGapDots - 1),
                    kGapDotSize,
                  ),
                  painter: _GapDotsPainter(
                    through: through,
                    color: color,
                    swell: swell,
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _GapDotsPainter extends CustomPainter {
  const _GapDotsPainter({
    required this.through,
    required this.color,
    required this.swell,
  });

  final double through;
  final Color color;
  final double swell;

  @override
  void paint(Canvas canvas, Size size) {
    final radius = (kGapDotSize / 2) * swell;
    final stride = (kGapDotSize + kGapDotGap) * swell;

    for (var i = 0; i < kGapDots; i++) {
      final lit = (through * kGapDots - i).clamp(0.0, 1.0);
      final dotAlpha = kGapDotRest + (1.0 - kGapDotRest) * lit;
      final paint = Paint()
        ..color = color.withValues(alpha: dotAlpha.clamp(0.0, 1.0))
        ..style = PaintingStyle.fill;

      final cx = radius + i * stride;
      final cy = size.height / 2;
      canvas.drawCircle(Offset(cx, cy), radius, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _GapDotsPainter old) =>
      old.through != through || old.color != color || old.swell != swell;
}

/// Swept highlight text using Apple Music's feathered wavefront algorithm ported from sweepTo in PlayerLyrics.kt
class _SweptLyricText extends StatelessWidget {
  const _SweptLyricText({
    required this.app,
    required this.line,
    required this.docOffset,
    required this.style,
    required this.color,
    required this.isActive,
    required this.textAlign,
  });

  final AppController app;
  final LyricLine line;
  final Duration docOffset;
  final TextStyle style;
  final Color color;
  final bool isActive;
  final TextAlign textAlign;

  double _calculateRevealedChars(Duration currentPos) {
    final posMs = currentPos.inMilliseconds;
    final lineStartMs = line.start.inMilliseconds;

    if (line.words.isEmpty) {
      final lineEndMs = line.end?.inMilliseconds ?? (lineStartMs + 4000);
      if (posMs <= lineStartMs) return 0.0;
      if (posMs >= lineEndMs) return line.text.length.toDouble();
      final span = math.max(1, lineEndMs - lineStartMs);
      return ((posMs - lineStartMs) / span) * line.text.length;
    }

    var offset = 0;
    for (var i = 0; i < line.words.length; i++) {
      final word = line.words[i];
      final wordIdx = line.text.indexOf(word.text, offset);
      final s = wordIdx >= 0 ? wordIdx : offset;
      final e = s + word.text.length;
      final wStartMs = word.start.inMilliseconds;
      final wEndMs = word.end.inMilliseconds;

      if (posMs < wStartMs) return s.toDouble();
      if (posMs < wEndMs) {
        final span = math.max(1, wEndMs - wStartMs);
        final through = (posMs - wStartMs) / span;
        return s + through * (e - s);
      }

      // Fill inter-word pause smoothly across whitespace
      final next = i + 1 < line.words.length ? line.words[i + 1] : null;
      if (next != null && posMs < next.start.inMilliseconds) {
        final nextIdx = line.text.indexOf(next.text, e);
        final gapStart = nextIdx >= 0 ? nextIdx : e;
        final pause = math.max(1, next.start.inMilliseconds - wEndMs);
        final through = (posMs - wEndMs) / pause;
        return e + through * (gapStart - e);
      }
      offset = e;
    }
    return line.text.length.toDouble();
  }

  bool _isHoldingNote(Duration currentPos) {
    if (line.words.isEmpty) return false;
    final posMs = currentPos.inMilliseconds;
    for (final word in line.words) {
      final span = (word.end - word.start).inMilliseconds;
      if (span >= 1100 &&
          posMs >= word.start.inMilliseconds &&
          posMs <= word.end.inMilliseconds) {
        return true;
      }
    }
    return false;
  }

  double _calculateCharX(String text, double revealedChars, TextStyle style, double maxWidth) {
    if (text.isEmpty || revealedChars <= 0) return 0.0;
    if (revealedChars >= text.length) return maxWidth;
    final charIdx = revealedChars.floor().clamp(0, text.length - 1);
    final frac = (revealedChars - charIdx).clamp(0.0, 1.0);
    final painter = TextPainter(
      text: TextSpan(text: text, style: style),
      textAlign: textAlign,
      textDirection: TextDirection.ltr,
    )..layout(maxWidth: maxWidth);
    final boxes = painter.getBoxesForSelection(
      TextSelection(baseOffset: charIdx, extentOffset: math.min(text.length, charIdx + 1)),
    );
    if (boxes.isNotEmpty) {
      final b = boxes.first;
      return b.left + frac * (b.right - b.left);
    }
    return maxWidth * (revealedChars / text.length).clamp(0.0, 1.0);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: app.playback,
      builder: (context, _) {
        final offsetMs = app.setting<num>('lyrics_offset_ms', 0).round();
        final currentPos = app.playback.position +
            docOffset +
            Duration(milliseconds: offsetMs);

        final lineStartMs = line.start.inMilliseconds;
        final lineEndMs = line.end?.inMilliseconds ??
            (line.words.isNotEmpty
                ? line.words.last.end.inMilliseconds
                : lineStartMs + 4000);

        final isPast = currentPos.inMilliseconds >= lineEndMs;
        final isBefore = currentPos.inMilliseconds < lineStartMs;

        final revealedChars = isPast
            ? line.text.length.toDouble()
            : (isBefore ? 0.0 : _calculateRevealedChars(currentPos));

        final isGlow = isActive && _isHoldingNote(currentPos);

        return LayoutBuilder(
          builder: (context, constraints) {
            final charX = _calculateCharX(
              line.text,
              revealedChars,
              style,
              constraints.maxWidth,
            );

            return Stack(
              children: [
                // 1. Unsung base text (rendered at kUnsungAlpha = 0.45 or full if isPast)
                Text(
                  line.text.isEmpty ? '♪' : line.text,
                  style: style.copyWith(
                    color: color.withValues(alpha: isPast ? 0.95 : kUnsungAlpha),
                  ),
                  textAlign: textAlign,
                ),
                // 2. Apple Bloom halo glow for held notes
                if (isGlow)
                  ImageFiltered(
                    imageFilter: ui.ImageFilter.blur(
                      sigmaX: kGlowRadius,
                      sigmaY: kGlowRadius,
                    ),
                    child: Text(
                      line.text,
                      style: style.copyWith(
                        color: color.withValues(alpha: kGlowAlpha),
                      ),
                      textAlign: textAlign,
                    ),
                  ),
                // 3. Lit portion with feathered wavefront wipe ahead of revealedChars
                if (!isBefore)
                  ShaderMask(
                    blendMode: BlendMode.dstIn,
                    shaderCallback: (bounds) {
                      if (bounds.width <= 0 || isPast) {
                        return const LinearGradient(
                          colors: [Colors.white, Colors.white],
                        ).createShader(bounds);
                      }

                      final progress = (charX / bounds.width).clamp(0.0, 1.0);
                      final featherFraction =
                          (kWipeFeather / bounds.width).clamp(0.01, 0.35);
                      final lead = (progress + featherFraction).clamp(0.0, 1.0);

                      if (textAlign == TextAlign.end) {
                        final startCut = (1.0 - lead).clamp(0.0, 1.0);
                        final endCut = (1.0 - progress).clamp(0.0, 1.0);
                        return ui.Gradient.linear(
                          Offset(bounds.width, 0),
                          Offset.zero,
                          [
                            Colors.white,
                            Colors.white,
                            Colors.transparent,
                            Colors.transparent,
                          ],
                          [0.0, startCut, endCut, 1.0],
                        );
                      } else {
                        return ui.Gradient.linear(
                          Offset.zero,
                          Offset(bounds.width, 0),
                          [
                            Colors.white,
                            Colors.white,
                            Colors.transparent,
                            Colors.transparent,
                          ],
                          [0.0, progress, lead, 1.0],
                        );
                      }
                    },
                    child: Text(
                      line.text,
                      style: style.copyWith(color: color),
                      textAlign: textAlign,
                    ),
                  ),
              ],
            );
          },
        );
      },
    );
  }
}
