import 'dart:math';

enum QueueTier { user, context, autoplay }

enum RepeatMode {
  off,
  all,
  one;

  RepeatMode get next => values[(index + 1) % values.length];
}

// Catalog, rendition, source and queue identity remain distinct even for duplicates.
final class QueueEntry {
  QueueEntry({
    required this.entryId,
    required this.catalogId,
    required this.renditionId,
    required this.sourceId,
    required this.title,
    this.artist = '',
    this.localPath,
    this.tier = QueueTier.context,
    Map<String, Object?> metadata = const {},
  }) : metadata = Map.unmodifiable(metadata) {
    if ([entryId, catalogId, renditionId, sourceId].any((v) => v.isEmpty)) {
      throw ArgumentError('Empty identity');
    }
  }
  final String entryId, catalogId, renditionId, sourceId, title, artist;
  final String? localPath;
  final QueueTier tier;
  final Map<String, Object?> metadata;
  QueueEntry inTier(QueueTier value) => QueueEntry(
    entryId: entryId,
    catalogId: catalogId,
    renditionId: renditionId,
    sourceId: sourceId,
    title: title,
    artist: artist,
    localPath: localPath,
    tier: value,
    metadata: metadata,
  );
  QueueEntry replaceRendition(QueueEntry value) => QueueEntry(
    entryId: entryId,
    catalogId: value.catalogId,
    renditionId: value.renditionId,
    sourceId: value.sourceId,
    title: value.title,
    artist: value.artist,
    localPath: value.localPath,
    tier: tier,
    metadata: {...metadata, ...value.metadata},
  );
  Map<String, Object?> toJson() => {
    'entryId': entryId,
    'catalogId': catalogId,
    'renditionId': renditionId,
    'sourceId': sourceId,
    'title': title,
    'artist': artist,
    'localPath': localPath,
    'tier': tier.name,
    'metadata': metadata,
  };
  factory QueueEntry.fromJson(Map<String, Object?> j) => QueueEntry(
    entryId: j['entryId'] as String,
    catalogId: j['catalogId'] as String,
    renditionId: j['renditionId'] as String,
    sourceId: j['sourceId'] as String,
    title: j['title'] as String,
    artist: j['artist'] as String,
    localPath: j['localPath'] as String?,
    tier: QueueTier.values.byName(j['tier'] as String),
    metadata: (j['metadata'] as Map).cast<String, Object?>(),
  );
}

final class LocalQueue {
  LocalQueue(Iterable<QueueEntry> rows, {int cursor = 0})
    : entries = List.unmodifiable(rows),
      cursor = cursor {
    if ((entries.isEmpty && cursor != 0) ||
        (entries.isNotEmpty && (cursor < 0 || cursor >= entries.length)) ||
        entries.map((e) => e.entryId).toSet().length != entries.length) {
      throw ArgumentError('Invalid queue identities/cursor');
    }
  }
  final List<QueueEntry> entries;
  final int cursor;
  QueueEntry? get current => entries.isEmpty ? null : entries[cursor];
  List<QueueEntry> get upcoming => entries.skip(cursor + 1).toList();
  LocalQueue _at(List<QueueEntry> rows, int at) =>
      LocalQueue(rows, cursor: rows.isEmpty ? 0 : at);
  LocalQueue trimmed() {
    final n = max(0, cursor - 25);
    return _at(entries.sublist(n), cursor - n);
  }

  LocalQueue _transition() {
    var at = cursor;
    final rows = <QueueEntry>[];
    for (var i = 0; i < entries.length; i++) {
      if (current?.tier == QueueTier.context &&
          i < cursor &&
          entries[i].tier == QueueTier.user) {
        at--;
      } else {
        rows.add(entries[i]);
      }
    }
    return _at(rows, at).trimmed();
  }

  // Manual Next is independent of repeat-one; EOF obeys all three repeat states.
  LocalQueue next({
    RepeatMode repeat = RepeatMode.off,
    bool automatic = false,
  }) {
    if (entries.isEmpty || (automatic && repeat == RepeatMode.one)) return this;
    if (cursor + 1 < entries.length)
      return _at(entries, cursor + 1)._transition();
    return repeat == RepeatMode.all ? _at(entries, 0)._transition() : this;
  }

