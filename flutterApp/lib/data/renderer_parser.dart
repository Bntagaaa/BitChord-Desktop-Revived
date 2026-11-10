// Port source: InnertubeParser.kt (responsive rows, two-row cards and shelves).
import '../domain/music.dart';

typedef JsonMap = Map<String, dynamic>;
JsonMap object(dynamic value) => value is JsonMap ? value : <String, dynamic>{};
List<dynamic> array(dynamic value) => value is List ? value : const [];
String text(dynamic value) {
  final map = object(value);
  if (map['simpleText'] is String) return map['simpleText'] as String;
  return array(map['runs']).map((run) => object(run)['text'] ?? '').join();
}

dynamic at(dynamic root, List<String> keys) {
  dynamic value = root;
  for (final key in keys) {
    value = object(value)[key];
  }
  return value;
}

Iterable<JsonMap> renderers(dynamic root, String name) sync* {
  if (root is Map) {
    for (final entry in root.entries) {
      if (entry.key == name && entry.value is JsonMap) {
        yield entry.value as JsonMap;
      }
      yield* renderers(entry.value, name);
    }
  } else if (root is List) {
    for (final child in root) {
      yield* renderers(child, name);
    }
  }
}

String? thumbnail(dynamic root) {
  // thumbnails is an array rather than a renderer object.
  final urls = <JsonMap>[];
  void walk(dynamic value) {
    if (value is Map) {
      final thumbs = value['thumbnails'];
      if (thumbs is List) urls.addAll(thumbs.map(object));
      for (final child in value.values) {
        walk(child);
      }
    } else if (value is List) {
      for (final child in value) {
        walk(child);
      }
    }
  }

  walk(root);
  if (urls.isEmpty) return null;
  urls.sort(
    (a, b) => ((a['width'] as num?) ?? 0).compareTo((b['width'] as num?) ?? 0),
  );
  return urls.isEmpty ? null : urls.last['url'] as String?;
}

class RendererParser {
  static final _duration = RegExp(r'^\d+(?::\d{2}){1,2}$');
  static final _tally = RegExp(
    r'^[\d,.]+\s*[KMB]?\s*(plays?|views?|listeners?)$',
    caseSensitive: false,
  );
  static const _types = {
    'song',
    'video',
    'album',
    'single',
    'ep',
    'artist',
    'playlist',
  };

  Song? song(
    JsonMap row, {
    String? artistFallback,
    String? albumId,
    String? albumName,
  }) {
    final id =
        at(row, ['playlistItemData', 'videoId']) ??
        at(row, [
          'overlay',
          'musicItemThumbnailOverlayRenderer',
          'content',
          'musicPlayButtonRenderer',
          'playNavigationEndpoint',
          'watchEndpoint',
          'videoId',
        ]);
    final columns = array(row['flexColumns']);
    String column(int index) => index < columns.length
        ? text(
            at(columns[index], [
              'musicResponsiveListItemFlexColumnRenderer',
              'text',
            ]),
          )
        : '';
    final title = column(0);
    if (id is! String || title.isEmpty) return null;
    final parts = column(
      1,
    ).split(' • ').where((part) => part.trim().isNotEmpty).toList();
    final fixed = array(row['fixedColumns']).map(
      (value) => text(
        at(value, ['musicResponsiveListItemFixedColumnRenderer', 'text']),
      ),
    );
    final duration = [...parts, ...fixed].where(_duration.hasMatch).firstOrNull;
    final artist = parts
        .where(
          (part) =>
              !_duration.hasMatch(part) &&
              !_types.contains(part.toLowerCase()) &&
              !_tally.hasMatch(part),
        )
        .firstOrNull;
    final runs = columns.expand(
      (column) => array(
        at(column, [
          'musicResponsiveListItemFlexColumnRenderer',
          'text',
          'runs',
        ]),
      ),
    );
    String? artistId;
    for (final run in runs) {
      final browse = at(run, [
        'navigationEndpoint',
        'browseEndpoint',
        'browseId',
      ]);
      if (browse is! String) continue;
      if (browse.startsWith('UC')) artistId ??= browse;
      if (browse.startsWith('MPRE')) {
        albumId ??= browse;
        albumName ??= object(run)['text'] as String?;
      }
    }
    return Song(
      id: id,
      title: title,
      artist: artist ?? artistFallback ?? 'Unknown artist',
      artwork: thumbnail(row['thumbnail']),
      durationText: duration,
      artistId: artistId,
      albumId: albumId,
      albumName: albumName,
      playlistSetVideoId:
          at(row, ['playlistItemData', 'playlistSetVideoId']) as String?,
      isExplicit: renderers(row, 'musicInlineBadgeRenderer').any(
        (badge) => at(badge, ['icon', 'iconType']) == 'MUSIC_EXPLICIT_BADGE',
      ),
      isVideo: parts.any((part) => part.toLowerCase() == 'video'),
    );
  }

