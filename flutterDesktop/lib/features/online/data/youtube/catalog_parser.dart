import '../../domain/catalog.dart';

// Reference-backed renderer semantics; bounded, type-checked traversal.
final class YouTubeCatalogParser {
  // InnertubeParser.parseSearchSuggestions: query endpoints take precedence
  // over styled display runs; entity results have different tap actions.
  static List<String> suggestions(Map<String, dynamic> data) {
    final seen = <String>{};
    for (final row in nodes(data, 'searchSuggestionRenderer')) {
      final endpoint = nodes(
        row['navigationEndpoint'],
        'searchEndpoint',
      ).firstOrNull;
      final query = string(endpoint?['query']) ?? text(row['suggestion']);
      if (query.trim().isNotEmpty) seen.add(query);
      if (seen.length >= 20) break;
    }
    return List.unmodifiable(seen);
  }

  static String? string(Object? value) => value is String ? value : null;
  static Map? map(Object? value) => value is Map ? value : null;
  static List list(Object? value) => value is List ? value : const [];

  static Iterable<Map<String, dynamic>> nodes(Object? value, String key) sync* {
    final pending = <({Object? value, int depth})>[(value: value, depth: 0)];
    var visited = 0;
    while (pending.isNotEmpty) {
      final item = pending.removeLast();
      if (++visited > 30000 || item.depth > 64) {
        throw const ProviderFailure(ProviderProblem.malformed);
      }
      final current = item.value;
      if (current is Map) {
        if (current[key] case final Map<String, dynamic> found) yield found;
        for (final child in current.values.toList().reversed) {
          if (child is Map || child is List)
            pending.add((value: child, depth: item.depth + 1));
        }
      } else if (current is List) {
        for (final child in current.reversed) {
          if (child is Map || child is List)
            pending.add((value: child, depth: item.depth + 1));
        }
      }
    }
  }

  static String text(Object? value) {
    if (value is! Map) return '';
    if (value['simpleText'] case final String text) return text;
    return list(
      value['runs'],
    ).whereType<Map>().map((run) => string(run['text']) ?? '').join();
  }

  static int? duration(String text) {
    final match = RegExp(
      r'^(?:(\d+):)?(\d{1,2}):(\d{2})$',
    ).firstMatch(text.trim());
    if (match == null) return null;
    final hours = int.tryParse(match[1] ?? '0') ?? 0;
    final minutes = int.parse(match[2]!), seconds = int.parse(match[3]!);
    if (seconds >= 60 || (match[1] != null && minutes >= 60)) return null;
    return hours * 3600 + minutes * 60 + seconds;
  }

  static CatalogEntry? _entry(Map<String, dynamic> row) {
    final flex = list(row['flexColumns']);
    Object? column(int index) => index >= flex.length
        ? null
        : map(
            map(flex[index])?['musicResponsiveListItemFlexColumnRenderer'],
          )?['text'];
    final title = text(row['title'] ?? column(0));
    final subtitle = text(row['subtitle'] ?? column(1));
    final navigation = row['navigationEndpoint'] ?? row['onTap'];
    final browse = nodes(navigation, 'browseEndpoint').firstOrNull;
    final browseId = string(browse?['browseId']);
    final watch =
        nodes(navigation, 'watchEndpoint').firstOrNull ??
        nodes(row['overlay'], 'watchEndpoint').firstOrNull;
    final videoId =
        string(map(row['playlistItemData'])?['videoId']) ??
        string(watch?['videoId']);
    final id = browseId ?? videoId;
    if (id == null || id.isEmpty || title.isEmpty) return null;
    final thumbs = list(
      map(
        nodes(row, 'musicThumbnailRenderer').firstOrNull?['thumbnail'],
      )?['thumbnails'],
    );
    final thumb = thumbs.whereType<Map>().lastOrNull;
    final image = Uri.tryParse(string(thumb?['url']) ?? '');
    final width = thumb?['width'] is num ? thumb!['width'] as num : 1;
    final height = thumb?['height'] is num ? thumb!['height'] as num : 1;
    final type = string(
      nodes(row, 'watchEndpointMusicConfig').firstOrNull?['musicVideoType'],
    );
    final isVideo = type == 'MUSIC_VIDEO_TYPE_ATV'
        ? false
        : (type == 'MUSIC_VIDEO_TYPE_OMV' ||
              type == 'MUSIC_VIDEO_TYPE_UGC' ||
              (type == null && height > 0 && width / height > 1.3));
    int? seconds = duration(text(row['lengthText'] ?? column(2)));
    for (final fixed in list(row['fixedColumns'])) {
      seconds ??= duration(
        text(
          map(
            map(fixed)?['musicResponsiveListItemFixedColumnRenderer'],
          )?['text'],
        ),
      );
    }
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
      params: string(browse?['params']),
      durationSeconds: seconds,
      isVideo: browseId == null && isVideo,
      setVideoId: string(map(row['playlistItemData'])?['playlistSetVideoId']),
    );
  }

  static List<CatalogEntry> _entries(Object? value) {
    final entries = <CatalogEntry>[];
    var visited = 0;
    void walk(Object? value, [int depth = 0]) {
      if (++visited > 30000 || depth > 64)
        throw const ProviderFailure(ProviderProblem.malformed);
      if (entries.length >= 400) return;
      if (value is List) {
        for (final child in value) {
          walk(child, depth + 1);
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
          if (pair.key == 'musicCardShelfRenderer')
            walk(row['contents'], depth + 1);
        } else {
          walk(pair.value, depth + 1);
        }
      }
    }

    walk(value);
    return entries;
  }

  static CatalogPage parsePage(
    Map<String, dynamic> data, {
    String? browseId,
    bool search = false,
  }) {
    // Live Home continuations carry an empty tab shell in `contents` alongside
    // the actual next shelves in `continuationContents`.
    final root = data['continuationContents'] ?? data['contents'] ?? data;
    final shelves = <CatalogShelf>[];
    var visited = 0;
    void collect(Object? value, [int depth = 0]) {
      if (++visited > 30000 || depth > 64)
        throw const ProviderFailure(ProviderProblem.malformed);
      if (shelves.length >= 40) return;
      if (value is List) {
        for (final child in value) {
          collect(child, depth + 1);
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
              moreId: string(endpoint?['browseId']),
              params: string(endpoint?['params']),
              layout: fromList ? ShelfLayout.tracks : ShelfLayout.cards,
            ),
          );
        } else {
          collect(pair.value, depth + 1);
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
    final thumbnails = list(
      map(
        nodes(header, 'musicThumbnailRenderer').firstOrNull?['thumbnail'],
      )?['thumbnails'],
    );
    final art = string(thumbnails.whereType<Map>().lastOrNull?['url']);
    final continuationRoot =
        playlist ??
        nodes(root, 'sectionListContinuation').firstOrNull ??
        (search
            ? nodes(root, 'musicShelfContinuation').firstOrNull ??
                  nodes(root, 'musicShelfRenderer').firstOrNull
            : null) ??
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
        string(
          nodes(continuations, 'continuationCommand').firstOrNull?['token'],
        ) ??
        string(
          nodes(
            continuations,
            'nextContinuationData',
          ).firstOrNull?['continuation'],
        );
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
