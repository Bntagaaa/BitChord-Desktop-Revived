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

    // Tap beyond left edge
    await tester.tapAt(sliderTopLeft + const Offset(-20.0, 14.0));
    await tester.pumpAndSettle();
    expect(currentValue, 0.0);

    // Tap beyond right edge
    await tester.tapAt(sliderTopLeft + const Offset(150.0, 14.0));
    await tester.pumpAndSettle();
    expect(currentValue, 1.0);
  });
}
