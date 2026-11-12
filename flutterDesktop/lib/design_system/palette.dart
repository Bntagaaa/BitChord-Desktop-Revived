import 'package:flutter/material.dart';
import '../features/shell/domain/shell_settings.dart';

// Values transcribed from DesktopTheme.kt at Kotlin 1.9.2.
final class BitChordPalette {
  const BitChordPalette(this.tokens);
  final Map<String, Color> tokens;
  Color operator [](String name) => tokens[name]!;
  static const dark = BitChordPalette({
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
    'accent': Color(0xFFFFFFFF),
    'onAccent': Color(0xFF000000),
    'foreground': Color(0xFFFFFFFF),
  });
  static const light = BitChordPalette({
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
    'onAccent': Color(0xFFFFFFFF),
  });
  static BitChordPalette resolve(ShellSettings settings, Brightness system) {
    final darkMode =
        settings.appearance == Appearance.dark ||
        (settings.appearance == Appearance.system && system == Brightness.dark);
    if (!darkMode) return light;
    if (settings.appearance == Appearance.dark && settings.pitchBlack) {
      return BitChordPalette({
        ...dark.tokens,
        for (final key in [
          'background',
          'surface',
          'sidebarSurface',
          'chromeSurface',
        ])
          key: Colors.black,
      });
    }
    return dark;
  }
}
