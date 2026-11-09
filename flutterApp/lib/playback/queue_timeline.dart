import 'dart:math';

import '../domain/models.dart';

/// The desktop's three-tier queue, ported from DesktopQueue/QueueTimeline.
/// Identities belong to entries, so two copies of one recording remain distinct.
class PlaybackQueue {
  PlaybackQueue(Iterable<Song> songs, {this.index = 0})
    : songs = List.unmodifiable(songs);

  factory PlaybackQueue.empty() => PlaybackQueue(const [], index: -1);

  final List<Song> songs;
  final int index;
  Song? get current => index >= 0 && index < songs.length ? songs[index] : null;
  bool get hasNext => current != null && index + 1 < songs.length;
  bool get hasPrevious => current != null && index > 0;
  List<Song> get upcoming => songs.skip(index + 1).toList(growable: false);
  static const maxHistory = 25;
  static int _sequence = 0;
  static final _random = Random.secure();

  static String _newId() =>
      '${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}-'
      '${(_sequence++).toRadixString(36)}-${_random.nextInt(1 << 32).toRadixString(36)}';

  static Song entry(Song song, QueueTier tier, {bool fresh = false}) =>
      song.copyWith(
        queueTier: tier,
        queueEntryId: fresh ? _newId() : song.queueEntryId ?? _newId(),
      );

  PlaybackQueue startContext(
    List<Song> context, {
    int at = 0,
    String? source,
    bool preserveUserQueue = true,
  }) {
    if (context.isEmpty) return PlaybackQueue.empty();
    final selected = at.clamp(0, context.length - 1);
    final manual = preserveUserQueue
        ? upcoming.where((s) => s.queueTier == QueueTier.userQueue).toList()
        : <Song>[];
    final seen = <String>{...manual.map((s) => s.queueEntryId!)};
    final entries = context.map((song) {
      var result = entry(
        song.copyWith(playbackSource: source),
        QueueTier.context,
      );
      if (!seen.add(result.queueEntryId!)) {
        result = entry(result, QueueTier.context, fresh: true);
        seen.add(result.queueEntryId!);
      }
      return result;
    }).toList();
    return PlaybackQueue([
      ...entries.take(selected + 1),
      ...manual,
      ...entries.skip(selected + 1),
    ], index: selected);
  }

  PlaybackQueue enqueue(Song song, {bool next = false}) {
    if (songs.isEmpty) {
      return PlaybackQueue([entry(song, QueueTier.userQueue, fresh: true)]);
    }
    var at = index + 1;
    if (!next) {
      while (at < songs.length && songs[at].queueTier == QueueTier.userQueue) {
        at++;
      }
    }
    return PlaybackQueue(
      [...songs]..insert(at, entry(song, QueueTier.userQueue, fresh: true)),
      index: index,
    );
  }

  PlaybackQueue appendAutoplay(Iterable<Song> additions) {
    final known = songs.map((s) => s.videoId).toSet();
    final fresh = additions
        .where((s) => known.add(s.videoId))
        .map((s) => entry(s, QueueTier.autoplay, fresh: true));
    return PlaybackQueue([...songs, ...fresh], index: index);
  }

  PlaybackQueue withoutAutoplay() => PlaybackQueue([
    ...songs.take(index + 1),
    ...upcoming.where((s) => s.queueTier != QueueTier.autoplay),
  ], index: index);

  PlaybackQueue withoutUserQueue() => PlaybackQueue([
    ...songs.take(index + 1),
    ...upcoming.where((s) => s.queueTier != QueueTier.userQueue),
  ], index: index);

  PlaybackQueue next() =>
      hasNext ? PlaybackQueue(songs, index: index + 1).afterTransition() : this;

  PlaybackQueue previous() =>
      hasPrevious ? PlaybackQueue(songs, index: index - 1) : this;

  /// Forward queue picks preserve manually requested tracks according to tier.
  PlaybackQueue jump(int target) {
    if (target < 0 || target >= songs.length) return this;
    if (target <= index) {
      return PlaybackQueue(songs, index: target).afterTransition();
    }
    final song = songs[target];
    final later = songs.skip(target + 1);
    final manual = upcoming.where((s) => s.queueTier == QueueTier.userQueue);
    final laterAutoplay = later.where((s) => s.queueTier == QueueTier.autoplay);
    final rebuilt = switch (song.queueTier) {
      QueueTier.autoplay => [
        song.copyWith(
          queueTier: QueueTier.context,
          playbackSource: song.playbackSource ?? song.title,
          playbackSourceType: song.playbackSourceType ?? 'QUEUE',
        ),
        ...manual,
        ...laterAutoplay,
      ],
      QueueTier.context => [
        song,
        ...manual,
        ...later.where((s) => s.queueTier == QueueTier.context),
        ...laterAutoplay,
      ],
      QueueTier.userQueue => [
        song,
        ...later.where((s) => s.queueTier == QueueTier.userQueue),
        ...upcoming.where((s) => s.queueTier == QueueTier.context),
        ...upcoming.where((s) => s.queueTier == QueueTier.autoplay),
      ],
    };
    return PlaybackQueue([
      ...songs.take(index + 1),
      ...rebuilt,
    ], index: index + 1).afterTransition();
  }

