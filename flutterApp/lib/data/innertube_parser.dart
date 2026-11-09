import '../domain/models.dart';

typedef JsonMap = Map<String, dynamic>;

/// Parsers mirror the renderer contracts in the source Kotlin implementation.
/// Browse targets are read before watch overlays so albums never become songs.
class InnertubeParser {
  static JsonMap object(Object? value) => value is Map<String, dynamic>
      ? value
      : value is Map
      ? Map<String, dynamic>.from(value)
      : const {};
  static List<dynamic> array(Object? value) => value is List ? value : const [];
  static Object? at(Object? root, List<Object> path) {
    var value = root;
    for (final key in path) {
      if (key is int) {
        final list = array(value);
        value = key >= 0 && key < list.length ? list[key] : null;
      } else {
        value = object(value)[key];
      }
    }
    return value;
  }

  static String? string(Object? value) =>
      value is String && value.isNotEmpty ? value : null;
  static String text(Object? value) {
    if (value is String) return value;
    final map = object(value);
    return string(map['simpleText']) ??
        array(map['runs'])
            .map((r) => object(r)['text'] as String? ?? '')
            .join();
  }

  static Iterable<JsonMap> collect(
    Object? root,
    String key, [
    int depth = 0,
  ]) sync* {
    if (depth > 64) return;
    if (root is Map) {
      final found = root[key];
      if (found is Map) yield object(found);
      for (final value in root.values) {
        yield* collect(value, key, depth + 1);
      }
    } else if (root is List) {
      for (final value in root) {
        yield* collect(value, key, depth + 1);
      }
    }
  }

  static String? findString(Object? root, String key, [int depth = 0]) {
    if (depth > 64) return null;
    if (root is Map) {
      final own = string(root[key]);
      if (own != null) return own;
      for (final value in root.values) {
        final found = findString(value, key, depth + 1);
        if (found != null) return found;
      }
    } else if (root is List) {
      for (final value in root) {
        final found = findString(value, key, depth + 1);
        if (found != null) return found;
      }
    }
    return null;
  }

  static String? thumbnail(Object? root) {
    final images = <JsonMap>[];
    void visit(Object? node, int depth) {
      if (depth > 16) return;
      if (node is Map) {
        for (final image in array(node['thumbnails'])) {
          if (string(object(image)['url']) != null) images.add(object(image));
        }
        for (final entry in node.entries) {
          if (entry.key != 'thumbnails') visit(entry.value, depth + 1);
        }
      } else if (node is List) {
        for (final item in node) {
          visit(item, depth + 1);
        }
      }
    }

    visit(root, 0);
    if (images.isEmpty) return null;
    images.sort(
      (a, b) =>
          ((a['width'] as num?) ?? 0).compareTo((b['width'] as num?) ?? 0),
    );
    final url = images.last['url'] as String;
    return url.startsWith('//') ? 'https:$url' : url;
  }

  static String? continuation(Object? root) {
    for (final item in collect(root, 'continuationItemRenderer')) {
      final token = string(
        at(item, ['continuationEndpoint', 'continuationCommand', 'token']),
      );
      if (token != null) return token;
    }
    return collect(
      root,
      'nextContinuationData',
    ).map((r) => string(r['continuation'])).whereType<String>().firstOrNull;
  }

  static BrowseType browseType(String id, [String? pageType]) {
    final type = pageType ?? '';
    if (type.contains('ARTIST') || id.startsWith('UC')) {
      return BrowseType.artist;
    }
    if (type.contains('ALBUM') || id.startsWith('MPRE')) {
      return BrowseType.album;
    }
    if (type.contains('PLAYLIST') ||
        id.startsWith('VL') ||
        id.startsWith('PL')) {
      return BrowseType.playlist;
    }
    return BrowseType.other;
  }

