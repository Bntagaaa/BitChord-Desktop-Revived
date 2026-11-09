import 'package:bitchord/data/innertube.dart';
import 'package:bitchord/data/lyrics.dart';
import 'package:bitchord/data/renderer_parser.dart';
import 'package:bitchord/domain/music.dart';
import 'package:bitchord/domain/queue.dart';
import 'package:bitchord/ui/player.dart';
import 'package:bitchord/ui/theme.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('SAPISID preference order and origin-bound signing follow source', () {
    expect(
      InnerTube.sapisid(
        '__Secure-3PAPISID=third; SAPISID=first; __Secure-1PAPISID=last',
      ),
      'first',
    );
    expect(InnerTube.sapisid('__Secure-1PAPISID=last'), 'last');
    expect(InnerTube.authorization('value', 1), startsWith('SAPISIDHASH 1_'));
    expect(
      InnerTube.authorization('value', 1).length,
      'SAPISIDHASH 1_'.length + 40,
    );
  });
  test(
    'lyrics search preserves recording variants while stripping upload credits',
    () {
      expect(
        lyricsTitle('Song (Live) (feat. Artist) [Official Video]'),
        'Song (Live)',
      );
      expect(lyricsTitle('Song (Remix)'), 'Song (Remix)');
    },
  );
  test(
    'LRC gap threshold and fractional milliseconds follow the 4 second policy',
    () {
      final rows = parseLrc(
        '[00:04.00]Opening\n[00:06.125]Next\n[00:07.00]\n[00:08.00]Closing\n[00:09.00]',
      );
      expect(rows.map((line) => line.time.inMilliseconds), [
        0,
        4000,
        6125,
        8000,
        9000,
      ]);
    },
  );
  test(
    'context queue preserves manually queued entries and their identities',
    () {
      const first = Song(
        id: 'local:first',
        title: 'first',
        artist: '',
        localUri: null,
      );
      const second = Song(
        id: 'local:second',
        title: 'second',
        artist: '',
        localUri: null,
      );
      final queue = QueueTimeline()
        ..oneOff(first, 'Local Music')
        ..enqueue(second);
      final identity = queue.entries.last.id;
      queue.context([first, first], 1, 'Local Music');
      expect(queue.index, 1);
      expect(queue.entries.last.id, identity);
      expect(queue.entries.last.tier, QueueTier.user);
    },
  );
  test('MPED cards preserve the actual video identity as a track target', () {
    final item = RendererParser().card({
      'title': {
        'runs': [
          {'text': 'Never Gonna Give You Up'},
        ],
      },
      'navigationEndpoint': {
        'browseEndpoint': {'browseId': 'MPEDdQw4w9WgXcQ'},
      },
    });
    expect(item?.song?.id, 'dQw4w9WgXcQ');
    expect(item?.browseId, isNull);
  });
  test(
    'cover bounds and action midpoint follow the shared column geometry',
    () {
      final geometry = PlayerGeometry.calculate(490, 716, 88, 24);
      expect(geometry.size, 490);
      expect(geometry.top, 57);
      expect(geometry.actionsTop, 587.5);
    },
  );
  test('jumping past context preserves manual queue rows and their origin', () {
    const first = Song(id: 'local:first', title: 'first', artist: '');
    const second = Song(id: 'local:second', title: 'second', artist: '');
    const manual = Song(id: 'local:manual', title: 'manual', artist: '');
    final queue = QueueTimeline()..context([first, second], 0, 'Album');
    queue.enqueue(manual);
    final identity = queue.entries[1].id;
    queue.jump(2);
    expect(queue.current, second);
    expect(queue.entries[1].id, identity);
    expect(queue.source, 'Album');
  });
  test('both theme palettes retain all source color roles', () {
    expect(AppColors.dark.values.keys.toSet(), AppColors.light.values.keys.toSet());
  });
}
