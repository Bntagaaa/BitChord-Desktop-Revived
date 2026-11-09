import 'dart:async';

import '../domain/models.dart';
import 'innertube_client.dart';
import 'innertube_parser.dart';
import 'lyrics_service.dart';
import 'song_matcher.dart';

/// The application-facing catalogue; all displayed results come from live sources.
class MusicRepository {
  MusicRepository({required this.client, LyricsService? lyricsService})
    : _lyrics = lyricsService ?? LyricsService(innertube: client);
  final InnertubeClient client;
  final LyricsService _lyrics;
  final _cache = <String, _CacheEntry>{};
  final _inflight = <String, Future<Object>>{};
  int _cacheGeneration = 0;
  static const _cacheLimit = 32;
  static const _cacheTtl = Duration(minutes: 3);
  static const likedMusic = 'VLLM';

  Future<T> _memo<T extends Object>(
    String key,
    Future<T> Function() request,
  ) async {
    final generation = _cacheGeneration;
    final scoped = '${client.sessionGeneration}:$generation:$key';
    final cached = _cache.remove(scoped);
    if (cached != null &&
        DateTime.now().difference(cached.created) < _cacheTtl) {
      _cache[scoped] = cached;
      return cached.value as T;
    }
    if (_inflight[scoped] != null) return await _inflight[scoped]! as T;
    final pending = request();
    _inflight[scoped] = pending;
    try {
      final result = await pending;
      if (generation == _cacheGeneration) {
        _cache[scoped] = _CacheEntry(result);
        while (_cache.length > _cacheLimit) {
          _cache.remove(_cache.keys.first);
        }
      }
      return result;
    } finally {
      // The same future has just been awaited above; removal does not launch it.
      unawaited(_inflight.remove(scoped));
    }
  }

  void clearCache() {
    _cacheGeneration++;
    _cache.clear();
    _lyrics.clearCache();
  }

  Future<HomeFeed> home({bool refresh = false, String? continuation}) {
    if (continuation != null) return moreHome(continuation);
    if (refresh) clearCache();
    return _memo(
      'home',
      () async =>
          InnertubeParser.parseHome(await client.browse('FEmusic_home')),
    );
  }

  Future<HomeFeed> moreHome(String continuation) async =>
      InnertubeParser.parseHome(await client.browseContinuation(continuation));
  Future<HomeFeed> explore({bool refresh = false}) {
    if (refresh) clearCache();
    return _memo(
      'explore',
      () async =>
          InnertubeParser.parseHome(await client.browse('FEmusic_explore')),
    );
  }

  Future<HomeFeed> newReleases() => _memo(
    'new-releases',
    () async =>
        InnertubeParser.parseHome(await client.browse('FEmusic_new_releases')),
  );
  Future<HomeFeed> browseShelves(String id, {String? params}) => _memo(
    'shelves:$id:$params',
    () async =>
        InnertubeParser.parseHome(await client.browse(id, params: params)),
  );

  Future<SearchPage> search(
    String query, {
    SearchFilter filter = SearchFilter.all,
    String? continuation,
    bool typeahead = false,
  }) {
    final trimmed = query.trim();
    if (trimmed.isEmpty && continuation == null) {
      return Future.value(const SearchPage());
    }
    return _memo(
      'search:$trimmed:${filter.name}:$continuation:$typeahead',
      () async {
        final raw = await client.post('search', {
          if (continuation == null)
            'query': trimmed
          else
            'continuation': continuation,
          if (filter.params != null) 'params': filter.params,
        }, authenticated: !typeahead);
        return InnertubeParser.parseSearch(raw, filter: filter);
      },
    );
  }

  Future<List<String>> searchSuggestions(String query) async {
    if (query.trim().isEmpty) return const [];
    return InnertubeParser.suggestions(
      await client.post('music/get_search_suggestions', {
        'input': query.trim(),
      }, authenticated: false),
    );
  }