  static JsonMap _browseEndpoint(Object? root) =>
      object(at(root, ['navigationEndpoint', 'browseEndpoint']));
  static String _column(JsonMap r, int index) => text(
    at(r, [
      'flexColumns',
      index,
      'musicResponsiveListItemFlexColumnRenderer',
      'text',
    ]),
  );
  static bool? _videoFlag(Object? root) {
    switch (findString(root, 'musicVideoType')) {
      case 'MUSIC_VIDEO_TYPE_ATV':
        return false;
      case 'MUSIC_VIDEO_TYPE_OMV':
      case 'MUSIC_VIDEO_TYPE_UGC':
        return true;
      default:
        return null;
    }
  }

  static bool _wide(Object? root) {
    final widths = collect(root, 'musicThumbnailRenderer');
    for (final renderer in widths) {
      final image = object(
        array(at(renderer, ['thumbnail', 'thumbnails'])).lastOrNull,
      );
      final w = (image['width'] as num?) ?? 0;
      final h = (image['height'] as num?) ?? 0;
      if (h > 0 && w / h > 1.25) return true;
    }
    return false;
  }

  static final _duration = RegExp(r'^\d{1,3}:\d{2}(?::\d{2})?$');
  static final _tally = RegExp(
    r'^[\d.,]+[KMB]?\s+(views|plays|likes|subscribers)$',
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
  static String artistFromSubtitle(String value) =>
      value
          .split(RegExp(r'\s*[•·]\s*'))
          .where(
            (p) =>
                p.isNotEmpty &&
                !_types.contains(p.toLowerCase()) &&
                !_duration.hasMatch(p) &&
                !_tally.hasMatch(p) &&
                !RegExp(r'^\d{4}$').hasMatch(p),
          )
          .firstOrNull ??
      '';

  static List<ArtistRef> _artists(Object? root) {
    final out = <String, ArtistRef>{};
    for (final run in _runs(root)) {
      final endpoint = _browseEndpoint(run);
      final id = string(endpoint['browseId']);
      final name = string(run['text']);
      if (id != null &&
          name != null &&
          browseType(id, findString(endpoint, 'pageType')) ==
              BrowseType.artist) {
        out[id] = ArtistRef(name: name, browseId: id);
      }
    }
    return out.values.toList(growable: false);
  }

  static Iterable<JsonMap> _runs(Object? root, [int depth = 0]) sync* {
    if (depth > 48) return;
    if (root is Map) {
      for (final run in array(root['runs'])) {
        yield object(run);
      }
      for (final entry in root.entries) {
        if (entry.key != 'runs') yield* _runs(entry.value, depth + 1);
      }
    } else if (root is List) {
      for (final item in root) {
        yield* _runs(item, depth + 1);
      }
    }
  }

  static ({String? id, String? name}) _album(Object? root) {
    for (final run in _runs(root)) {
      final endpoint = _browseEndpoint(run);
      final id = string(endpoint['browseId']);
      if (id != null &&
          browseType(id, findString(endpoint, 'pageType')) ==
              BrowseType.album) {
        return (id: id, name: string(run['text']));
      }
    }
    return (id: null, name: null);
  }

  static bool _explicit(Object? root) =>
      collect(
        root,
        'icon',
      ).any((r) => r['iconType'] == 'MUSIC_EXPLICIT_BADGE') ||
      collect(
        root,
        'musicInlineBadgeRenderer',
      ).any((r) => findString(r, 'iconType') == 'MUSIC_EXPLICIT_BADGE');
  static String? _feedback(Object? root) {
    for (final service in collect(root, 'menuServiceItemRenderer')) {
      if (at(service, ['icon', 'iconType']) == 'DELETE') {
        return string(
          at(service, ['serviceEndpoint', 'feedbackEndpoint', 'feedbackToken']),
        );
      }
    }
    return null;
  }

  static BrowseItem? responsiveBrowse(JsonMap r) {
    final endpoint = _browseEndpoint(r);
    final id = string(endpoint['browseId']);
    final title = _column(r, 0);
    if (id == null || title.isEmpty || id.startsWith('MPED')) return null;
    return BrowseItem(
      browseId: id,
      title: title,
      subtitle: _column(r, 1),
      thumbnailUrl: thumbnail(r['thumbnail']),
      type: browseType(id, findString(endpoint, 'pageType')),
    );
  }

  static Song? responsiveSong(JsonMap r, {Song? fallback}) {
    // The row itself may be a browse result with a playable overlay.
    if (responsiveBrowse(r) != null) return null;
    final id =
        string(at(r, ['playlistItemData', 'videoId'])) ??
        string(at(r, ['navigationEndpoint', 'watchEndpoint', 'videoId'])) ??
        string(
          at(r, [
            'overlay',
            'musicItemThumbnailOverlayRenderer',
            'content',
            'musicPlayButtonRenderer',
            'playNavigationEndpoint',
            'watchEndpoint',
            'videoId',
          ]),
        );
    final title = _column(r, 0);
    if (id == null || title.isEmpty) return null;
    final subtitle = _column(r, 1);
    final credits = _artists(r['flexColumns']);
    final album = _album(r['flexColumns']);
    final durations = <String>[
      ...subtitle.split(RegExp(r'\s*[•·]\s*')),
      ...array(r['fixedColumns']).map(
        (c) =>
            text(at(c, ['musicResponsiveListItemFixedColumnRenderer', 'text'])),
      ),
    ];
    final artist = credits.isNotEmpty
        ? credits.map((a) => a.name).join(', ')
        : artistFromSubtitle(subtitle);
    return Song(
      videoId: id,
      title: title,
      artist: artist.isNotEmpty ? artist : fallback?.artist ?? 'Unknown artist',
      thumbnailUrl: thumbnail(r['thumbnail']) ?? fallback?.thumbnailUrl,
      durationText: durations.where(_duration.hasMatch).lastOrNull,
      artistId: credits.firstOrNull?.browseId ?? fallback?.artistId,
      artists: credits.isNotEmpty ? credits : fallback?.artists ?? const [],
      albumId: album.id ?? fallback?.albumId,
      albumName: album.name ?? fallback?.albumName,
      setVideoId: string(at(r, ['playlistItemData', 'playlistSetVideoId'])),
      isVideo:
          _videoFlag(r) ??
          (subtitle.toLowerCase().startsWith('video •') ||
              _wide(r['thumbnail'])),
      isExplicit: _explicit(r),
      feedbackToken: _feedback(r),
    );
  }

  static ShelfItem? twoRow(JsonMap r) {
    final title = text(r['title']);
    final endpoint = object(r['navigationEndpoint']);
    final browseId = string(at(endpoint, ['browseEndpoint', 'browseId']));
    final videoId =
        string(at(endpoint, ['watchEndpoint', 'videoId'])) ??
        (browseId?.startsWith('MPED') == true ? browseId!.substring(4) : null);
    final id = browseId?.startsWith('MPED') == true ? null : browseId;
    if (title.isEmpty || (id == null && videoId == null)) return null;
    final subtitle = text(r['subtitle']);
    final isVideo =
        id == null && (_videoFlag(r) ?? _wide(r['thumbnailRenderer']));
    final credits = _artists(r['subtitle']);
    final album = _album(r['subtitle']);
    return ShelfItem(
      title: title,
      subtitle: subtitle,
      thumbnailUrl: thumbnail(r['thumbnailRenderer']),
      videoId: id == null ? videoId : null,
      browseId: id,
      type: id == null
          ? BrowseType.other
          : browseType(id, findString(endpoint, 'pageType')),
      isVideo: isVideo,
      track: id == null && videoId != null
          ? Song(
              videoId: videoId,
              title: title,
              artist: artistFromSubtitle(subtitle),
              thumbnailUrl: thumbnail(r['thumbnailRenderer']),
              artists: credits,
              artistId: credits.firstOrNull?.browseId,
              albumId: album.id,
              albumName: album.name,
              isVideo: isVideo,
              durationText: subtitle
                  .split(RegExp(r'\s*[•·]\s*'))
                  .where(_duration.hasMatch)
                  .lastOrNull,
              isExplicit: _explicit(r),
            )
          : null,
    );
  }

  static ShelfItem? _shelfItem(JsonMap root) {
    if (root['musicTwoRowItemRenderer'] is Map) {
      return twoRow(object(root['musicTwoRowItemRenderer']));
    }
    final row = object(root['musicResponsiveListItemRenderer']);
    if (row.isEmpty) return null;
    final browse = responsiveBrowse(row);
    if (browse != null) {
      return ShelfItem(
        title: browse.title,
        subtitle: browse.subtitle,
        thumbnailUrl: browse.thumbnailUrl,
        browseId: browse.browseId,
        type: browse.type,
      );
    }
    final song = responsiveSong(row);
    return song == null
        ? null
        : ShelfItem(
            title: song.title,
            subtitle: song.artist,
            thumbnailUrl: song.thumbnailUrl,
            videoId: song.videoId,
            isVideo: song.isVideo,
            track: song,
          );
  }

  static SearchPage parseSearch(
    JsonMap root, {
    SearchFilter filter = SearchFilter.all,
  }) {
    final results = <SearchResult>[];
    for (final card in collect(root, 'musicCardShelfRenderer')) {
      final title = text(card['title']);
      final endpoint = object(card['onTap']);
      final id = string(at(endpoint, ['watchEndpoint', 'videoId']));
      final browseId = string(at(endpoint, ['browseEndpoint', 'browseId']));
      if (title.isEmpty) continue;
      if (browseId != null) {
        results.add(
          SearchResult.browse(
            BrowseItem(
              browseId: browseId,
              title: title,
              subtitle: text(card['subtitle']),
              thumbnailUrl: thumbnail(card['thumbnail']),
              type: browseType(browseId, findString(endpoint, 'pageType')),
            ),
          ),
        );
      } else if (id != null) {
        results.add(
          SearchResult.track(
            Song(
              videoId: id,
              title: title,
              artist: artistFromSubtitle(text(card['subtitle'])),
              thumbnailUrl: thumbnail(card['thumbnail']),
              isVideo: _videoFlag(card) ?? false,
            ),
            isTopResult: true,
          ),
        );
      }
    }
    for (final row in collect(root, 'musicResponsiveListItemRenderer')) {
      final browse = responsiveBrowse(row);
      if (browse != null) {
        results.add(SearchResult.browse(browse));
        continue;
      }
      final song = responsiveSong(row);
      if (song != null) results.add(SearchResult.track(song));
    }
    final seen = <String>{};
    return SearchPage(
      results: results.where((r) {
        if (r.song != null) {
          if (filter == SearchFilter.videos && !r.song!.isVideo) return false;
          if (filter != SearchFilter.videos && r.song!.isVideo) return false;
        }
        return seen.add(r.key);
      }).toList(),
      continuation: continuation(root),
    );
  }

  static HomeFeed parseHome(JsonMap root) {
    final shelves = <HomeShelf>[];
    void walk(Object? node, int depth) {
      if (depth > 48) return;
      if (node is Map) {
        for (final kind in [
          'musicCarouselShelfRenderer',
          'musicShelfRenderer',
          'gridRenderer',
        ]) {
          if (node[kind] is! Map) continue;
          final r = object(node[kind]);
          final header = object(r['header']);
          final title =
              text(
                    at(header, [
                      'musicCarouselShelfBasicHeaderRenderer',
                      'title',
                    ]),
                  )
                  .ifEmpty(text(at(header, ['gridHeaderRenderer', 'title'])))
                  .ifEmpty(text(r['title']));
          final rawItems = array(
            r[kind == 'gridRenderer' ? 'items' : 'contents'],
          );
          final items = rawItems
              .map((v) => _shelfItem(object(v)))
              .whereType<ShelfItem>()
              .toList();
          if (title.isNotEmpty && items.isNotEmpty) {
            final list = rawItems.any(
              (v) => object(v).containsKey('musicResponsiveListItemRenderer'),
            );
            shelves.add(
              HomeShelf(
                title: title,
                items: items,
                subtitle: text(
                  at(header, [
                    'musicCarouselShelfBasicHeaderRenderer',
                    'strapline',
                  ]),
                ),
                moreBrowseId: findString(header, 'browseId'),
                moreParams: findString(header, 'params'),
                layout: list
                    ? ShelfLayout.list
                    : items.where((i) => i.isVideo).length * 2 > items.length
                    ? ShelfLayout.video
                    : ShelfLayout.cards,
              ),
            );
          }
          return; // Nested card content belongs to this shelf, not another one.
        }
        for (final value in node.values) {
          walk(value, depth + 1);
        }
      } else if (node is List) {
        for (final value in node) {
          walk(value, depth + 1);
        }
      }
    }

    walk(root, 0);
    return HomeFeed(shelves: shelves, continuation: continuation(root));
  }

  static JsonMap header(JsonMap root) {
    for (final name in [
      'musicResponsiveHeaderRenderer',
      'musicDetailHeaderRenderer',
      'musicImmersiveHeaderRenderer',
      'musicVisualHeaderRenderer',
      'musicEditablePlaylistDetailHeaderRenderer',
    ]) {
      final found = collect(root, name).firstOrNull;
      if (found != null) return found;
    }
    return const {};
  }

  static DetailPage parseDetail(JsonMap root, String browseId) {
    final h = header(root);
    final title = text(h['title']).ifEmpty(browseId);
    final subtitle = [
      'straplineTextOne',
      'subtitle',
      'secondSubtitle',
    ].map((k) => text(h[k])).where((s) => s.isNotEmpty).toSet().join(' • ');
    final type = browseType(browseId);
    final albumCredits = _artists(h);
    final art = thumbnail(h);
    final fallback = type == BrowseType.album
        ? Song(
            videoId: '',
            title: '',
            artist: albumCredits
                .map((a) => a.name)
                .join(', ')
                .ifEmpty(artistFromSubtitle(subtitle)),
            artists: albumCredits,
            artistId: albumCredits.firstOrNull?.browseId,
            albumId: browseId,
            albumName: title,
            thumbnailUrl: art,
          )
        : null;
    final scope =
        root['continuationContents'] ??
        at(root, [
          'contents',
          'twoColumnBrowseResultsRenderer',
          'secondaryContents',
        ]) ??
        root;
    final playlist = object(at(scope, ['musicPlaylistShelfContinuation']));
    final primary = playlist.isNotEmpty
        ? playlist
        : collect(scope, 'musicPlaylistShelfRenderer').firstOrNull ??
              collect(scope, 'musicShelfContinuation').firstOrNull ??
              collect(
                scope,
                'musicShelfRenderer',
              ).where((r) => text(r['title']) != 'Suggestions').firstOrNull ??
              scope;
    final songs = collect(primary, 'musicResponsiveListItemRenderer')
        .map((r) => responsiveSong(r, fallback: fallback))
        .whereType<Song>()
        .toList();
    final suggested = collect(scope, 'musicShelfRenderer')
        .where((r) => text(r['title']) == 'Suggestions')
        .expand((r) => collect(r, 'musicResponsiveListItemRenderer'))
        .map(responsiveSong)
        .whereType<Song>()
        .toList();
    return DetailPage(
      browseId: browseId,
      title: title,
      subtitle: subtitle,
      thumbnailUrl: art,
      type: type,
      songs: songs,
      shelves: parseHome(root).shelves,
      suggestedSongs: suggested,
      continuation: continuation(primary),
      description: collect(root, 'musicDescriptionShelfRenderer')
          .map((r) => text(r['description']))
          .where((s) => s.isNotEmpty)
          .firstOrNull,
      playlistId:
          findString(h, 'playlistId') ??
          (browseId.startsWith('VL') ? browseId.substring(2) : null),
      isOwned: type == BrowseType.playlist && playlistOwned(root) == true,
    );
  }

  /// Header state only: a song row's delete icon cannot establish ownership.
  /// Null means a continuation/older response did not expose ownership state.
  static bool? playlistOwned(JsonMap root) {
    if (collect(root, 'musicEditablePlaylistDetailHeaderRenderer').isNotEmpty) {
      return true;
    }
    final header = collect(root, 'musicResponsiveHeaderRenderer').firstOrNull;
    if (header == null) return null;
    if (collect(header, 'menuNavigationItemRenderer').any(
      (menu) =>
          const {'DELETE', 'EDIT'}.contains(at(menu, ['icon', 'iconType'])),
    )) {
      return true;
    }
    return !array(header['buttons']).any((button) {
      final toggle = object(object(button)['toggleButtonRenderer']);
      return at(toggle, ['defaultIcon', 'iconType']) == 'BOOKMARK_BORDER' ||
          at(toggle, ['toggledIcon', 'iconType']) == 'BOOKMARK';
    });
  }

  static List<Song> parseWatchQueue(JsonMap root, {bool preferVideo = false}) {
    final replacements = <String, JsonMap>{};
    final dropped = <String>{};
    for (final wrapper in collect(root, 'playlistPanelVideoWrapperRenderer')) {
      final halves = collect(wrapper, 'playlistPanelVideoRenderer').toList();
      if (halves.isEmpty) continue;
      final wanted = halves
          .where((r) => (_videoFlag(r) ?? false) == preferVideo)
          .firstOrNull;
      final firstId = string(halves.first['videoId']);
      if (wanted != null && firstId != null) {
        replacements[firstId] = wanted;
        dropped.addAll(
          halves.skip(1).map((r) => string(r['videoId'])).whereType<String>(),
        );
      }
    }
    final songs = <String, Song>{};
    for (final found in collect(root, 'playlistPanelVideoRenderer')) {
      final foundId = string(found['videoId']);
      if (foundId == null || dropped.contains(foundId)) continue;
      final r = replacements[foundId] ?? found;
      final id = string(r['videoId']);
      final title = text(r['title']);
      if (id == null || title.isEmpty) continue;
      final byline = text(r['longBylineText'])
          .ifEmpty(text(r['shortBylineText']));
      final credits = _artists(r['longBylineText']);
      final album = _album(r['longBylineText']);
      songs[id] = Song(
        videoId: id,
        title: title,
        artist: byline.split('•').first.trim(),
        thumbnailUrl: thumbnail(r['thumbnail']),
        durationText: string(text(r['lengthText'])),
        artists: credits,
        artistId: credits.firstOrNull?.browseId,
        albumId: album.id,
        albumName: album.name,
        isVideo: _videoFlag(r) ?? byline.contains('views'),
        isExplicit: _explicit(r),
        feedbackToken: _feedback(r),
      );
    }
    return songs.values.toList();
  }

  static List<ShelfItem> libraryItems(JsonMap root) {
    final out = <String, ShelfItem>{};
    for (final row in collect(root, 'musicTwoRowItemRenderer')) {
      final item = twoRow(row);
      if (item?.browseId != null) out[item!.browseId!] = item;
    }
    for (final row in collect(root, 'musicResponsiveListItemRenderer')) {
      final item = responsiveBrowse(row);
      if (item != null) {
        out[item.browseId] = ShelfItem(
          title: item.title,
          subtitle: item.subtitle,
          thumbnailUrl: item.thumbnailUrl,
          browseId: item.browseId,
          type: item.type,
        );
      }
    }
    return out.values.toList();
  }

  static List<String> suggestions(JsonMap root) =>
      collect(root, 'searchSuggestionRenderer')
          .map(
            (r) =>
                string(
                  at(r, ['navigationEndpoint', 'searchEndpoint', 'query']),
                ) ??
                text(r['suggestion']),
          )
          .where((s) => s.isNotEmpty)
          .toSet()
          .toList();

  static Account? account(JsonMap root) {
    final r = collect(root, 'activeAccountHeaderRenderer').firstOrNull;
    if (r == null) return null;
    final name = text(r['accountName']);
    return name.isEmpty
        ? null
        : Account(
            name: name,
            email: text(r['email']),
            thumbnailUrl: thumbnail(r['accountPhoto']),
          );
  }
}

extension _IfEmpty on String {
  String ifEmpty(String fallback) => isEmpty ? fallback : this;
}
