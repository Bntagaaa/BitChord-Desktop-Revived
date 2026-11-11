import 'dart:async';
import 'package:bitchord/data/innertube.dart';
import 'package:bitchord/data/lyrics.dart';
import 'package:bitchord/data/settings.dart';
import 'package:bitchord/domain/application.dart';
import 'package:bitchord/domain/music.dart';
import 'package:bitchord/platform/audio.dart';
import 'package:bitchord/ui/player.dart';
import 'package:bitchord/ui/shell.dart';
import 'package:bitchord/ui/theme.dart';
import 'package:bitchord/ui/widgets.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class _Audio implements AudioOutput {
  final positions = StreamController<Duration>.broadcast(sync: true);
  final durations = StreamController<Duration>.broadcast(sync: true);
  final states = StreamController<bool>.broadcast(sync: true);
  final ends = StreamController<bool>.broadcast(sync: true);
  final failures = StreamController<String>.broadcast(sync: true);
  @override
  Stream<Duration> get position => positions.stream;
  @override
  Stream<Duration> get duration => durations.stream;
  @override
  Stream<bool> get playing => states.stream;
  @override
  Stream<bool> get completed => ends.stream;
  @override
  Stream<String> get errors => failures.stream;
  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {}
  @override
  Future<void> playPause() async {}
  Duration lastSeek = Duration.zero;
  @override
  Future<void> seek(Duration position) async {
    lastSeek = position;
  }

  @override
  Future<void> setVolume(double value) async {}
  @override
  Future<void> setRate(double value) async {}
  @override
  Future<void> close() async {
    await Future.wait([
      positions.close(),
      durations.close(),
      states.close(),
      ends.close(),
      failures.close(),
    ]);
  }
}

class _Lyrics extends LyricsRepository {
  @override
  Future<List<LyricLine>> fetch(Song song, Duration duration) async => [
    LyricLine(Duration.zero, 'Sample Line 1'),
    LyricLine(const Duration(seconds: 4), 'Sample Line 2'),
  ];
}

// Player transitions and lyric widgets can schedule continuous frames.
// Pump a finite interval to verify navigation without waiting for all tickers
// in the entire widget tree to stop.
Future<void> _pumpPlayerUi(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 400));
}

