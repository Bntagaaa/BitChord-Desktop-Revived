import 'dart:typed_data';
import 'dart:io';
import 'package:bitchord/data/artwork_blur.dart';
import 'package:bitchord/ui/artwork_backdrop.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'constant artwork preserves the actual cover color through all blur passes',
    () {
      final pixels = Uint8List.fromList([
        for (var i = 0; i < 128 * 128; i++) ...[18, 52, 86, 255],
      ]);
      final original = Uint8List.fromList(pixels);
      expect(blurArtworkRgba(pixels, 128, 128), pixels);
      expect(pixels, original); // Input belongs to Flutter's decoded image.
    },
  );
  test(
    'clamped box blur spreads a bright edge without wrapping it to the far side',
    () {
      final pixels = Uint8List.fromList([
        for (var y = 0; y < 24; y++)
          for (var x = 0; x < 24; x++) ...[x == 0 ? 255 : 0, 0, 0, 255],
      ]);
      final blurred = blurArtworkRgba(pixels, 24, 24);
      expect(blurred[0], greaterThan(0));
      expect(blurred[4], greaterThan(0));
      expect(blurred[23 * 4], 0);
      expect(blurred[1], 0);
      expect(blurred[2], 0);
      expect(blurred[3], 255);
    },
  );
  test('pixel reads enforce the original small-artwork budget', () {
    expect(
      () => blurArtworkRgba(Uint8List(256 * 256 * 4), 256, 256),
      throwsArgumentError,
    );
    expect(() => blurArtworkRgba(Uint8List(1), 1, 1), throwsArgumentError);
    expect(blurArtworkRgba(Uint8List.fromList([1, 2, 3, 255]), 1, 1), [
      1,
      2,
      3,
      255,
    ]);
  });
  testWidgets(
    'prepared real artwork owns its image across cache eviction and clears on track change',
    (tester) async {
      final url = File('assets/icons/app_icon.png').absolute.uri.toString();
      await tester.runAsync(() async {
        final image = await ArtworkBlurCache.shared.read(
          url,
          const ImageConfiguration(),
        );
        expect(image, isNotNull);
        expect(image!.width, lessThanOrEqualTo(128));
        expect(image.height, lessThanOrEqualTo(128));
        image.dispose();
      });
      await tester.pumpWidget(MaterialApp(home: FullArtworkBackdrop(url: url)));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 321));
      expect(find.byType(RawImage), findsOneWidget);
      // The view keeps its own native reference when the LRU entry is removed.
      ArtworkBlurCache.shared.dispose();
      await tester.pump();
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(
        const MaterialApp(home: FullArtworkBackdrop(url: null)),
      );
      await tester.pump();
      expect(find.byType(RawImage), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
