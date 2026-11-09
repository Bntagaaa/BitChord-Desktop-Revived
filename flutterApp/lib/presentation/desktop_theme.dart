import 'package:flutter/material.dart';

/// Follow the OS until the listener explicitly picks a theme.
enum DesktopThemeMode {
  system('System'),
  light('Light'),
  dark('Dark');

  final String label;
  const DesktopThemeMode(this.label);

  static DesktopThemeMode fromStored(String? value) {
    return DesktopThemeMode.values.firstWhere(
      (m) => m.name == value,
      orElse: () => DesktopThemeMode.system,
    );
  }
}

/// Semantic desktop tokens translated 1:1 from DesktopTheme.kt.
class DesktopThemePalette {
  final Color background;
  final Color surface;
  final Color surfaceRaised;
  final Color glass;
  final Color glassStrong;
  final Color barGlass;
  final Color barEdge;
  final Color accent;
  final Color onAccent;
  final Color foreground;
  final Color secondary;
  final Color divider;
  final Color destructive;
  final Color cardFill;
  final Color cardInsetFill;
  final Color cardEdge;
  final Color rowHover;
  final Color scrim;
  final Color searchSurface;
  final Color topPlayerSurface;
  final Color floatingPlayerSurface;
  final Color subtleFill;
  final Color stripedRow;
  final Color navAccent;
  final Color navSelectedSurface;
  final Color sidebarSurface;
  final Color chromeSurface;
  final Color lyricsSurface;
  final Color replaySurface;

  const DesktopThemePalette({
    required this.background,
    required this.surface,
    required this.surfaceRaised,
    required this.glass,
    required this.glassStrong,
    required this.barGlass,
    required this.barEdge,
    required this.accent,
    required this.onAccent,
    required this.foreground,
    required this.secondary,
    required this.divider,
    required this.destructive,
    required this.cardFill,
    required this.cardInsetFill,
    required this.cardEdge,
    required this.rowHover,
    required this.scrim,
    required this.searchSurface,
    required this.topPlayerSurface,
    required this.floatingPlayerSurface,
    required this.subtleFill,
    required this.stripedRow,
    required this.navAccent,
    required this.navSelectedSurface,
    required this.sidebarSurface,
    required this.chromeSurface,
    required this.lyricsSurface,
    required this.replaySurface,
  });

  DesktopThemePalette copyWith({
    Color? background,
    Color? surface,
    Color? surfaceRaised,
    Color? glass,
    Color? glassStrong,
    Color? barGlass,
    Color? barEdge,
    Color? accent,
    Color? onAccent,
    Color? foreground,
    Color? secondary,
    Color? divider,
    Color? destructive,
    Color? cardFill,
    Color? cardInsetFill,
    Color? cardEdge,
    Color? rowHover,
    Color? scrim,
    Color? searchSurface,
    Color? topPlayerSurface,
    Color? floatingPlayerSurface,
    Color? subtleFill,
    Color? stripedRow,
    Color? navAccent,
    Color? navSelectedSurface,
    Color? sidebarSurface,
    Color? chromeSurface,
    Color? lyricsSurface,
    Color? replaySurface,
  }) {
    return DesktopThemePalette(
      background: background ?? this.background,
      surface: surface ?? this.surface,
      surfaceRaised: surfaceRaised ?? this.surfaceRaised,
      glass: glass ?? this.glass,
      glassStrong: glassStrong ?? this.glassStrong,
      barGlass: barGlass ?? this.barGlass,
      barEdge: barEdge ?? this.barEdge,
      accent: accent ?? this.accent,
      onAccent: onAccent ?? this.onAccent,
      foreground: foreground ?? this.foreground,
      secondary: secondary ?? this.secondary,
      divider: divider ?? this.divider,
      destructive: destructive ?? this.destructive,
      cardFill: cardFill ?? this.cardFill,
      cardInsetFill: cardInsetFill ?? this.cardInsetFill,
      cardEdge: cardEdge ?? this.cardEdge,
      rowHover: rowHover ?? this.rowHover,
      scrim: scrim ?? this.scrim,
      searchSurface: searchSurface ?? this.searchSurface,
      topPlayerSurface: topPlayerSurface ?? this.topPlayerSurface,
      floatingPlayerSurface: floatingPlayerSurface ?? this.floatingPlayerSurface,
      subtleFill: subtleFill ?? this.subtleFill,
      stripedRow: stripedRow ?? this.stripedRow,
      navAccent: navAccent ?? this.navAccent,
      navSelectedSurface: navSelectedSurface ?? this.navSelectedSurface,
      sidebarSurface: sidebarSurface ?? this.sidebarSurface,
      chromeSurface: chromeSurface ?? this.chromeSurface,
      lyricsSurface: lyricsSurface ?? this.lyricsSurface,
      replaySurface: replaySurface ?? this.replaySurface,
    );
  }
}

