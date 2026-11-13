import 'dart:convert';

import '../domain/account_port.dart';
import '../domain/catalog.dart';
import 'provider_http.dart';

// Reference: shared/jvmMain/data/spotify/SpotifyLibrary.kt, pinned 1.9.2.
// This returns metadata only. It never claims Spotify DRM media is a stream URL.
final class SpotifyLibrary {
  SpotifyLibrary(this.http, {required this.credentials, required this.generation, Uri? endpoint})
    : endpoint = endpoint ?? Uri.parse('https://api-partner.spotify.com/pathfinder/v2/query');
  final ProviderHttp http;
  final Future<Map<String, dynamic>> Function(RequestScope, bool) credentials;
  final int Function() generation;
  final Uri endpoint;
  static const libraryHash = '973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3';
  static const playlistHash = '346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763';
  static const likedHash = '087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240';
  static final likedArtwork = Uri.parse('https://misc.scdn.co/liked-songs/liked-songs-640.png');

  Future<Map<String, dynamic>> _query(String operation, String hash, Map<String, Object?> variables, RequestScope scope) async {
    final epoch = generation();
    final validOrigin = endpoint.scheme == 'https' && endpoint.host == 'api-partner.spotify.com' && endpoint.port == 443 && endpoint.path == '/pathfinder/v2/query';
    final fixture = endpoint.scheme == 'http' && endpoint.host == '127.0.0.1';
    if ((!validOrigin && !fixture) || endpoint.userInfo.isNotEmpty || endpoint.hasQuery || endpoint.hasFragment) throw const ProviderFailure(ProviderProblem.unsupported);
    for (var attempt = 0; attempt < 2; attempt++) {
      final session = await credentials(scope, attempt != 0);
      scope.check();
      if (epoch != generation()) throw const ProviderFailure(ProviderProblem.cancelled);
      final bearer = session['accessToken'], client = session['clientToken'];
      if (bearer is! String || client is! String || !_safeToken(bearer) || !_safeToken(client)) throw const AccountFailure(AccountIssue.loginRequired);
      final response = await http.request(endpoint, scope,
        method: 'POST', maxBytes: 4 * 1024 * 1024,
        headers: {'Authorization': 'Bearer $bearer', 'Client-Token': client, 'app-platform': 'WebPlayer', 'Origin': 'https://open.spotify.com', 'Referer': 'https://open.spotify.com/', 'Accept': 'application/json'},
        targetAllowed: (uri) => uri.origin == endpoint.origin && uri.path == endpoint.path && uri.userInfo.isEmpty && !uri.hasQuery && !uri.hasFragment,
        jsonBody: {'operationName': operation, 'variables': variables, 'extensions': {'persistedQuery': {'version': 1, 'sha256Hash': hash}}});
      scope.check();
      if (epoch != generation()) throw const ProviderFailure(ProviderProblem.cancelled);
      if (response.status == 401 && attempt == 0) continue;
      if ([401, 403].contains(response.status)) throw const AccountFailure(AccountIssue.loginRequired);
      if (response.status == 429) throw const ProviderFailure(ProviderProblem.rateLimited, 429);
      if (response.status != 200) throw ProviderFailure(ProviderProblem.unavailable, response.status);
      Map<String, dynamic> decoded;
      try { decoded = jsonDecode(utf8.decode(response.bytes)) as Map<String, dynamic>; }
      catch (_) { throw const ProviderFailure(ProviderProblem.malformed); }
      if (decoded['errors'] is List && (decoded['errors'] as List).isNotEmpty) {
        // Persisted-query/schema changes must not masquerade as an empty library.
        throw const ProviderFailure(ProviderProblem.unavailable);
      }
      if (decoded['data'] is! Map<String, dynamic>) throw const ProviderFailure(ProviderProblem.malformed);
      return decoded['data'] as Map<String, dynamic>;
    }
    throw const AccountFailure(AccountIssue.loginRequired);
  }

  static bool _safeToken(String token) => token.isNotEmpty && token.length <= 16384 && !RegExp(r'[\x00-\x20]').hasMatch(token);
  static Map _map(Object? value) => value is Map ? value : const {};
  static List _list(Object? value) => value is List ? value : const [];
  static String _text(Object? value) => value is String ? value : '';

  int _offset(String key, String? continuation) {
    if (continuation == null) return 0;
    final parts = continuation.split('|');
    if (parts.length != 4 || parts[0] != 'spotify-v1' || parts[1] != '$generationValue' || parts[2] != key) throw const ProviderFailure(ProviderProblem.cancelled);
    final offset = int.tryParse(parts[3]);
    if (offset == null || offset < 0 || offset > 100000) throw const ProviderFailure(ProviderProblem.malformed);
    return offset;
  }
  int get generationValue => generation();
  String? _next(String key, int offset, int rawCount, Object? total) {
    if (total is! num || total < 0 || total > 100000 || rawCount > 100) throw const ProviderFailure(ProviderProblem.malformed);
    final next = offset + rawCount;
    if (next >= total || rawCount == 0) return null;
    return 'spotify-v1|$generationValue|$key|$next';
  }

