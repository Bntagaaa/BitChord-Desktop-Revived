import 'dart:async';
import 'dart:convert';

import 'package:desktop_multi_window/desktop_multi_window.dart';
import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';

import '../application/app_controller.dart';
import '../domain/models.dart';
import '../presentation/desktop_theme.dart';
import '../presentation/widgets.dart';

/// The secondary engine only renders metadata and sends commands. It never
/// creates a decoder, opens a profile, or refreshes account tokens.
class PipCoordinator {
  PipCoordinator(this.app, this.mainWindow);
  final AppController app;
  final WindowController mainWindow;
  WindowController? _window;
  Future<void>? _opening;
  Completer<void>? _ready;
  String? _readyWindowId;
  Timer? _timer;
  StreamSubscription<dynamic>? _windows;
  bool _sending = false;
  bool _closed = false;

  Future<void> initialize() async {
    await mainWindow.setWindowMethodHandler((call) async {
      switch (call.method) {
        case 'ready':
          _readyWindowId = call.arguments as String;
          if (_ready != null && !_ready!.isCompleted) _ready!.complete();
          return !_closed;
        case 'snapshot':
          return _snapshot();
        case 'playPause':
          await app.playback.playPause();
        case 'previous':
          await app.playback.previous();
        case 'next':
          await app.playback.next();
        case 'seek':
          await app.playback.seek(
            Duration(milliseconds: (call.arguments as num).toInt()),
          );
        case 'like':
          final song = app.playback.currentSong;
          if (song != null) await app.toggleLike(song);
        case 'showMain':
          await windowManager.show();
          await windowManager.focus();
        case 'pin':
          await app.setSetting('pip_pinned', call.arguments == true);
        case 'closed':
          if (call.arguments == _window?.windowId) {
            _window = null;
            _timer?.cancel();
          }
      }
      return null;
    });
    _windows = onWindowsChanged.listen((_) async {
      if (_window == null || _closed) return;
      final current = await WindowController.getAll();
      if (!current.any((entry) => entry.windowId == _window?.windowId)) {
        _window = null;
        _timer?.cancel();
      }
    });
    app.onOpenPiP = open;
  }

  Future<void> open() {
    if (_closed) return Future.value();
    return _opening ??= _open().whenComplete(() {
      _opening = null;
    });
  }

  Future<void> _open() async {
    if (_closed) return;
    final existing = _window;
    if (existing != null) {
      try {
        await existing.show();
        await existing.invokeMethod<void>('focus');
        _startPublishing();
        return;
      } on Object {
        final windows = await WindowController.getAll();
        if (windows.any((item) => item.windowId == existing.windowId)) {
          throw const ServiceException(
            'The picture-in-picture window is still starting. Try again in a moment.',
          );
        }
        _window = null;
      }
    }
    if (_closed) return;
    _ready = Completer<void>();
    _readyWindowId = null;
    final created = await WindowController.create(
      WindowConfiguration(
        hiddenAtLaunch: true,
        arguments: jsonEncode({
          'type': 'pip',
          'mainWindowId': mainWindow.windowId,
          'snapshot': _snapshot(),
        }),
      ),
    );
    _window = created;
    if (_readyWindowId != created.windowId) {
      await _ready!.future.timeout(const Duration(seconds: 10));
    }
    if (_closed) return;
    await created.show();
    _startPublishing();
  }

