import '../domain/models.dart';

/// Recording matching follows the source's title/edition/artist/duration rules.
/// A weak search hit is returned as unmatched instead of importing a cover.
class SongMatcher {
  static Song? best(
    Iterable<Song> candidates, {
    required String title,
    required String artist,
    Duration? duration,
    String? album,
  }) {
    final wanted = _title(title);
    final wantedArtists = _artists(artist);
    Song? best;
    var bestScore = -1.0;
    for (final candidate in candidates) {
      final actual = _title(candidate.title);
      if (wanted.core.isEmpty || actual.core.isEmpty) continue;
      if (wanted.core != actual.core) continue;
      if (wanted.versions.length != actual.versions.length ||
          !wanted.versions.containsAll(actual.versions)) {
        continue;
      }
      final credited = _artists(candidate.artist);
      final shared = wantedArtists.intersection(credited);
      if (wantedArtists.isNotEmpty && credited.isNotEmpty && shared.isEmpty) {
        continue;
      }
      var score = 100.0 + shared.length * 15;
      if (duration != null &&
          duration > Duration.zero &&
          candidate.duration > Duration.zero) {
        final gap = (duration - candidate.duration).inMilliseconds.abs() / 1000;
        if (gap > 30) continue;
        score += 30 - gap;
      }
      if (album != null &&
          normalize(album) == normalize(candidate.albumName ?? '')) {
        score += 10;
      }
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    return best;
  }

  static String normalize(String value) {
    const foldedLetters = {
      'a': 'áàâäãåāăą',
      'c': 'çćč',
      'd': 'ďđ',
      'e': 'éèêëēėęě',
      'g': 'ğ',
      'i': 'íìîïīįı',
      'l': 'ł',
      'n': 'ñńň',
      'o': 'óòôöõøōő',
      'r': 'ř',
      's': 'śšş',
      't': 'ť',
      'u': 'úùûüūůű',
      'y': 'ýÿ',
      'z': 'žźż',
    };
    var text = value.toLowerCase();
    for (final entry in foldedLetters.entries) {
      text = text.replaceAll(RegExp('[${entry.value}]'), entry.key);
    }
    return text
        .replaceAll(RegExp(r'[^\p{L}\p{N}]+', unicode: true), ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
  }

  static Set<String> _artists(String value) => value
      .split(
        RegExp(
          r'\s*(?:,|&|;|\bfeat\.?|\bfeaturing\b|\bwith\b)\s*',
          caseSensitive: false,
        ),
      )
      .map(normalize)
      .where((s) => s.isNotEmpty && s != 'unknown artist')
      .toSet();

  static ({String core, Set<String> versions}) _title(String title) {
    final versions = RegExp(
      r'\b(live|remix|acoustic|instrumental|karaoke|demo|slowed|sped|nightcore)\b',
      caseSensitive: false,
    ).allMatches(title).map((m) => m[1]!.toLowerCase()).toSet();
    final clean = title
        .replaceAll(RegExp(r'\([^)]*\)|\[[^\]]*\]'), ' ')
        .split(' | ')
        .first
        .split(' - ')
        .first
        .replaceAll(
          RegExp(
            r'\b(?:official (?:audio|video|music video)|lyrics?|lyrical|full song)\b',
            caseSensitive: false,
          ),
          ' ',
        );
    return (core: normalize(clean), versions: versions);
  }
}
