import 'dart:convert';

import '../domain/catalog.dart';
import 'provider_http.dart';
import 'youtube_session.dart';
import 'youtube_resolver.dart';

// Independent WEB_REMIX catalog adapter; the player/cipher/PoToken contract is
// separate. A catalog response is never treated as a playable URL.
final class YouTubeMusic implements CatalogProvider {
  YouTubeMusic(this.http, {Uri? origin, this.resolver})
    : origin = origin ?? Uri.parse('https://music.youtube.com');
  final ProviderHttp http;
  final Uri origin;
  String? _version;
  final YouTubeResolver? resolver;
  YouTubeSession? _session;
  int _accountGeneration = 0;
  bool get signedIn => _session != null;
  Future<void> signIn(String cookie, RequestScope scope) async {
    if (origin.origin != YouTubeSession.origin)
      throw const ProviderFailure(ProviderProblem.unsupported);
    final generation = ++_accountGeneration;
    final session = await YouTubeSession.verify(cookie, http, scope);
    scope.check();
    if (generation != _accountGeneration)
      throw const ProviderFailure(ProviderProblem.cancelled);
    // Also fence queries begun while verification was in flight.
    ++_accountGeneration;
    _session = session;
    _version = session.version;
  }

  void signOut() {
    ++_accountGeneration;
    _session = null;
    _version = null;
  }

  @override
  String get id => 'youtube';
  Future<void> _initialize(RequestScope scope) async {
    if (_version != null) return;
    final generation = _accountGeneration;
    final response = await http.request(
      origin,
      scope,
      maxBytes: 3 * 1024 * 1024,
    );
    if (response.status != 200)
      throw ProviderFailure(ProviderProblem.unavailable, response.status);
    final html = utf8.decode(response.bytes);
    final version = RegExp(
      r'"INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"',
    ).firstMatch(html)?.group(1);
    if (version == null) throw const ProviderFailure(ProviderProblem.malformed);
    scope.check();
    if (generation != _accountGeneration)
      throw const ProviderFailure(ProviderProblem.cancelled);
    _version = version;
  }

  Future<CatalogPage> _call(
    String endpoint,
    Map<String, Object?> body,
    RequestScope scope,
  ) async {
    final generation = _accountGeneration;
    await _initialize(scope);
    if (generation != _accountGeneration)
      throw const ProviderFailure(ProviderProblem.cancelled);
    final session = _session;
    Map<String, dynamic> data;
    try {
      data = await http.json(
        origin.resolve('/youtubei/v1/$endpoint?prettyPrint=false'),
        scope,
        headers: {
          'Origin': origin.origin,
          if (session != null && origin.origin == YouTubeSession.origin)
            ...session.headers(),
          'X-YouTube-Client-Name': '67',
          'X-YouTube-Client-Version': _version!,
        },
        body: {
          'context': {
            'client': {
              'clientName': 'WEB_REMIX',
              'clientVersion': _version,
              'hl': 'en',
              'gl': 'US',
              if (session?.visitorData != null)
                'visitorData': session!.visitorData,
            },
            if (session?.dataSyncId != null)
              'user': {'onBehalfOfUser': session!.dataSyncId},
          },
          ...body,
        },
      );
    } on ProviderFailure catch (e) {
      if (e.problem == ProviderProblem.unauthorized &&
          generation == _accountGeneration &&
          _session != null)
        signOut();
      rethrow;
    }
    scope.check();
    if (generation != _accountGeneration)
      throw const ProviderFailure(ProviderProblem.cancelled);
    return parsePage(data, browseId: body['browseId'] as String?);
  }

