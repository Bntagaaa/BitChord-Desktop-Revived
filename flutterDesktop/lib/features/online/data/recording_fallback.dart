import '../domain/catalog.dart';

// Port of the conservative direction of Kotlin DesktopTrackMatcher for a
// transparent alternate-source path. No guesses for videos, remixes, live
// takes or ambiguous version/duration matches.
final class MatchedRecording {
  const MatchedRecording(this.entry, this.media);
  final CatalogEntry entry;
  final ResolvedMedia media;
}

final class RecordingFallback {
  static final _brackets = RegExp(r'\\(([^()]*)\\)|\\[([^\\[\\]]*)\\]');
  static final _separator = RegExp(r'\\s+[-–—|]\\s+');
  static final _versions = RegExp(
    r'\\b(remix|rmx|live|acoustic|instrumental|karaoke|'
    r'cover|sped|slowed|nightcore|unplugged|mashup|bootleg)\\b',
  );
  static final _neutral = RegExp(
    r'^(?:official\\s+)?(?:(?:music|visual|lyric)\\s+)?'
    r'(?:video|audio|lyrics?|visualizer|hd|hq|4k)$',
  );

  static String _normalize(String value) => value
      .toLowerCase()
      .replaceAll('&amp;', ' and ')
      .replaceAll('&', ' and ')
      .replaceAll(RegExp(r'[^a-z0-9]+'), ' ')
      .trim()
      .replaceAll(RegExp(r'\\s+'), ' ');

  static String _artist(String subtitle) {
    final segments = subtitle
        .split(RegExp(r'[•·]'))
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();
    if (segments.isEmpty) return '';
    final first = segments.first.toLowerCase();
    final chosen = segments.length > 1 &&
            ['song', 'video', 'music', 'single'].contains(first)
        ? segments[1]
        : segments.first;
    return _normalize(chosen
        .replaceAll(RegExp(r'\\s+-\\s+topic$', caseSensitive: false), '')
        .split(RegExp(r',|\\bfeat\\.?\\b|\\bft\\.?\\b', caseSensitive: false))
        .first);
  }

  static ({String core, String versions}) _parts(String title, String artist) {
    final variants = <String>{};
    void classify(String qualifier) {
      for (final match in _versions.allMatches(qualifier.toLowerCase())) {
        variants.add(match.group(1)!);
      }
    }

    var text = title.toLowerCase().replaceAll('&amp;', ' and ');
    text = text.replaceAllMapped(_brackets, (match) {
      classify(match.group(1) ?? match.group(2) ?? '');
      return ' ';
    });
    final pieces = text.split(_separator);
    if (pieces.length > 1) {
      if (_normalize(pieces.first) == artist) {
        text = pieces.skip(1).join(' ');
      } else {
        final trailing = pieces.last.trim();
        if (_neutral.hasMatch(trailing) ||
            _versions.hasMatch(trailing) ||
            _normalize(trailing) == artist) {
          classify(trailing);
          text = pieces.take(pieces.length - 1).join(' ');
        }
      }
    }
    text = text.replaceFirst(
      RegExp(r'\\s+\\b(?:feat\\.?|ft\\.?|featuring)\\b.*$'),
      '',
    );
    final core = _normalize(text);
    return (core: core, versions: (variants.toList()..sort()).join(','));
  }

  static CatalogEntry? match(
    CatalogEntry target,
    Iterable<CatalogEntry> candidates,
  ) {
    if (target.kind != CatalogKind.track || target.isVideo) return null;
    final artist = _artist(target.subtitle);
    final title = _parts(target.title, artist);
    if (artist.isEmpty || title.core.isEmpty) return null;
    final suitable = candidates
        .where((entry) {
          if (entry.provider != 'jiosaavn' ||
              entry.kind != CatalogKind.track ||
              _artist(entry.subtitle) != artist) return false;
          final parts = _parts(entry.title, artist);
          if (title.core != parts.core || title.versions != parts.versions) {
            return false;
          }
          final wanted = target.durationSeconds;
          final actual = entry.durationSeconds;
          return wanted == null || actual == null ||
              (wanted - actual).abs() <= 12;
        })
        .take(25)
        .toList();
    if (suitable.isEmpty) return null;
    // Without a reference duration, two differing cuts must not be guessed.
    if (target.durationSeconds == null) {
      final durations = suitable
          .map((e) => e.durationSeconds)
          .whereType<int>()
          .toList();
      if (durations.isNotEmpty &&
          durations.reduce((a, b) => a > b ? a : b) -
                  durations.reduce((a, b) => a < b ? a : b) > 12) return null;
    } else {
      suitable.sort((a, b) =>
          ((a.durationSeconds ?? 0) - target.durationSeconds!).abs()
              .compareTo(((b.durationSeconds ?? 0) -
                      target.durationSeconds!).abs()));
    }
    return suitable.first;
  }

  static Future<MatchedRecording?> resolve(
    CatalogEntry target,
    CatalogProvider alternate,
    AudioQuality quality,
    RequestScope scope,
  ) async {
    if (target.provider != 'youtube' || target.isVideo) return null;
    final artist = _artist(target.subtitle);
    final core = _parts(target.title, artist).core;
    if (artist.isEmpty || core.isEmpty) return null;
    CatalogPage results;
    try {
      results = await alternate.search(
        '$core $artist',
        scope,
        filter: SearchFilter.songs,
      );
    } on ProviderFailure {
      scope.check();
      return null;
    }
    scope.check();
    final selected = match(target, results.entries);
    if (selected == null) return null;
    try {
      final media = await alternate.resolve(selected, quality, scope);
      scope.check();
      if (media.provider != selected.provider ||
          media.trackId != selected.id) return null;
      return MatchedRecording(selected, media);
    } on ProviderFailure {
      scope.check();
      return null;
    }
  }
}
