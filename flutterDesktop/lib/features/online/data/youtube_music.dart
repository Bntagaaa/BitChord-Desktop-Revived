import 'dart:convert';

import '../domain/catalog.dart';
import 'provider_http.dart';

// Independent WEB_REMIX catalog adapter; the player/cipher/PoToken contract is
// separate. A catalog response is never treated as a playable URL.
final class YouTubeMusic implements CatalogProvider {
  YouTubeMusic(this.http, {Uri? origin})
    : origin = origin ?? Uri.parse('https://music.youtube.com');
  final ProviderHttp http;
  final Uri origin;
  String? _version;
  @override
  String get id => 'youtube';
  Future<void> _initialize(RequestScope scope) async {
    if (_version != null) return;
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
    _version = version;
  }

  Future<CatalogPage> _call(
    String endpoint,
    Map<String, Object?> body,
    RequestScope scope,
  ) async {
    await _initialize(scope);
    final data = await http.json(
      origin.resolve('/youtubei/v1/$endpoint?prettyPrint=false'),
      scope,
      headers: {
        'Origin': origin.origin,
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
          },
        },
        ...body,
      },
    );
    scope.check();
    return parsePage(data);
  }

  @override
  Future<CatalogPage> search(
    String query,
    RequestScope scope, {
    String? continuation,
  }) => _call('search', {
    if (continuation == null) 'query': query.trim(),
    if (continuation == null) 'params': 'EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D',
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
    throw const ProviderFailure(ProviderProblem.unsupported);
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

  static CatalogPage parsePage(Map<String, dynamic> data) {
    final entries = <CatalogEntry>[];
    // Only rows/cards in content containers: exclude menus, queues and suggestions.
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
            ].contains(pair.key) &&
            pair.value is Map<String, dynamic>) {
          final row = pair.value as Map<String, dynamic>;
          final flex = row['flexColumns'] as List? ?? [];
          final title = text(
            row['title'] ??
                (flex.isEmpty
                    ? null
                    : (flex.first
                          as Map)['musicResponsiveListItemFlexColumnRenderer']?['text']),
          );
          final subtitle = text(
            row['subtitle'] ??
                (flex.length < 2
                    ? null
                    : (flex[1]
                          as Map)['musicResponsiveListItemFlexColumnRenderer']?['text']),
          );
          final video =
              (row['playlistItemData'] as Map?)?['videoId'] ??
              nodes(
                row['navigationEndpoint'],
                'watchEndpoint',
              ).firstOrNull?['videoId'] ??
              nodes(row['overlay'], 'watchEndpoint').firstOrNull?['videoId'];
          final browse = nodes(
            row['navigationEndpoint'],
            'browseEndpoint',
          ).firstOrNull;
          final browseId = browse?['browseId'] as String?;
          final id = video as String? ?? browseId;
          if (id == null || id.isEmpty || title.isEmpty) continue;
          final thumbs =
              nodes(
                    row,
                    'musicThumbnailRenderer',
                  ).firstOrNull?['thumbnail']?['thumbnails']
                  as List?;
          final image = thumbs?.lastOrNull is Map
              ? (thumbs!.last as Map)['url'] as String?
              : null;
          entries.add(
            CatalogEntry(
              provider: 'youtube',
              id: id,
              title: title,
              subtitle: subtitle,
              kind: video != null
                  ? CatalogKind.track
                  : browseId!.startsWith('MPRE')
                  ? CatalogKind.album
                  : browseId.startsWith('UC')
                  ? CatalogKind.artist
                  : CatalogKind.playlist,
              artwork: image == null ? null : Uri.tryParse(image),
              params: browse?['params'] as String?,
            ),
          );
        } else {
          walk(pair.value);
        }
      }
    }

    walk(data['contents'] ?? data['continuationContents'] ?? data);
    final token =
        nodes(data, 'continuationCommand').firstOrNull?['token'] as String? ??
        nodes(data, 'nextContinuationData').firstOrNull?['continuation']
            as String?;
    return CatalogPage(entries, continuation: token);
  }
}
