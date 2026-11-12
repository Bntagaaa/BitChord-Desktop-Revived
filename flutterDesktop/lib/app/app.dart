import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../design_system/palette.dart';
import '../design_system/theme.dart';
import '../features/shell/application/shell_controller.dart';
import 'router.dart';

class BitChordApp extends ConsumerStatefulWidget {
  const BitChordApp({super.key});
  @override
  ConsumerState<BitChordApp> createState() => _BitChordAppState();
}

class _BitChordAppState extends ConsumerState<BitChordApp>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    PaintingBinding.instance.imageCache.maximumSizeBytes = 64 * 1024 * 1024;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.hidden ||
        state == AppLifecycleState.paused) {
      PaintingBinding.instance.imageCache.clear();
      PaintingBinding.instance.imageCache.clearLiveImages();
    }
  }

  @override
  void didChangePlatformBrightness() {
    setState(() {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(shellProvider);
    final system =
        WidgetsBinding.instance.platformDispatcher.platformBrightness;
    final palette = BitChordPalette.resolve(settings, system);
    final brightness = palette['foreground'].computeLuminance() > .5
        ? Brightness.dark
        : Brightness.light;
    return MaterialApp.router(
      title: 'BitChord',
      debugShowCheckedModeBanner: false,
      theme: bitChordTheme(palette, brightness),
      routerConfig: ref.watch(routerProvider),
    );
  }
}
