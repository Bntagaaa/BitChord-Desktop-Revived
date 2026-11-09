import 'package:flutter/material.dart';

/// Values ported 1:1 from DesktopTheme.kt in BitChord 1.9.2.
@immutable
class DesktopPalette extends ThemeExtension<DesktopPalette> {
  const DesktopPalette({
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

  // Backwards compatible aliases for existing widgets
  Color get raised => surfaceRaised;
  Color get selected => navSelectedSurface;
  Color get sidebar => sidebarSurface;
  Color get player => floatingPlayerSurface;
  Color get edge => barEdge;

  static const dark = DesktopPalette(
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

  static const pitchBlack = DesktopPalette(
    background: Colors.black,
    surface: Colors.black,
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
    sidebarSurface: Colors.black,
    chromeSurface: Colors.black,
    lyricsSurface: Color(0xFF202020),
    replaySurface: Color(0xFF202020),
  );

  static const light = DesktopPalette(
    background: Color(0xFFF7F8FB),
    surface: Colors.white,
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
    cardFill: Colors.white,
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

  static DesktopPalette of(BuildContext context) =>
      Theme.of(context).extension<DesktopPalette>() ?? dark;

  @override
  DesktopPalette copyWith({
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
  }) => DesktopPalette(
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
    floatingPlayerSurface:
        floatingPlayerSurface ?? this.floatingPlayerSurface,
    subtleFill: subtleFill ?? this.subtleFill,
    stripedRow: stripedRow ?? this.stripedRow,
    navAccent: navAccent ?? this.navAccent,
    navSelectedSurface: navSelectedSurface ?? this.navSelectedSurface,
    sidebarSurface: sidebarSurface ?? this.sidebarSurface,
    chromeSurface: chromeSurface ?? this.chromeSurface,
    lyricsSurface: lyricsSurface ?? this.lyricsSurface,
    replaySurface: replaySurface ?? this.replaySurface,
  );

  @override
  DesktopPalette lerp(covariant DesktopPalette? other, double t) {
    if (other == null) return this;
    Color c(Color a, Color b) => Color.lerp(a, b, t)!;
    return DesktopPalette(
      background: c(background, other.background),
      surface: c(surface, other.surface),
      surfaceRaised: c(surfaceRaised, other.surfaceRaised),
      glass: c(glass, other.glass),
      glassStrong: c(glassStrong, other.glassStrong),
      barGlass: c(barGlass, other.barGlass),
      barEdge: c(barEdge, other.barEdge),
      accent: c(accent, other.accent),
      onAccent: c(onAccent, other.onAccent),
      foreground: c(foreground, other.foreground),
      secondary: c(secondary, other.secondary),
      divider: c(divider, other.divider),
      destructive: c(destructive, other.destructive),
      cardFill: c(cardFill, other.cardFill),
      cardInsetFill: c(cardInsetFill, other.cardInsetFill),
      cardEdge: c(cardEdge, other.cardEdge),
      rowHover: c(rowHover, other.rowHover),
      scrim: c(scrim, other.scrim),
      searchSurface: c(searchSurface, other.searchSurface),
      topPlayerSurface: c(topPlayerSurface, other.topPlayerSurface),
      floatingPlayerSurface:
          c(floatingPlayerSurface, other.floatingPlayerSurface),
      subtleFill: c(subtleFill, other.subtleFill),
      stripedRow: c(stripedRow, other.stripedRow),
      navAccent: c(navAccent, other.navAccent),
      navSelectedSurface: c(navSelectedSurface, other.navSelectedSurface),
      sidebarSurface: c(sidebarSurface, other.sidebarSurface),
      chromeSurface: c(chromeSurface, other.chromeSurface),
      lyricsSurface: c(lyricsSurface, other.lyricsSurface),
      replaySurface: c(replaySurface, other.replaySurface),
    );
  }
}

ThemeData buildDesktopTheme({
  required Brightness brightness,
  bool pitchBlack = false,
  bool legacyFont = false,
}) {
  final DesktopPalette p;
  if (brightness == Brightness.dark) {
    p = pitchBlack ? DesktopPalette.pitchBlack : DesktopPalette.dark;
  } else {
    p = DesktopPalette.light;
  }

  final font = legacyFont ? 'SFProDisplayLegacy' : 'SFProDisplay';
  TextStyle style(
    double size,
    FontWeight weight, [
    double? tracking,
    double height = 1.2,
  ]) => TextStyle(
    fontFamily: font,
    fontSize: size,
    fontWeight: weight,
    color: p.foreground,
    letterSpacing: tracking,
    height: height,
  );

  final scheme =
      ColorScheme.fromSeed(
        seedColor: p.navAccent,
        brightness: brightness,
      ).copyWith(
        primary: p.foreground,
        onPrimary: p.background,
        surface: p.surface,
        onSurface: p.foreground,
        outline: p.divider,
        secondary: p.navAccent,
      );

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    fontFamily: font,
    scaffoldBackgroundColor: p.background,
    canvasColor: p.background,
    dividerColor: p.divider,
    visualDensity: VisualDensity.compact,
    extensions: <ThemeExtension<dynamic>>[p],
    // Exact typographic roles matched to BitChord 1.9.2 bitChordTypography
    textTheme: TextTheme(
      displayLarge: style(34, FontWeight.w800, legacyFont ? -0.8 : -0.5),
      headlineLarge: style(30, FontWeight.w800, legacyFont ? -0.7 : -0.4),
      headlineMedium: style(22, FontWeight.w700, legacyFont ? -0.4 : -0.25),
      titleLarge: style(20, FontWeight.w700, legacyFont ? -0.3 : -0.2),
      titleMedium: style(16, FontWeight.w600, legacyFont ? -0.2 : 0.05),
      titleSmall: style(14, FontWeight.w600),
      bodyLarge: style(16, FontWeight.w400, legacyFont ? null : 0.2),
      bodyMedium: style(14, FontWeight.w400, legacyFont ? null : 0.3),
      bodySmall: style(12, FontWeight.w400),
      labelLarge: style(14, FontWeight.w600),
      labelMedium: style(12, FontWeight.w600, legacyFont ? null : 0.25),
      labelSmall: style(11, FontWeight.w600, legacyFont ? null : 0.3),
    ),
    iconTheme: IconThemeData(color: p.foreground, size: 20),
    splashFactory: NoSplash.splashFactory,
    tooltipTheme: TooltipThemeData(
      waitDuration: const Duration(milliseconds: 550),
      decoration: BoxDecoration(
        color: p.surfaceRaised,
        borderRadius: BorderRadius.circular(7),
        border: Border.all(color: p.barEdge),
      ),
      textStyle: style(12, FontWeight.w400),
    ),
    scrollbarTheme: ScrollbarThemeData(
      thickness: const WidgetStatePropertyAll(5),
      radius: const Radius.circular(8),
      thumbColor: WidgetStatePropertyAll(p.secondary.withAlpha(90)),
    ),
    sliderTheme: SliderThemeData(
      trackHeight: 3,
      thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 5),
      overlayShape: const RoundSliderOverlayShape(overlayRadius: 10),
      activeTrackColor: p.foreground,
      inactiveTrackColor: p.foreground.withAlpha(45),
      thumbColor: p.foreground,
    ),
    inputDecorationTheme: InputDecorationTheme(
      isDense: true,
      filled: true,
      fillColor: p.surfaceRaised,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide.none,
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: p.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: BorderSide(color: p.barEdge),
      ),
    ),
    dividerTheme: DividerThemeData(color: p.divider, thickness: .5, space: 1),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: p.surfaceRaised,
      contentTextStyle: style(13, FontWeight.w400),
      behavior: SnackBarBehavior.floating,
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(foregroundColor: p.foreground),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: p.foreground,
        foregroundColor: p.background,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
  );
}
