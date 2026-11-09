import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:desktop_multi_window/desktop_multi_window.dart';
import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';
import 'package:path/path.dart' as p;
import 'package:window_manager/window_manager.dart';

import 'application/app_controller.dart';
import 'application/download_service.dart';
import 'application/local_music_service.dart';
import 'data/addon_registry.dart';
import 'data/auth_store.dart';
import 'data/innertube_client.dart';
import 'data/jiosaavn_client.dart';
import 'data/local_store.dart';
import 'data/music_repository.dart';
import 'platform/desktop_integration.dart';
import 'platform/pip_window.dart';
import 'playback/playback.dart';
import 'presentation/desktop_shell.dart';
import 'presentation/desktop_theme.dart';

Future<void> main(List<String> arguments) async {
  WidgetsFlutterBinding.ensureInitialized();
  PaintingBinding.instance.imageCache
    ..maximumSize = 200
    ..maximumSizeBytes = 64 * 1024 * 1024;
  try {
    await windowManager.ensureInitialized();
    final window = await WindowController.fromCurrentEngine();
    final raw = window.arguments.trim();
    final windowArguments = raw.isEmpty
        ? <String, dynamic>{}
        : jsonDecode(raw) as Map<String, dynamic>;
    if (windowArguments['type'] == 'pip') {
      // A PiP engine is a view of the main player, never another player/profile.
      runApp(PipApplication(window: window, arguments: windowArguments));
      return;
    }
    MediaKit.ensureInitialized();
    final store = await LocalStore.open();
    final client = InnertubeClient(
      language: store.getSetting('content_language', 'en'),
      region: store.getSetting('content_country', 'US'),
    );
    final repository = MusicRepository(client: client);
    final addons = AddonRegistry();
    final saavn = JioSaavnClient();
    YoutubeStreamResolver makeResolver({required bool forDownload}) {
      final qualityKey = forDownload ? 'download_quality' : 'audio_quality';
      return YoutubeStreamResolver(
        maxAudioKbps: store.getSetting(qualityKey, 'LOSSLESS') == 'LOW'
            ? 64
            : null,
        authenticatedPlayer: client.player,
        alternateSource: (song) async {
          final quality = store.getSetting(qualityKey, 'LOSSLESS');
          AddonStream? stream;
          if (quality == 'LOSSLESS') {
            stream = await addons.resolve(
              song,
              quality: quality,
              forDownload: forDownload,
            );
          }
          if (stream == null &&
              store.getSetting('source_jiosaavn_enabled', true)) {
            try {
              stream = await saavn.resolve(song, quality: quality);
            } on Exception {
              // A source miss falls through to the original YouTube recording.
            }
          }
          if (stream == null) return null;
          return ResolvedStream(
            uri: stream.uri,
            headers: stream.headers,
            format: StreamFormat(
              codec: stream.codec ?? stream.format,
              kbps: stream.kbps,
              sampleRateHz: stream.sampleRateHz,
              bitDepth: stream.bitDepth,
            ),
            source: stream.sourceId,
            container: stream.format,
            allowDownloads: stream.allowDownloads,
            sourceQuality: stream.codec?.toUpperCase(),
          );
        },
      );
    }

    final playbackResolver = makeResolver(forDownload: false);
    final downloadResolver = makeResolver(forDownload: true);
    final playback = PlaybackController(
      resolver: playbackResolver,
      radioLoader: repository.radioForSong,
      songNormalizer: (song) =>
          song.isYouTube && store.getSetting('content_prefer_music_only', false)
          ? repository.audioVersion(song)
          : Future.value(song),
      onPersist: (snapshot) =>
          store.setSetting('playback_snapshot', snapshot.toJson()),
    );
    final home =
        Platform.environment['USERPROFILE'] ?? Platform.environment['HOME'];
    final defaultDownloads = home == null
        ? p.join(store.directory.path, 'downloads')
        : p.join(home, 'Music', 'BitChord');
    final app = AppController(
      store: store,
      client: client,
      repository: repository,
      playback: playback,
      authStore: AuthStore(),
      addons: addons,
      localMusic: LocalMusicService(
        Directory(p.join(store.directory.path, 'artwork')),
      ),
      downloadService: DownloadService(
        resolver: downloadResolver,
        directory: Directory(
          store.getSetting('download_folder', defaultDownloads),
        ),
      ),
    );
    await app.restoreLocalSession();
    final pip = PipCoordinator(app, window);
    final desktop = DesktopIntegration(
      app,
      onShutdown: () async {
        await pip.close();
        await app.close();
        await downloadResolver.close();
        saavn.close();
      },
    );
    runApp(BitChordApplication(app: app, desktop: desktop));
    // The first frame is independent of network responses and account lookup.
    unawaited(_start(app, pip, desktop));
  } on Object catch (failure) {
    runApp(_StartupFailure(kind: failure.runtimeType.toString()));
  }
}

