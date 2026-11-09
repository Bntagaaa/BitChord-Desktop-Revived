import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/presentation/desktop_theme.dart';
import 'package:bitchord_desktop/presentation/desktop_thin_slider.dart';

void main() {
  testWidgets('DesktopThinSlider renders and handles tap gesture', (tester) async {
    double currentValue = 0.5;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: DesktopThinSlider(
              value: currentValue,
              width: 100.0,
              palette: darkPalette,
              onChanged: (val) {
                currentValue = val;
              },
            ),
          ),
        ),
      ),
    );

    expect(find.byType(DesktopThinSlider), findsOneWidget);

    // Tap at 80% of width
    final sliderFinder = find.byType(DesktopThinSlider);
    final sliderTopLeft = tester.getTopLeft(sliderFinder);
    await tester.tapAt(sliderTopLeft + const Offset(80.0, 14.0));
    await tester.pumpAndSettle();

    expect(currentValue, closeTo(0.8, 0.05));
  });

  testWidgets('DesktopThinSlider clamps between 0.0 and 1.0', (tester) async {
    double currentValue = 0.5;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: DesktopThinSlider(
              value: currentValue,
              width: 100.0,
              palette: darkPalette,
              onChanged: (val) {
                currentValue = val;
              },
            ),
          ),
        ),
      ),
    );

    final sliderFinder = find.byType(DesktopThinSlider);
    final sliderTopLeft = tester.getTopLeft(sliderFinder);

    // Tap at left edge (0%)
    await tester.tapAt(sliderTopLeft + const Offset(0.0, 14.0));
    await tester.pumpAndSettle();
    expect(currentValue, 0.0);

    // Tap at right edge (100%)
    await tester.tapAt(sliderTopLeft + const Offset(100.0, 14.0));
    await tester.pumpAndSettle();
    expect(currentValue, 1.0);

    // Drag from center beyond left edge (clamps to 0.0)
    final gesture =
        await tester.startGesture(sliderTopLeft + const Offset(50.0, 14.0));
    await gesture.moveBy(const Offset(-100.0, 0.0));
    await gesture.up();
    await tester.pumpAndSettle();
    expect(currentValue, 0.0);

    // Drag from center beyond right edge (clamps to 1.0)
    final gesture2 =
        await tester.startGesture(sliderTopLeft + const Offset(50.0, 14.0));
    await gesture2.moveBy(const Offset(200.0, 0.0));
    await gesture2.up();
    await tester.pumpAndSettle();
    expect(currentValue, 1.0);
  });
}
