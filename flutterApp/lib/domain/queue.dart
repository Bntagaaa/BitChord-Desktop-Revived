// Port source: QueueTimeline.kt @ 348b613a; context/user/autoplay ordering.
import 'dart:math';
import 'music.dart';

enum QueueTier { user, context, autoplay }

class QueueEntry {
  QueueEntry(this.song, this.tier, {this.source = ''}) : id = _id();
  QueueEntry._(this.song, this.tier, this.id, this.source);
  final Song song;
  final QueueTier tier;
  final String id;
  final String source;
  static final _random = Random.secure();
  static String _id() {
    final bytes = List.generate(16, (_) => _random.nextInt(256));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    final hex = bytes.map((value) => value.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}';
  }
  QueueEntry promoted() => QueueEntry._(song, QueueTier.context, id, source);
}

class QueueTimeline {
  final List<QueueEntry> entries = [];
  List<String> _beforeShuffle = [];
  int index = -1;
  String get source => index >= 0 && index < entries.length ? entries[index].source : '';
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
        .map((song) => QueueEntry(song, QueueTier.context, source: origin))
        .toList();
    entries
      ..clear()
      ..addAll([
        ...context.take(selectedIndex + 1),
        ...manual,
        ...context.skip(selectedIndex + 1),
      ]);
    index = selectedIndex;
  }

  void oneOff(Song song, String origin) {
    final manual = _manual;
    entries
      ..clear()
      ..addAll([QueueEntry(song, QueueTier.context, source: origin), ...manual]);
    index = 0;
  }

  void enqueue(Song song, {bool next = false}) {
    var insertion = (index + 1).clamp(0, entries.length).toInt();
    if (!next) {
      while (insertion < entries.length &&
          entries[insertion].tier == QueueTier.user) {
        insertion++;
      }
    }
    entries.insert(insertion, QueueEntry(song, QueueTier.user, source: source));
  }

  void jump(int target) {
    if (target < 0 || target >= entries.length) return;
    if (target <= index) { index = target; return; }
    final selected = entries[target];
    final manual = _manual;
    final remaining = entries.skip(target + 1).toList();
    final future = entries.skip(index + 1).toList();
    final rebuilt = switch (selected.tier) {
      QueueTier.autoplay => [selected.promoted(), ...manual,
        ...remaining.where((entry) => entry.tier == QueueTier.autoplay)],
      QueueTier.context => [selected, ...manual,
        ...remaining.where((entry) => entry.tier == QueueTier.context),
        ...remaining.where((entry) => entry.tier == QueueTier.autoplay)],
      QueueTier.user => [selected,
        ...remaining.where((entry) => entry.tier == QueueTier.user),
        ...future.where((entry) => entry.tier == QueueTier.context),
        ...future.where((entry) => entry.tier == QueueTier.autoplay)],
    };
    entries..clear()..addAll(rebuilt); index = 0;
  }

  void shuffleStartingOrder() {
    if (current == null) return;
    final selected = entries[index];
    final rest = [for (var i = 0; i < entries.length; i++) if (i != index) entries[i]];
    final context = rest.where((entry) => entry.tier == QueueTier.context).toList()..shuffle();
    final autoplay = rest.where((entry) => entry.tier == QueueTier.autoplay).toList()..shuffle();
    entries..clear()..addAll([selected,
      ...rest.where((entry) => entry.tier == QueueTier.user), ...context, ...autoplay]);
    index = 0;
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
