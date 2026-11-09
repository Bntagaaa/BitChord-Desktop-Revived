import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/data/lyrics_client.dart';

void main() {
  group('DesktopLyricsClient parseLrc', () {
    test('parses standard [mm:ss.xx] timestamps into milliseconds', () {
      const lrc = '''
[00:12.50]Hello from the other side
[00:18.25]I must have called a thousand times
[00:24.00]To tell you I'm sorry
''';

      final lines = DesktopLyricsClient.parseLrc(lrc);
      expect(lines.length, greaterThanOrEqualTo(3));
      // First line has intro gap because 12.5s >= 3s
      expect(lines[0].timeMs, 0);
      expect(lines[0].text, '');
      expect(lines[1].timeMs, 12500);
      expect(lines[1].text, 'Hello from the other side');
      expect(lines[2].timeMs, 18250);
      expect(lines[2].text, 'I must have called a thousand times');
      expect(lines[3].timeMs, 24000);
      expect(lines[3].text, "To tell you I'm sorry");
    });

    test('handles inline word tags by stripping them', () {
      const lrc = '[01:05.10]I <01:05.30>love <01:05.70>you';
      final lines = DesktopLyricsClient.parseLrc(lrc);
      expect(lines.last.text, 'I love you');
    });

    test('returns empty list for empty or non-timestamped text', () {
      const lrc = 'Just plain lyrics text with no timestamps';
      final lines = DesktopLyricsClient.parseLrc(lrc);
      expect(lines, isEmpty);
    });
  });
}
