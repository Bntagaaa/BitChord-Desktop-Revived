import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'palette.dart';

abstract final class ShellMetrics {
  static const initialWidth = 1220.0;
  static const initialHeight = 780.0;
  static const minimumWidth = 900.0;
  static const minimumHeight = 600.0;
  static const sidebarWidth = 220.0;
  static const railWidth = 72.0;
  static const railBreakpoint = 980.0;
  static const dockWidth = 760.0;
  static const dockHeight = 58.0;
  static const pageGutter = 16.0;
  static const sidebarDuration = Duration(milliseconds: 180);
}

ThemeData bitChordTheme(BitChordPalette palette, Brightness brightness) {
  // SF Pro is not redistributed until its license is established. Default
  // logical weights below use the reference DEFAULT physical-cut remapping.
  TextStyle role(double size, FontWeight weight, double tracking) => TextStyle(
    fontSize: size, fontWeight: weight, letterSpacing: tracking,
    color: palette['foreground']);
  return ThemeData(brightness: brightness, useMaterial3: true,
    scaffoldBackgroundColor: palette['background'],
    colorScheme: ColorScheme.fromSeed(seedColor: palette['navAccent'], brightness: brightness)
      .copyWith(primary: palette['navAccent'], surface: palette['surface'], onSurface: palette['foreground']),
    textTheme: TextTheme(
      displayLarge: role(34, FontWeight.w700, -.5),
      headlineLarge: role(30, FontWeight.w700, -.4),
      headlineMedium: role(22, FontWeight.w600, -.25),
      titleLarge: role(20, FontWeight.w600, -.2),
      titleMedium: role(16, FontWeight.w500, .05),
      bodyLarge: role(16, FontWeight.w400, .2),
      bodyMedium: role(14, FontWeight.w400, .3),
      labelMedium: role(12, FontWeight.w500, .25),
      labelSmall: role(11, FontWeight.w500, .3)),
    tooltipTheme: const TooltipThemeData(waitDuration: Duration(milliseconds: 400)));
}

class GlassSurface extends StatelessWidget {
  const GlassSurface({super.key, required this.palette, required this.reduceBlur, required this.child});
  final BitChordPalette palette;
  final bool reduceBlur;
  final Widget child;
  @override
  Widget build(BuildContext context) => ClipRRect(borderRadius: BorderRadius.circular(16),
    child: BackdropFilter(filter: ui.ImageFilter.blur(sigmaX: reduceBlur ? 0 : 20, sigmaY: reduceBlur ? 0 : 20),
      child: DecoratedBox(decoration: BoxDecoration(
        color: palette[reduceBlur ? 'floatingPlayerSurface' : 'barGlass'],
        border: Border.all(color: palette['barEdge'], width: .5), borderRadius: BorderRadius.circular(16)), child: child)));
}