  void _startPublishing() {
    if (_closed || _window == null) return;
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(milliseconds: 350), (_) {
      unawaited(_publish());
    });
  }

  Map<String, Object?> _snapshot() {
    final player = app.playback;
    final song = player.currentSong;
    final lyrics = app.lyrics;
    final index = lyrics?.activeIndex(player.position) ?? -1;
    return {
      'song': song?.toJson(),
      'playing': player.playing,
      'buffering': player.buffering,
      'positionMs': player.position.inMilliseconds,
      'durationMs': player.duration.inMilliseconds,
      'liked': song != null && app.isLiked(song),
      'pinned': app.setting('pip_pinned', true),
      'lyric': app.lyricsLoading
          ? 'Finding lyrics…'
          : lyrics?.isInstrumental == true
          ? 'Instrumental'
          : index >= 0 && index < (lyrics?.lines.length ?? 0)
          ? lyrics!.lines[index].text
          : '',
      'legacyFont': app.setting('app_font', 'DEFAULT') == 'LEGACY',
    };
  }

  Future<void> _publish() async {
    if (_closed || _sending || _window == null) return;
    _sending = true;
    try {
      await _window!
          .invokeMethod<void>('update', _snapshot())
          .timeout(const Duration(seconds: 2));
    } on Object {
      /* A closing or starting engine may not have its channel yet. */
    } finally {
      _sending = false;
    }
  }

  Future<void> close() async {
    _closed = true;
    app.onOpenPiP = null;
    try {
      await _opening;
    } on Object {
      /* Close the tracked window even when startup failed. */
    }
    _timer?.cancel();
    await _windows?.cancel();
    final window = _window;
    _window = null;
    if (window != null) {
      try {
        await window.invokeMethod<void>('close');
      } on Object {
        /* Already closed. */
      }
    }
    await mainWindow.setWindowMethodHandler(null);
    app.onOpenPiP = null;
  }
}

class PipApplication extends StatefulWidget {
  const PipApplication({
    super.key,
    required this.window,
    required this.arguments,
  });
  final WindowController window;
  final Map<String, dynamic> arguments;
  @override
  State<PipApplication> createState() => _PipApplicationState();
}

class _PipApplicationState extends State<PipApplication> with WindowListener {
  late final WindowController parent;
  Map<String, dynamic> snapshot = {};
  double? _seek;

  @override
  void initState() {
    super.initState();
    parent = WindowController.fromWindowId(
      widget.arguments['mainWindowId'] as String,
    );
    snapshot = Map<String, dynamic>.from(
      widget.arguments['snapshot'] as Map? ?? {},
    );
    windowManager.addListener(this);
    unawaited(_initialize());
  }

  Future<void> _initialize() async {
    await widget.window.setWindowMethodHandler((call) async {
      if (call.method == 'update' && mounted) {
        setState(() {
          snapshot = Map<String, dynamic>.from(call.arguments as Map);
        });
      }
      if (call.method == 'close') await windowManager.destroy();
      if (call.method == 'focus') await windowManager.focus();
      return null;
    });
    final keepOpen = await parent.invokeMethod<bool>(
      'ready',
      widget.window.windowId,
    );
    if (keepOpen != true) {
      await windowManager.destroy();
      return;
    }
    await windowManager.waitUntilReadyToShow(
      const WindowOptions(
        size: Size(500, 340),
        minimumSize: Size(300, 190),
        center: true,
        title: 'BitChord · Picture in picture',
        skipTaskbar: false,
        backgroundColor: Color(0xFF0D111E),
      ),
      () async {
        await windowManager.show();
      },
    );
    await windowManager.setAlwaysOnTop(snapshot['pinned'] != false);
    final fresh = await parent.invokeMethod<Map<dynamic, dynamic>>('snapshot');
    if (fresh != null && mounted) {
      setState(() {
        snapshot = Map<String, dynamic>.from(fresh);
      });
    }
  }

  Future<void> _command(String name, [Object? value]) async {
    try {
      await parent.invokeMethod<void>(name, value);
    } on Object {
      if (mounted) {
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(
          const SnackBar(content: Text('The main player is not available.')),
        );
      }
    }
  }

  @override
  void onWindowClose() {
    unawaited(_command('closed', widget.window.windowId));
  }

