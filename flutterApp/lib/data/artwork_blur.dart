// Original sharedUi ArtworkMeshBackdrop.kt: three clamped box passes on a
// maximum 128 px decode. This is the full-cover landscape background, distinct
// from the seam-aware mesh used by the original portrait/replay surfaces.
import 'dart:math' as math;
import 'dart:typed_data';

Uint8List blurArtworkRgba(Uint8List rgba, int width, int height) {
  if (width < 1 || height < 1 || rgba.length != width * height * 4) {
    throw ArgumentError('Invalid artwork pixel dimensions');
  }
  if (width > 128 || height > 128) {
    throw ArgumentError(
      'Artwork must be decoded within the source 128px budget',
    );
  }
  if (width < 2 || height < 2) return Uint8List.fromList(rgba);
  final source = Uint8List.fromList(rgba), target = Uint8List(rgba.length);
  final radius = math.max(2, math.min(width, height) ~/ 12);
  for (var pass = 0; pass < 3; pass++) {
    _boxPass(source, target, width, height, radius, true);
    _boxPass(target, source, width, height, radius, false);
  }
  return source;
}

void _boxPass(
  Uint8List source,
  Uint8List target,
  int width,
  int height,
  int radius,
  bool horizontal,
) {
  final major = horizontal ? width : height,
      minor = horizontal ? height : width,
      window = radius * 2 + 1;
  for (var fixed = 0; fixed < minor; fixed++) {
    int offset(int at) {
      final position = at.clamp(0, major - 1);
      return (horizontal
              ? fixed * width + position
              : position * width + fixed) *
          4;
    }

    var red = 0, green = 0, blue = 0;
    for (var delta = -radius; delta <= radius; delta++) {
      final at = offset(delta);
      red += source[at];
      green += source[at + 1];
      blue += source[at + 2];
    }
    for (var moving = 0; moving < major; moving++) {
      final at =
          (horizontal ? fixed * width + moving : moving * width + fixed) * 4;
      target[at] = red ~/ window;
      target[at + 1] = green ~/ window;
      target[at + 2] = blue ~/ window;
      target[at + 3] = 255;
      final leaving = offset(moving - radius),
          entering = offset(moving + radius + 1);
      red += source[entering] - source[leaving];
      green += source[entering + 1] - source[leaving + 1];
      blue += source[entering + 2] - source[leaving + 2];
    }
  }
}