Future<void> _start(
  AppController app,
  PipCoordinator pip,
  DesktopIntegration desktop,
) async {
  // Network/catalogue startup and optional desktop services are independent.
  final loading = app.initialize().catchError((Object failure) {
    app.reportNotice(
      'Account and catalogue startup could not finish (${failure.runtimeType}).',
    );
  });
  try {
    await desktop.initialize();
  } on Object {
    desktop.unavailable.add('Desktop window services');
  }
  try {
    await pip.initialize();
  } on Object {
    desktop.unavailable.add('Picture-in-picture');
  }
  await loading;
  if (desktop.unavailable.isNotEmpty) {
    app.reportNotice(
      '${desktop.unavailable.join(', ')} could not connect to this desktop session.',
    );
  }
}

class BitChordApplication extends StatelessWidget {
  const BitChordApplication({
    super.key,
    required this.app,
    required this.desktop,
  });
  final AppController app;
  final DesktopIntegration desktop;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app,
    builder: (context, _) {
      final theme = app.setting('desktop_theme_mode', 'SYSTEM');
      final legacy = app.setting('app_font', 'DEFAULT') == 'LEGACY';
      final scale =
          app.setting<num>('ui_scale_percent', 100).toDouble().clamp(80, 150) /
          100;
      return MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'BitChord Desktop Revived',
        theme: buildDesktopTheme(
          brightness: Brightness.light,
          legacyFont: legacy,
        ),
        darkTheme: buildDesktopTheme(
          brightness: Brightness.dark,
          legacyFont: legacy,
          pitchBlack:
              theme == 'DARK' && app.setting('desktop_pitch_black', false),
        ),
        themeMode: switch (theme) {
          'LIGHT' => ThemeMode.light,
          'DARK' => ThemeMode.dark,
          _ => ThemeMode.system,
        },
        builder: (context, child) => ValueListenableBuilder<bool>(
          valueListenable: desktop.tickersEnabled,
          builder: (context, enabled, _) => TickerMode(
            enabled: enabled,
            child: LayoutBuilder(
              builder: (context, constraints) {
                final logicalSize = Size(
                  constraints.maxWidth / scale,
                  constraints.maxHeight / scale,
                );
                return OverflowBox(
                  alignment: Alignment.topLeft,
                  minWidth: logicalSize.width,
                  maxWidth: logicalSize.width,
                  minHeight: logicalSize.height,
                  maxHeight: logicalSize.height,
                  child: Transform.scale(
                    scale: scale,
                    alignment: Alignment.topLeft,
                    child: MediaQuery(
                      data: MediaQuery.of(context).copyWith(size: logicalSize),
                      child: child!,
                    ),
                  ),
                );
              },
            ),
          ),
        ),
        home: DesktopShell(controller: app),
      );
    },
  );
}

class _StartupFailure extends StatelessWidget {
  const _StartupFailure({required this.kind});
  final String kind;
  @override
  Widget build(BuildContext context) => MaterialApp(
    theme: buildDesktopTheme(brightness: Brightness.dark),
    home: Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 540),
            child: Text(
              'BitChord could not start ($kind).\n\nCheck that the complete desktop bundle is installed and your profile folder is writable. Your saved music has not been reset.',
              style: const TextStyle(fontSize: 18),
            ),
          ),
        ),
      ),
    ),
  );
}