  CatalogItem? card(JsonMap row) {
    final title = text(row['title']);
    if (title.isEmpty) return null;
    final endpoint = object(row['navigationEndpoint']);
    final browse = object(endpoint['browseEndpoint']);
    String? browseId = browse['browseId'] as String?;
    var video = at(endpoint, ['watchEndpoint', 'videoId']) as String?;
    if (browseId?.startsWith('MPED') ?? false) {
      video = browseId!.substring(4);
      browseId = null;
    }
    final art = thumbnail(row['thumbnailRenderer']);
    final subtitle = text(row['subtitle']);

    final timeStatus = at(row, [
      'thumbnailOverlay',
      'musicItemThumbnailOverlayRenderer',
      'content',
      'thumbnailOverlayTimeStatusRenderer',
      'text',
    ]);
    final overlayDuration = text(timeStatus).trim();
    final subParts = subtitle.split(' • ').map((s) => s.trim()).toList();
    final durationFromSub = subParts.where(_duration.hasMatch).firstOrNull;
    final finalDuration = overlayDuration.isNotEmpty ? overlayDuration : durationFromSub;

    final isExplicit = renderers(row, 'musicInlineBadgeRenderer').any(
      (badge) => at(badge, ['icon', 'iconType']) == 'MUSIC_EXPLICIT_BADGE',
    ) || renderers(row, 'subtitleBadges').any(
      (badge) => at(badge, ['icon', 'iconType']) == 'MUSIC_EXPLICIT_BADGE',
    );

    final isVideo = (video != null && browseId == null) &&
        (at(row, ['aspectRatio'])?.toString().contains('16_9') == true ||
            (finalDuration != null && browseId == null) ||
            subtitle.toLowerCase().contains('video'));

    return CatalogItem(
      title: title,
      subtitle: subtitle,
      artwork: art,
      browseId: browseId,
      params: browse['params'] as String?,
      song: video == null
          ? null
          : Song(
              id: video,
              title: title,
              artist: subtitle,
              artwork: art,
              durationText: finalDuration,
              isVideo: isVideo,
              isExplicit: isExplicit,
            ),
      isVideo: isVideo,
      videoId: video,
      durationText: finalDuration,
      isExplicit: isExplicit,
    );
  }

  CatalogItem? browseRow(JsonMap row) {
    final endpoint = object(at(row, ['navigationEndpoint', 'browseEndpoint']));
    final id = endpoint['browseId'] as String?;
    if (id == null) return null;
    final columns = array(row['flexColumns']);
    final title = text(
      at(columns.firstOrNull, [
        'musicResponsiveListItemFlexColumnRenderer',
        'text',
      ]),
    );
    if (title.isEmpty) return null;
    final subtitle = columns.length < 2
        ? ''
        : text(
            at(columns[1], [
              'musicResponsiveListItemFlexColumnRenderer',
              'text',
            ]),
          );
    return CatalogItem(
      title: title,
      subtitle: subtitle,
      browseId: id,
      params: endpoint['params'] as String?,
      artwork: thumbnail(row['thumbnail']),
    );
  }

