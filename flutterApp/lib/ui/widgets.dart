import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'dart:io';
import 'dart:math';
import 'package:flutter_svg/flutter_svg.dart';
import 'theme.dart';

ImageProvider<Object> artworkProvider(String url) {
  final uri = Uri.tryParse(url);
  return uri?.scheme == 'file'
      ? FileImage(File.fromUri(uri!))
      : NetworkImage(url);
}

class SourceIcon extends StatelessWidget {
  const SourceIcon(this.name, {super.key, this.size = 22, this.color});
  final String name;
  final double size;
  final Color? color;
  @override
  Widget build(BuildContext context) => SvgPicture.asset(
    'assets/icons/$name.svg',
    width: size,
    height: size,
    colorFilter: ColorFilter.mode(
      color ?? paletteOf(context).foreground,
      BlendMode.srcIn,
    ),
  );
}

class Artwork extends StatelessWidget {
  const Artwork(this.url, {super.key, this.size, this.radius = 12});
  final String? url;
  final double? size;
  final double radius;
  @override
  Widget build(BuildContext context) {
    final pixelSize = size == null
        ? null
        : (size! * MediaQuery.devicePixelRatioOf(context)).ceil();
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: SizedBox(
        width: size,
        height: size,
        child: ColoredBox(
          color: paletteOf(context).surfaceRaised,
          child: url == null
              ? const Center(child: Icon(Icons.music_note_rounded, size: 28))
              : Image(
                  image: ResizeImage.resizeIfNeeded(
                    pixelSize,
                    null,
                    artworkProvider(url!),
                  ),
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => const Center(
                    child: Icon(Icons.music_note_rounded, size: 28),
                  ),
                ),
        ),
      ),
    );
  }
}

class TransportButton extends StatelessWidget {
  const TransportButton(
    this.icon,
    this.label,
    this.action, {
    super.key,
    this.size = 34,
    this.active = false,
  });
  final String icon;
  final String label;
  final VoidCallback? action;
  final double size;
  final bool active;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: size,
    height: size,
    child: IconButton(
      padding: EdgeInsets.zero,
      tooltip: label,
      onPressed: action,
      icon: SourceIcon(
        icon,
        size: size == 36 ? 23 : 20,
        color: action == null
            ? paletteOf(context).secondary.withValues(alpha: .4)
            : active
            ? paletteOf(context).foreground
            : paletteOf(context).secondary,
      ),
    ),
  );
}

enum ArtworkHoverMode {
  darkenOnly,
  darkenAndPlay,
}

class ArtworkHover extends StatefulWidget {
  const ArtworkHover({
    super.key,
    required this.child,
    this.mode = ArtworkHoverMode.darkenOnly,
    this.radius = 12.0,
    this.hovered,
  });

  final Widget child;
  final ArtworkHoverMode mode;
  final double radius;
  final bool? hovered;

  @override
  State<ArtworkHover> createState() => _ArtworkHoverState();
}

class _ArtworkHoverState extends State<ArtworkHover> {
  bool _localHovered = false;