  /// The active row cannot be removed by the queue's remove action.
  PlaybackQueue removeAt(int at) {
    if (at < 0 || at >= songs.length || at == index) return this;
    return PlaybackQueue(
      [...songs]..removeAt(at),
      index: at < index ? index - 1 : index,
    );
  }

  /// [to] is the final index; ReorderableListView callers adjust its insert index.
  PlaybackQueue move(int from, int to) {
    if (from < 0 ||
        to < 0 ||
        from >= songs.length ||
        to >= songs.length ||
        from == to) {
      return this;
    }
    final rows = [...songs];
    rows.insert(to, rows.removeAt(from));
    final active = current?.queueEntryId;
    return PlaybackQueue(
      rows,
      index: rows.indexWhere((s) => s.queueEntryId == active),
    );
  }

  PlaybackQueue replaceCurrent(Song replacement) {
    final active = current;
    if (active == null) return this;
    return replaceEntry(active.queueEntryId!, replacement);
  }

  PlaybackQueue replaceEntry(String entryId, Song replacement) {
    final at = songs.indexWhere((song) => song.queueEntryId == entryId);
    if (at < 0) return this;
    final active = songs[at];
    final rows = [...songs];
    rows[at] = replacement.copyWith(
      queueEntryId: active.queueEntryId,
      queueTier: active.queueTier,
      playbackSource: active.playbackSource,
      playbackSourceType: active.playbackSourceType,
      playbackSourceId: active.playbackSourceId,
      radioName: active.radioName,
      isVideoOrigin: active.isVideoOrigin,
    );
    return PlaybackQueue(rows, index: index);
  }

  PlaybackQueue afterTransition() {
    final shouldConsume = current?.queueTier == QueueTier.context;
    final before = songs
        .take(index)
        .where((s) => !shouldConsume || s.queueTier != QueueTier.userQueue)
        .toList();
    final trim = max(0, before.length - maxHistory);
    return PlaybackQueue([
      ...before.skip(trim),
      ...songs.skip(index),
    ], index: before.length - trim);
  }

  PlaybackQueue shuffledAhead([Random? random]) {
    final manual = upcoming.where((s) => s.queueTier == QueueTier.userQueue);
    List<Song> shuffleTier(QueueTier tier) {
      final original = upcoming.where((s) => s.queueTier == tier).toList();
      final shuffled = [...original]..shuffle(random);
      if (shuffled.length > 1 &&
          List.generate(
            shuffled.length,
            (i) => shuffled[i].queueEntryId == original[i].queueEntryId,
          ).every((same) => same)) {
        shuffled.add(shuffled.removeAt(0));
      }
      return shuffled;
    }

    return PlaybackQueue([
      ...songs.take(index + 1),
      ...manual,
      ...shuffleTier(QueueTier.context),
      ...shuffleTier(QueueTier.autoplay),
    ], index: index);
  }

  PlaybackQueue inOriginalOrder(List<String> original) {
    final order = {for (var i = 0; i < original.length; i++) original[i]: i};
    final rows = [...upcoming];
    final currentOrder = {
      for (var i = 0; i < rows.length; i++) rows[i].queueEntryId: i,
    };
    rows.sort((a, b) {
      final tier = a.queueTier.index.compareTo(b.queueTier.index);
      if (tier != 0) return tier;
      final ai =
          order[a.queueEntryId] ??
          original.length + currentOrder[a.queueEntryId]!;
      final bi =
          order[b.queueEntryId] ??
          original.length + currentOrder[b.queueEntryId]!;
      return ai.compareTo(bi);
    });
    return PlaybackQueue([...songs.take(index + 1), ...rows], index: index);
  }

  List<String> get orderKeys => songs.map((s) => s.queueEntryId!).toList();

  static PlaybackQueue restored(List<Song> songs, int index) {
    if (songs.isEmpty) return PlaybackQueue.empty();
    final known = <String>{};
    final safe = songs.map((song) {
      var row = entry(song, song.queueTier);
      if (!known.add(row.queueEntryId!)) {
        row = entry(row, row.queueTier, fresh: true);
        known.add(row.queueEntryId!);
      }
      return row;
    }).toList();
    return PlaybackQueue(safe, index: index.clamp(0, safe.length - 1));
  }
}
