import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/playback/queue_timeline.dart';

Song song(String id, {QueueTier tier = QueueTier.context}) =>
    Song(videoId: id, title: id, artist: 'Artist', queueTier: tier);

void main() {
  test('duplicate recordings receive unique immutable queue identities', () {
    final one = song('duplicate');
    var queue = PlaybackQueue.empty().startContext([one, one, one]);
    final keys = queue.orderKeys;
    expect(keys.toSet().length, 3);
    queue = queue.move(2, 1).shuffledAhead(Random(3));
    expect(queue.orderKeys.toSet(), keys.toSet());
    expect(queue.current!.queueEntryId, keys.first);
  });

  test('Add to queue ends manual section; Play next heads it', () {
    var queue = PlaybackQueue.empty().startContext([song('A'), song('B')]);
    queue = queue.appendAutoplay([song('radio')]);
    queue = queue
        .enqueue(song('manual1'))
        .enqueue(song('manual2'))
        .enqueue(song('next'), next: true);
    expect(queue.songs.map((s) => s.videoId), [
      'A',
      'next',
      'manual1',
      'manual2',
      'B',
      'radio',
    ]);
  });

  test(
    'switching context preserves manually queued tracks after selection',
    () {
      var queue = PlaybackQueue.empty().startContext([song('old')]);
      queue = queue.enqueue(song('manual'));
      final manualId = queue.songs[1].queueEntryId;
      queue = queue.startContext(
        [song('album0'), song('album1'), song('album2')],
        at: 1,
        source: 'Album',
      );
      expect(queue.index, 1);
      expect(queue.songs.map((s) => s.videoId), [
        'album0',
        'album1',
        'manual',
        'album2',
      ]);
      expect(queue.songs[2].queueEntryId, manualId);
      expect(queue.current!.playbackSource, 'Album');
    },
  );

  test(
    'jump to context preserves manual queue and removes skipped context',
    () {
      var queue = PlaybackQueue.empty().startContext([
        song('A'),
        song('B'),
        song('C'),
      ]);
      queue = queue.enqueue(song('manual')).appendAutoplay([song('radio')]);
      queue = queue.jump(3);
      expect(queue.current!.videoId, 'C');
      expect(queue.upcoming.map((s) => s.videoId), ['manual', 'radio']);
    },
  );

  test('jump to manual track consumes bypassed manual tracks only', () {
    var queue = PlaybackQueue.empty().startContext([song('A'), song('B')]);
    queue = queue
        .enqueue(song('one'))
        .enqueue(song('two'))
        .enqueue(song('three'))
        .appendAutoplay([song('radio')]);
    queue = queue.jump(2);
    expect(queue.current!.videoId, 'two');
    expect(queue.upcoming.map((s) => s.videoId), ['three', 'B', 'radio']);
  });

  test('jump to autoplay promotes target and keeps requested tracks', () {
    var queue = PlaybackQueue.empty().startContext([song('A'), song('B')]);
    queue = queue.enqueue(song('manual')).appendAutoplay([
      song('radio1'),
      song('radio2'),
    ]);
    queue = queue.jump(3);
    expect(queue.current!.videoId, 'radio1');
    expect(queue.current!.queueTier, QueueTier.context);
    expect(queue.upcoming.map((s) => s.videoId), ['manual', 'radio2']);
  });

  test(
    'shuffle never shuffles manual tier and undo restores each occurrence',
    () {
      var queue = PlaybackQueue.empty().startContext(
        ['A', 'B', 'C', 'D', 'E'].map(song).toList(),
      );
      queue = queue
          .enqueue(song('manual1'))
          .enqueue(song('manual2'))
          .appendAutoplay([song('radio1'), song('radio2')]);
      final order = queue.orderKeys;
      final shuffled = queue.shuffledAhead(Random(8));
      expect(shuffled.songs.take(3).map((s) => s.videoId), [
        'A',
        'manual1',
        'manual2',
      ]);
      expect(shuffled.orderKeys, isNot(order));
      expect(shuffled.inOriginalOrder(order).orderKeys, order);
    },
  );

  test('moving/removing other rows retains the active entry', () {
    var queue = PlaybackQueue.empty().startContext([
      song('A'),
      song('B'),
      song('C'),
    ], at: 1);
    final currentId = queue.current!.queueEntryId;
    queue = queue.move(2, 0);
    expect(queue.index, 2);
    expect(queue.current!.queueEntryId, currentId);
    queue = queue.removeAt(0);
    expect(queue.index, 1);
    expect(queue.current!.queueEntryId, currentId);
    expect(identical(queue.removeAt(queue.index), queue), isTrue);
  });

  test('disabling autoplay preserves all context and manual tracks', () {
    var queue = PlaybackQueue.empty().startContext([song('A'), song('B')]);
    queue = queue.enqueue(song('manual')).appendAutoplay([song('radio')]);
    expect(queue.withoutAutoplay().songs.map((s) => s.videoId), [
      'A',
      'manual',
      'B',
    ]);
    expect(queue.withoutUserQueue().songs.map((s) => s.videoId), [
      'A',
      'B',
      'radio',
    ]);
  });

  test('played manual tracks are consumed when returning to context', () {
    var queue = PlaybackQueue.empty().startContext([song('A'), song('B')]);
    queue = queue.enqueue(song('manual')).next();
    expect(queue.current!.videoId, 'manual');
    queue = queue.next();
    expect(queue.songs.map((s) => s.videoId), ['A', 'B']);
    expect(queue.index, 1);
  });

  test('history retention is bounded without dropping the active row', () {
    var queue = PlaybackQueue.empty().startContext(
      List.generate(80, (i) => song('track$i')),
    );
    for (var i = 0; i < 65; i++) {
      queue = queue.next();
    }
    expect(queue.index, PlaybackQueue.maxHistory);
    expect(queue.current!.videoId, 'track65');
    expect(queue.songs.length, 40);
  });
}