void main() {
  testWidgets(
    'ImmersivePlayer directly mounts PlayerPane.lyrics when supplied via initialPane',
    (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() => tester.view.resetPhysicalSize());

      final app = MusicApplication(
        settings: Settings.memory(),
        api: InnerTube(),
        audio: _Audio(),
        lyrics: _Lyrics(),
      );
      final song = Song(
        id: 'local:s1',
        title: 'Test Song',
        artist: 'Artist',
        localUri: Uri.file('/bitchord-player-navigation-test.flac'),
      );
      debugPrint('player_navigation first: before app.play');
      await app.play(song);
      debugPrint('player_navigation first: after app.play');

      debugPrint('player_navigation first: before pumpWidget');
      await tester.pumpWidget(
        MaterialApp(
          theme: appTheme(app.settings, Brightness.dark),
          home: ImmersivePlayer(app: app, initialPane: PlayerPane.lyrics),
        ),
      );
      debugPrint('player_navigation first: after pumpWidget');
      await _pumpPlayerUi(tester);
      debugPrint('player_navigation first: after bounded UI pumps');

      expect(find.byType(ImmersivePlayer), findsOneWidget);
      final player = tester.widget<ImmersivePlayer>(
        find.byType(ImmersivePlayer),
      );
      expect(player.initialPane, PlayerPane.lyrics);

      // Verify lyrics action button is marked active
      final lyricsButtons = tester.widgetList<TransportButton>(
        find.byWidgetPredicate(
          (w) => w is TransportButton && w.icon == 'player_transport_lyrics',
        ),
      );
      expect(lyricsButtons.any((b) => b.active), isTrue);

      debugPrint('player_navigation first: before app.close');
      await app.close();
      debugPrint('player_navigation first: after app.close');
    },
  );

  testWidgets(
    'tapping dock lyrics button opens ImmersivePlayer with initialPane: PlayerPane.lyrics',
    (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() => tester.view.resetPhysicalSize());

      final app = MusicApplication(
        settings: Settings.memory(),
        api: InnerTube(),
        audio: _Audio(),
        lyrics: _Lyrics(),
      );
      final song = Song(
        id: 'local:s1',
        title: 'Test Song',
        artist: 'Artist',
        localUri: Uri.file('/bitchord-player-navigation-test.flac'),
      );
      await app.play(song);

      await tester.pumpWidget(
        MaterialApp(
          theme: appTheme(app.settings, Brightness.dark),
          home: MusicShell(application: app),
        ),
      );
      await _pumpPlayerUi(tester);

      final dockLyricsBtn = find.byWidgetPredicate(
        (w) => w is TransportButton && w.icon == 'player_transport_lyrics',
      );
      expect(dockLyricsBtn, findsOneWidget);

      await tester.tap(dockLyricsBtn);
      await _pumpPlayerUi(tester);

      final playerFinder = find.byType(ImmersivePlayer);
      expect(playerFinder, findsOneWidget);
      final playerWidget = tester.widget<ImmersivePlayer>(playerFinder);
      expect(playerWidget.initialPane, PlayerPane.lyrics);

      await app.close();
    },
  );

  testWidgets(
    'tapping dock queue button opens ImmersivePlayer with initialPane: PlayerPane.queue',
    (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() => tester.view.resetPhysicalSize());

      final app = MusicApplication(
        settings: Settings.memory(),
        api: InnerTube(),
        audio: _Audio(),
        lyrics: _Lyrics(),
      );
      final song = Song(
        id: 'local:s1',
        title: 'Test Song',
        artist: 'Artist',
        localUri: Uri.file('/bitchord-player-navigation-test.flac'),
      );
      await app.play(song);

      await tester.pumpWidget(
        MaterialApp(
          theme: appTheme(app.settings, Brightness.dark),
          home: MusicShell(application: app),
        ),
      );
      await _pumpPlayerUi(tester);

      final dockQueueBtn = find.byWidgetPredicate(
        (w) => w is TransportButton && w.icon == 'player_transport_up_next',
      );
      expect(dockQueueBtn, findsOneWidget);

      await tester.tap(dockQueueBtn);
      await _pumpPlayerUi(tester);

      final playerFinder = find.byType(ImmersivePlayer);
      expect(playerFinder, findsOneWidget);
      final playerWidget = tester.widget<ImmersivePlayer>(playerFinder);
      expect(playerWidget.initialPane, PlayerPane.queue);

      await app.close();
    },
  );

  testWidgets(
    'tapping miniplayer title opens ImmersivePlayer with default PlayerPane.main',
    (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() => tester.view.resetPhysicalSize());

      final app = MusicApplication(
        settings: Settings.memory(),
        api: InnerTube(),
        audio: _Audio(),
        lyrics: _Lyrics(),
      );
      final song = Song(
        id: 'local:s1',
        title: 'Test Song',
        artist: 'Artist',
        localUri: Uri.file('/bitchord-player-navigation-test.flac'),
      );
      await app.play(song);

      await tester.pumpWidget(
        MaterialApp(
          theme: appTheme(app.settings, Brightness.dark),
          home: MusicShell(application: app),
        ),
      );
      await _pumpPlayerUi(tester);

      final titleFinder = find.text('Test Song');
      expect(titleFinder, findsOneWidget);

      await tester.tap(titleFinder);
      await _pumpPlayerUi(tester);

      final playerFinder = find.byType(ImmersivePlayer);
      expect(playerFinder, findsOneWidget);
      final playerWidget = tester.widget<ImmersivePlayer>(playerFinder);
      expect(playerWidget.initialPane, PlayerPane.main);

      await app.close();
    },
  );

  testWidgets(
    'tapping a lyric line sets app.position.value to the line timestamp and seeks audio',
    (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() => tester.view.resetPhysicalSize());

      final audio = _Audio();
      final app = MusicApplication(
        settings: Settings.memory(),
        api: InnerTube(),
        audio: audio,
        lyrics: _Lyrics(),
      );
      final song = Song(
        id: 'local:s1',
        title: 'Test Song',
        artist: 'Artist',
        localUri: Uri.file('/bitchord-player-navigation-test.flac'),
      );
      await app.play(song);

      await tester.pumpWidget(
        MaterialApp(
          theme: appTheme(app.settings, Brightness.dark),
          home: ImmersivePlayer(app: app, initialPane: PlayerPane.lyrics),
        ),
      );
      await _pumpPlayerUi(tester);

      final line2Finder = find.text('Sample Line 2');
      expect(line2Finder, findsOneWidget);

      await tester.tap(line2Finder);
      await tester.pump();

      expect(app.position.value, const Duration(seconds: 4));
      expect(audio.lastSeek, const Duration(seconds: 4));

      final line1Finder = find.text('Sample Line 1');
      expect(line1Finder, findsOneWidget);

      await tester.tap(line1Finder);
      await tester.pump();

      expect(app.position.value, Duration.zero);
      expect(audio.lastSeek, Duration.zero);

      await app.close();
    },
  );

  testWidgets(
    'rapid sequential lyric line taps cleanly update seek position without throwing',
    (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() => tester.view.resetPhysicalSize());

      final audio = _Audio();
      final app = MusicApplication(
        settings: Settings.memory(),
        api: InnerTube(),
        audio: audio,
        lyrics: _Lyrics(),
      );
      final song = Song(
        id: 'local:s1',
        title: 'Test Song',
        artist: 'Artist',
        localUri: Uri.file('/bitchord-player-navigation-test.flac'),
      );
      await app.play(song);

      await tester.pumpWidget(
        MaterialApp(
          theme: appTheme(app.settings, Brightness.dark),
          home: ImmersivePlayer(app: app, initialPane: PlayerPane.lyrics),
        ),
      );
      await _pumpPlayerUi(tester);

      final line1Finder = find.text('Sample Line 1');
      final line2Finder = find.text('Sample Line 2');

      await tester.tap(line1Finder);
      await tester.tap(line2Finder);
      await tester.pump();

      expect(app.position.value, const Duration(seconds: 4));
      expect(audio.lastSeek, const Duration(seconds: 4));

      await app.close();
    },
  );
}
