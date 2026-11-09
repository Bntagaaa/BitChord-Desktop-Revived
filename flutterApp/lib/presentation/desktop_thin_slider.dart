import 'package:flutter/material.dart';
import 'desktop_theme.dart';

/// Apple Music's scrubber, ported 1:1 from Kotlin [DesktopThinSlider.kt].
///
/// Features:
/// - Capsule-ended rounded progress bar without circular thumb knobs.
/// - Dynamic animated height expansion between [idleHeight] and [activeHeight]
///   when hovered or dragged.
/// - Generous invisible hit target of (activeHeight + 22.0) for smooth dragging.
class DesktopThinSlider extends StatefulWidget {
  final double value;
  final ValueChanged<double> onChanged;
  final VoidCallback? onChangeEnd;
  final double width;
  final double idleHeight;
  final double activeHeight;
  final DesktopThemePalette palette;

  const DesktopThinSlider({
    super.key,
    required this.value,
    required this.onChanged,
    this.onChangeEnd,
    this.width = 72.0,
    this.idleHeight = 4.0,
    this.activeHeight = 7.0,
    required this.palette,
  });

  @override
  State<DesktopThinSlider> createState() => _DesktopThinSliderState();
}

class _DesktopThinSliderState extends State<DesktopThinSlider>
    with SingleTickerProviderStateMixin {
  bool _isDragging = false;
  bool _isHovered = false;

  void _handleDrag(Offset localPosition, double totalWidth) {
    if (totalWidth <= 0) return;
    final clamped = (localPosition.dx / totalWidth).clamp(0.0, 1.0);
    widget.onChanged(clamped);
  }

  @override
  Widget build(BuildContext context) {
    final active = _isDragging || _isHovered;
    final currentHeight = active ? widget.activeHeight : widget.idleHeight;
    final hitTargetHeight = widget.activeHeight + 22.0;

    final inactiveColor = widget.palette.foreground.withValues(
      alpha: widget.palette.surface.r < 0.5 ? 0.26 : 0.30,
    );
    final activeColor = widget.palette.foreground.withValues(alpha: 0.92);

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onHorizontalDragStart: (details) {
          setState(() => _isDragging = true);
          _handleDrag(details.localPosition, widget.width);
        },
        onHorizontalDragUpdate: (details) {
          _handleDrag(details.localPosition, widget.width);
        },
        onHorizontalDragEnd: (_) {
          setState(() => _isDragging = false);
          widget.onChangeEnd?.call();
        },
        onTapDown: (details) {
          _handleDrag(details.localPosition, widget.width);
          widget.onChangeEnd?.call();
        },
        child: SizedBox(
          width: widget.width,
          height: hitTargetHeight,
          child: Center(
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 140),
              curve: Curves.easeOutCubic,
              height: currentHeight,
              width: widget.width,
              child: CustomPaint(
                painter: _ThinSliderPainter(
                  value: widget.value.clamp(0.0, 1.0),
                  activeColor: activeColor,
                  inactiveColor: inactiveColor,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ThinSliderPainter extends CustomPainter {
  final double value;
  final Color activeColor;
  final Color inactiveColor;

  _ThinSliderPainter({
    required this.value,
    required this.activeColor,
    required this.inactiveColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final radius = Radius.circular(size.height / 2.0);

    // Inactive track
    final inactivePaint = Paint()
      ..color = inactiveColor
      ..style = PaintingStyle.fill;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(0, 0, size.width, size.height),
        radius,
      ),
      inactivePaint,
    );

    // Active track
    if (value > 0.0) {
      final activePaint = Paint()
        ..color = activeColor
        ..style = PaintingStyle.fill;
      final filledWidth = (size.width * value).clamp(size.height, size.width);
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(0, 0, filledWidth, size.height),
          radius,
        ),
        activePaint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _ThinSliderPainter oldDelegate) {
    return oldDelegate.value != value ||
        oldDelegate.activeColor != activeColor ||
        oldDelegate.inactiveColor != inactiveColor;
  }
}
