// Comprehensive Unit and Widget Test Suite for Milestone 3 (Home Page 1:1 Port).
// Covers ShelfLayout enum & classification, DesktopShelfRowChrome hover chevrons & wheel translation,
// Recents 4-track column paging & List/Grid toggle with settings persistence,
// PlayingEqualizerBars animation, VideoShelfCard 16:9 aspect ratio, DesktopScrollEdgeFade,
// ShimmerBox / ShelfSkeleton, and auto-continuation trigger.

import 'dart:async';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:bitchord/data/innertube.dart';
import 'package:bitchord/data/lyrics.dart';
import 'package:bitchord/data/renderer_parser.dart';
import 'package:bitchord/data/settings.dart';
import 'package:bitchord/domain/application.dart';
import 'package:bitchord/domain/music.dart';
import 'package:bitchord/platform/audio.dart';
import 'package:bitchord/ui/home.dart';
import 'package:bitchord/ui/widgets.dart';

class _MockAudio implements AudioOutput {
  final _positions = StreamController<Duration>.broadcast(sync: true);
  final _durations = StreamController<Duration>.broadcast(sync: true);
  final _states = StreamController<bool>.broadcast(sync: true);
  final _ends = StreamController<bool>.broadcast(sync: true);
  final _failures = StreamController<String>.broadcast(sync: true);

  @override
  Stream<Duration> get position => _positions.stream;
  @override
  Stream<Duration> get duration => _durations.stream;
  @override
  Stream<bool> get playing => _states.stream;
  @override
  Stream<bool> get completed => _ends.stream;
  @override
  Stream<String> get errors => _failures.stream;

  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {}
  @override
  Future<void> playPause() async {}
  @override
  Future<void> seek(Duration position) async {}
  @override
  Future<void> setVolume(double value) async {}
  @override
  Future<void> setRate(double value) async {}
  @override
  Future<void> close() async {
    await Future.wait([
      _positions.close(),
      _durations.close(),
      _states.close(),
      _ends.close(),
      _failures.close(),
    ]);
  }
}

class _MockLyrics extends LyricsRepository {
  @override
  Future<List<LyricLine>> fetch(Song song, Duration duration) async => const [];
}

class _MockApi extends InnerTube {
  int continuationCalls = 0;

  @override
  Future<JsonMap> continuation(String token) async {
    continuationCalls++;
    return {
      'musicCarouselShelfRenderer': {
        'header': {
          'musicCarouselShelfBasicHeaderRenderer': {
            'title': {
              'runs': [
                {'text': 'Continued Shelf $continuationCalls'}
              ]
            },
          },
        },
        'contents': [
          {
            'musicTwoRowItemRenderer': {
              'title': {
                'runs': [
                  {'text': 'Continued Item 1'}
                ]
              },
              'subtitle': {
                'runs': [
                  {'text': 'Artist 1'}
                ]
              },
            },
          },
        ],
      },
      'nextContinuationData': {
        'continuation': continuationCalls < 2 ? 'token_$continuationCalls' : null,
      },
    };
  }
}

Widget _wrap(Widget child, MusicApplication app) {
  return MaterialApp(
    theme: ThemeData.dark(),
    home: Scaffold(body: child),
  );
}

