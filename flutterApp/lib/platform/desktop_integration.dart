import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:local_notifier/local_notifier.dart';
import 'package:tray_manager/tray_manager.dart';
import 'package:window_manager/window_manager.dart';

import '../application/app_controller.dart';
import 'system_media_service.dart';

class DesktopIntegration with WindowListener, TrayListener {
  DesktopIntegration(this.app, {required this.onShutdown});
  final AppController app;
  final Future<void> Function() onShutdown;
  final ValueNotifier<bool> tickersEnabled = ValueNotifier(true);
  final List<String> unavailable = [];
  SystemMediaService? _media;
  bool _trayReady = false;
  bool _notificationsReady = false;
  bool _focused = true;
  bool _visible = true;
  bool _minimized = false;
  bool _closing = false;
  String? _lastSong;
  String? _lastTrayState;
  Timer? _boundsDebounce;
  LocalNotification? _notification;

  Future<void> initialize() async {
    await windowManager.ensureInitialized();
    final width = app
        .setting<num>('window_width', 1280)
        .toDouble()
        .clamp(900, 3840)
        .toDouble();
    final height = app
        .setting<num>('window_height', 800)
        .toDouble()
        .clamp(600, 2160)
        .toDouble();
    await windowManager.waitUntilReadyToShow(
      WindowOptions(
        size: Size(width, height),
        minimumSize: const Size(900, 600),
        center: true,
        title: 'BitChord Desktop Revived',
        titleBarStyle: TitleBarStyle.normal,
        backgroundColor: const Color(0xFF202020),
      ),
      () async {
        await windowManager.show();
        await windowManager.focus();
      },
    );
    await windowManager.setPreventClose(true);
    windowManager.addListener(this);
    trayManager.addListener(this);
    try {
      await trayManager.setIcon(
        Platform.isWindows
            ? 'assets/branding/app-icon.ico'
            : 'assets/branding/app-icon.png',
      );
      if (!Platform.isLinux) {
        await trayManager.setToolTip('BitChord Desktop Revived');
      }
      _trayReady = true;
      await _updateTray();
    } on Object {
      unavailable.add('System tray');
    }
    try {
      await localNotifier.setup(
        appName: 'BitChord Desktop Revived',
        shortcutPolicy: ShortcutPolicy.requireCreate,
      );
      _notificationsReady = true;
    } on Object {
      unavailable.add('Desktop notifications');
    }
    try {
      _media = SystemMediaService(app.playback);
      await _media!.start(raise: show, quit: exitApplication);
    } on Object {
      unavailable.add('System media controls');
      await _media?.close();
      _media = null;
    }
    app.addListener(_preferencesChanged);
    app.playback.addListener(_playbackChanged);
    _playbackChanged();
  }

  Future<void> show() async {
    await windowManager.show();
    await windowManager.restore();
    await windowManager.focus();
    _visible = true;
    _minimized = false;
    _focusPolicy();
  }

  void _preferencesChanged() => _focusPolicy();

  void _focusPolicy() {
    tickersEnabled.value =
        _visible &&
        !_minimized &&
        (_focused || app.setting('animate_lyrics_while_unfocused', false));
  }

  void _playbackChanged() {
    final song = app.playback.currentSong;
    final identity = song?.queueEntryId ?? song?.videoId;
    if (_lastSong != identity) {
      _lastSong = identity;
      unawaited(
        windowManager.setTitle(
          song == null
              ? 'BitChord Desktop Revived'
              : '${song.title} — ${song.artist} · BitChord',
        ),
      );
      if (_notificationsReady &&
          song != null &&
          app.setting('desktop_notifications', false)) {
        unawaited(_notification?.close());
        final notification = LocalNotification(
          title: song.title,
          body: song.artist,
        );
        _notification = notification;
        notification.onClick = () {
          unawaited(show());
        };
        unawaited(notification.show().catchError((Object _) {}));
      }
    }
    final trayState = '$identity:${app.playback.playing}';
    if (_trayReady && _lastTrayState != trayState) {
      _lastTrayState = trayState;
      unawaited(_updateTray());
    }
  }

  Future<void> _updateTray() async {
    if (!_trayReady) return;
    try {
      await trayManager.setContextMenu(
        Menu(
          items: [
            MenuItem(key: 'show', label: 'Show BitChord'),
            MenuItem.separator(),
            MenuItem(
              key: 'previous',
              label: 'Previous',
              disabled: app.playback.currentSong == null,
            ),
            MenuItem(
              key: 'toggle',
              label: app.playback.playing ? 'Pause' : 'Play',
              disabled: app.playback.currentSong == null,
            ),
            MenuItem(
              key: 'next',
              label: 'Next',
              disabled: app.playback.currentSong == null,
            ),
            MenuItem.separator(),
            MenuItem(key: 'quit', label: 'Quit'),
          ],
        ),
      );
    } on Object {
      /* A desktop session can remove its tray host while playing. */
    }
  }

  @override
  void onTrayIconMouseDown() {
    unawaited(show());
  }

  @override
  void onTrayIconRightMouseDown() {
    unawaited(trayManager.popUpContextMenu());
  }

  @override
  void onTrayMenuItemClick(MenuItem menuItem) {
    switch (menuItem.key) {
      case 'show':
        unawaited(show());
      case 'toggle':
        unawaited(app.playback.playPause());
      case 'next':
        unawaited(app.playback.next());
      case 'previous':
        unawaited(app.playback.previous());
      case 'quit':
        unawaited(exitApplication());
    }
  }

  @override
  void onWindowFocus() {
    _focused = true;
    _focusPolicy();
  }

  @override
  void onWindowBlur() {
    _focused = false;
    _focusPolicy();
  }

  @override
  void onWindowMinimize() {
    _minimized = true;
    _focusPolicy();
  }

  @override
  void onWindowRestore() {
    _minimized = false;
    _visible = true;
    _focusPolicy();
  }

  @override
  void onWindowResize() {
    _boundsDebounce?.cancel();
    _boundsDebounce = Timer(const Duration(milliseconds: 450), () async {
      if (_closing ||
          await windowManager.isFullScreen() ||
          await windowManager.isMaximized()) {
        return;
      }
      final size = await windowManager.getSize();
      await app.store.setSettings({
        'window_width': size.width,
        'window_height': size.height,
      });
    });
  }

  @override
  void onWindowClose() {
    if (_closing) return;
    if (_trayReady && app.setting('close_to_tray', true)) {
      _visible = false;
      _focusPolicy();
      unawaited(windowManager.hide());
    } else {
      unawaited(exitApplication());
    }
  }

  Future<void> exitApplication() async {
    if (_closing) return;
    _closing = true;
    await close();
    await onShutdown();
    await windowManager.destroy();
  }

  Future<void> close() async {
    _boundsDebounce?.cancel();
    app.removeListener(_preferencesChanged);
    app.playback.removeListener(_playbackChanged);
    windowManager.removeListener(this);
    trayManager.removeListener(this);
    await _notification?.close();
    await _media?.close();
    if (_trayReady) {
      await trayManager.destroy();
      _trayReady = false;
    }
  }
}
