import 'dart:async';
import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';
import '../../design_system/theme.dart';
import '../../features/shell/domain/shell_settings.dart';
import 'diagnostics.dart';

final class DesktopHost with WindowListener implements DesktopWindow {
  DesktopHost(this.diagnostics);
  final Diagnostics diagnostics;
  Future<void> Function()? beforeClose;
  bool _closing = false;
  bool _maximizedBeforeFullscreen = false;

  Future<void> initialize() async {
    await windowManager.ensureInitialized();
    await windowManager.setPreventClose(true);
    windowManager.addListener(this);
    await windowManager.waitUntilReadyToShow(
      const WindowOptions(
        size: Size(ShellMetrics.initialWidth, ShellMetrics.initialHeight),
        minimumSize: Size(
          ShellMetrics.minimumWidth,
          ShellMetrics.minimumHeight,
        ),
        center: true,
        title: 'BitChord',
        titleBarStyle: TitleBarStyle.normal,
      ),
    );
  }

  Future<void> show() async {
    await windowManager.show();
    await windowManager.focus();
  }

  @override
  Future<void> toggleFullscreen() async {
    if (await windowManager.isFullScreen()) {
      await exitFullscreen();
    } else {
      _maximizedBeforeFullscreen = await windowManager.isMaximized();
      await windowManager.setFullScreen(true);
    }
  }

  @override
  Future<void> exitFullscreen() async {
    if (!await windowManager.isFullScreen()) return;
    await windowManager.setFullScreen(false);
    if (_maximizedBeforeFullscreen) await windowManager.maximize();
  }

  @override
  Future<void> close() async {
    if (_closing) return;
    _closing = true;
    try {
      await beforeClose?.call();
    } catch (error) {
      diagnostics.failure('shutdown', error);
    } finally {
      windowManager.removeListener(this);
      // Let the native runner destroy its window/controller in the normal
      // order. Force-quit posts WM_QUIT on Windows while its HWND is still live.
      await windowManager.setPreventClose(false);
      await windowManager.close();
    }
  }

  @override
  void onWindowClose() {
    unawaited(
      close().catchError((Object error) {
        diagnostics.failure('close window', error);
      }),
    );
  }
}
