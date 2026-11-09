import 'dart:async';
import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';
import 'package:window_manager/window_manager.dart';
import 'data/innertube.dart';
import 'data/settings.dart';
import 'domain/application.dart';
import 'platform/audio.dart';
import 'ui/shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();
  await windowManager.ensureInitialized();
  final application = MusicApplication(settings: await Settings.load(),
    api: InnerTube(), audio: NativeAudioOutput());
  await windowManager.waitUntilReadyToShow(const WindowOptions(
    title: 'BitChord', size: Size(1200, 800), minimumSize: Size(560, 360),
    titleBarStyle: TitleBarStyle.normal), () async {
      await windowManager.show(); await windowManager.focus();
    });
  await windowManager.setPreventClose(true);
  windowManager.addListener(_Exit(application));
  runApp(BitChordApplication(application: application));
}

class _Exit extends WindowListener {
  _Exit(this.application);
  final MusicApplication application;
  bool _closing = false;
  @override void onWindowClose() {
    if (_closing) return; _closing = true;
    unawaited(() async {
      await application.close();
      await windowManager.destroy();
    }());
  }
}