  @override
  Future<CatalogPage> search(
    String query,
    RequestScope scope, {
    String? continuation,
    SearchFilter filter = SearchFilter.all,
  }) => _call('search', {
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
  }) => _call('browse', {
    if (continuation == null) 'browseId': browseId,
    if (params != null) 'params': params,
    if (continuation != null) 'continuation': continuation,
  }, scope);
  @override
  Future<ResolvedMedia> resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope scope,
  ) async {
    scope.check();
    final selected = resolver;
    if (selected == null)
      throw const ProviderFailure(ProviderProblem.unsupported);
    return selected.resolve(track, quality, scope);
  }

  static Iterable<Map<String, dynamic>> nodes(Object? value, String key) sync* {
    if (value is Map) {
      if (value[key] case final Map<String, dynamic> found) yield found;
      for (final child in value.values) {
        yield* nodes(child, key);
      }
    } else if (value is List) {
      for (final child in value) {
        yield* nodes(child, key);
      }
    }
  }

  static String text(Object? value) {
    if (value is! Map) return '';
    if (value['simpleText'] case final String t) return t;
    return (value['runs'] as List? ?? [])
        .whereType<Map>()
        .map((r) => r['text'] ?? '')
        .join();
  }

  static CatalogEntry? _entry(Map<String, dynamic> row) {
    final flex = row['flexColumns'] as List? ?? [];
    Object? column(int index) {
      if (flex.length <= index || flex[index] is! Map) return null;
      final cell =
          (flex[index] as Map)['musicResponsiveListItemFlexColumnRenderer'];
      return cell is Map ? cell['text'] : null;
    }

    final title = text(row['title'] ?? column(0));
    final subtitle = text(row['subtitle'] ?? column(1));
    final navigation = row['navigationEndpoint'] ?? row['onTap'];
    final browse = nodes(navigation, 'browseEndpoint').firstOrNull;
    final browseId = browse?['browseId'] as String?;
    final video =
        (row['playlistItemData'] as Map?)?['videoId'] ??
        nodes(navigation, 'watchEndpoint').firstOrNull?['videoId'] ??
        nodes(row['overlay'], 'watchEndpoint').firstOrNull?['videoId'];
    // Collection play overlays must not turn albums/artists into track cards.
    final id = browseId ?? video as String?;
    if (id == null || id.isEmpty || title.isEmpty) return null;
    final thumbnails =
        nodes(
              row,
              'musicThumbnailRenderer',
            ).firstOrNull?['thumbnail']?['thumbnails']
            as List? ??
        [];
    final thumb = thumbnails.lastOrNull is Map
        ? thumbnails.last as Map
        : const {};
    final image = thumb['url'] is String
        ? Uri.tryParse(thumb['url'] as String)
        : null;
    final width = thumb['width'] as num? ?? 1,
        height = thumb['height'] as num? ?? 1;
    return CatalogEntry(
      provider: 'youtube',
      id: id,
      title: title,
      subtitle: subtitle,
      kind: browseId == null
          ? CatalogKind.track
          : browseId.startsWith('MPRE')
          ? CatalogKind.album
          : browseId.startsWith('UC')
          ? CatalogKind.artist
          : CatalogKind.playlist,
      artwork: image?.scheme == 'https' ? image : null,
      params: browse?['params'] as String?,
      isVideo: browseId == null && width / height > 1.3,
    );
  }

  static List<CatalogEntry> _entries(Object? value) {
    final entries = <CatalogEntry>[];
    void walk(Object? value) {
      if (entries.length >= 400) return;
      if (value is List) {
        for (final child in value) {
          walk(child);
        }
      }
      if (value is! Map) return;
      for (final pair in value.entries) {
        if ([
          'menu',
          'overlay',
          'suggestions',
          'header',
          'background',
        ].contains(pair.key))
          continue;
        if ([
              'musicResponsiveListItemRenderer',
              'musicTwoRowItemRenderer',
              'musicCardShelfRenderer',
            ].contains(pair.key) &&
            pair.value is Map<String, dynamic>) {
          final row = pair.value as Map<String, dynamic>;
          final entry = _entry(row);
          if (entry != null) entries.add(entry);
          if (pair.key == 'musicCardShelfRenderer') walk(row['contents']);
        } else {
          walk(pair.value);
        }
      }
    }

    walk(value);
    return entries;
  }

  static CatalogPage parsePage(Map<String, dynamic> data, {String? browseId}) {
    final root = data['contents'] ?? data['continuationContents'] ?? data;
    final shelves = <CatalogShelf>[];
    void collect(Object? value) {
      if (shelves.length >= 40) return;
      if (value is List) {
        for (final child in value) {
          collect(child);
        }
      }
      if (value is! Map) return;
      for (final pair in value.entries) {
        if (['menu', 'header', 'suggestions', 'background'].contains(pair.key))
          continue;
        if ([
              'musicCarouselShelfRenderer',
              'musicShelfRenderer',
              'gridRenderer',
            ].contains(pair.key) &&
            pair.value is Map<String, dynamic>) {
          final shelf = pair.value as Map<String, dynamic>;
          final header =
              nodes(
                shelf['header'],
                'musicCarouselShelfBasicHeaderRenderer',
              ).firstOrNull ??
              nodes(shelf['header'], 'gridHeaderRenderer').firstOrNull ??
              shelf;
          final content = shelf['contents'] ?? shelf['items'];
          final entries = _entries(content);
          if (entries.isEmpty) continue;
          final endpoint =
              nodes(header['title'], 'browseEndpoint').firstOrNull ??
              nodes(
                header['moreContentButton'],
                'browseEndpoint',
              ).firstOrNull ??
              nodes(header['endIcons'], 'browseEndpoint').firstOrNull ??
              nodes(
                header['navigationEndpoint'],
                'browseEndpoint',
              ).firstOrNull ??
              nodes(shelf['bottomEndpoint'], 'browseEndpoint').firstOrNull ??
              nodes(shelf['bottomButton'], 'browseEndpoint').firstOrNull;
          final title = text(header['title']);
          final fromList =
              content is List &&
              content.firstOrNull is Map &&
              (content.first as Map).containsKey(
                'musicResponsiveListItemRenderer',
              );
          shelves.add(
            CatalogShelf(
              title: title.isEmpty ? 'For you' : title,
              subtitle: text(header['strapline']),
              entries: entries,
              moreId: endpoint?['browseId'] as String?,
              params: endpoint?['params'] as String?,
              layout: fromList ? ShelfLayout.tracks : ShelfLayout.cards,
            ),
          );
        } else {
          collect(pair.value);
        }
      }
    }

    collect(root);
    final playlist =
        nodes(root, 'musicPlaylistShelfRenderer').firstOrNull ??
        nodes(root, 'musicPlaylistShelfContinuation').firstOrNull ??
        ((browseId?.startsWith('MPRE') ?? false)
            ? nodes(root, 'musicShelfRenderer').firstOrNull
            : null);
    final entries = _entries(playlist ?? root);
    final header =
        nodes(data, 'musicResponsiveHeaderRenderer').firstOrNull ??
        nodes(data['header'], 'musicDetailHeaderRenderer').firstOrNull ??
        nodes(data['header'], 'musicImmersiveHeaderRenderer').firstOrNull ??
        nodes(data['header'], 'musicVisualHeaderRenderer').firstOrNull;
    final headerTitle = text(header?['title']);
    final thumb =
        nodes(
              header,
              'musicThumbnailRenderer',
            ).firstOrNull?['thumbnail']?['thumbnails']
            as List?;
    final art = thumb?.lastOrNull is Map
        ? (thumb!.last as Map)['url'] as String?
        : null;
    final continuationRoot =
        playlist ??
        nodes(root, 'sectionListContinuation').firstOrNull ??
        nodes(root, 'sectionListRenderer').firstOrNull ??
        nodes(root, 'musicShelfContinuation').firstOrNull ??
        nodes(root, 'musicShelfRenderer').firstOrNull ??
        nodes(root, 'appendContinuationItemsAction').firstOrNull ??
        root;
    // Only the feed/collection continuation, never an individual carousel's token.
    Object? continuations;
    if (continuationRoot is Map) {
      continuations = continuationRoot['continuations'];
      if (continuations == null) {
        final children =
            continuationRoot['contents'] ??
            continuationRoot['continuationItems'];
        if (children is List) {
          continuations = [
            for (final child in children)
              if (child is Map && child['continuationItemRenderer'] != null)
                child['continuationItemRenderer'],
          ];
        }
      }
    }
    final token =
        nodes(continuations, 'continuationCommand').firstOrNull?['token']
            as String? ??
        nodes(
              continuations,
              'nextContinuationData',
            ).firstOrNull?['continuation']
            as String?;
    return CatalogPage(
      entries,
      shelves: playlist == null ? shelves : const [],
      continuation: token,
      header: headerTitle.isEmpty
          ? null
          : CatalogHeader(
              title: headerTitle,
              subtitle: text(header?['subtitle']),
              artwork: art == null ? null : Uri.tryParse(art),
            ),
    );
  }
}
