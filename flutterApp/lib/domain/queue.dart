// Port source: QueueTimeline.kt @ 348b613a; context/user/autoplay ordering.
import 'dart:math';
import 'music.dart';

enum QueueTier { user, context, autoplay }

class QueueEntry {
  QueueEntry(this.song, this.tier) : id = _id();
  final Song song;
  final QueueTier tier;
  final String id;
  static final _random = Random.secure();
  static String _id() => List.generate(
    16,
    (_) => _random.nextInt(256).toRadixString(16).padLeft(2, '0'),
  ).join();
}

class QueueTimeline {
  final List<QueueEntry> entries = [];
  List<String> _beforeShuffle = [];
  int index = -1;
  String source = '';
  Song? get current =>
      index >= 0 && index < entries.length ? entries[index].song : null;
  List<QueueEntry> get _manual => entries
      .skip(index + 1)
      .where((entry) => entry.tier == QueueTier.user)
      .toList();
  void context(List<Song> songs, int selected, String origin) {
    if (songs.isEmpty) return;
    final manual = _manual;
    final selectedIndex = selected.clamp(0, songs.length - 1).toInt();
    final context = songs
        .map((song) => QueueEntry(song, QueueTier.context))
        .toList();
    entries
      ..clear()
      ..addAll([
        ...context.take(selectedIndex + 1),
        ...manual,
        ...context.skip(selectedIndex + 1),
      ]);
    index = selectedIndex;
    source = origin;
  }

  void oneOff(Song song, String origin) {
    final manual = _manual;
    entries
      ..clear()
      ..addAll([QueueEntry(song, QueueTier.context), ...manual]);
    index = 0;
    source = origin;
  }

  void enqueue(Song song, {bool next = false}) {
    var insertion = (index + 1).clamp(0, entries.length).toInt();
    if (!next) {
      while (insertion < entries.length &&
          entries[insertion].tier == QueueTier.user) {
        insertion++;
      }
    }
    entries.insert(insertion, QueueEntry(song, QueueTier.user));
  }

  void setShuffle(bool enabled) {
    final start = (index + 1).clamp(0, entries.length).toInt();
    final upcoming = entries.skip(start).toList();
    if (enabled) {
      _beforeShuffle = upcoming.map((entry) => entry.id).toList();
      List<QueueEntry> section(QueueTier tier) {
        final original = upcoming.where((entry) => entry.tier == tier).toList();
        final shuffled = [...original]..shuffle();
        if (shuffled.length > 1 &&
            List.generate(
              shuffled.length,
              (index) => shuffled[index] == original[index],
            ).every((value) => value)) {
          shuffled.add(shuffled.removeAt(0));
        }
        return shuffled;
      }

      entries.replaceRange(start, entries.length, [
        ...upcoming.where((entry) => entry.tier == QueueTier.user),
        ...section(QueueTier.context),
        ...section(QueueTier.autoplay),
      ]);
    } else {
      final rank = {
        for (var i = 0; i < _beforeShuffle.length; i++) _beforeShuffle[i]: i,
      };
      upcoming.sort((a, b) {
        final tier = a.tier.index.compareTo(b.tier.index);
        return tier != 0
            ? tier
            : (rank[a.id] ?? 999999).compareTo(rank[b.id] ?? 999999);
      });
      entries.replaceRange(start, entries.length, upcoming);
      _beforeShuffle = [];
    }
  }
}
