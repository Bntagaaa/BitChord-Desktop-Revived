import 'package:flutter/material.dart';

/// Values ported from DesktopTheme.kt, not sampled from compressed screenshots.
@immutable
class DesktopPalette extends ThemeExtension<DesktopPalette> {
  const DesktopPalette({
    required this.background,
    required this.surface,
    required this.raised,
    required this.foreground,
    required this.secondary,
    required this.divider,
    required this.navAccent,
    required this.selected,
    required this.sidebar,
    required this.glass,
    required this.player,
    required this.rowHover,
    required this.stripedRow,
    required this.edge,
  });

  final Color background, surface, raised, foreground, secondary, divider;
  final Color navAccent,
      selected,
      sidebar,
      glass,
      player,
      rowHover,
      stripedRow,
      edge;

  static const dark = DesktopPalette(
    background: Color(0xFF202020),
    surface: Color(0xFF0D0D0F),
    raised: Color(0xFF1C1C1E),
    foreground: Colors.white,
    secondary: Color(0xFF8E8E93),
    divider: Color(0xFF2C2C2E),
    navAccent: Color(0xFFFA586A),
    selected: Color(0xFF2D2D2D),
    sidebar: Color(0xFF0D0D0F),
    glass: Color(0xB31C1C1E),
    player: Color(0xFF202124),
    rowHover: Color(0x1AFFFFFF),
    stripedRow: Color(0x0AFFFFFF),
    edge: Color(0x1AFFFFFF),
  );
  static const light = DesktopPalette(
    background: Color(0xFFF7F8FB),
    surface: Colors.white,
    raised: Color(0xFFEDEFF4),
    foreground: Color(0xFF1B1C20),
    secondary: Color(0xFF4D5767),
    divider: Color(0xFFD5DAE4),
    navAccent: Color(0xFFCD3659),
    selected: Color(0xFFDDDDDD),
    sidebar: Color(0xFFE9ECF2),
    glass: Color(0xDDEFF1F5),
    player: Color(0xFFE5E9F0),
    rowHover: Color(0x1E25324D),
    stripedRow: Color(0x0D1D2636),
    edge: Color(0x251D2636),
  );

  static DesktopPalette of(BuildContext context) =>
      Theme.of(context).extension<DesktopPalette>() ?? dark;

  @override
  DesktopPalette copyWith({
    Color? background,
    Color? surface,
    Color? raised,
    Color? foreground,
    Color? secondary,
    Color? divider,
    Color? navAccent,
    Color? selected,
    Color? sidebar,
    Color? glass,
    Color? player,
    Color? rowHover,
    Color? stripedRow,
    Color? edge,
  }) => DesktopPalette(
    background: background ?? this.background,
    surface: surface ?? this.surface,
    raised: raised ?? this.raised,
    foreground: foreground ?? this.foreground,
    secondary: secondary ?? this.secondary,
    divider: divider ?? this.divider,
    navAccent: navAccent ?? this.navAccent,
    selected: selected ?? this.selected,
    sidebar: sidebar ?? this.sidebar,
    glass: glass ?? this.glass,
    player: player ?? this.player,
    rowHover: rowHover ?? this.rowHover,
    stripedRow: stripedRow ?? this.stripedRow,
    edge: edge ?? this.edge,
  );

  @override
  DesktopPalette lerp(covariant DesktopPalette? other, double t) {
    if (other == null) return this;
    Color c(Color a, Color b) => Color.lerp(a, b, t)!;
    return DesktopPalette(
      background: c(background, other.background),
      surface: c(surface, other.surface),
      raised: c(raised, other.raised),
      foreground: c(foreground, other.foreground),
      secondary: c(secondary, other.secondary),
      divider: c(divider, other.divider),
      navAccent: c(navAccent, other.navAccent),
      selected: c(selected, other.selected),
      sidebar: c(sidebar, other.sidebar),
      glass: c(glass, other.glass),
      player: c(player, other.player),
      rowHover: c(rowHover, other.rowHover),
      stripedRow: c(stripedRow, other.stripedRow),
      edge: c(edge, other.edge),
    );
  }
}

ThemeData buildDesktopTheme({
  required Brightness brightness,
  bool pitchBlack = false,
  bool legacyFont = false,
}) {
  final base = brightness == Brightness.dark
      ? DesktopPalette.dark
      : DesktopPalette.light;
  final p = brightness == Brightness.dark && pitchBlack
      ? base.copyWith(
          background: Colors.black,
          surface: Colors.black,
          sidebar: Colors.black,
        )
      : base;
  final font = legacyFont ? 'SFProDisplayLegacy' : 'SFProDisplay';
  TextStyle style(double size, FontWeight weight, [double? tracking]) =>
      TextStyle(
        fontFamily: font,
        fontSize: size,
        fontWeight: weight,
        color: p.foreground,
        letterSpacing: tracking,
        height: 1.2,
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
    textTheme: TextTheme(
      displayLarge: style(34, FontWeight.w800, legacyFont ? -.8 : -.5),
      headlineLarge: style(30, FontWeight.w800, legacyFont ? -.7 : -.4),
      headlineMedium: style(22, FontWeight.w700, legacyFont ? -.4 : -.25),
      titleLarge: style(20, FontWeight.w700, legacyFont ? -.3 : -.2),
      titleMedium: style(16, FontWeight.w600, legacyFont ? -.2 : .05),
      titleSmall: style(14, FontWeight.w600),
      bodyLarge: style(16, FontWeight.w400, legacyFont ? null : .2),
      bodyMedium: style(14, FontWeight.w400, legacyFont ? null : .3),
      bodySmall: style(12, FontWeight.w400),
      labelLarge: style(14, FontWeight.w600),
      labelMedium: style(12, FontWeight.w600, legacyFont ? null : .25),
      labelSmall: style(11, FontWeight.w600, legacyFont ? null : .3),
    ),
    iconTheme: IconThemeData(color: p.foreground, size: 20),
    splashFactory: NoSplash.splashFactory,
    tooltipTheme: TooltipThemeData(
      waitDuration: const Duration(milliseconds: 550),
      decoration: BoxDecoration(
        color: p.raised,
        borderRadius: BorderRadius.circular(7),
        border: Border.all(color: p.edge),
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
      fillColor: p.raised,
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
        side: BorderSide(color: p.edge),
      ),
    ),
    dividerTheme: DividerThemeData(color: p.divider, thickness: .5, space: 1),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: p.raised,
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
