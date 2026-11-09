import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/presentation/desktop_theme.dart';

double _contrastRatio(Color foreground, Color background) {
  double linear(double channel) {
    return channel <= 0.04045
        ? channel / 12.92
        : pow((channel + 0.055) / 1.055, 2.4).toDouble();
  }

  double luminance(Color color) {
    return 0.2126 * linear(color.red / 255.0) +
        0.7152 * linear(color.green / 255.0) +
        0.0722 * linear(color.blue / 255.0);
  }

  final first = luminance(foreground);
  final second = luminance(background);
  return (max(first, second) + 0.05) / (min(first, second) + 0.05);
}

void main() {
  test('loads known modes and rejects corrupted stored preferences', () {
    expect(DesktopThemeMode.fromStored('dark'), DesktopThemeMode.dark);
    expect(DesktopThemeMode.fromStored('light'), DesktopThemeMode.light);
    expect(DesktopThemeMode.fromStored('system'), DesktopThemeMode.system);
    expect(DesktopThemeMode.fromStored('not-a-theme'), DesktopThemeMode.system);
    expect(DesktopThemeMode.fromStored(''), DesktopThemeMode.system);
  });

  test('dark mode defaults to the original desktop colors', () {
    expect(darkPalette.background, const Color(0xFF202020));
    expect(darkPalette.sidebarSurface, const Color(0xFF0D0D0F));
    expect(darkPalette.surface, const Color(0xFF0D0D0F));
    expect(darkPalette.chromeSurface, const Color(0xFF0D0D0F));
    expect(darkPalette.navAccent, const Color(0xFFFA586A));
    expect(darkPalette.foreground, Colors.white);
    expect(darkPalette.accent, Colors.white);
    expect(darkPalette.onAccent, Colors.black);
  });

  test('selected sidebar background is gray while icons and indicators stay rose', () {
    expect(darkPalette.navAccent, const Color(0xFFFA586A));
    expect(lightPalette.navAccent, const Color(0xFFCD3659));
    expect(darkPalette.navSelectedSurface, const Color(0xFF2D2D2D));
    expect(lightPalette.navSelectedSurface, const Color(0xFFDDDDDD));
    expect(darkPalette.sidebarSurface, const Color(0xFF0D0D0F));
    expect(lightPalette.sidebarSurface, const Color(0xFFE9ECF2));
    expect(darkPalette.navAccent != darkPalette.navSelectedSurface, isTrue);
    expect(lightPalette.navAccent != lightPalette.navSelectedSurface, isTrue);
  });

  test('pitch black makes dark backgrounds fully black', () {
    expect(pitchBlackPalette.background, Colors.black);
    expect(pitchBlackPalette.sidebarSurface, Colors.black);
    expect(pitchBlackPalette.surface, Colors.black);
    expect(pitchBlackPalette.chromeSurface, Colors.black);
    expect(pitchBlackPalette.navAccent, darkPalette.navAccent);
    expect(pitchBlackPalette.floatingPlayerSurface, darkPalette.floatingPlayerSurface);
    expect(_contrastRatio(pitchBlackPalette.secondary, pitchBlackPalette.sidebarSurface) >= 4.5, isTrue);
  });

  test('light palette has distinct surfaces and accessible body text', () {
    expect(darkPalette.background != lightPalette.background, isTrue);
    expect(lightPalette.background != lightPalette.surface, isTrue);
    expect(_contrastRatio(lightPalette.foreground, lightPalette.background) >= 7.0, isTrue);
    expect(_contrastRatio(lightPalette.secondary, lightPalette.background) >= 4.5, isTrue);
    expect(_contrastRatio(lightPalette.onAccent, lightPalette.accent) >= 7.0, isTrue);
    expect(_contrastRatio(lightPalette.foreground, lightPalette.floatingPlayerSurface) >= 7.0, isTrue);
    expect(_contrastRatio(lightPalette.secondary, lightPalette.floatingPlayerSurface) >= 4.5, isTrue);
    expect(lightPalette.background != lightPalette.floatingPlayerSurface, isTrue);
    expect(lightPalette.searchSurface != lightPalette.surfaceRaised, isTrue);
  });
}