  CatalogPage page(JsonMap root, {bool plainShelves = false}) {
    final songs = <Song>[];
    final results = <CatalogItem>[];
    final seen = <String>{};
    for (final row in renderers(root, 'musicResponsiveListItemRenderer')) {
      final value = song(row);
      if (value != null && seen.add(value.playlistSetVideoId ?? value.id)) {
        songs.add(value);
      }
      if (value == null) {
        final item = browseRow(row);
        if (item != null && seen.add('browse:${item.browseId}')) {
          results.add(item);
        }
      }
    }
    final shelves = <Shelf>[];
    for (final rendererName in [
      'musicCarouselShelfRenderer',
      if (plainShelves) 'musicShelfRenderer',
      'gridRenderer',
    ]) {
      for (final shelf in renderers(root, rendererName)) {
        final header = object(shelf['header']);
        final basicHeader = object(header['musicCarouselShelfBasicHeaderRenderer']);
        final title = text(basicHeader['title']);
        final heading = title.isNotEmpty ? title : text(shelf['title']);
        final strapline = text(basicHeader['strapline']);

        final moreEndpoint = object(
          at(basicHeader, ['moreContentButton', 'buttonRenderer', 'navigationEndpoint', 'browseEndpoint']) ??
          at(basicHeader, ['moreContentButton', 'musicMoreContentButtonRenderer', 'navigationEndpoint', 'browseEndpoint']) ??
          at(shelf, ['bottomEndpoint', 'browseEndpoint']) ??
          at(shelf, ['bottomButton', 'buttonRenderer', 'navigationEndpoint', 'browseEndpoint']),
        );
        final moreBrowseId = moreEndpoint['browseId'] as String?;
        final moreParams = moreEndpoint['params'] as String?;

        final contents = array(shelf['contents'] ?? shelf['items']);
        var fromList = false;
        if (contents.isNotEmpty && object(contents.first)['musicResponsiveListItemRenderer'] != null) {
          fromList = true;
        }

        final items = <CatalogItem>[];
        for (final row in contents) {
          final twoRow = object(row)['musicTwoRowItemRenderer'];
          final responsive = object(row)['musicResponsiveListItemRenderer'];
          if (twoRow != null) {
            final value = card(object(twoRow));
            if (value != null) items.add(value);
          } else if (responsive != null) {
            final value = song(object(responsive));
            if (value != null) {
              items.add(
                CatalogItem(
                  title: value.title,
                  subtitle: value.artist,
                  artwork: value.artwork,
                  song: value,
                  videoId: value.id,
                  isVideo: value.isVideo,
                  durationText: value.durationText,
                  isExplicit: value.isExplicit,
                ),
              );
            }
          }
        }
        if (items.isNotEmpty) {
          final layout = shelfLayoutFor(
            title: heading,
            subtitle: strapline.isNotEmpty ? strapline : null,
            items: items,
            fromList: fromList,
          );
          shelves.add(
            Shelf(
              heading,
              items,
              subtitle: strapline.isNotEmpty ? strapline : null,
              moreBrowseId: moreBrowseId,
              moreParams: moreParams,
              layout: layout,
            ),
          );
        }
      }
    }
    final header = [
      ...renderers(root['header'], 'musicDetailHeaderRenderer'),
      ...renderers(root['header'], 'musicResponsiveHeaderRenderer'),
      ...renderers(root['header'], 'musicImmersiveHeaderRenderer'),
    ].firstOrNull;
    final continuation =
        renderers(root, 'nextContinuationData').firstOrNull?['continuation'] ??
        at(renderers(root, 'continuationItemRenderer').firstOrNull, [
          'continuationEndpoint',
          'continuationCommand',
          'token',
        ]);
    return CatalogPage(
      title: text(header?['title']),
      subtitle: text(header?['subtitle']),
      artwork: thumbnail(header),
      shelves: shelves,
      songs: plainShelves ? const [] : songs,
      items: results,
      continuation: continuation as String?,
    );
  }
}
