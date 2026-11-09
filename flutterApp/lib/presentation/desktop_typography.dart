import 'package:flutter/material.dart';

/// Font modes supported by BitChord Desktop (translated from DesktopFontSettings.kt).
enum DesktopFontMode {
  defaultMode('Default'),
  legacy('Legacy');

  final String label;
  const DesktopFontMode(this.label);
}

/// Builds the BitChord typography scale translated 1:1 from BitChordTypography.kt.
TextTheme buildBitChordTypography({
  required Color color,
  DesktopFontMode mode = DesktopFontMode.defaultMode,
}) {
  final isLegacy = mode == DesktopFontMode.legacy;
  final fontFamily = isLegacy ? 'SFProDisplayLegacy' : 'SFProDisplay';

  double tracking(double modern, double original) => isLegacy ? original : modern;
  double? bodyTracking(double modern) => isLegacy ? null : modern;

  TextStyle baseStyle({
    required FontWeight fontWeight,
    required double fontSize,
    double? letterSpacing,
  }) {
    return TextStyle(
      fontFamily: fontFamily,
      fontWeight: fontWeight,
      fontSize: fontSize,
      letterSpacing: letterSpacing,
      color: color,
    );
  }

  return TextTheme(
    displayLarge: baseStyle(
      fontWeight: FontWeight.w800,
      fontSize: 34,
      letterSpacing: tracking(-0.5, -0.8),
    ),
    headlineLarge: baseStyle(
      fontWeight: FontWeight.w800,
      fontSize: 30,
      letterSpacing: tracking(-0.4, -0.7),
    ),
    headlineMedium: baseStyle(
      fontWeight: FontWeight.w700,
      fontSize: 22,
      letterSpacing: tracking(-0.25, -0.4),
    ),
    titleLarge: baseStyle(
      fontWeight: FontWeight.w700,
      fontSize: 20,
      letterSpacing: tracking(-0.2, -0.3),
    ),
    titleMedium: baseStyle(
      fontWeight: FontWeight.w600,
      fontSize: 16,
      letterSpacing: tracking(0.05, -0.2),
    ),
    bodyLarge: baseStyle(
      fontWeight: FontWeight.w400,
      fontSize: 16,
      letterSpacing: bodyTracking(0.2),
    ),
    bodyMedium: baseStyle(
      fontWeight: FontWeight.w400,
      fontSize: 14,
      letterSpacing: bodyTracking(0.3),
    ),
    labelMedium: baseStyle(
      fontWeight: FontWeight.w600,
      fontSize: 12,
      letterSpacing: bodyTracking(0.25),
    ),
    labelSmall: baseStyle(
      fontWeight: FontWeight.w600,
      fontSize: 11,
      letterSpacing: bodyTracking(0.3),
    ),
  );
}

/// Static typography accessors matching BitChord design tokens.
abstract class DesktopTypography {
  static TextStyle displayLarge(Color color) => TextStyle(
        fontFamily: 'SFProDisplay',
        fontWeight: FontWeight.w800,
        fontSize: 34,
        letterSpacing: -0.5,
        color: color,
      );

  static TextStyle headlineLarge(Color color) => TextStyle(
        fontFamily: 'SFProDisplay',
        fontWeight: FontWeight.w800,
        fontSize: 30,
        letterSpacing: -0.4,
        color: color,
      );

  static TextStyle headlineMedium(Color color) => TextStyle(
        fontFamily: 'SFProDisplay',
        fontWeight: FontWeight.w700,
        fontSize: 22,
        letterSpacing: -0.25,
        color: color,
      );

  static TextStyle titleLarge(Color color) => TextStyle(
        fontFamily: 'SFProDisplay',
        fontWeight: FontWeight.w700,
        fontSize: 20,
        letterSpacing: -0.2,
        color: color,
      );

  static TextStyle titleMedium(Color color) => TextStyle(
        fontFamily: 'SFProDisplay',
        fontWeight: FontWeight.w600,
        fontSize: 16,
        letterSpacing: 0.05,
        color: color,
      );

  static TextStyle bodyLarge(Color color) => TextStyle(
        fontFamily: 'SFProDisplay',
        fontWeight: FontWeight.w400,
        fontSize: 16,
        letterSpacing: 0.2,
        color: color,
      );

  static TextStyle bodyMedium(Color color) => TextStyle(
        fontFamily: 'SFProDisplay',
        fontWeight: FontWeight.w400,
        fontSize: 14,
        letterSpacing: 0.3,
        color: color,
      );

  static TextStyle bodySmall(Color color) => TextStyle(
        fontFamily: 'SFProDisplay',
        fontWeight: FontWeight.w400,
        fontSize: 12,
        letterSpacing: 0.25,
        color: color,
      );

  static TextStyle caption(Color color) => TextStyle(
        fontFamily: 'SFProDisplay',
        fontWeight: FontWeight.w500,
        fontSize: 11,
        letterSpacing: 0.3,
        color: color,
      );
}