  @override
  Widget build(BuildContext context) {
    final isHovered = widget.hovered ?? _localHovered;
    return MouseRegion(
      onEnter: (_) => setState(() => _localHovered = true),
      onExit: (_) => setState(() => _localHovered = false),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(widget.radius),
        child: Stack(
          fit: StackFit.passthrough,
          children: [
            widget.child,
            Positioned.fill(
              child: AnimatedOpacity(
                duration: const Duration(milliseconds: 170),
                opacity: isHovered ? 1.0 : 0.0,
                child: Container(
                  color: Colors.black.withValues(alpha: 0.38),
                  alignment: Alignment.center,
                  child: widget.mode == ArtworkHoverMode.darkenAndPlay
                      ? const Icon(
                          Icons.play_arrow_rounded,
                          color: Colors.white,
                          size: 26,
                        )
                      : null,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class ExplicitBadge extends StatelessWidget {
  const ExplicitBadge({
    super.key,
    this.color,
    this.size = 14.0,
  });

  final Color? color;
  final double size;

  @override
  Widget build(BuildContext context) {
    final effectiveColor =
        color ?? Theme.of(context).colorScheme.onSurfaceVariant;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: effectiveColor.withValues(alpha: 0.16),
        borderRadius: BorderRadius.circular(2.5),
        border: Border.all(
          color: effectiveColor.withValues(alpha: 0.65),
          width: 0.8,
        ),
      ),
      alignment: Alignment.center,
      child: Text(
        'E',
        style: TextStyle(
          color: effectiveColor,
          fontSize: size * 0.68,
          fontWeight: FontWeight.w800,
          height: 1.0,
        ),
      ),
    );
  }
}

class VideoDurationBadge extends StatelessWidget {
  const VideoDurationBadge({
    super.key,
    required this.durationText,
  });

  final String durationText;

  @override
  Widget build(BuildContext context) {
    if (durationText.trim().isEmpty) return const SizedBox.shrink();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.75),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        durationText,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 11,
          fontWeight: FontWeight.w600,
          height: 1.1,
        ),
      ),
    );
  }
}

class PlayingEqualizerBars extends StatefulWidget {
  const PlayingEqualizerBars({super.key});

  @override
  State<PlayingEqualizerBars> createState() => _PlayingEqualizerBarsState();
}

class _PlayingEqualizerBarsState extends State<PlayingEqualizerBars>
    with SingleTickerProviderStateMixin {
  late final AnimationController _anim;

  @override
  void initState() {
    super.initState();
    _anim = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1300),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _anim.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 24,
      height: 24,
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.52),
        borderRadius: BorderRadius.circular(6),
      ),
      alignment: Alignment.center,
      child: AnimatedBuilder(
        animation: _anim,
        builder: (context, _) {
          final t = _anim.value;
          final h1 = (0.38 +
                  0.50 * (0.5 + 0.5 * sin(t * 2 * pi * (1300 / 520))))
              .clamp(0.25, 1.0);
          final h2 = (0.78 -
                  0.45 *
                      (0.5 + 0.5 * sin((t + 0.3) * 2 * pi * (1300 / 650))))
              .clamp(0.25, 1.0);
          final h3 = (0.52 +
                  0.40 *
                      (0.5 + 0.5 * sin((t + 0.6) * 2 * pi * (1300 / 780))))
              .clamp(0.25, 1.0);
          return Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              _bar(h1 * 14.0),
              const SizedBox(width: 2),
              _bar(h2 * 14.0),
              const SizedBox(width: 2),
              _bar(h3 * 14.0),
            ],
          );
        },
      ),
    );
  }

  Widget _bar(double height) => Container(
        width: 3,
        height: height,
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(2),
        ),
      );
}

class DesktopScrollEdgeFade extends StatelessWidget {
  const DesktopScrollEdgeFade({
    super.key,
    required this.child,
    required this.controller,
    this.topFade = 34.0,
    this.bottomFade = 54.0,
  });

  final Widget child;
  final ScrollController controller;
  final double topFade;
  final double bottomFade;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        final hasClients = controller.hasClients;
        final canScrollBackward = hasClients && controller.offset > 0.0;
        final canScrollForward = hasClients &&
            controller.position.maxScrollExtent > 0 &&
            controller.offset < controller.position.maxScrollExtent;

        if (!canScrollBackward && !canScrollForward) {
          return child;
        }

