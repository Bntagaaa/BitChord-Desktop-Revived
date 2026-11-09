import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/domain/song.dart';
import 'package:bitchord_desktop/presentation/desktop_new_page.dart';
import 'package:bitchord_desktop/presentation/desktop_theme.dart';

void main() {
  testWidgets('DesktopNewPage renders header, albums carousel, and trending grid',
      (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    Song? selected;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: DesktopNewPage(
            palette: darkPalette,
            onSongSelected: (s) {
              selected = s;
            },
          ),
        ),
      ),
    );

    await tester.pumpAndSettle();

    expect(find.text('New'), findsOneWidget);
    expect(find.text('New albums & singles'), findsOneWidget);
    expect(find.text('Trending'), findsOneWidget);
    expect(find.text('Dicari Onty Onty'), findsOneWidget);
    expect(find.text('DIRANTAI DIGELANGI RINDU'), findsOneWidget);

    // Tap on album card
    final albumFinder = find.text('Dicari Onty Onty');
    await tester.tap(albumFinder);
    await tester.pumpAndSettle();

    expect(selected, isNotNull);
    expect(selected!.title, 'Dicari Onty Onty');
  });
}
