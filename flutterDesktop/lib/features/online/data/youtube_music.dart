import '../domain/catalog.dart';
import 'provider_http.dart';
import 'youtube/accounts.dart';
import 'youtube/catalog_client.dart';
import 'youtube/catalog_parser.dart';
import 'youtube_resolver.dart';
import 'youtube_session.dart';

// Public provider boundary retained for the existing UI. Catalog, account
// lifecycle, extraction, deployment and format policy no longer share one class.
final class YouTubeMusic implements CatalogProvider {
  YouTubeMusic(this.http, {Uri? origin, this.resolver})
    : origin = origin ?? Uri.parse(YouTubeSession.origin) {
    _catalog = YouTubeCatalogClient(http, _accounts, this.origin);
  }
  final ProviderHttp http;
  final Uri origin;
  final YouTubeResolver? resolver;
  final _accounts = YouTubeAccounts();
  late final YouTubeCatalogClient _catalog;
  @override
  String get id => 'youtube';
  bool get signedIn => _accounts.signedIn;
  int get accountGeneration => _accounts.generation;

  Future<void> signIn(String cookie, RequestScope scope) {
    if (origin.origin != YouTubeSession.origin) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    return _accounts.signIn(cookie, http, scope);
  }

  void signOut() => _accounts.signOut();

  Future<List<String>> suggestions(String input, RequestScope scope) async {
    if (input.trim().isEmpty) return const [];
    return YouTubeCatalogParser.suggestions(
      await _catalog.request('music/get_search_suggestions', {
        'input': input,
      }, scope),
    );
  }

  @override
  Future<CatalogPage> search(
    String query,
    RequestScope scope, {
    String? continuation,
    SearchFilter filter = SearchFilter.all,
  }) => _catalog.call('search', {
    if (continuation == null) 'query': query.trim(),
    if (continuation == null && filter != SearchFilter.all)
      'params': switch (filter) {
        SearchFilter.songs => 'EgWKAQIIAWoKEAkQChAFEAMQBA==',
        SearchFilter.albums => 'EgWKAQIYAWoKEAkQChAFEAMQBA==',
        SearchFilter.artists => 'EgWKAQIgAWoKEAkQChAFEAMQBA==',
        SearchFilter.playlists => 'EgWKAQIoAWoKEAkQChAFEAMQBA==',
        SearchFilter.all => '',
      },
    if (continuation != null) 'continuation': continuation,
  }, scope);

  @override
  Future<CatalogPage> browse(
    String browseId,
    RequestScope scope, {
    String? continuation,
    String? params,
  }) => _catalog.call('browse', {
    if (continuation == null) 'browseId': browseId,
    if (params != null) 'params': params,
    if (continuation != null) 'continuation': continuation,
  }, scope);

