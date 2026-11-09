import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/application/app_controller.dart';
import 'package:bitchord_desktop/application/download_service.dart';
import 'package:bitchord_desktop/application/local_music_service.dart';
import 'package:bitchord_desktop/data/auth_store.dart';
import 'package:bitchord_desktop/data/innertube_client.dart';
import 'package:bitchord_desktop/data/local_store.dart';
import 'package:bitchord_desktop/data/music_repository.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/playback/playback_controller.dart';
import 'package:bitchord_desktop/presentation/desktop_shell.dart';
import 'package:bitchord_desktop/presentation/desktop_theme.dart';
import 'package:bitchord_desktop/presentation/menus.dart';
import 'package:bitchord_desktop/presentation/player_views.dart';
import 'package:bitchord_desktop/presentation/widgets.dart';

const _song = Song(
  videoId: 'fixture',
  title:
      'A long song title that should wrap gracefully across a narrow desktop window',
  artist: 'A long artist name',
  durationText: '3:45',
  albumName: 'Album',
);

class _PlaybackFixture extends PlaybackController {
  Song? activeSong;
  @override
  Song? get currentSong => activeSong;
  @override
  Duration get duration => const Duration(minutes: 3, seconds: 45);
  @override
  Duration get position => const Duration(seconds: 12);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory directory;
  late AppController app;
  late _PlaybackFixture playback;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp('bitchord-ui-');
    final store = await LocalStore.open(directory: directory);
    final client = InnertubeClient();
    playback = _PlaybackFixture();
    app = AppController(
      store: store,
      client: client,
      repository: MusicRepository(client: client),
      playback: playback,
      authStore: AuthStore(),
      localMusic: LocalMusicService(Directory('${directory.path}/artwork')),
      downloadService: DownloadService(
        resolver: playback.resolver,
        directory: Directory('${directory.path}/downloads'),
      ),
    );
    app.homeFeed = const HomeFeed(
      shelves: [
        HomeShelf(
          title: 'Top Picks for You',
          items: [
            ShelfItem(
              title: 'A personal station',
              subtitle: 'Music you love',
              browseId: 'fixture',
            ),
            ShelfItem(
              title: 'Another station',
              subtitle: 'Discover something new',
              browseId: 'fixture2',
            ),
          ],
        ),
      ],
    );
  });
  tearDown(() async {
    await app.close();
    await directory.delete(recursive: true);
  });

  Widget host(Widget child, {Brightness brightness = Brightness.dark}) =>
      MaterialApp(
        theme: buildDesktopTheme(brightness: brightness),
        home: Scaffold(body: child),
      );

  testWidgets(
    'main destinations stay usable at narrow and regular desktop sizes',
    (tester) async {
      addTearDown(() => tester.view.resetPhysicalSize());
      addTearDown(() => tester.view.resetDevicePixelRatio());
      tester.view.devicePixelRatio = 1;
      for (final size in const [
        Size(800, 600),
        Size(1280, 720),
        Size(800, 600),
      ]) {
        tester.view.physicalSize = size;
        for (final route in [
          AppRoute.home,
          AppRoute.library,
          AppRoute.search,
          AppRoute.settings,
          AppRoute.spotify,
          AppRoute.downloads,
        ]) {
          app.route = route;
          await tester.pumpWidget(host(DesktopShell(controller: app)));
          await tester.pump(const Duration(milliseconds: 90));
          expect(
            tester.takeException(),
            isNull,
            reason: '$route during the sidebar transition at $size',
          );
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull, reason: '$route at $size');
        }
      }
    },
  );

  testWidgets('empty and playing dock retain the source 58 pixel height', (
    tester,
  ) async {
    for (final song in <Song?>[null, _song]) {
      playback.activeSong = song;
      await tester.pumpWidget(
        host(
          Center(
            child: SizedBox(
              width: 760,
              child: PlayerDock(app: app, onExpand: () {}, onPanel: (_) {}),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.getSize(find.byType(PlayerDock)).height, 58);
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets('song menu has transparent barrier and functional sleep action', (
    tester,
  ) async {
    await tester.pumpWidget(
      host(
        Builder(
          builder: (context) => TextButton(
            onPressed: () => showSongMenu(context, app, _song),
            child: const Text('Open menu'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open menu'));
    await tester.pumpAndSettle();
    expect(find.text('Play next'), findsOneWidget);
    expect(find.text('Dislike'), findsOneWidget);
    final barriers = tester.widgetList<ModalBarrier>(find.byType(ModalBarrier));
    expect(barriers, isNotEmpty);
    expect(
      // Flutter represents a zero-alpha modal barrier with a null color.
      barriers.every((barrier) => (barrier.color?.a ?? 0) == 0),
      isTrue,
    );
    await tester.ensureVisible(find.text('Sleep timer'));
    await tester.tap(find.text('Sleep timer'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('End of this track'));
    await tester.pumpAndSettle();
    expect(playback.sleepAfterTrack, isTrue);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'light lyrics use the semantic foreground and seeking is optional',
    (tester) async {
      app.lyrics = const LyricsDocument(
        source: 'Test',
        lines: [LyricLine(text: 'Readable in light mode')],
      );
      await tester.pumpWidget(
        host(
          SizedBox(width: 340, child: LyricsView(app: app)),
          brightness: Brightness.light,
        ),
      );
      await tester.pumpAndSettle();
      final line = tester.widget<Text>(find.text('Readable in light mode'));
      expect(line.style?.color, DesktopPalette.light.foreground);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('fullscreen landscape keeps artwork across pane changes', (
    tester,
  ) async {
    addTearDown(() => tester.view.resetPhysicalSize());
    addTearDown(() => tester.view.resetDevicePixelRatio());
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1280, 720);
    playback.activeSong = _song;
    await tester.pumpWidget(
      host(NowPlayingView(app: app, onClose: () {})),
    );
    await tester.pumpAndSettle();
    expect(find.byType(Artwork), findsWidgets);
    expect(find.byTooltip('Open lyrics'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.byTooltip('Open lyrics'));
    await tester.pumpAndSettle();
    expect(find.byType(LyricsView), findsOneWidget);
    expect(find.byType(Artwork), findsWidgets);
    expect(tester.takeException(), isNull);

    await tester.tap(find.byTooltip('Up next'));
    await tester.pumpAndSettle();
    expect(find.byType(QueueView), findsOneWidget);
    expect(find.byType(LyricsView), findsNothing);
    expect(find.byType(Artwork), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Quick Search opens with keyboard focus', (tester) async {
    await tester.pumpWidget(host(DesktopShell(controller: app)));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Quick Search (Ctrl+K)'));
    await tester.pumpAndSettle();
    expect(find.text('Quick Search'), findsOneWidget);
    expect(
      tester.widget<TextField>(find.byType(TextField)).focusNode?.hasFocus,
      isTrue,
    );
    expect(tester.takeException(), isNull);
  });
}