        return ShaderMask(
          blendMode: BlendMode.dstIn,
          shaderCallback: (bounds) {
            final topPx = (canScrollBackward ? topFade : 0.0)
                .clamp(0.0, bounds.height / 3.0);
            final bottomPx = (canScrollForward ? bottomFade : 0.0)
                .clamp(0.0, bounds.height / 3.0);

            final topFraction = bounds.height > 0
                ? (topPx / bounds.height).clamp(0.0, 1.0)
                : 0.0;
            final bottomFraction = bounds.height > 0
                ? (1.0 - (bottomPx / bounds.height)).clamp(0.0, 1.0)
                : 1.0;

            if (canScrollBackward && canScrollForward) {
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
            } else if (canScrollBackward) {
              return LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: const [Colors.transparent, Colors.white],
                stops: [0.0, topFraction],
              ).createShader(bounds);
            } else {
              return LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: const [Colors.white, Colors.transparent],
                stops: [bottomFraction, 1.0],
              ).createShader(bounds);
            }
          },
          child: child,
        );
      },
    );
  }
}

class DesktopShelfRowChrome extends StatefulWidget {
  const DesktopShelfRowChrome({
    super.key,
    this.child,
    this.builder,
    this.controller,
    this.endInset = 0.0,
  }) : assert(child != null || builder != null, 'Provide child or builder');

  final Widget? child;
  final Widget Function(BuildContext context, ScrollController controller)?
      builder;
  final ScrollController? controller;
  final double endInset;

  @override
  State<DesktopShelfRowChrome> createState() => _DesktopShelfRowChromeState();
}

class _DesktopShelfRowChromeState extends State<DesktopShelfRowChrome> {
  ScrollController? _internalController;
  ScrollController get _effectiveController =>
      widget.controller ?? (_internalController ??= ScrollController());

  bool _hovered = false;
  bool _canScrollBackward = false;
  bool _canScrollForward = false;

  static const double _overlapPx = 120.0;
  static const double _wheelStep = 90.0;

  @override
  void initState() {
    super.initState();
    _effectiveController.addListener(_updateScrollMetrics);
    WidgetsBinding.instance.addPostFrameCallback((_) => _updateScrollMetrics());
  }