  @override
  Future<ResolvedMedia> resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope scope,
  ) => _resolve(track, quality, scope);

  // A renewed URL must identify exactly the same encoded rendition. Selecting
  // a different format halfway through a ranged file corrupts decoder input.
  Future<ResolvedMedia> renew(
    CatalogEntry track,
    AudioQuality quality,
    ResolvedMedia previous,
    RequestScope scope, {
    required int generation,
  }) {
    if (generation != accountGeneration)
      throw const ProviderFailure(ProviderProblem.cancelled);
    if (previous.provider != id || previous.trackId != track.id) {
      throw const ProviderFailure(ProviderProblem.integrity);
    }
    return _resolve(track, quality, scope, rendition: previous.rendition);
  }

  Future<ResolvedMedia> _resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope parent, {
    String? rendition,
  }) => _accounts.run(parent, (scope, session) async {
    final resolver = this.resolver;
    if (resolver == null)
      throw const ProviderFailure(ProviderProblem.youtubeRuntimeUnavailable);
    // A delegated/multi-account context must not be silently switched to the
    // cookie jar's default account. Explicit primary-account media only until
    // authenticated multi-account selection has its own verified contract.
    if (session != null &&
        (session.authUser != '0' || session.pageId != null)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    return resolver.resolve(
      track,
      quality,
      scope,
      cookie: session?.headers()['Cookie'],
      visitorData: session == null ? _catalog.guestVisitorData : null,
      requiredRendition: rendition,
    );
  });

  /// Kotlin YtMusicRepository.library: real signed-in liked music, saved
  /// tracks, playlists, albums, artists, subscriptions and podcasts. Each
  /// section uses the existing authenticated and cancellation-fenced browse
  /// adapter. No guest library is invented.
  Future<CatalogPage> library(RequestScope scope) async {
    if (!signedIn) {
      throw const ProviderFailure(ProviderProblem.unauthorized);
    }
    const sources = <(String, String, ShelfLayout)>[
      ('Liked Music', 'VLLM', ShelfLayout.tracks),
      ('Songs', 'FEmusic_liked_videos', ShelfLayout.tracks),
      ('Playlists', 'FEmusic_liked_playlists', ShelfLayout.cards),
      ('Albums', 'FEmusic_liked_albums', ShelfLayout.cards),
      ('Artists', 'FEmusic_library_corpus_track_artists', ShelfLayout.cards),
      ('Subscriptions', 'FEmusic_library_corpus_artists', ShelfLayout.cards),
      ('Podcasts', 'FEmusic_library_non_music_audio_list', ShelfLayout.cards),
    ];
    final shelves = <CatalogShelf>[];
    ProviderFailure? firstFailure;
    for (final (title, browseId, layout) in sources) {
      scope.check();
      try {
        final rows = <CatalogEntry>[];
        final seen = <String>{};
        String? token;
        for (var pageCount = 0; pageCount < 10; pageCount++) {
          scope.check();
          final page = await browse(
            browseId,
            scope,
            continuation: token,
          );
          final entries = page.entries.isNotEmpty
              ? page.entries
              : [for (final shelf in page.shelves) ...shelf.entries];
          for (final entry in entries) {
            if (seen.add(entry.key)) rows.add(entry);
            if (rows.length >= 400) break;
          }
          final next = page.continuation;
          if (next == null || next == token || rows.length >= 400) break;
          token = next;
        }
        if (rows.isNotEmpty) {
          shelves.add(
            CatalogShelf(
              title: title,
              entries: rows,
              layout: layout,
              moreId: browseId,
            ),
          );
        }
      } on ProviderFailure catch (error) {
        if ([
          ProviderProblem.cancelled,
          ProviderProblem.unauthorized,
          ProviderProblem.loginRequired,
        ].contains(error.problem)) {
          rethrow;
        }
        firstFailure ??= error;
      }
    }
    scope.check();
    if (shelves.isEmpty && firstFailure != null) throw firstFailure;
    return CatalogPage(const [], shelves: shelves);
  }

  static String _safeTitle(String title) {
    final value = title.trim();
    if (value.isEmpty ||
        value.length > 150 ||
        RegExp(r'[\x00-\x1f\x7f]').hasMatch(value)) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    return value;
  }

  static String _playlistId(String input) {
    final id = input.startsWith('VL') ? input.substring(2) : input;
    if (!RegExp(r'^[A-Za-z0-9_-]{8,128}
  static Iterable<Map<String, dynamic>> nodes(Object? value, String key) =>
      YouTubeCatalogParser.nodes(value, key);
  static String text(Object? value) => YouTubeCatalogParser.text(value);
  static CatalogPage parsePage(Map<String, dynamic> data, {String? browseId}) =>
      YouTubeCatalogParser.parsePage(data, browseId: browseId);
}
).hasMatch(id)) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    return id;
  }

  static String _videoId(String input) {
    if (!RegExp(r'^[A-Za-z0-9_-]{11}
  static Iterable<Map<String, dynamic>> nodes(Object? value, String key) =>
      YouTubeCatalogParser.nodes(value, key);
  static String text(Object? value) => YouTubeCatalogParser.text(value);
  static CatalogPage parsePage(Map<String, dynamic> data, {String? browseId}) =>
      YouTubeCatalogParser.parsePage(data, browseId: browseId);
}
).hasMatch(input)) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    return input;
  }

  static String? _resultPlaylistId(Object? value, [int depth = 0]) {
    if (depth > 12) return null;
    if (value is Map) {
      final id = value['playlistId'];
      if (id is String && id.isNotEmpty) return id;
      for (final child in value.values) {
        final nested = _resultPlaylistId(child, depth + 1);
        if (nested != null) return nested;
      }
    } else if (value is List) {
      for (final child in value) {
        final nested = _resultPlaylistId(child, depth + 1);
        if (nested != null) return nested;
      }
    }
    return null;
  }

  Future<String> createPlaylist(
    String title,
    PlaylistVisibility visibility,
    RequestScope scope,
  ) async {
    final response = await _catalog.mutate('playlist/create', {
      'title': _safeTitle(title),
      'description': '',
      'privacyStatus': visibility.name.toUpperCase(),
    }, scope);
    final result = _resultPlaylistId(response);
    if (result == null) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    return _playlistId(result);
  }

  Future<void> renamePlaylist(
    String playlistId,
    String title,
    RequestScope scope,
  ) async {
    await _catalog.mutate('browse/edit_playlist', {
      'playlistId': _playlistId(playlistId),
      'actions': [
        {'action': 'ACTION_SET_PLAYLIST_NAME', 'playlistName': _safeTitle(title)},
      ],
    }, scope);
  }

  Future<void> deletePlaylist(
    String playlistId,
    RequestScope scope,
  ) async {
    await _catalog.mutate('playlist/delete', {
      'playlistId': _playlistId(playlistId),
    }, scope);
  }

  Future<Map<String, String>> addToPlaylist(
    String playlistId,
    List<String> videoIds,
    RequestScope scope,
  ) async {
    if (videoIds.isEmpty || videoIds.length > 100) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    final response = await _catalog.mutate('browse/edit_playlist', {
      'playlistId': _playlistId(playlistId),
      'actions': [
        for (final id in videoIds)
          {'action': 'ACTION_ADD_VIDEO', 'addedVideoId': _videoId(id)},
      ],
    }, scope);
    final entries = response['playlistEditResults'];
    final result = <String, String>{};
    if (entries is List) {
      for (final item in entries) {
        if (item is! Map) continue;
        final added = item['playlistEditVideoAddedResultData'];
        if (added is! Map) continue;
        final id = added['videoId'], setId = added['setVideoId'];
        if (id is String && setId is String && setId.isNotEmpty) {
          result[id] = setId;
        }
      }
    }
    return Map.unmodifiable(result);
  }

  Future<void> removeFromPlaylist(
    String playlistId,
    List<({String setVideoId, String videoId})> entries,
    RequestScope scope,
  ) async {
    if (entries.isEmpty || entries.length > 100) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    final actions = <Map<String, String>>[];
    for (final entry in entries) {
      if (!RegExp(r'^[A-Za-z0-9_-]{6,160}
  static Iterable<Map<String, dynamic>> nodes(Object? value, String key) =>
      YouTubeCatalogParser.nodes(value, key);
  static String text(Object? value) => YouTubeCatalogParser.text(value);
  static CatalogPage parsePage(Map<String, dynamic> data, {String? browseId}) =>
      YouTubeCatalogParser.parsePage(data, browseId: browseId);
}
).hasMatch(entry.setVideoId)) {
        throw const ProviderFailure(ProviderProblem.malformed);
      }
      actions.add({
        'action': 'ACTION_REMOVE_VIDEO',
        'setVideoId': entry.setVideoId,
        'removedVideoId': _videoId(entry.videoId),
      });
    }
    await _catalog.mutate('browse/edit_playlist', {
      'playlistId': _playlistId(playlistId),
      'actions': actions,
    }, scope);
  }

  // Compatibility entrypoints for reference fixtures and existing consumers.
  static Iterable<Map<String, dynamic>> nodes(Object? value, String key) =>
      YouTubeCatalogParser.nodes(value, key);
  static String text(Object? value) => YouTubeCatalogParser.text(value);
  static CatalogPage parsePage(Map<String, dynamic> data, {String? browseId}) =>
      YouTubeCatalogParser.parsePage(data, browseId: browseId);
}