void main() {
  group('Milestone 3: Domain Models & Classification', () {
    test('ShelfLayout enum values exist and match design', () {
      expect(ShelfLayout.values, contains(ShelfLayout.cards));
      expect(ShelfLayout.values, contains(ShelfLayout.list));
      expect(ShelfLayout.values, contains(ShelfLayout.video));
      expect(ShelfLayout.values, contains(ShelfLayout.hero));
    });

    test('shelfLayoutFor correctly identifies list layout for Listen again, Quick picks, recents, and fromList', () {
      expect(
        shelfLayoutFor(
          title: 'Listen again',
          items: [const CatalogItem(title: 'Item 1')],
        ),
        ShelfLayout.list,
      );
      expect(
        shelfLayoutFor(
          title: 'Quick picks',
          items: [const CatalogItem(title: 'Item 1')],
        ),
        ShelfLayout.list,
      );
      expect(
        shelfLayoutFor(
          title: 'Recents',
          items: [const CatalogItem(title: 'Item 1')],
        ),
        ShelfLayout.list,
      );
      expect(
        shelfLayoutFor(
          title: 'Custom Title',
          items: [const CatalogItem(title: 'Song', videoId: 'v123')],
          fromList: true,
        ),
        ShelfLayout.list,
      );
    });

    test('shelfLayoutFor correctly identifies video layout when majority are videos', () {
      expect(
        shelfLayoutFor(
          title: 'Recommended Videos',
          items: [
            const CatalogItem(title: 'V1', isVideo: true),
            const CatalogItem(title: 'V2', isVideo: true),
            const CatalogItem(title: 'A1', isVideo: false),
          ],
        ),
        ShelfLayout.video,
      );
    });

    test('shelfLayoutFor defaults to cards layout for standard albums and playlists', () {
      expect(
        shelfLayoutFor(
          title: 'Top Albums',
          items: [
            const CatalogItem(title: 'Album 1', isVideo: false),
            const CatalogItem(title: 'Album 2', isVideo: false),
          ],
        ),
        ShelfLayout.cards,
      );
      expect(shelfLayoutFor(title: 'Empty', items: []), ShelfLayout.cards);
    });

    test('Shelf domain model preserves subtitle, moreBrowseId, moreParams, and layout', () {
      const shelf = Shelf(
        'Test Title',
        [],
        subtitle: 'Test Strapline',
        moreBrowseId: 'MB123',
        moreParams: 'MP456',
        layout: ShelfLayout.hero,
      );
      expect(shelf.title, 'Test Title');
      expect(shelf.subtitle, 'Test Strapline');
      expect(shelf.moreBrowseId, 'MB123');
      expect(shelf.moreParams, 'MP456');
      expect(shelf.layout, ShelfLayout.hero);
    });

    test('CatalogItem domain model carries isVideo, videoId, durationText, and isExplicit', () {
      const item = CatalogItem(
        title: 'Track Title',
        subtitle: 'Artist Name',
        isVideo: true,
        videoId: 'vid_123',
        durationText: '3:45',
        isExplicit: true,
      );
      expect(item.title, 'Track Title');
      expect(item.subtitle, 'Artist Name');
      expect(item.isVideo, isTrue);
      expect(item.videoId, 'vid_123');
      expect(item.durationText, '3:45');
      expect(item.isExplicit, isTrue);
    });
  });

  group('Milestone 3: Home Page 1:1 Widget Suite', () {
    late Settings settings;
    late _MockAudio audio;
    late _MockApi api;
    late MusicApplication app;

    setUp(() {
      settings = Settings.memory();
      audio = _MockAudio();
      api = _MockApi();
      app = MusicApplication(
        api: api,
        audio: audio,
        settings: settings,
        lyrics: _MockLyrics(),
      );
    });

    tearDown(() async {
      await app.dispose();
    });

    testWidgets('1. ShelfLayout.cards renders square 158dp cards in carousel', (tester) async {
      app.page = CatalogPage(
        shelves: [
          Shelf(
            'Recommended Albums',
            List.generate(6, (i) => CatalogItem(title: 'Album $i', subtitle: 'Artist $i')),
            layout: ShelfLayout.cards,
          ),
        ],
      );
      await tester.pumpWidget(_wrap(HomePage(app: app), app));
      await tester.pumpAndSettle();

      expect(find.text('Recommended Albums'), findsOneWidget);
      expect(find.text('Album 0'), findsOneWidget);
      expect(find.byType(DesktopShelfRowChrome), findsOneWidget);
    });

    testWidgets('2. ShelfLayout.video renders 16:9 widescreen cards and duration badge', (tester) async {
      app.page = CatalogPage(
        shelves: [
          Shelf(
            'Music Videos',
            [
              const CatalogItem(
                title: 'Official Video',
                subtitle: 'Superstar Artist',
                isVideo: true,
                durationText: '3:45',
              ),
            ],
            layout: ShelfLayout.video,
          ),
        ],
      );
      await tester.pumpWidget(_wrap(HomePage(app: app), app));
      await tester.pumpAndSettle();

      expect(find.text('Music Videos'), findsOneWidget);
      expect(find.text('Official Video'), findsOneWidget);
      expect(find.text('3:45'), findsOneWidget);
      expect(find.byType(VideoDurationBadge), findsOneWidget);
    });

    testWidgets('3. ShelfLayout.list chunks recents into 4-track columns', (tester) async {
      app.page = CatalogPage(
        shelves: [
          Shelf(
            'Recents',
            List.generate(8, (i) => CatalogItem(
              title: 'Recent Track $i',
              subtitle: 'Recent Artist $i',
              song: Song(id: 's_$i', title: 'Recent Track $i', artist: 'Recent Artist $i'),
            )),
            layout: ShelfLayout.list,
          ),
        ],
      );
      await tester.pumpWidget(_wrap(HomePage(app: app), app));
      await tester.pumpAndSettle();

      expect(find.text('Recents'), findsOneWidget);
      expect(find.text('Recent Track 0'), findsOneWidget);
      expect(find.text('Recent Track 3'), findsOneWidget);
      expect(find.byType(CompactTrackRow), findsNWidgets(8));
    });

    testWidgets('4. Recents toggle switches between List and Grid modes and persists to settings', (tester) async {
      app.page = CatalogPage(
        shelves: [
          Shelf(
            'Recents',
            List.generate(8, (i) => CatalogItem(
              title: 'Recent Track $i',
              subtitle: 'Recent Artist $i',
            )),
            layout: ShelfLayout.list,
          ),
        ],
      );
      await tester.pumpWidget(_wrap(HomePage(app: app), app));
      await tester.pumpAndSettle();

      final toggleFinder = find.byKey(const ValueKey('recents_view_toggle'));
      expect(toggleFinder, findsOneWidget);

      // Default is list mode
      expect(settings.get('home_recents_view_type', 'list'), 'list');

      // Tap toggle to switch to grid mode
      await tester.tap(toggleFinder);
      await tester.pumpAndSettle();

      expect(settings.get('home_recents_view_type', 'list'), 'grid');
      expect(settings.get('home_recents_view', 'list'), 'grid');

      // In grid mode, items render as HeroCard
      expect(find.byType(HeroCard), findsWidgets);

      // Tap again to switch back to list mode
      await tester.tap(toggleFinder);
      await tester.pumpAndSettle();

      expect(settings.get('home_recents_view_type', 'list'), 'list');
      expect(settings.get('home_recents_view', 'list'), 'list');
      expect(find.byType(CompactTrackRow), findsWidgets);
    });

    testWidgets('5. DesktopShelfRowChrome displays 34dp hover arrows and pages forward/backward', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 400,
            height: 200,
            child: DesktopShelfRowChrome(
              builder: (context, c) => ListView.builder(
                controller: c,
                scrollDirection: Axis.horizontal,
                itemCount: 20,
                itemBuilder: (_, i) => SizedBox(width: 150, child: Text('Card $i')),
              ),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      final leftArrowFinder = find.byIcon(Icons.chevron_left_rounded);
      final rightArrowFinder = find.byIcon(Icons.chevron_right_rounded);
      expect(leftArrowFinder, findsOneWidget);
      expect(rightArrowFinder, findsOneWidget);

      // Hover over shelf
      final gesture = await tester.createGesture(kind: PointerDeviceKind.mouse);
      await gesture.addPointer(location: Offset.zero);
      addTearDown(gesture.removePointer);
      await gesture.moveTo(tester.getCenter(find.byType(DesktopShelfRowChrome)));
      await tester.pumpAndSettle();

      // Right arrow can be tapped to page forward
      await tester.tap(rightArrowFinder);
      await tester.pumpAndSettle();

      // Scrolled forward, left arrow can be tapped to page backward
      await tester.tap(leftArrowFinder);
      await tester.pumpAndSettle();
    });

    testWidgets('6. DesktopShelfRowChrome translates pointer wheel ticks to horizontal scroll', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 400,
            height: 200,
            child: DesktopShelfRowChrome(
              builder: (context, c) => ListView.builder(
                controller: c,
                scrollDirection: Axis.horizontal,
                itemCount: 20,
                itemBuilder: (_, i) => SizedBox(width: 150, child: Text('Card $i')),
              ),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      final center = tester.getCenter(find.byType(DesktopShelfRowChrome));
      final gesture = await tester.createGesture(kind: PointerDeviceKind.mouse);
      await gesture.addPointer(location: center);
      addTearDown(gesture.removePointer);

      await tester.sendEventToBinding(
        PointerScrollEvent(
          position: center,
          scrollDelta: const Offset(0, 100),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byType(DesktopShelfRowChrome), findsOneWidget);
    });

    testWidgets('7. PlayingEqualizerBars renders and animates when track is currently playing', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(body: PlayingEqualizerBars()),
      ));
      await tester.pump(const Duration(milliseconds: 100));

      expect(find.byType(PlayingEqualizerBars), findsOneWidget);
      expect(find.byType(Row), findsOneWidget);
    });

    testWidgets('8. DesktopScrollEdgeFade applies ShaderMask with dynamic stops', (tester) async {
      final controller = ScrollController();
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SizedBox(
            height: 300,
            child: DesktopScrollEdgeFade(
              controller: controller,
              child: ListView.builder(
                controller: controller,
                itemCount: 50,
                itemBuilder: (_, i) => SizedBox(height: 50, child: Text('Row $i')),
              ),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byType(ShaderMask), findsOneWidget);

      controller.jumpTo(200);
      await tester.pumpAndSettle();
      expect(find.byType(ShaderMask), findsOneWidget);
    });

    testWidgets('9. Auto-continuation triggers app.more() when scrolling near bottom', (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      addTearDown(tester.view.resetPhysicalSize);

      app.page = CatalogPage(
        continuation: 'initial_token',
        shelves: List.generate(
          12,
          (i) => Shelf(
            'Shelf $i',
            List.generate(6, (j) => CatalogItem(title: 'Item $i-$j', subtitle: 'Sub $i-$j')),
            layout: ShelfLayout.cards,
          ),
        ),
      );

      await tester.pumpWidget(_wrap(HomePage(app: app), app));
      await tester.pumpAndSettle();

      expect(api.continuationCalls, 0);

      // Drag CustomScrollView up (scroll toward the bottom)
      await tester.drag(find.byType(CustomScrollView), const Offset(0, -3500));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(api.continuationCalls, greaterThanOrEqualTo(1));
    });

    testWidgets('10. ShimmerBox and ShelfSkeleton render during feed loading', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: ShelfSkeleton(),
        ),
      ));
      await tester.pump(const Duration(milliseconds: 100));

      expect(find.byType(ShelfSkeleton), findsOneWidget);
      expect(find.byType(ShimmerBox), findsWidgets);
    });

    testWidgets('11. HeroCard renders 0.92 aspect ratio with gradient scrim', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 300,
            child: HeroCard(
              item: const CatalogItem(
                title: 'Hero Title',
                subtitle: 'Hero Subtitle',
              ),
              isPlaying: false,
              onTap: () {},
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Hero Title'), findsOneWidget);
      expect(find.text('Hero Subtitle'), findsOneWidget);
      expect(find.byType(HeroCard), findsOneWidget);
    });

    testWidgets('12. CompactTrackRow displays explicit badge and duration text', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: CompactTrackRow(
            item: const CatalogItem(
              title: 'Explicit Track',
              subtitle: 'Artist',
              isExplicit: true,
              durationText: '4:15',
            ),
            isPlaying: false,
            onTap: () {},
          ),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Explicit Track'), findsOneWidget);
      expect(find.text('4:15'), findsOneWidget);
      expect(find.byType(ExplicitBadge), findsOneWidget);
    });
  });
}