  Future<DetailPage> browse(
    String browseId, {
    String? continuation,
    String? params,
    bool refresh = false,
  }) {
    if (refresh) clearCache();
    return _memo('browse:$browseId:$continuation:$params', () async {
      final root = continuation == null
          ? await client.browse(browseId, params: params)
          : await client.browseContinuation(continuation);
      final page = InnertubeParser.parseDetail(root, browseId);
      if (continuation != null ||
          page.type != BrowseType.album ||
          page.playlistId == null) {
        return page;
      }
      // Albums may show only a preview; their play button names the full listing.
      final playlistId = page.playlistId!;
      final full = InnertubeParser.parseDetail(
        await client.browse('VL$playlistId'),
        'VL$playlistId',
      );
      return DetailPage(
        browseId: browseId,
        title: page.title,
        subtitle: page.subtitle,
        thumbnailUrl: page.thumbnailUrl,
        type: page.type,
        songs: full.songs
            .map(
              (song) => song.copyWith(
                albumId: browseId,
                albumName: page.title,
                thumbnailUrl: song.thumbnailUrl ?? page.thumbnailUrl,
              ),
            )
            .toList(),
        shelves: page.shelves,
        continuation: full.continuation,
        description: page.description,
        playlistId: playlistId,
        isOwned: false,
      );
    });
  }

  Stream<List<Song>> songPages(
    String browseId, {
    bool Function()? isCancelled,
  }) async* {
    String? next;
    final seen = <String>{};
    do {
      if (isCancelled?.call() == true) return;
      final page = await browse(browseId, continuation: next);
      if (isCancelled?.call() == true) return;
      yield page.songs;
      next = page.continuation;
      if (next != null && !seen.add(next)) {
        throw const ServiceException(
          'YouTube Music repeated a page token; loading stopped to avoid a loop.',
        );
      }
    } while (next != null);
  }

  Future<List<Song>> allSongs(
    String browseId, {
    bool Function()? isCancelled,
  }) async {
    final result = <Song>[];
    await for (final page in songPages(browseId, isCancelled: isCancelled)) {
      result.addAll(page);
    }
    return result;
  }

  Future<List<Song>> radio(String videoId, {bool preferVideo = false}) => _memo(
    'radio:$videoId:$preferVideo',
    () async => InnertubeParser.parseWatchQueue(
      await client.post('next', {
        'videoId': videoId,
        'playlistId': 'RDAMVM$videoId',
        'isAudioOnly': !preferVideo,
      }),
      preferVideo: preferVideo,
    ),
  );

  /// A local/addon recording needs a catalogue match before starting YouTube
  /// radio; opaque source IDs are never submitted as YouTube video IDs.
  Future<List<Song>> radioForSong(Song song) async {
    if (song.isYouTube) return radio(song.videoId);
    final page = await search(
      '${song.title} ${song.artist}',
      filter: SearchFilter.songs,
    );
    final matched = SongMatcher.best(
      page.songs.where((s) => s.isYouTube),
      title: song.title,
      artist: song.artist,
      duration: song.duration,
      album: song.albumName,
    );
    if (matched == null) {
      throw const ServiceException(
        'No matching YouTube Music recording was found to start radio.',
      );
    }
    return radio(matched.videoId);
  }

  Future<List<Song>> watchQueue(
    String playlistId, {
    String? videoId,
    String? params,
    bool preferVideo = false,
  }) async => InnertubeParser.parseWatchQueue(
    await client.post('next', {
      'playlistId': playlistId,
      'isAudioOnly': !preferVideo,
      'videoId': ?videoId,
      'params': ?params,
    }),
    preferVideo: preferVideo,
  );
  Future<Song?> videoVersion(Song song) async {
    if (!song.isYouTube) return null;
    if (song.isVideo) return song;
    final candidates = await radio(song.videoId, preferVideo: true);
    return candidates.firstOrNull?.isVideo == true ? candidates.first : null;
  }

  Future<Song> audioVersion(Song song) async {
    if (!song.isVideo || !song.isYouTube) return song;
    final candidates = await radio(song.videoId);
    return candidates.firstOrNull?.isVideo == false
        ? candidates.first.copyWith(isVideoOrigin: true)
        : song;
  }

