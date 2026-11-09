import 'package:bitchord/data/settings.dart';
import 'package:bitchord/ui/theme.dart';
import 'package:bitchord/ui/widgets.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';

void main() {
  testWidgets('theme and original SVG assets update with saved font choice', (
    tester,
  ) async {
    final settings = Settings.memory();
    await tester.pumpWidget(
      ListenableBuilder(
        listenable: settings,
        builder: (context, _) => MaterialApp(
          theme: appTheme(settings, Brightness.dark),
          home: const Scaffold(
            body: Column(children: [SourceIcon('Home'), Text('BitChord')]),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(SvgPicture), findsOneWidget);
    final initial = Theme.of(tester.element(find.text('BitChord')));
    expect(initial.textTheme.bodyMedium?.fontFamily, 'SFProDefault');
    await settings.set('app_font', 'LEGACY');
    await tester.pumpAndSettle();
    final changed = Theme.of(tester.element(find.text('BitChord')));
    expect(changed.textTheme.bodyMedium?.fontFamily, 'SFProLegacy');
    expect(changed.scaffoldBackgroundColor, AppColors.dark.background);
    await settings.set('desktop_theme_mode', 'DARK');
    await settings.set('desktop_pitch_black', true);
    await tester.pumpAndSettle();
    expect(
      Theme.of(tester.element(find.text('BitChord'))).scaffoldBackgroundColor,
      Colors.black,
    );
  });
}