  @override
  void didUpdateWidget(DesktopShelfRowChrome oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.controller != oldWidget.controller) {
      oldWidget.controller?.removeListener(_updateScrollMetrics);
      _effectiveController.addListener(_updateScrollMetrics);
      WidgetsBinding.instance
          .addPostFrameCallback((_) => _updateScrollMetrics());
    }
  }

  @override
  void dispose() {
    _effectiveController.removeListener(_updateScrollMetrics);
    _internalController?.dispose();
    super.dispose();
  }

  void _updateScrollMetrics() {
    if (!mounted || !_effectiveController.hasClients) return;
    final pos = _effectiveController.position;
    final canBack = pos.pixels > 0.5;
    final canFwd =
        pos.maxScrollExtent > 0 && pos.pixels < pos.maxScrollExtent - 0.5;
    if (canBack != _canScrollBackward || canFwd != _canScrollForward) {
      setState(() {
        _canScrollBackward = canBack;
        _canScrollForward = canFwd;
      });
    }
  }

  void _page({required bool forward}) {
    if (!_effectiveController.hasClients) return;
    final pos = _effectiveController.position;
    final viewport = pos.viewportDimension;
    final distance =
        (viewport - widget.endInset - _overlapPx).clamp(_overlapPx, viewport);
    final target = (pos.pixels + (forward ? distance : -distance))
        .clamp(0.0, pos.maxScrollExtent);
    _effectiveController.animateTo(
      target,
      duration: const Duration(milliseconds: 320),
      curve: Curves.easeOutCubic,
    );
  }

  void _onPointerSignal(PointerSignalEvent event) {
    if (event is PointerScrollEvent && _effectiveController.hasClients) {
      final delta =
          event.scrollDelta.dy != 0 ? event.scrollDelta.dy : event.scrollDelta.dx;
      if (delta != 0) {
        final pos = _effectiveController.position;
        final target = (pos.pixels + (delta.sign * _wheelStep))
            .clamp(0.0, pos.maxScrollExtent);
        _effectiveController.jumpTo(target);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final content = widget.builder != null
        ? widget.builder!(context, _effectiveController)
        : widget.child!;

    return NotificationListener<ScrollNotification>(
      onNotification: (notification) {
        _updateScrollMetrics();
        return false;
      },
      child: MouseRegion(
        onEnter: (_) {
          _updateScrollMetrics();
          setState(() => _hovered = true);
        },
        onExit: (_) => setState(() => _hovered = false),
        child: Listener(
          onPointerSignal: _onPointerSignal,
          child: ScrollConfiguration(
            behavior: ScrollConfiguration.of(context).copyWith(
              dragDevices: {
                PointerDeviceKind.touch,
                PointerDeviceKind.mouse,
                PointerDeviceKind.trackpad,
                PointerDeviceKind.stylus,
              },
            ),
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                content,
                Positioned(
                  left: 6,
                  top: 0,
                  bottom: 0,
                  child: Center(
                    child: AnimatedOpacity(
                      opacity: (_hovered && _canScrollBackward) ? 1.0 : 0.0,
                      duration: const Duration(milliseconds: 170),
                      child: IgnorePointer(
                        ignoring: !(_hovered && _canScrollBackward),
                        child: _ShelfChevronButton(
                          icon: Icons.chevron_left_rounded,
                          tooltip: 'Scroll left',
                          onTap: () => _page(forward: false),
                        ),
                      ),
                    ),
                  ),
                ),
                Positioned(
                  right: 6 + widget.endInset,
                  top: 0,
                  bottom: 0,
                  child: Center(
                    child: AnimatedOpacity(
                      opacity: (_hovered && _canScrollForward) ? 1.0 : 0.0,
                      duration: const Duration(milliseconds: 170),
                      child: IgnorePointer(
                        ignoring: !(_hovered && _canScrollForward),
                        child: _ShelfChevronButton(
                          icon: Icons.chevron_right_rounded,
                          tooltip: 'Scroll right',
                          onTap: () => _page(forward: true),
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
    );
  }
}

class _ShelfChevronButton extends StatelessWidget {
  const _ShelfChevronButton({
    required this.icon,
    required this.tooltip,
    required this.onTap,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black.withValues(alpha: 0.55),
      shape: const CircleBorder(),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          width: 34,
          height: 34,
          child: Tooltip(
            message: tooltip,
            child: Icon(icon, color: Colors.white, size: 22),
          ),
        ),
      ),
    );
  }
}

class ShimmerBox extends StatefulWidget {
  const ShimmerBox({
    super.key,
    required this.width,
    required this.height,
    this.radius = 6.0,
  });

  final double width;
  final double height;
  final double radius;

  @override
  State<ShimmerBox> createState() => _ShimmerBoxState();
}

class _ShimmerBoxState extends State<ShimmerBox>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    )..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final baseColor = Theme.of(context).colorScheme.surfaceContainerHighest;
    final highlightColor = Theme.of(context)
        .colorScheme
        .onSurfaceVariant
        .withValues(alpha: 0.16);

    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        return ClipRRect(
          borderRadius: BorderRadius.circular(widget.radius),
          child: Container(
            width: widget.width,
            height: widget.height,
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment(-1.5 + _controller.value * 3.0, 0),
                end: Alignment(-0.5 + _controller.value * 3.0, 0),
                colors: [baseColor, highlightColor, baseColor],
              ),
            ),
          ),
        );
      },
    );
  }
}

class ShelfSkeleton extends StatelessWidget {
  const ShelfSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const ShimmerBox(width: 160, height: 18, radius: 4),
          const SizedBox(height: 14),
          Row(
            children: List.generate(
              3,
              (index) => Padding(
                padding: const EdgeInsets.only(right: 14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    ShimmerBox(width: 158, height: 158, radius: 12),
                    SizedBox(height: 10),
                    ShimmerBox(width: 120, height: 13, radius: 3),
                    SizedBox(height: 6),
                    ShimmerBox(width: 80, height: 11, radius: 3),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