  Future<Account> account() async {
    client.requireSession();
    final account = InnertubeParser.account(
      await client.post('account/account_menu', {}),
    );
    if (account == null) {
      throw const ServiceException('The signed-in account could not be read.');
    }
    return account;
  }

  Future<List<AccountChannel>> accountChannels() async {
    client.requireSession();
    final raw = await client.post('account/accounts_list', {});
    final channels = <String, AccountChannel>{};
    for (final item in InnertubeParser.collect(raw, 'accountItem')) {
      final name = InnertubeParser.text(item['accountName']);
      final pageId = InnertubeParser.findString(item, 'pageId');
      final dataSyncId = InnertubeClient.normalizeDataSyncId(
        InnertubeParser.findString(item, 'datasyncIdToken'),
      );
      if (name.isEmpty || (pageId == null && dataSyncId == null)) continue;
      final channel = AccountChannel(
        name: name,
        subtitle: InnertubeParser.text(item['channelHandle']),
        thumbnailUrl: InnertubeParser.thumbnail(item['accountPhoto']),
        pageId: pageId,
        dataSyncId: dataSyncId,
        activeOnWeb: item['isSelected'] == true,
      );
      channels[channel.key] = channel;
    }
    return channels.values.toList();
  }

  Future<LibraryPage> library() async {
    client.requireSession();
    const feeds = {
      'Playlists': 'FEmusic_liked_playlists',
      'Albums': 'FEmusic_liked_albums',
      'Artists': 'FEmusic_library_corpus_track_artists',
      'Subscriptions': 'FEmusic_library_corpus_artists',
      'Podcasts': 'FEmusic_library_non_music_audio_list',
    };
    final warnings = <String>[];
    // Attach failure handlers before starting the parallel shelf requests.
    // A failed liked-songs request must not become an unhandled async error.
    Future<DetailPage> songFeed(String id, String label) async {
      try {
        return await browse(id);
      } on Exception {
        warnings.add('$label could not be loaded.');
        return DetailPage(browseId: id, title: label);
      }
    }

    final likedFuture = songFeed(likedMusic, 'Liked songs');
    final addedFuture = songFeed('FEmusic_liked_videos', 'Library songs');
    final shelves = await Future.wait(
      feeds.entries.map((entry) async {
        try {
          return HomeShelf(
            title: entry.key,
            items: await _libraryItemsPaged(entry.value),
            moreBrowseId: entry.value,
          );
        } on Exception {
          warnings.add('${entry.key} could not be loaded.');
          return HomeShelf(title: entry.key);
        }
      }),
    );
    final liked = await likedFuture;
    final added = await addedFuture;
    final ids = liked.songs.map((s) => s.videoId).toSet();
    return LibraryPage(
      likedSongs: liked.songs,
      librarySongs: added.songs.where((s) => !ids.contains(s.videoId)).toList(),
      shelves: shelves.where((s) => s.items.isNotEmpty).toList(),
      likedContinuation: liked.continuation,
      warnings: warnings,
    );
  }

  Future<List<Song>> history() async {
    client.requireSession();
    return allSongs('FEmusic_history');
  }

  Future<List<MoodGenreSection>> moodAndGenres() async {
    final raw = await client.browse('FEmusic_moods_and_genres');
    return InnertubeParser.collect(raw, 'gridRenderer')
        .map((grid) {
          final title = InnertubeParser.text(
            InnertubeParser.at(grid, ['header', 'gridHeaderRenderer', 'title']),
          );
          final items =
              InnertubeParser.collect(grid, 'musicNavigationButtonRenderer')
                  .map((button) {
                    final endpoint = InnertubeParser.object(
                      InnertubeParser.at(button, [
                            'clickCommand',
                            'browseEndpoint',
                          ]) ??
                          InnertubeParser.at(button, [
                            'navigationEndpoint',
                            'browseEndpoint',
                          ]),
                    );
                    final id = InnertubeParser.string(endpoint['browseId']);
                    if (id == null) return null;
                    return MoodGenre(
                      title: InnertubeParser.text(button['buttonText']),
                      browseId: id,
                      params: InnertubeParser.string(endpoint['params']),
                      stripeColor: int.tryParse(
                        '${InnertubeParser.at(button, ['solid', 'leftStripeColor'])}',
                      ),
                    );
                  })
                  .whereType<MoodGenre>()
                  .toList();
          return MoodGenreSection(title: title, items: items);
        })
        .where((s) => s.title.isNotEmpty && s.items.isNotEmpty)
        .toList();
  }