  static Uri? _image(Object? source) {
    final candidates = _list(source).whereType<Map>().toList();
    candidates.sort((a, b) => ((b['width'] as num?) ?? 0).compareTo((a['width'] as num?) ?? 0));
    for (final row in candidates) {
      final uri = Uri.tryParse(_text(row['url']));
      if (uri != null && uri.scheme == 'https' && uri.userInfo.isEmpty && !uri.hasFragment) return uri;
    }
    return null;
  }

  Future<CatalogPage> playlists(RequestScope scope, {String? continuation}) async {
    final offset = _offset('library', continuation);
    final data = await _query('libraryV3', libraryHash, {
      'filters': ['Playlists'], 'order': null, 'textFilter': '',
      'features': ['LIKED_SONGS', 'YOUR_EPISODES_V2', 'PRERELEASES', 'EVENTS'],
      'limit': 50, 'offset': offset, 'flatten': true, 'expandedFolders': <String>[],
      'folderUri': null, 'includeFoldersWhenFlattening': false,
    }, scope);
    final library = _map(_map(data['me'])['libraryV3']);
    if (library['items'] is! List) throw const ProviderFailure(ProviderProblem.malformed);
    final items = _list(library['items']);
    final entries = <CatalogEntry>[
      if (offset == 0) CatalogEntry(provider: 'spotify', id: 'spotify:playlist:liked', title: 'Liked Songs', subtitle: 'Spotify', kind: CatalogKind.playlist, artwork: likedArtwork),
    ];
    for (final item in items.take(100)) {
      final wrapper = _map(_map(item)['item']), row = _map(_map(_map(item)['item'])['data']);
      final uri = _text(wrapper['_uri']).isNotEmpty ? _text(wrapper['_uri']) : _text(row['uri']);
      if (!RegExp(r'^spotify:playlist:[a-zA-Z0-9]{22}$').hasMatch(uri) || _text(row['name']).isEmpty) continue;
      final images = _list(_map(row['images'])['items']);
      entries.add(CatalogEntry(provider: 'spotify', id: uri, title: _text(row['name']), subtitle: _text(_map(_map(row['ownerV2'])['data'])['name']), kind: CatalogKind.playlist, artwork: _image(images.isEmpty ? null : _map(images.first)['sources'])));
    }
    return CatalogPage(entries, continuation: _next('library', offset, items.length, library['totalCount']));
  }

  Future<CatalogPage> tracks(String id, RequestScope scope, {String? continuation}) async {
    final liked = id == 'spotify:playlist:liked';
    if (!liked && !RegExp(r'^spotify:playlist:[a-zA-Z0-9]{22}$').hasMatch(id)) throw const ProviderFailure(ProviderProblem.unsupported);
    final offset = _offset(id, continuation);
    final data = await _query(liked ? 'fetchLibraryTracks' : 'fetchPlaylist', liked ? likedHash : playlistHash, {
      if (!liked) 'uri': id, 'offset': offset, 'limit': 100,
      if (!liked) 'enableWatchFeedEntrypoint': false,
    }, scope);
    final playlist = _map(data['playlistV2']);
    final content = liked ? _map(_map(_map(data['me'])['library'])['tracks']) : _map(playlist['content']);
    if (content['items'] is! List) throw const ProviderFailure(ProviderProblem.malformed);
    final items = _list(content['items']);
    final entries = <CatalogEntry>[];
    for (final item in items.take(100)) {
      final wrapper = _map(_map(item)[liked ? 'track' : 'itemV2']), row = _map(_map(_map(item)[liked ? 'track' : 'itemV2'])['data']);
      final uri = _text(row['uri']).isNotEmpty ? _text(row['uri']) : _text(wrapper['_uri']);
      if (!RegExp(r'^spotify:track:[a-zA-Z0-9]{22}$').hasMatch(uri) || _text(row['name']).isEmpty) continue;
      final artists = _list(_map(row['artists'])['items']).map((artist) => _text(_map(_map(artist)['profile'])['name'])).where((name) => name.isNotEmpty).join(', ');
      final album = _map(row['albumOfTrack']);
      final milliseconds = _map(row['trackDuration'] ?? row['duration'])['totalMilliseconds'];
      entries.add(CatalogEntry(provider: 'spotify', id: uri.substring('spotify:track:'.length), title: _text(row['name']), subtitle: artists, durationSeconds: milliseconds is num && milliseconds > 0 ? (milliseconds / 1000).round() : null, artwork: _image(_map(album['coverArt'])['sources'])));
    }
    final artworkItems = _list(_map(playlist['images'])['items']);
    return CatalogPage(entries, continuation: _next(id, offset, items.length, content['totalCount']), header: offset != 0 ? null : CatalogHeader(title: liked ? 'Liked Songs' : _text(playlist['name']), subtitle: 'Spotify · playback matched to YouTube Music', artwork: liked ? likedArtwork : _image(artworkItems.isEmpty ? null : _map(artworkItems.first)['sources'])));
  }
}
