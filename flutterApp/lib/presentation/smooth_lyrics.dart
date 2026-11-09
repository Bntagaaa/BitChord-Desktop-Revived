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

const double kBrowsingAlpha = 0.8;
const double kInactiveScale = 0.98;
const double kPressedScale = 0.96;

/// High-fidelity Apple Music-style smooth lyric panel ported from 1.9.2
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
    with WidgetsBindingObserver {
  final ScrollController _scroll = ScrollController();
  List<GlobalKey> _lineKeys = [];
  LyricsDocument? _doc;
  int _activeLine = -1;
  DateTime _manualBrowsingUntil = DateTime.fromMillisecondsSinceEpoch(0);
  bool _isWindowFocused = true;

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
    WidgetsBinding.instance.addPostFrameCallback(
      (_) => _scrollToActive(jump: true),
    );
  }

  int _resolveActiveLine() {
    final offsetMs = widget.app.setting<num>('lyrics_offset_ms', 0).round();
    final adjustedPos =
        widget.app.playback.position + Duration(milliseconds: offsetMs);
    return _doc?.activeIndex(adjustedPos) ?? -1;
  }

  void _onPositionTick() {
    if (!mounted) return;
    final next = _resolveActiveLine();
    if (next != _activeLine) {
      setState(() => _activeLine = next);
      if (_isWindowFocused ||
          widget.app.setting('animate_lyrics_while_unfocused', false)) {
        WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToActive());
      }
    }
  }

  void _scrollToActive({bool jump = false}) {
    if (!mounted ||
        _activeLine < 0 ||
        _activeLine >= _lineKeys.length ||
        DateTime.now().isBefore(_manualBrowsingUntil)) {
      return;
    }
    final targetContext = _lineKeys[_activeLine].currentContext;
    if (targetContext == null) return;

    Scrollable.ensureVisible(
      targetContext,
      alignment: widget.centerFocus ? 0.38 : 0.25,
      duration: jump || widget.app.setting('reduce_animation', false)
          ? Duration.zero
          : kLyricSettleDuration,
      curve: kLyricEasing,
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _isWindowFocused = state == AppLifecycleState.resumed;
    if (_isWindowFocused) _scrollToActive(jump: true);
  }

  @override
  void dispose() {
    widget.app.playback.removeListener(_onPositionTick);
    WidgetsBinding.instance.removeObserver(this);
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

    return LayoutBuilder(
      builder: (context, constraints) =>
          NotificationListener<ScrollNotification>(
            onNotification: (notice) {
              if (notice is ScrollStartNotification &&
                  notice.dragDetails != null) {
                _manualBrowsingUntil = DateTime.now().add(
                  const Duration(seconds: 5),
                );
              }
              if (notice is UserScrollNotification &&
                  notice.direction != ScrollDirection.idle) {
                _manualBrowsingUntil = DateTime.now().add(
                  const Duration(seconds: 5),
                );
              }
              return false;
            },
            child: Scrollbar(
              controller: _scroll,
              child: SingleChildScrollView(
                controller: _scroll,
                padding: EdgeInsets.fromLTRB(
                  widget.immersive ? 20 : 28,
                  constraints.maxHeight * 0.32,
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
                        index: i,
                        activeLine: _activeLine,
                        isSynced: _doc!.isSynced,
                        docOffset: _doc!.offset,
                        fontSize: baseFontSize,
                        app: widget.app,
                        immersive: widget.immersive,
                        isLight: isLight,
                        reduceBlur: reduceBlur,
                        onSeek: (target) {
                          _manualBrowsingUntil =
                              DateTime.fromMillisecondsSinceEpoch(0);
                          widget.app.playback.seek(target);
                        },
                      ),
                    const SizedBox(height: 32),
                    Text(
                      _doc!.source.isNotEmpty
                          ? 'Lyrics by ${_doc!.source}'
                          : '',
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
    );
  }
}

class _SmoothLyricRow extends StatefulWidget {
  const _SmoothLyricRow({
    super.key,
    required this.line,
    required this.index,
    required this.activeLine,
    required this.isSynced,
    required this.docOffset,
    required this.fontSize,
    required this.app,
    required this.immersive,
    required this.isLight,
    required this.reduceBlur,
    required this.onSeek,
  });

  final LyricLine line;
  final int index;
  final int activeLine;
  final bool isSynced;
  final Duration docOffset;
  final double fontSize;
  final AppController app;
  final bool immersive;
  final bool isLight;
  final bool reduceBlur;
  final ValueChanged<Duration> onSeek;

  @override
  State<_SmoothLyricRow> createState() => _SmoothLyricRowState();
}

class _SmoothLyricRowState extends State<_SmoothLyricRow> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    final p = DesktopPalette.of(context);
    final isActive = widget.index == widget.activeLine;
    final distance = (widget.index - widget.activeLine).abs();
    final step = distance.clamp(0, kLineFalloffAlpha.length - 1);

    final double targetAlpha;
    if (!widget.isSynced) {
      targetAlpha = 0.95;
    } else if (isActive) {
      targetAlpha = 1.0;
    } else {
      final baseFalloff = kLineFalloffAlpha[step];
      targetAlpha = widget.isLight ? math.max(baseFalloff, 0.72) : baseFalloff;
    }

    final double blurRadius;
    if (!widget.isSynced || widget.reduceBlur || isActive) {
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
        : (widget.isLight ? const Color(0xFF1B2230) : p.foreground);

    final isDuetEnd = widget.line.alignment == LyricAlignment.end;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: GestureDetector(
        onTapDown: (_) => setState(() => _pressed = true),
        onTapCancel: () => setState(() => _pressed = false),
        onTapUp: (_) => setState(() => _pressed = false),
        onTap: widget.isSynced
            ? () {
                final offsetMs = widget.app
                    .setting<num>('lyrics_offset_ms', 0)
                    .round();
                final target =
                    widget.line.start -
                    widget.docOffset -
                    Duration(milliseconds: offsetMs);
                widget.onSeek(target);
              }
            : null,
        child: TweenAnimationBuilder<double>(
          tween: Tween<double>(begin: targetScale, end: targetScale),
          duration: _pressed
              ? const Duration(milliseconds: 120)
              : kLyricSettleDuration,
          curve: kLyricEasing,
          builder: (context, scale, child) => Transform.scale(
            scale: scale,
            alignment: isDuetEnd ? Alignment.centerRight : Alignment.centerLeft,
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
                    child: _buildLineContent(textColor, isActive, isDuetEnd),
                  )
                : _buildLineContent(textColor, isActive, isDuetEnd),
          ),
        ),
      ),
    );
  }

  Widget _buildLineContent(Color color, bool isActive, bool isDuetEnd) {
    final textAlign = isDuetEnd ? TextAlign.end : TextAlign.start;
    final crossAlign = isDuetEnd
        ? CrossAxisAlignment.end
        : CrossAxisAlignment.start;

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
        if (isActive && widget.line.isWordSynced)
          _SweptLyricText(
            app: widget.app,
            line: widget.line,
            docOffset: widget.docOffset,
            style: lineStyle,
            color: color,
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

/// Swept highlight text using Apple Music's feathered wavefront algorithm ported from sweepTo
class _SweptLyricText extends StatelessWidget {
  const _SweptLyricText({
    required this.app,
    required this.line,
    required this.docOffset,
    required this.style,
    required this.color,
    required this.textAlign,
  });

  final AppController app;
  final LyricLine line;
  final Duration docOffset;
  final TextStyle style;
  final Color color;
  final TextAlign textAlign;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: app.playback,
      builder: (context, _) {
        final offsetMs = app.setting<num>('lyrics_offset_ms', 0).round();
        final currentPos =
            app.playback.position +
            docOffset +
            Duration(milliseconds: offsetMs);

        return Stack(
          children: [
            // 1. Unsung base text (rendered at kUnsungAlpha = 0.45)
            Text(
              line.text,
              style: style.copyWith(
                color: color.withAlpha((255 * kUnsungAlpha).round()),
              ),
              textAlign: textAlign,
            ),
            // 2. Sung portion with smooth feathered leading edge
            ShaderMask(
              blendMode: BlendMode.dstIn,
              shaderCallback: (bounds) {
                final lineEnd = line.end ??
                    (line.words.isNotEmpty
                        ? line.words.last.end
                        : line.start + const Duration(seconds: 4));
                final totalSpan = (lineEnd - line.start).inMilliseconds;
                final elapsed = (currentPos - line.start).inMilliseconds;
                final progress = totalSpan <= 0
                    ? (currentPos >= line.start ? 1.0 : 0.0)
                    : (elapsed / totalSpan).clamp(0.0, 1.0);

                // Feather wavefront runs ahead by kWipeFeather px to prevent abrupt syllable popping
                final featherFraction = bounds.width > 0
                    ? (kWipeFeather / bounds.width).clamp(0.02, 0.25)
                    : 0.08;
                final lead = (progress + featherFraction).clamp(0.0, 1.0);

                if (textAlign == TextAlign.end) {
                  // RTL / Duet End: sweep fills right-to-left
                  final startCut = (1.0 - lead).clamp(0.0, 1.0);
                  final endCut = (1.0 - progress).clamp(0.0, 1.0);
                  return ui.Gradient.linear(
                    Offset(bounds.width, 0),
                    Offset.zero,
                    [
                      Colors.white,
                      Colors.white,
                      Colors.white.withAlpha(0),
                      Colors.white.withAlpha(0),
                    ],
                    [0.0, endCut, startCut, 1.0],
                  );
                } else {
                  // LTR: sweep fills left-to-right
                  return ui.Gradient.linear(
                    Offset.zero,
                    Offset(bounds.width, 0),
                    [
                      Colors.white,
                      Colors.white,
                      Colors.white.withAlpha(0),
                      Colors.white.withAlpha(0),
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
  }
}