  @override
  Widget build(BuildContext context) {
    final raw = snapshot['song'];
    final song = raw is Map
        ? Song.fromJson(Map<String, dynamic>.from(raw))
        : null;
    final duration = ((snapshot['durationMs'] as num?)?.toDouble() ?? 0)
        .clamp(0, double.maxFinite)
        .toDouble();
    final position =
        _seek ??
        ((snapshot['positionMs'] as num?)?.toDouble() ?? 0)
            .clamp(0, duration)
            .toDouble();
    final pinned = snapshot['pinned'] != false;
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: buildDesktopTheme(
        brightness: Brightness.dark,
        legacyFont: snapshot['legacyFont'] == true,
      ),
      home: Scaffold(
        backgroundColor: const Color(0xFF0D111E),
        body: DecoratedBox(
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [Color(0xFF20355D), Color(0xFF090B12)],
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              children: [
                Row(
                  children: [
                    const Text(
                      'BitChord',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const Spacer(),
                    IconButton(
                      tooltip: pinned ? 'Unpin window' : 'Always on top',
                      iconSize: 16,
                      constraints: const BoxConstraints.tightFor(
                        width: 28,
                        height: 28,
                      ),
                      icon: Icon(
                        pinned ? Icons.push_pin : Icons.push_pin_outlined,
                      ),
                      onPressed: () async {
                        await windowManager.setAlwaysOnTop(!pinned);
                        await _command('pin', !pinned);
                        if (mounted) {
                          setState(() {
                            snapshot['pinned'] = !pinned;
                          });
                        }
                      },
                    ),
                    IconButton(
                      tooltip: 'Open main player',
                      iconSize: 16,
                      constraints: const BoxConstraints.tightFor(
                        width: 28,
                        height: 28,
                      ),
                      onPressed: () => _command('showMain'),
                      icon: const Icon(Icons.open_in_new),
                    ),
                  ],
                ),
                Expanded(
                  child: Row(
                    children: [
                      if (song != null)
                        Flexible(
                          flex: 2,
                          child: AspectRatio(
                            aspectRatio: 1,
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(8),
                              child: Artwork(song.thumbnailUrl, size: 160),
                            ),
                          ),
                        ),
                      if (song != null) const SizedBox(width: 16),
                      Expanded(
                        flex: 3,
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              song?.title ?? 'No music playing',
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            if (song != null)
                              Text(
                                song.artist,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: Colors.white60,
                                ),
                              ),
                            const SizedBox(height: 12),
                            Text(
                              snapshot['lyric'] as String? ?? '',
                              maxLines: 3,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                SizedBox(
                  height: 24,
                  child: Row(
                    children: [
                      Text(
                        formatDuration(
                          Duration(milliseconds: position.toInt()),
                        ),
                        style: const TextStyle(
                          fontSize: 10,
                          color: Colors.white60,
                        ),
                      ),
                      Expanded(
                        child: SliderTheme(
                          data: const SliderThemeData(
                            trackHeight: 2,
                            thumbShape: RoundSliderThumbShape(
                              enabledThumbRadius: 4,
                            ),
                          ),
                          child: Slider(
                            value: position,
                            max: duration > 0 ? duration : 1,
                            onChanged: duration <= 0
                                ? null
                                : (value) => setState(() {
                                    _seek = value;
                                  }),
                            onChangeEnd: (value) {
                              _seek = null;
                              unawaited(_command('seek', value.toInt()));
                            },
                          ),
                        ),
                      ),
                      Text(
                        formatDuration(
                          Duration(milliseconds: duration.toInt()),
                        ),
                        style: const TextStyle(
                          fontSize: 10,
                          color: Colors.white60,
                        ),
                      ),
                    ],
                  ),
                ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    IconButton(
                      tooltip: 'Like',
                      onPressed: song == null ? null : () => _command('like'),
                      icon: Icon(
                        snapshot['liked'] == true
                            ? Icons.favorite
                            : Icons.favorite_border,
                        size: 19,
                      ),
                    ),
                    const SizedBox(width: 16),
                    IconButton(
                      tooltip: 'Previous',
                      onPressed: song == null
                          ? null
                          : () => _command('previous'),
                      icon: const Icon(Icons.skip_previous_rounded),
                    ),
                    IconButton.filledTonal(
                      tooltip: snapshot['playing'] == true ? 'Pause' : 'Play',
                      onPressed: song == null
                          ? null
                          : () => _command('playPause'),
                      icon: Icon(
                        snapshot['playing'] == true
                            ? Icons.pause_rounded
                            : Icons.play_arrow_rounded,
                      ),
                    ),
                    IconButton(
                      tooltip: 'Next',
                      onPressed: song == null ? null : () => _command('next'),
                      icon: const Icon(Icons.skip_next_rounded),
                    ),
                    const SizedBox(width: 44),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    windowManager.removeListener(this);
    super.dispose();
  }
}