  LocalQueue previous() => cursor == 0 ? this : _at(entries, cursor - 1);
  bool previousRestarts(int positionUs) => positionUs > 10000000;
  LocalQueue jump(int target) {
    if (target < 0 || target >= entries.length) return this;
    if (target <= cursor) return _at(entries, target)._transition();
    final selected = entries[target];
    final future = entries.skip(cursor + 1);
    final after = entries.skip(target + 1);
    final manual = (selected.tier == QueueTier.user ? after : future).where(
      (e) => e.tier == QueueTier.user,
    );
    final context = (selected.tier == QueueTier.user ? future : after).where(
      (e) => e.tier == QueueTier.context,
    );
    return _at([
      ...entries.take(cursor + 1),
      selected.tier == QueueTier.autoplay
          ? selected.inTier(QueueTier.context)
          : selected,
      ...manual,
      if (selected.tier != QueueTier.autoplay) ...context,
      ...after.where((e) => e.tier == QueueTier.autoplay),
    ], cursor + 1)._transition();
  }

  LocalQueue enqueue(List<QueueEntry> rows, {required bool playNext}) {
    final list = entries.toList();
    var at = entries.isEmpty ? 0 : cursor + 1;
    if (!playNext) {
      while (at < list.length && list[at].tier == QueueTier.user) {
        at++;
      }
    }
    list.insertAll(at, rows.map((e) => e.inTier(QueueTier.user)));
    return _at(list, cursor);
  }

  LocalQueue remove(int at) {
    if (at < 0 || at >= entries.length || at == cursor) return this;
    final list = entries.toList()..removeAt(at);
    return _at(list, at < cursor ? cursor - 1 : cursor);
  }

  LocalQueue move(int from, int to) {
    if (from < 0 || to < 0 || from >= entries.length || to >= entries.length)
      return this;
    final id = current?.entryId;
    final list = entries.toList();
    list.insert(to, list.removeAt(from));
    return _at(list, list.indexWhere((e) => e.entryId == id));
  }

  LocalQueue clearManual() => _at([
    ...entries.take(cursor + 1),
    ...upcoming.where((e) => e.tier != QueueTier.user),
  ], cursor);
  LocalQueue withoutAutoplay() => _at([
    ...entries.take(cursor + 1),
    ...upcoming.where((e) => e.tier != QueueTier.autoplay),
  ], cursor);
  LocalQueue replaceCurrent(String expectedEntryId, QueueEntry replacement) {
    if (current?.entryId != expectedEntryId) return this;
    final list = entries.toList();
    list[cursor] = current!.replaceRendition(replacement);
    return _at(list, cursor);
  }

  LocalQueue afterHandoff(String entryId) {
    if (current?.entryId == entryId) return this;
    if (cursor + 1 < entries.length && entries[cursor + 1].entryId == entryId)
      return next();
    if (cursor == entries.length - 1 && entries.first.entryId == entryId)
      return _at(entries, 0)._transition();
    return this; // A late nomination cannot adopt an unrelated selection.
  }

  LocalQueue shuffle(int seed) {
    final rng = Random(seed);
    List<QueueEntry> section(QueueTier tier) {
      final original = upcoming.where((e) => e.tier == tier).toList();
      final shuffled = original.toList()..shuffle(rng);
      if (original.length > 1 &&
          List.generate(
            original.length,
            (i) => original[i].entryId == shuffled[i].entryId,
          ).every((v) => v)) {
        shuffled.add(shuffled.removeAt(0));
      }
      return shuffled;
    }

    return _at([
      ...entries.take(cursor + 1),
      ...upcoming.where((e) => e.tier == QueueTier.user),
      ...section(QueueTier.context),
      ...section(QueueTier.autoplay),
    ], cursor);
  }

  LocalQueue restoreOrder(List<String> original) {
    final byId = {for (final e in upcoming) e.entryId: e};
    final restored = <QueueEntry>[];
    for (final id in original) {
      final e = byId.remove(id);
      if (e != null) restored.add(e);
    }
    restored.addAll(byId.values);
    return _at([
      ...entries.take(cursor + 1),
      for (final tier in QueueTier.values)
        ...restored.where((e) => e.tier == tier),
    ], cursor);
  }

  Map<String, Object?> toJson() => {
    'cursor': cursor,
    'entries': entries.map((e) => e.toJson()).toList(),
  };
  factory LocalQueue.fromJson(Map<String, Object?> j) => LocalQueue(
    (j['entries'] as List).map(
      (e) => QueueEntry.fromJson((e as Map).cast<String, Object?>()),
    ),
    cursor: j['cursor'] as int,
  );
}