  Future<void> rate(String videoId, LikeStatus status) async {
    client.requireSession();
    final endpoint = switch (status) {
      LikeStatus.like => 'like/like',
      LikeStatus.dislike => 'like/dislike',
      LikeStatus.indifferent => 'like/removelike',
    };
    await client.post(endpoint, {
      'target': {'videoId': videoId},
    });
    clearCache();
  }

  Future<void> setSaved(String playlistId, bool saved) async {
    client.requireSession();
    await client.post(saved ? 'like/like' : 'like/removelike', {
      'target': {'playlistId': _rawPlaylist(playlistId)},
    });
    clearCache();
  }

  Future<void> setSubscribed(String channelId, bool subscribed) async {
    client.requireSession();
    await client.post(
      subscribed ? 'subscription/subscribe' : 'subscription/unsubscribe',
      {
        'channelIds': [channelId],
      },
    );
    clearCache();
  }

  Future<void> feedback(String token) async {
    client.requireSession();
    await client.post('feedback', {
      'feedbackTokens': [token],
    });
    clearCache();
  }

  Future<SongMenu> songMenu(String videoId) async {
    final raw = await client.post('next', {
      'videoId': videoId,
      'isAudioOnly': true,
    });
    final row = InnertubeParser.collect(
      raw,
      'playlistPanelVideoRenderer',
    ).where((r) => r['videoId'] == videoId).firstOrNull;
    if (row == null) return const SongMenu();
    final likeValue = InnertubeParser.findString(row, 'likeStatus');
    String? add, remove;
    var inLibrary = false;
    for (final item in InnertubeParser.collect(
      row,
      'toggleMenuServiceItemRenderer',
    )) {
      final icon = InnertubeParser.at(item, ['defaultIcon', 'iconType']);
      if (icon != 'LIBRARY_ADD' && icon != 'LIBRARY_REMOVE') continue;
      final first = InnertubeParser.findString(
        item['defaultServiceEndpoint'],
        'feedbackToken',
      );
      final second = InnertubeParser.findString(
        item['toggledServiceEndpoint'],
        'feedbackToken',
      );
      if (icon == 'LIBRARY_ADD') {
        add = first;
        remove = second;
      } else {
        remove = first;
        add = second;
        inLibrary = true;
      }
    }
    return SongMenu(
      likeStatus: switch (likeValue) {
        'LIKE' => LikeStatus.like,
        'DISLIKE' => LikeStatus.dislike,
        'INDIFFERENT' => LikeStatus.indifferent,
        _ => null,
      },
      inLibrary: inLibrary,
      addToLibraryToken: add,
      removeFromLibraryToken: remove,
    );
  }

  Future<String> createPlaylist(
    String title, {
    PlaylistPrivacy privacy = PlaylistPrivacy.private,
    List<String> videoIds = const [],
    String description = '',
  }) async {
    client.requireSession();
    if (title.trim().isEmpty) throw ArgumentError('A playlist needs a name.');
    final response = await client.post('playlist/create', {
      'title': title.trim(),
      'description': description,
      'privacyStatus': privacy.name.toUpperCase(),
      if (videoIds.isNotEmpty) 'videoIds': videoIds,
    });
    clearCache();
    final id = InnertubeParser.findString(response, 'playlistId');
    if (id == null) {
      throw const ServiceException(
        'Playlist creation returned no identifier. Check your library before retrying.',
      );
    }
    return id;
  }

  Future<JsonMap> _edit(String id, List<JsonMap> actions) async {
    client.requireSession();
    final response = await client.post('browse/edit_playlist', {
      'playlistId': _rawPlaylist(id),
      'actions': actions,
    });
    final status = response['status'];
    if (status != null && status != 'STATUS_SUCCEEDED') {
      throw const ServiceException('YouTube Music refused the playlist edit.');
    }
    clearCache();
    return response;
  }