const darkPalette = DesktopThemePalette(
  background: Color(0xFF202020),
  surface: Color(0xFF0D0D0F),
  surfaceRaised: Color(0xFF1C1C1E),
  glass: Color(0x661C1C1E),
  glassStrong: Color(0xCC0D0D0F),
  barGlass: Color(0xB324242A),
  barEdge: Color(0x1AFFFFFF),
  accent: Colors.white,
  onAccent: Colors.black,
  foreground: Colors.white,
  secondary: Color(0xFF8E8E93),
  divider: Color(0xFF2C2C2E),
  destructive: Color(0xFFFF453A),
  cardFill: Color(0xFA1A1A1D),
  cardInsetFill: Color(0x14FFFFFF),
  cardEdge: Color(0x1FFFFFFF),
  rowHover: Color(0x1AFFFFFF),
  scrim: Color(0x8C000000),
  searchSurface: Color(0xFF27272B),
  topPlayerSurface: Color(0xFF323236),
  floatingPlayerSurface: Color(0xFF202124),
  subtleFill: Color(0x14FFFFFF),
  stripedRow: Color(0x0AFFFFFF),
  navAccent: Color(0xFFFA586A),
  navSelectedSurface: Color(0xFF2D2D2D),
  sidebarSurface: Color(0xFF0D0D0F),
  chromeSurface: Color(0xFF0D0D0F),
  lyricsSurface: Color(0xFF202020),
  replaySurface: Color(0xFF202020),
);

/// Opt-in true black across both main content and navigation, without swapping their colors.
final pitchBlackPalette = darkPalette.copyWith(
  background: Colors.black,
  surface: Colors.black,
  sidebarSurface: Colors.black,
  chromeSurface: Colors.black,
);

/// Neutral, slightly warm daylight surfaces and charcoal controls, with BitChord's rose navigation
/// identity retained. Strong text/icon contrast also works when blur is disabled.
const lightPalette = DesktopThemePalette(
  background: Color(0xFFF7F8FB),
  surface: Color(0xFFFFFFFF),
  surfaceRaised: Color(0xFFEDEFF4),
  glass: Color(0xDDEFF1F5),
  glassStrong: Color(0xF4F7F8FC),
  barGlass: Color(0xE8E7EBF1),
  barEdge: Color(0x251D2636),
  accent: Color(0xFF1C1D21),
  onAccent: Colors.white,
  foreground: Color(0xFF1B1C20),
  secondary: Color(0xFF4D5767),
  divider: Color(0xFFD5DAE4),
  destructive: Color(0xFFBE3545),
  cardFill: Color(0xFFFFFFFF),
  cardInsetFill: Color(0xFFEEF1F6),
  cardEdge: Color(0x241D2636),
  rowHover: Color(0x1E25324D),
  scrim: Color(0x750D0E13),
  searchSurface: Color(0xFFE8ECF3),
  topPlayerSurface: Color(0xFFE4E8EF),
  floatingPlayerSurface: Color(0xFFE5E9F0),
  subtleFill: Color(0x111D2636),
  stripedRow: Color(0x0D1D2636),
  navAccent: Color(0xFFCD3659),
  navSelectedSurface: Color(0xFFDDDDDD),
  sidebarSurface: Color(0xFFE9ECF2),
  chromeSurface: Color(0xFFF0F2F6),
  lyricsSurface: Color(0xFFEDF0F6),
  replaySurface: Color(0xFFF5F6FA),
);

/// State controller for desktop theme.
class DesktopThemeController extends ChangeNotifier {
  DesktopThemeMode _mode = DesktopThemeMode.system;
  bool _pitchBlack = false;
  bool _osDark = true;

  DesktopThemeMode get mode => _mode;
  bool get pitchBlack => _pitchBlack;

  bool get isDark => _mode == DesktopThemeMode.system
      ? _osDark
      : _mode == DesktopThemeMode.dark;

  bool get isLight => !isDark;

  DesktopThemePalette get palette {
    if (isLight) return lightPalette;
    if (_mode == DesktopThemeMode.dark && _pitchBlack) return pitchBlackPalette;
    return darkPalette;
  }

  void setMode(DesktopThemeMode mode) {
    if (_mode == mode) return;
    _mode = mode;
    notifyListeners();
  }

  void setPitchBlack(bool enabled) {
    if (_pitchBlack == enabled) return;
    _pitchBlack = enabled;
    notifyListeners();
  }

  void updateOsDark(bool isDark) {
    if (_osDark == isDark) return;
    _osDark = isDark;
    if (_mode == DesktopThemeMode.system) {
      notifyListeners();
    }
  }
}
