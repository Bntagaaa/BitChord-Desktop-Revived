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
  static const contentBottomReserve = 132.0;
  static const sidebarDuration = Duration(milliseconds: 180);
}

// The supplied five static font cuts identify themselves as "bitchord-sans"
// (regular/medium/semibold/bold/heavy, 400..800). Resolve the installed system
// family without redistributing font binaries of unverified licensing status.
// Desktop fallbacks are retained for machines without the custom family.
const bitChordDesktopFontFamily = 'bitchord-sans';
const bitChordDesktopFontFallbacks = <String>[
  'Inter',
  'Segoe UI',
  'Noto Sans',
  'Roboto',
];

ThemeData bitChordTheme(BitChordPalette palette, Brightness brightness) {
  // Use the reference visual metrics; real glyph width depends on the
  // installed bitchord-sans family and its registered physical font cuts.
  TextStyle role(double size, FontWeight weight, double tracking) => TextStyle(
    fontSize: size,
    fontWeight: weight,
    fontFamily: bitChordDesktopFontFamily,
    fontFamilyFallback: bitChordDesktopFontFallbacks,
    letterSpacing: tracking,
    color: palette['foreground'],
    // Kotlin's customized roles use fresh TextStyles, without inherited
    // Material line heights. Keep natural font metrics for those roles.
    height: ui.kTextHeightNone,
  );
  return ThemeData(
    brightness: brightness,
    useMaterial3: true,
    fontFamily: bitChordDesktopFontFamily,
    fontFamilyFallback: bitChordDesktopFontFallbacks,
    scaffoldBackgroundColor: palette['background'],
    colorScheme:
        ColorScheme.fromSeed(
          seedColor: palette['navAccent'],
          brightness: brightness,
        ).copyWith(
          primary: palette['navAccent'],
          surface: palette['surface'],
          onSurface: palette['foreground'],
        ),
    textTheme: TextTheme(
      displayLarge: role(34, FontWeight.w700, -.5),
      displayMedium: role(45, FontWeight.w400, 0).copyWith(height: 52 / 45),
      displaySmall: role(36, FontWeight.w400, 0).copyWith(height: 44 / 36),
      headlineLarge: role(30, FontWeight.w700, -.4),
      headlineMedium: role(22, FontWeight.w600, -.25),
      headlineSmall: role(24, FontWeight.w400, 0).copyWith(height: 32 / 24),
      titleLarge: role(20, FontWeight.w600, -.2),
      titleMedium: role(16, FontWeight.w500, .05),
      titleSmall: role(14, FontWeight.w400, .1).copyWith(height: 20 / 14),
      bodyLarge: role(16, FontWeight.w400, .2),
      bodyMedium: role(14, FontWeight.w400, .3),
      bodySmall: role(12, FontWeight.w400, .4).copyWith(height: 16 / 12),
      labelLarge: role(14, FontWeight.w400, .1).copyWith(height: 20 / 14),
      labelMedium: role(12, FontWeight.w500, .25),
      labelSmall: role(11, FontWeight.w500, .3),
    ),
    tooltipTheme: const TooltipThemeData(
      waitDuration: Duration(milliseconds: 400),
    ),
  );
}

class GlassSurface extends StatelessWidget {
  const GlassSurface({
    super.key,
    required this.palette,
    required this.reduceBlur,
    required this.child,
    this.tintAlpha = .55,
    this.blurRadius = 30,
    this.solidToken = 'floatingPlayerSurface',
  });
  final BitChordPalette palette;
  final bool reduceBlur;
  final Widget child;
  final double tintAlpha;
  final double blurRadius;
  final String solidToken;
  @override
  Widget build(BuildContext context) {
    final fill = DecoratedBox(
      decoration: BoxDecoration(
        // DesktopFrosted tints the blurred page with chromeSurface at 0.55;
        // barGlass is used only on the reference's no-Haze fallback path.
        color: reduceBlur
            ? palette[solidToken]
            : palette['chromeSurface'].withValues(alpha: tintAlpha),
        border: Border.all(color: palette['barEdge'], width: .5),
        borderRadius: BorderRadius.circular(16),
      ),
      child: child,
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: reduceBlur
          ? fill
          : BackdropFilter(
              filter: ui.ImageFilter.blur(
                sigmaX: blurRadius,
                sigmaY: blurRadius,
              ),
              child: fill,
            ),
    );
  }
}