  Future<Map<String, String>> addToPlaylist(
    String id,
    List<String> videoIds,
  ) async {
    final raw = await _edit(
      id,
      videoIds
          .map(
            (v) => <String, dynamic>{
              'action': 'ACTION_ADD_VIDEO',
              'addedVideoId': v,
            },
          )
          .toList(),
    );
    return {
      for (final row in InnertubeParser.collect(
        raw,
        'playlistEditVideoAddedResultData',
      ))
        if (row['videoId'] is String && row['setVideoId'] is String)
          row['videoId'] as String: row['setVideoId'] as String,
    };
  }

  Future<void> removeFromPlaylist(String id, List<Song> songs) async {
    if (songs.any((s) => s.setVideoId == null)) {
      throw const ServiceException(
        'Reload this playlist before removing an entry.',
      );
    }
    await _edit(
      id,
      songs
          .map(
            (s) => <String, dynamic>{
              'action': 'ACTION_REMOVE_VIDEO',
              'setVideoId': s.setVideoId,
              'removedVideoId': s.videoId,
            },
          )
          .toList(),
    );
  }

  Future<void> movePlaylistEntry(
    String id,
    String setVideoId, {
    String? beforeSetVideoId,
  }) async {
    await _edit(id, [
      {
        'action': 'ACTION_MOVE_VIDEO_BEFORE',
        'setVideoId': setVideoId,
        'movedSetVideoIdSuccessor': ?beforeSetVideoId,
      },
    ]);
  }

  Future<void> renamePlaylist(String id, String title) async {
    if (title.trim().isEmpty) throw ArgumentError('A playlist needs a name.');
    await _edit(id, [
      {'action': 'ACTION_SET_PLAYLIST_NAME', 'playlistName': title.trim()},
    ]);
  }

  Future<void> deletePlaylist(String id) async {
    client.requireSession();
    await client.post('playlist/delete', {'playlistId': _rawPlaylist(id)});
    clearCache();
  }

  Future<List<BrowseItem>> userPlaylists() async {
    client.requireSession();
    final items = await _libraryItemsPaged('FEmusic_liked_playlists');
    return items
        .where((i) {
          if (i.browseId?.startsWith('VL') != true) return false;
          final raw = _rawPlaylist(i.browseId ?? '');
          return raw.isNotEmpty &&
              !['LM', 'SE', 'RD', 'OLAK', 'MPRE'].any(raw.startsWith);
        })
        .map(
          (i) => BrowseItem(
            browseId: i.browseId!,
            title: i.title,
            subtitle: i.subtitle,
            thumbnailUrl: i.thumbnailUrl,
            type: BrowseType.playlist,
          ),
        )
        .toList();
  }

  Future<List<ShelfItem>> _libraryItemsPaged(String id) async {
    final items = <String, ShelfItem>{};
    final seenTokens = <String>{};
    String? token;
    do {
      final response = token == null
          ? await client.browse(id)
          : await client.browseContinuation(token);
      for (final item in InnertubeParser.libraryItems(response)) {
        final key = item.browseId ?? item.videoId;
        if (key != null) items[key] = item;
      }
      token = InnertubeParser.continuation(response);
    } while (token != null && seenTokens.add(token));
    return items.values.toList();
  }

  static String _rawPlaylist(String id) =>
      id.startsWith('VL') ? id.substring(2) : id;

  Future<LyricsDocument?> lyrics(Song song, {List<String>? providers}) =>
      _lyrics.fetch(song, providers: providers);
  Future<LyricsDocument> translateLyrics(
    LyricsDocument document,
    String language, {
    bool romanize = false,
  }) => _lyrics.translate(document, language, romanize: romanize);

  void close() {
    _cache.clear();
    _lyrics.close();
    client.close();
  }
}

class _CacheEntry {
  _CacheEntry(this.value) : created = DateTime.now();
  final Object value;
  final DateTime created;
}
