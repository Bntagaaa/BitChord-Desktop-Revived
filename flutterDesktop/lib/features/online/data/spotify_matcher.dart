import 'dart:collection';
import '../domain/catalog.dart';

final class RecordingMatchFailure implements Exception {
  const RecordingMatchFailure();
  @override
  String toString() => 'No unambiguous matching recording was found on YouTube Music. No unrelated song was substituted.';
}

// SpotifyImporter resolves metadata through YouTube Music, not Spotify DRM.
// Keep that separation visible and reject unrelated artist/version matches.
final class SpotifyRecordingMatcher {
  SpotifyRecordingMatcher(this.search, this.generation);
  final Future<CatalogPage> Function(String, RequestScope) search;
  final int Function() generation;
  final _cache = LinkedHashMap<String, CatalogEntry>();
  int _epoch = -1;

  static String normalize(String text) => text.toLowerCase()
      .replaceAll('&amp;', '&')
      .replaceAll(RegExp('[.,!?;:"\u201c\u201d\u2018\u2019\\[\\]{}()_\u2013\u2014-]'), ' ')
      .replaceAll(RegExp(r'\s+'), ' ').trim();
  static String artist(String text) {
    final pieces = text.split(RegExp(r'[\u2022\u00b7]')).map((part) => part.trim()).where((part) => part.isNotEmpty).toList();
    if (pieces.isEmpty) return '';
    final first = ['song', 'video', 'single', 'music'].contains(pieces.first.toLowerCase()) && pieces.length > 1 ? pieces[1] : pieces.first;
    return normalize(first.replaceFirst(RegExp(r'\s+-\s+Topic$', caseSensitive: false), '').split(RegExp(r',|\s+feat\.?\s+|\s+ft\.?\s+', caseSensitive: false)).first);
  }
  static String title(String text) => normalize(text
      .replaceAll(RegExp(r'\((?:official\s+)?(?:audio|music video|lyric video|lyrics?|visualizer|hd|4k)\)|\[(?:official\s+)?(?:audio|music video|lyric video|lyrics?|visualizer|hd|4k)\]', caseSensitive: false), '')
      .replaceFirst(RegExp(r'\s+(?:feat\.?|ft\.?|featuring)\s+.*$', caseSensitive: false), ''));
  static String versions(String text) {
    final words = RegExp(r'\b(remix|live|acoustic|instrumental|karaoke|cover|sped|slowed|nightcore|unplugged|mashup|bootleg|remaster(?:ed)?)\b', caseSensitive: false)
        .allMatches(text).map((match) => match.group(1)!.toLowerCase()).toSet().toList()..sort();
    return words.join('|');
  }

  static CatalogEntry? choose(CatalogEntry target, Iterable<CatalogEntry> candidates) {
    final wantedTitle = title(target.title), wantedArtist = artist(target.subtitle);
    if (wantedTitle.isEmpty || wantedArtist.isEmpty) return null;
    final seen = <String>{};
    final possible = candidates.where((entry) {
      if (entry.provider != 'youtube' || entry.kind != CatalogKind.track || entry.isVideo || !RegExp(r'^[a-zA-Z0-9_-]{11}$').hasMatch(entry.id)) return false;
      if (title(entry.title) != wantedTitle || artist(entry.subtitle) != wantedArtist || versions(entry.title) != versions(target.title)) return false;
      if (target.durationSeconds != null && (entry.durationSeconds == null || (entry.durationSeconds! - target.durationSeconds!).abs() > 12)) return false;
      return seen.add(entry.id);
    }).take(25).toList();
    if (possible.isEmpty) return null;
    if (target.durationSeconds != null) {
      possible.sort((a, b) => (a.durationSeconds! - target.durationSeconds!).abs().compareTo((b.durationSeconds! - target.durationSeconds!).abs()));
    } else {
      final lengths = possible.map((row) => row.durationSeconds).whereType<int>().toList()..sort();
      if (lengths.length > 1 && lengths.last - lengths.first > 12) return null;
    }
    return possible.first;
  }

  Future<CatalogEntry> resolve(CatalogEntry target, RequestScope scope) async {
    scope.check();
    if (target.provider != 'spotify' || target.kind != CatalogKind.track || !RegExp(r'^[a-zA-Z0-9]{22}$').hasMatch(target.id)) throw const ProviderFailure(ProviderProblem.unsupported);
    final epoch = generation();
    if (_epoch != epoch) { _cache.clear(); _epoch = epoch; }
    final cached = _cache.remove(target.id);
    if (cached != null) { _cache[target.id] = cached; return cached; }
    final result = await search('${target.title} ${target.subtitle}', scope);
    scope.check();
    if (epoch != generation()) throw const ProviderFailure(ProviderProblem.cancelled);
    final chosen = choose(target, result.entries);
    if (chosen == null) throw const RecordingMatchFailure();
    final matched = CatalogEntry(provider: chosen.provider, id: chosen.id, title: chosen.title,
      subtitle: '${chosen.subtitle} · YouTube Music (matched from Spotify)',
      durationSeconds: chosen.durationSeconds, artwork: chosen.artwork ?? target.artwork);
    _cache[target.id] = matched;
    while (_cache.length > 100) { _cache.remove(_cache.keys.first); }
    return matched;
  }
}
