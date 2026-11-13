// Opt-in live guest catalog audit. Output contains counts/categories only:
// never account data, visitor/cookies, queries, item IDs, URLs or catalog text.
import 'dart:convert';
import 'dart:io';
import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/domain/catalog.dart';

Future<void> main() async {
  final youtube = YouTubeMusic(const ProviderHttp());
  final report = <String, Object?>{
    'recorded_at_utc': DateTime.now().toUtc().toIso8601String(),
    'platform': Platform.operatingSystem,
    'dart': Platform.version.split(' ').first,
    'authenticated': false,
    'physical_audio': 'NOT_TESTED',
    'visual_parity': 'NOT_TESTED',
  };
  Future<CatalogPage?> observe(
    String name,
    Future<CatalogPage> Function(RequestScope) call,
  ) async {
    final scope = RequestScope();
    try {
      final page = await call(scope);
      report[name] = {
        'status': page.entries.isEmpty ? 'EMPTY_UNVERIFIED' : 'LIVE_RESPONSE',
        'entries': page.entries.length,
        'shelves': page.shelves.length,
        'header': page.header != null,
        'continuation': page.continuation != null,
      };
      return page;
    } on ProviderFailure catch (error) {
      report[name] = {
        'status': 'BLOCKED',
        'failure': error.problem.name,
        'http': error.status,
      };
      return null;
    } catch (_) {
      report[name] = {'status': 'UNVERIFIED'};
      return null;
    } finally {
      scope.cancel();
    }
  }

  for (final browse in {
    'home': 'FEmusic_home',
    'new': 'FEmusic_new_releases',
    'explore': 'FEmusic_explore',
  }.entries) {
    final page = await observe(
      browse.key,
      (scope) => youtube.browse(browse.value, scope),
    );
    if (page?.continuation != null) {
      await observe(
        '${browse.key}_continuation',
        (scope) => youtube.browse(
          browse.value,
          scope,
          continuation: page!.continuation,
        ),
      );
    }
  }
  for (final filter in SearchFilter.values) {
    final page = await observe(
      'search_${filter.name}',
      (scope) => youtube.search('Beethoven', scope, filter: filter),
    );
    if (page?.continuation != null) {
      await observe(
        'search_${filter.name}_continuation',
        (scope) => youtube.search(
          'Beethoven',
          scope,
          filter: filter,
          continuation: page!.continuation,
        ),
      );
    }
    if ([
      SearchFilter.albums,
      SearchFilter.artists,
      SearchFilter.playlists,
    ].contains(filter)) {
      final detail = page?.entries
          .where((entry) => entry.kind != CatalogKind.track)
          .firstOrNull;
      if (detail != null) {
        await observe(
          'detail_${filter.name}',
          (scope) => youtube.browse(detail.id, scope, params: detail.params),
        );
      }
    }
  }
  final scope = RequestScope();
  try {
    final rows = await youtube.suggestions('Beethoven', scope);
    report['suggestions'] = {
      'status': rows.isEmpty ? 'EMPTY_UNVERIFIED' : 'LIVE_RESPONSE',
      'count': rows.length,
    };
  } on ProviderFailure catch (error) {
    report['suggestions'] = {
      'status': 'BLOCKED',
      'failure': error.problem.name,
      'http': error.status,
    };
  } finally {
    scope.cancel();
  }
  stdout.writeln(jsonEncode(report));
}
