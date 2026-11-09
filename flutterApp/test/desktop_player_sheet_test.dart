import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/domain/song.dart';
import 'package:bitchord_desktop/presentation/desktop_player_sheet.dart';
import 'package:bitchord_desktop/presentation/desktop_theme.dart';

void main() {
  testWidgets('DesktopPlayerSheet renders controls and lyrics when visible',
      (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    const testSong = Song(
      videoId: 'test-vid',
      title: 'Test Song Title',
      artist: 'Test Artist Name',
      thumbnailUrl: null,
      durationSeconds: 200,
    );

    bool dismissed = false;
    bool played = false;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: DesktopPlayerSheet(
            visible: true,
            song: testSong,
            isPlaying: false,
            isLoading: false,
            progress: 0.25,
            volume: 0.8,
            shuffle: false,
            palette: darkPalette,
            onDismiss: () => dismissed = true,
            onPlayPause: () => played = true,
            onPrevious: () {},
            onNext: () {},
            onSeek: (_) {},
            onVolumeChange: (_) {},
            onToggleShuffle: () {},
          ),
        ),
      ),
    );

    await tester.pumpAndSettle();

    expect(find.text('Test Song Title'), findsOneWidget);
    expect(find.text('Test Artist Name'), findsOneWidget);
    expect(find.text('Lyrics'), findsOneWidget);

    // Tap play button
    final playBtnFinder = find.byIcon(Icons.play_arrow_rounded);
    expect(playBtnFinder, findsOneWidget);
    await tester.tap(playBtnFinder);
    expect(played, isTrue);

    // Tap minimize button
    final minimizeBtnFinder = find.byIcon(Icons.keyboard_arrow_down_rounded);
    expect(minimizeBtnFinder, findsOneWidget);
    await tester.tap(minimizeBtnFinder);
    expect(dismissed, isTrue);
  });
}
