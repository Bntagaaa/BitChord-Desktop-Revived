import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/data/innertube_client.dart';
import 'package:bitchord_desktop/data/lyrics_service.dart';

void main() {
  test('enhanced LRC retains word timestamps, offset and duet alignment', () {
    final lyrics = LyricsService.parseLrc('''[ar:Artist]
[offset:-125]
[00:12.30]<R><00:12.30>Hello <00:12.750>world<00:13.50>
[00:20.00][00:35.00]Repeated line
''');
    expect(lyrics.isSynced, isTrue);
    expect(lyrics.offset, const Duration(milliseconds: -125));
    expect(lyrics.lines.map((l) => l.text), [
      'Hello world',
      'Repeated line',
      'Repeated line',
    ]);
    expect(lyrics.lines.first.alignment, LyricAlignment.end);
    expect(
      lyrics.lines.first.words.first.start,
      const Duration(milliseconds: 12300),
    );
    expect(
      lyrics.lines.first.words.last.end,
      const Duration(milliseconds: 13500),
    );
    expect(lyrics.activeIndex(const Duration(milliseconds: 12424)), -1);
    expect(lyrics.activeIndex(const Duration(milliseconds: 12425)), 0);
  });

  test('TTML reads namespace-independent timed words and voice alignment', () {
    final lyrics = LyricsService.parseTtml(
      '''<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div><p begin="1.2s" end="00:00:03.000" ttm:agent="v2"><span begin="1200ms" end="2s">Hello </span><span begin="2s" end="3s">world</span></p></div></body></tt>''',
    );
    expect(lyrics.lines.single.start, const Duration(milliseconds: 1200));
    expect(lyrics.lines.single.end, const Duration(seconds: 3));
    expect(lyrics.lines.single.text, 'Hello world');
    expect(lyrics.lines.single.words, hasLength(2));
    expect(lyrics.lines.single.alignment, LyricAlignment.end);
  });

  test(
    'QRC suffix and YRC prefix word timing formats produce the same text',
    () {
      final qrc = LyricsService.parseKaraoke(
        '[1000,1500]Hello (1000,500)world(1500,1000)',
      );
      final yrc = LyricsService.parseKaraoke(
        '[1000,1500](1000,500,0)Hello (1500,1000,0)world',
      );
      expect(qrc.lines.single.text, 'Hello world');
      expect(yrc.lines.single.text, 'Hello world');
      expect(
        qrc.lines.single.words.last.end,
        const Duration(milliseconds: 2500),
      );
      expect(
        yrc.lines.single.words.last.end,
        const Duration(milliseconds: 2500),
      );
    },
  );

  test('provider failures and HTML errors cannot masquerade as lyric text', () {
    expect(
      LyricsService.parseProvider(
        '{"error":"Too many requests","lyrics":"error"}',
        source: 'test',
      ),
      isNull,
    );
    expect(
      LyricsService.parseProvider(
        '<html><body>Unavailable</body></html>',
        source: 'test',
      ),
      isNull,
    );
    expect(
      LyricsService.parseProvider(
        '{"data":{"syncedLyrics":"[00:01.00]Line"}}',
        source: 'test',
      )?.lines.single.text,
      'Line',
    );
  });

  test(
    'translation keeps timing and source text aligned by explicit markers',
    () async {
      final client = MockClient((request) async {
        expect(request.url.host, 'translate.googleapis.com');
        expect(request.bodyFields['q'], contains('\uE0000000\uE001Hello'));
        return http.Response(
          jsonEncode([
            [
              [
                '\uE0000000\uE001Halo\n\uE0000001\uE001Dunia\n',
                null,
                null,
                null,
              ],
            ],
          ]),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      });
      final innertube = InnertubeClient(httpClient: client);
      final service = LyricsService(innertube: innertube, httpClient: client);
      const original = LyricsDocument(
        isSynced: true,
        source: 'test',
        lines: [
          LyricLine(text: 'Hello', start: Duration(seconds: 1)),
          LyricLine(text: 'World', start: Duration(seconds: 3)),
        ],
      );
      final translated = await service.translate(original, 'id');
      expect(translated.lines.map((l) => l.text), ['Hello', 'World']);
      expect(translated.lines.map((l) => l.translation), ['Halo', 'Dunia']);
      expect(translated.lines.last.start, const Duration(seconds: 3));
      service.close();
      innertube.close();
    },
  );
}
