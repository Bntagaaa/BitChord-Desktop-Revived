// Port source: DesktopTheme.kt and BitChordTypography.kt @ 348b613a.
import 'package:flutter/material.dart';
import '../data/settings.dart';
class AppColors {
  const AppColors(this.values);
  final Map<String, Color> values;
  Color get background => values['background']!;
  Color get surface => values['surface']!;
  Color get surfaceRaised => values['surfaceRaised']!;
  Color get glass => values['glass']!;
  Color get glassStrong => values['glassStrong']!;
  Color get barGlass => values['barGlass']!;
  Color get barEdge => values['barEdge']!;
  Color get secondary => values['secondary']!;
  Color get divider => values['divider']!;
  Color get destructive => values['destructive']!;
  Color get cardFill => values['cardFill']!;
  Color get cardInsetFill => values['cardInsetFill']!;
  Color get cardEdge => values['cardEdge']!;
  Color get rowHover => values['rowHover']!;
  Color get scrim => values['scrim']!;
  Color get searchSurface => values['searchSurface']!;
  Color get topPlayerSurface => values['topPlayerSurface']!;
  Color get floatingPlayerSurface => values['floatingPlayerSurface']!;
  Color get subtleFill => values['subtleFill']!;
  Color get stripedRow => values['stripedRow']!;
  Color get navAccent => values['navAccent']!;
  Color get navSelectedSurface => values['navSelectedSurface']!;
  Color get sidebarSurface => values['sidebarSurface']!;
  Color get chromeSurface => values['chromeSurface']!;
  Color get lyricsSurface => values['lyricsSurface']!;
  Color get replaySurface => values['replaySurface']!;
  static const dark = AppColors({
    'background': Color(0xFF202020),
    'surface': Color(0xFF0D0D0F),
    'surfaceRaised': Color(0xFF1C1C1E),
    'glass': Color(0x661C1C1E),
    'glassStrong': Color(0xCC0D0D0F),
    'barGlass': Color(0xB324242A),
    'barEdge': Color(0x1AFFFFFF),
    'secondary': Color(0xFF8E8E93),
    'divider': Color(0xFF2C2C2E),
    'destructive': Color(0xFFFF453A),
    'cardFill': Color(0xFA1A1A1D),
    'cardInsetFill': Color(0x14FFFFFF),
    'cardEdge': Color(0x1FFFFFFF),
    'rowHover': Color(0x1AFFFFFF),
    'scrim': Color(0x8C000000),
    'searchSurface': Color(0xFF27272B),
    'topPlayerSurface': Color(0xFF323236),
    'floatingPlayerSurface': Color(0xFF202124),
    'subtleFill': Color(0x14FFFFFF),
    'stripedRow': Color(0x0AFFFFFF),
    'navAccent': Color(0xFFFA586A),
    'navSelectedSurface': Color(0xFF2D2D2D),
    'sidebarSurface': Color(0xFF0D0D0F),
    'chromeSurface': Color(0xFF0D0D0F),
    'lyricsSurface': Color(0xFF202020),
    'replaySurface': Color(0xFF202020),
  });
  static const light = AppColors({
    'background': Color(0xFFF7F8FB),
    'surface': Color(0xFFFFFFFF),
    'surfaceRaised': Color(0xFFEDEFF4),
    'glass': Color(0xDDEFF1F5),
    'glassStrong': Color(0xF4F7F8FC),
    'barGlass': Color(0xE8E7EBF1),
    'barEdge': Color(0x251D2636),
    'accent': Color(0xFF1C1D21),
    'foreground': Color(0xFF1B1C20),
    'secondary': Color(0xFF4D5767),
    'divider': Color(0xFFD5DAE4),
    'destructive': Color(0xFFBE3545),
    'cardFill': Color(0xFFFFFFFF),
    'cardInsetFill': Color(0xFFEEF1F6),
    'cardEdge': Color(0x241D2636),
    'rowHover': Color(0x1E25324D),
    'scrim': Color(0x750D0E13),
    'searchSurface': Color(0xFFE8ECF3),
    'topPlayerSurface': Color(0xFFE4E8EF),
    'floatingPlayerSurface': Color(0xFFE5E9F0),
    'subtleFill': Color(0x111D2636),
    'stripedRow': Color(0x0D1D2636),
    'navAccent': Color(0xFFCD3659),
    'navSelectedSurface': Color(0xFFDDDDDD),
    'sidebarSurface': Color(0xFFE9ECF2),
    'chromeSurface': Color(0xFFF0F2F6),
    'lyricsSurface': Color(0xFFEDF0F6),
    'replaySurface': Color(0xFFF5F6FA),
  });
}

ThemeData appTheme(Settings settings, Brightness platform) {
  final mode = settings.get('desktop_theme_mode', 'SYSTEM');
  final dark = mode == 'DARK' || (mode == 'SYSTEM' && platform == Brightness.dark);
  final pitch = mode == 'DARK' && settings.get('desktop_pitch_black', false);
  final legacy = settings.get('app_font', 'DEFAULT') == 'LEGACY';
  final palette = dark ? AppColors.dark : AppColors.light;
  final colors = AppColors({...palette.values, if (pitch) ...{
    'background': Colors.black, 'surface': Colors.black, 'sidebarSurface': Colors.black, 'chromeSurface': Colors.black,
  }});
  final family = legacy ? 'SFProLegacy' : 'SFProDefault';
  final base = ThemeData(brightness: dark ? Brightness.dark : Brightness.light,
    useMaterial3: true, fontFamily: family, scaffoldBackgroundColor: colors.background,
    colorScheme: ColorScheme.fromSeed(seedColor: colors.navAccent,
      brightness: dark ? Brightness.dark : Brightness.light,
      surface: colors.surface, onSurface: colors.foreground, primary: colors.navAccent));
  TextStyle style(double size, FontWeight weight, double modern, [double? original]) => TextStyle(
    fontFamily: family, fontSize: size, fontWeight: weight, color: colors.foreground,
    letterSpacing: legacy ? original : modern);
  return base.copyWith(textTheme: base.textTheme.copyWith(
    displayLarge: style(34, FontWeight.w800, -.5, -.8),
    headlineLarge: style(30, FontWeight.w800, -.4, -.7),
    headlineMedium: style(22, FontWeight.w700, -.25, -.4),
    titleLarge: style(20, FontWeight.w700, -.2, -.3),
    titleMedium: style(16, FontWeight.w600, .05, -.2),
    bodyLarge: style(16, FontWeight.w400, .2),
    bodyMedium: style(14, FontWeight.w400, .3),
    labelMedium: style(12, FontWeight.w600, .25),
    labelSmall: style(11, FontWeight.w600, .3),
  ), extensions: [PaletteExtension(colors)]);
}

@immutable
class PaletteExtension extends ThemeExtension<PaletteExtension> {
  const PaletteExtension(this.colors);
  final AppColors colors;
  @override PaletteExtension copyWith({AppColors? colors}) => PaletteExtension(colors ?? this.colors);
  @override PaletteExtension lerp(covariant PaletteExtension? other, double t) => other == null ? this : PaletteExtension(
    AppColors({for (final key in colors.values.keys) key: Color.lerp(colors.values[key], other.colors.values[key], t)!}));
}
AppColors paletteOf(BuildContext context) => Theme.of(context).extension<PaletteExtension>()!.colors;
