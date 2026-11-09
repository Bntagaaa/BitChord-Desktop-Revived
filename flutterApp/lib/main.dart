import 'dart:async';
import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';
import 'presentation/desktop_shell.dart';
import 'presentation/desktop_theme.dart';
import 'presentation/desktop_typography.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await windowManager.ensureInitialized();

  const windowOptions = WindowOptions(
    size: Size(1220, 780),
    minimumSize: Size(900, 600),
    center: true,
    backgroundColor: Colors.transparent,
    skipTaskbar: false,
    title: 'BitChord',
  );

  unawaited(windowManager.waitUntilReadyToShow(windowOptions, () async {
    await windowManager.show();
    await windowManager.focus();
  }));

  runApp(const BitChordApp());
}

class BitChordApp extends StatefulWidget {
  const BitChordApp({super.key});

  @override
  State<BitChordApp> createState() => _BitChordAppState();
}

class _BitChordAppState extends State<BitChordApp> {
  final DesktopThemeController _themeController = DesktopThemeController();

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _themeController,
      builder: (context, _) {
        final palette = _themeController.palette;

        return MaterialApp(
          title: 'BitChord',
          debugShowCheckedModeBanner: false,
          themeMode: _themeController.isDark ? ThemeMode.dark : ThemeMode.light,
          theme: ThemeData(
            brightness: Brightness.light,
            scaffoldBackgroundColor: palette.background,
            textTheme: buildBitChordTypography(
              color: palette.foreground,
              mode: DesktopFontMode.defaultMode,
            ),
          ),
          darkTheme: ThemeData(
            brightness: Brightness.dark,
            scaffoldBackgroundColor: palette.background,
            textTheme: buildBitChordTypography(
              color: palette.foreground,
              mode: DesktopFontMode.defaultMode,
            ),
          ),
          home: DesktopShell(themeController: _themeController),
        );
      },
    );
  }
}
