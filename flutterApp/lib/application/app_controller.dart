import 'dart:async';
import 'dart:io';

import 'package:file_selector/file_selector.dart';
import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:url_launcher/url_launcher.dart';
import 'package:window_manager/window_manager.dart';

import '../data/auth_store.dart';
import '../data/addon_registry.dart';
import '../data/innertube_client.dart';
import '../data/local_store.dart';
import '../data/lyrics_service.dart';
import '../data/music_repository.dart';
import '../data/spotify_importer.dart';
import '../domain/models.dart';
import '../playback/playback.dart';
import 'download_service.dart';
import 'local_music_service.dart';

enum AppRoute {
  home,
  search,
  explore,
  library,
  localMusic,
  downloads,
  spotify,
  liked,
  history,
  settings,
  detail,
}

/// Application orchestration. Position ticks stay in PlaybackController so the
/// entire library does not rebuild for every audio clock update.
class AppController extends ChangeNotifier {
  AppController({
    required this.store,
    required this.client,
    required this.repository,
    required this.playback,
    required this.authStore,
    required this.localMusic,
    required this.downloadService,
    AddonRegistry? addons,
  }) : addons = addons ?? AddonRegistry() {
    playback.addListener(_onPlaybackChanged);
    downloadService.addListener(_onDownloadChanged);
  }

  final LocalStore store;
  final InnertubeClient client;
  final MusicRepository repository;
  final PlaybackController playback;
  final AuthStore authStore;
  final LocalMusicService localMusic;
  final DownloadService downloadService;
  final AddonRegistry addons;
  final Map<String, LikeStatus> _ratingOverrides = {};
  AppRoute route = AppRoute.home;
  HomeFeed homeFeed = const HomeFeed();
  HomeFeed exploreFeed = const HomeFeed();
  SearchPage searchPage = const SearchPage();
  DetailPage? detailPage;
  LibraryPage libraryPage = const LibraryPage();
  Account? account;
  String query = '';
  SearchFilter searchFilter = SearchFilter.all;
  String? error;
  String? notice;
  bool busy = false;
  bool lyricsLoading = false;
  bool lyricsTransforming = false;
  String? lyricsError;
  LyricsDocument? lyrics;
  List<Song> localSongs = const [];
  bool localMusicLoading = false;
  bool importingSpotify = false;
  int spotifyProgress = 0;
  int spotifyTotal = 0;
  List<String> spotifyImportWarnings = const [];
  Future<void> Function()? onOpenPiP;
  final List<_NavigationEntry> _back = [];
  int _navigationGeneration = 0;
  int _authIntent = 0;
  int _searchGeneration = 0;
  int _lyricsGeneration = 0;
  int _scanGeneration = 0;
  bool _closed = false;
  bool _cancelSpotify = false;
  String? _trackIdentity;
  String? _lastPlaybackError;
  String? _localPlaylistId;
  Timer? _sleepTimer;
  Timer? _scanDebounce;
  StreamSubscription<FileSystemEvent>? _watcher;
  DateTime? sleepDeadline;

  bool get signedIn => client.isSignedIn;
  bool get canGoBack => _back.isNotEmpty;
  Map<String, dynamic> get settings => store.settings;
  T setting<T>(String key, T fallback) => store.getSetting(key, fallback);
  List<Song> get history => store.history;
  List<Song> get downloads => store.downloads;
  List<Song> get likedSongs {
    final byId = <String, Song>{};
    for (final song in [...store.likedSongs, ...libraryPage.likedSongs]) {
      if (_ratingOverrides[song.videoId] == LikeStatus.indifferent ||
          _ratingOverrides[song.videoId] == LikeStatus.dislike ||
          store.dislikedIds.contains(song.videoId)) {
        continue;
      }
      byId.putIfAbsent(song.videoId, () => song);
    }
    return List.unmodifiable(byId.values);
  }

  List<LocalPlaylist> get localPlaylists => store.playlists;
  List<DownloadTask> get downloadTasks => downloadService.tasks;
  bool isLiked(Song song) => _ratingOverrides.containsKey(song.videoId)
      ? _ratingOverrides[song.videoId] == LikeStatus.like
      : store.likedIds.contains(song.videoId) ||
            libraryPage.likedSongs.any((item) => item.videoId == song.videoId);
  bool isDisliked(Song song) => store.dislikedIds.contains(song.videoId);
  List<AddonConfig> get addonSources => addons.sources;

  Future<void> restoreLocalSession() async {
    final savedPlayback = setting<Map<String, dynamic>>(
      'playback_snapshot',
      const {},
    );
    if (savedPlayback.isNotEmpty) {
      await playback.restore(PlaybackSnapshot.fromJson(savedPlayback));
    } else if (store.queue.isNotEmpty) {
      await playback.restore(
        PlaybackSnapshot(
          queue: store.queue,
          currentIndex: store.queueIndex,
          position: store.queuePosition,
          volume: setting<num>('volume', 100).toDouble(),
          shuffle: setting('shuffle', false),
          repeat: PlaybackRepeat.values.firstWhere(
            (value) => value.name == setting('repeat', 'off'),
            orElse: () => PlaybackRepeat.off,
          ),
        ),
      );
    }
    await _applyPlaybackPreferences(preserveSnapshot: savedPlayback.isNotEmpty);
    _notify();
  }

  Future<void> initialize() async {
    final intent = _authIntent;
    try {
      await addons.restore();
    } on Object {
      notice =
          'Saved music sources could not be restored from the system credential vault.';
    }
    try {
      if (intent != _authIntent) return;
      await authStore.restore(client);
      if (intent != _authIntent) return;
      if (signedIn) {
        final restored = await repository.account();
        if (intent != _authIntent) return;
        account = restored;
      }
    } on Object {
      // A locked/unavailable credential vault must not block local playback.
      notice =
          'Your account could not be restored. Local music is available; connect your account again to sync.';
    }
    _notify();
    await loadHome();
  }

  Future<void> _applyPlaybackPreferences({
    bool preserveSnapshot = false,
  }) async {
    if (!preserveSnapshot) {
      await playback.setVolume(setting<num>('volume', 100).toDouble());
      await playback.setAutoplay(setting('autoplay', playback.autoplay));
      await playback.setCrossfade(
        Duration(
          milliseconds: setting<num>(
            'crossfade_duration_ms',
            playback.crossfade.inMilliseconds,
          ).toInt(),
        ),
      );
      final gains = setting<List<dynamic>>('equalizer_gains', const []);
      if (gains.length == 7) {
        await playback.setEqualizer(
          gains.map((value) => (value as num).toDouble()).toList(),
        );
      }
    }
    if (setting('content_prefer_music_only', false)) {
      await playback.setVideoEnabled(false);
    }
    client.language = setting('content_language', 'en');
    client.region = setting('content_country', 'US');
    final audioResolver = playback.resolver;
    if (audioResolver is YoutubeStreamResolver) {
      audioResolver.maxAudioKbps = setting('audio_quality', 'LOSSLESS') == 'LOW'
          ? 64
          : null;
    }
    final downloadResolver = downloadService.resolver;
    if (downloadResolver is YoutubeStreamResolver) {
      downloadResolver.maxAudioKbps =
          setting('download_quality', 'LOSSLESS') == 'LOW' ? 64 : null;
    }
  }

  void navigate(AppRoute destination) {
    if (route == destination && destination != AppRoute.detail) return;
    _remember();
    route = destination;
    _navigationGeneration++;
    error = null;
    busy = false;
    _notify();
    switch (destination) {
      case AppRoute.home:
        if (homeFeed.shelves.isEmpty) unawaited(loadHome());
      case AppRoute.explore:
        if (exploreFeed.shelves.isEmpty) unawaited(_loadExplore());
      case AppRoute.library:
      case AppRoute.liked:
        if (signedIn) unawaited(_loadLibrary());
      case AppRoute.localMusic:
        unawaited(reloadLocalMusic());
      default:
        break;
    }
  }

  void _remember() {
    _back.add(
      _NavigationEntry(
        route,
        query,
        detailPage?.browseId,
        detailPage?.title,
        _localPlaylistId,
      ),
    );
    // Keep identifiers, not full copies of artwork/catalogue responses.
    if (_back.length > 30) _back.removeAt(0);
  }

  void goBack() {
    if (_back.isEmpty) return;
    final previous = _back.removeLast();
    route = previous.route;
    query = previous.query;
    _navigationGeneration++;
    error = null;
    busy = false;
    if (previous.route == AppRoute.detail && previous.localPlaylistId != null) {
      final matches = localPlaylists.where(
        (item) => item.id == previous.localPlaylistId,
      );
      if (matches.isNotEmpty) _showLocalPlaylist(matches.first);
    } else if (previous.route == AppRoute.detail && previous.browseId != null) {
      unawaited(_loadDetail(previous.browseId!, previous.title ?? ''));
    }
    _notify();
  }

  Future<void> loadHome() async {
    final session = client.sessionGeneration;
    await _pageRequest(() async {
      final response = await repository.home();
      if (!_closed && session == client.sessionGeneration) homeFeed = response;
    });
  }

  Future<void> _loadExplore() async {
    final session = client.sessionGeneration;
    await _pageRequest(() async {
      final response = await repository.explore();
      if (!_closed && session == client.sessionGeneration) {
        exploreFeed = response;
      }
    });
  }

  Future<void> _loadLibrary() async {
    final session = client.sessionGeneration;
    await _pageRequest(() async {
      final response = await repository.library();
      if (!_closed && session == client.sessionGeneration) {
        libraryPage = response;
      }
    });
  }

  Future<void> search(
    String value, {
    SearchFilter filter = SearchFilter.all,
  }) async {
    query = value.trim();
    searchFilter = filter;
    final generation = ++_searchGeneration;
    final session = client.sessionGeneration;
    if (route != AppRoute.search) {
      _remember();
      route = AppRoute.search;
      _navigationGeneration++;
    }
    error = null;
    busy = query.isNotEmpty;
    if (query.isEmpty) {
      searchPage = const SearchPage();
      _notify();
      return;
    }
    _notify();
    try {
      final result = await repository.search(query, filter: filter);
      if (generation != _searchGeneration ||
          _closed ||
          session != client.sessionGeneration) {
        return;
      }
      searchPage = result;
    } on Object catch (failure) {
      if (generation == _searchGeneration) error = _message(failure);
    } finally {
      if (generation == _searchGeneration) {
        busy = false;
        _notify();
      }
    }
  }

  Future<SearchPage> quickSearch(String value) async {
    final session = client.sessionGeneration;
    final result = await repository.search(value.trim(), typeahead: true);
    return session == client.sessionGeneration ? result : const SearchPage();
  }

  Future<void> openBrowse(BrowseItem item) async {
    _remember();
    route = AppRoute.detail;
    _localPlaylistId = null;
    _navigationGeneration++;
    detailPage = DetailPage(
      browseId: item.browseId,
      title: item.title,
      subtitle: item.subtitle,
      thumbnailUrl: item.thumbnailUrl,
      type: item.type,
    );
    _notify();
    await _loadDetail(item.browseId, item.title);
  }

  Future<void> _loadDetail(String id, String title) async {
    final generation = _navigationGeneration;
    final session = client.sessionGeneration;
    await _pageRequest(() async {
      final result = await repository.browse(id);
      if (generation == _navigationGeneration &&
          session == client.sessionGeneration) {
        detailPage = result;
      }
    });
  }

  Future<void> loadMoreHome() async {
    final token = homeFeed.continuation;
    if (token == null || busy) return;
    final session = client.sessionGeneration;
    await _pageRequest(() async {
      final more = await repository.home(continuation: token);
      if (_closed || session != client.sessionGeneration) return;
      homeFeed = HomeFeed(
        shelves: [...homeFeed.shelves, ...more.shelves],
        continuation: more.continuation,
      );
    });
  }

  Future<void> loadMoreSearch() async {
    final token = searchPage.continuation;
    if (token == null || busy) return;
    final generation = _searchGeneration;
    await _pageRequest(() async {
      final more = await repository.search(
        query,
        filter: searchFilter,
        continuation: token,
      );
      if (generation == _searchGeneration) {
        searchPage = SearchPage(
          results: [...searchPage.results, ...more.results],
          continuation: more.continuation,
        );
      }
    });
  }

  Future<void> loadMoreDetail() async {
    final page = detailPage;
    if (page == null || page.continuation == null || busy) return;
    final generation = _navigationGeneration;
    await _pageRequest(() async {
      final more = await repository.browse(
        page.browseId,
        continuation: page.continuation,
      );
      if (generation == _navigationGeneration) {
        detailPage = DetailPage(
          browseId: page.browseId,
          title: page.title,
          subtitle: page.subtitle,
          thumbnailUrl: page.thumbnailUrl,
          type: page.type,
          songs: [...page.songs, ...more.songs],
          shelves: page.shelves,
          continuation: more.continuation,
          description: page.description,
          playlistId: page.playlistId,
          isOwned: page.isOwned,
        );
      }
    });
  }

  Future<void> refreshCurrent() async {
    repository.clearCache();
    switch (route) {
      case AppRoute.home:
        await loadHome();
      case AppRoute.explore:
        await _loadExplore();
      case AppRoute.search:
        await search(query, filter: searchFilter);
      case AppRoute.library:
      case AppRoute.liked:
        if (signedIn) await _loadLibrary();
      case AppRoute.localMusic:
        await reloadLocalMusic();
      case AppRoute.detail:
        if (_localPlaylistId == null && detailPage != null) {
          await _loadDetail(detailPage!.browseId, detailPage!.title);
        }
      default:
        _notify();
    }
  }

  Future<void> playSong(
    Song song, {
    List<Song>? context,
    String? source,
  }) async {
    final songs = context ?? [song];
    var index = songs.indexWhere((entry) => identical(entry, song));
    if (index < 0) {
      index = songs.indexWhere((entry) => entry.videoId == song.videoId);
    }
    await playSongs(songs, index: index < 0 ? 0 : index, source: source);
  }

  Future<void> playSongs(List<Song> songs, {int index = 0, String? source}) =>
      _action(
        () => playback.playSongs(
          songs,
          index: index,
          source: source ?? _routeTitle,
        ),
      );

  String get _routeTitle => switch (route) {
    AppRoute.home => 'Home',
    AppRoute.search => 'Search',
    AppRoute.explore => 'New',
    AppRoute.detail => detailPage?.title ?? 'Library',
    AppRoute.liked => 'Liked Music',
    AppRoute.localMusic => 'Local Music',
    AppRoute.downloads => 'Downloads',
    AppRoute.history => 'History',
    _ => 'Library',
  };

  Future<void> startRadio(Song song) => _action(() async {
    final songs = await repository.radioForSong(song);
    if (songs.isEmpty) {
      throw const ServiceException(
        'No radio queue is available for this track.',
      );
    }
    await playback.playSongs(songs, source: '${song.title} Radio');
  });

  Future<void> toggleLike(Song song) =>
      _rate(song, isLiked(song) ? LikeStatus.indifferent : LikeStatus.like);
  Future<void> toggleDislike(Song song) => _rate(
    song,
    isDisliked(song) ? LikeStatus.indifferent : LikeStatus.dislike,
  );

  Future<void> _rate(Song song, LikeStatus rating) => _action(() async {
    _ratingOverrides[song.videoId] = rating;
    await store.setRating(song, rating);
    _notify();
    if (signedIn && song.isYouTube) {
      try {
        await repository.rate(song.videoId, rating);
      } on Object {
        notice =
            'Saved locally. Your YouTube Music account could not be updated.';
        _notify();
      }
    }
  });

  Future<void> createPlaylist(String title) => _action(() async {
    final playlist = await store.createPlaylist(title.trim());
    openPlaylist(playlist);
  });

  Future<List<BrowseItem>> cloudPlaylists() async {
    if (!signedIn) return const [];
    final session = client.sessionGeneration;
    final candidates = await repository.userPlaylists();
    final owned = <BrowseItem>[];
    // Saved playlists also include followed collections. Verify write access
    // using the actual detail response, in bounded batches, before offering them.
    for (var offset = 0; offset < candidates.length; offset += 4) {
      final batch = candidates.skip(offset).take(4).toList();
      final pages = await Future.wait(
        batch.map((item) => repository.browse(item.browseId)),
      );
      if (session != client.sessionGeneration) {
        throw const ServiceException(
          'Your account changed. Reopen the playlist chooser.',
        );
      }
      for (var i = 0; i < batch.length; i++) {
        if (pages[i].isOwned) owned.add(batch[i]);
      }
    }
    return owned;
  }

  Future<void> createCloudPlaylist(String title) => _action(() async {
    final id = await repository.createPlaylist(title);
    await openBrowse(
      BrowseItem(
        browseId: id.startsWith('VL') ? id : 'VL$id',
        title: title.trim(),
        type: BrowseType.playlist,
      ),
    );
    libraryPage = await repository.library();
    _notify();
  });

  Future<void> renameCloudPlaylist(String id, String title) =>
      _action(() async {
        await repository.renamePlaylist(id, title);
        await _refreshCloudPlaylist(id);
      });

  Future<void> addToCloudPlaylist(String id, Song song) => _action(() async {
    if (song.isLocal || song.videoId.contains(':')) {
      throw const ServiceException(
        'Only YouTube Music tracks can be added to an online playlist.',
      );
    }
    await repository.addToPlaylist(id, [song.videoId]);
    await _refreshCloudPlaylist(id);
    notice = 'Added to your YouTube Music playlist.';
    _notify();
  });

  Future<void> removeFromCloudPlaylist(String id, Song song) =>
      _action(() async {
        await repository.removeFromPlaylist(id, [song]);
        await _refreshCloudPlaylist(id);
      });

  Future<void> deleteCloudPlaylist(String id) => _action(() async {
    await repository.deletePlaylist(id);
    if (_isCurrentCloudPlaylist(id)) {
      route = AppRoute.library;
      detailPage = null;
      _navigationGeneration++;
    }
    libraryPage = await repository.library();
    _notify();
  });

  bool _isCurrentCloudPlaylist(String id) =>
      _localPlaylistId == null &&
      detailPage != null &&
      (detailPage!.playlistId == id ||
          detailPage!.browseId == id ||
          detailPage!.browseId == 'VL$id');

  Future<void> _refreshCloudPlaylist(String id) async {
    if (_isCurrentCloudPlaylist(id)) {
      final browseId = detailPage!.browseId;
      await _loadDetail(browseId, detailPage!.title);
    }
    libraryPage = await repository.library();
    _notify();
  }

  void openPlaylist(LocalPlaylist playlist) {
    _remember();
    route = AppRoute.detail;
    _navigationGeneration++;
    _showLocalPlaylist(playlist);
    _notify();
  }

  void _showLocalPlaylist(LocalPlaylist playlist) {
    _localPlaylistId = playlist.id;
    detailPage = DetailPage(
      browseId: 'local:${playlist.id}',
      title: playlist.title,
      subtitle: '${playlist.songs.length} songs',
      type: BrowseType.playlist,
      thumbnailUrl: playlist.songs.firstOrNull?.thumbnailUrl,
      songs: playlist.songs,
      playlistId: playlist.id,
      isOwned: true,
    );
  }

  Future<void> renamePlaylist(String id, String title) =>
      _editPlaylist(id, (playlist) => playlist.copyWith(title: title.trim()));
  Future<void> pinPlaylist(String id, bool pinned) =>
      _editPlaylist(id, (playlist) => playlist.copyWith(pinned: pinned));
  Future<void> addToPlaylist(String id, Song song) => _editPlaylist(
    id,
    (playlist) => playlist.copyWith(songs: [...playlist.songs, song]),
  );
  Future<void> removeFromPlaylist(String id, Song song) =>
      _editPlaylist(id, (playlist) {
        final songs = [...playlist.songs];
        final index = songs.indexWhere(
          (item) =>
              identical(item, song) ||
              (song.setVideoId != null
                  ? item.setVideoId == song.setVideoId
                  : item.videoId == song.videoId),
        );
        if (index >= 0) songs.removeAt(index);
        return playlist.copyWith(songs: songs);
      });

  Future<void> _editPlaylist(
    String id,
    LocalPlaylist Function(LocalPlaylist) edit,
  ) => _action(() async {
    final playlist = localPlaylists.firstWhere((item) => item.id == id);
    final updated = edit(playlist);
    if (updated.title.isEmpty) {
      throw const ServiceException('A playlist needs a name.');
    }
    await store.updatePlaylist(updated);
    if (_localPlaylistId == id) _showLocalPlaylist(updated);
    _notify();
  });

  Future<void> deletePlaylist(String id) => _action(() async {
    await store.deletePlaylist(id);
    if (_localPlaylistId == id) {
      _localPlaylistId = null;
      route = AppRoute.library;
      detailPage = null;
    }
    _notify();
  });

  Future<void> importLocalMusic() => _action(() async {
    final folder = await getDirectoryPath(
      confirmButtonText: 'Select music folder',
    );
    if (folder == null) return;
    await store.setSetting('local_music_folder', folder);
    await reloadLocalMusic();
  });

  Future<void> reloadLocalMusic() async {
    final generation = ++_scanGeneration;
    localMusicLoading = true;
    _notify();
    try {
      final chosen = setting('local_music_folder', '');
      final userHome =
          Platform.environment['USERPROFILE'] ??
          Platform.environment['HOME'] ??
          '';
      final roots = chosen.isNotEmpty
          ? [chosen]
          : [p.join(userHome, 'Music'), p.join(userHome, 'Downloads')];
      final songs = await localMusic.scan(
        roots,
        downloads: downloads,
        filterNonMusic: setting('filter_non_music_audio', false),
      );
      if (generation == _scanGeneration) localSongs = songs;
      if (chosen.isNotEmpty) await _watchLocalMusic(chosen);
    } on Object catch (failure) {
      error = _message(failure);
    } finally {
      if (generation == _scanGeneration) {
        localMusicLoading = false;
        _notify();
      }
    }
  }

  Future<void> _watchLocalMusic(String folder) async {
    await _watcher?.cancel();
    try {
      _watcher = Directory(folder)
          .watch(recursive: true)
          .listen(
            (event) {
              _scanDebounce?.cancel();
              _scanDebounce = Timer(const Duration(milliseconds: 750), () {
                if (!_closed) unawaited(reloadLocalMusic());
              });
            },
            onError: (Object _) {
              /* Manual refresh remains available. */
            },
          );
    } on FileSystemException {
      /* Some mounted filesystems do not provide watchers. */
    }
  }

  Future<void> download(Song song) => _action(() async {
    final saved = await downloadService.download(song);
    await store.saveDownloads([
      saved,
      ...downloads.where((item) => item.videoId != saved.videoId),
    ]);
    _notify();
  });

  void cancelDownload(String videoId) => downloadService.cancel(videoId);

  Future<void> removeDownload(
    Song song, {
    bool deleteFile = false,
  }) => _action(() async {
    if (deleteFile && song.localPath != null) {
      final file = File(song.localPath!);
      // Only files managed by this rewrite can be deleted by its Downloads UI.
      if (!p.isWithin(
        downloadService.directory.absolute.path,
        file.absolute.path,
      )) {
        throw const ServiceException(
          'This file is outside the app download folder. Remove it with your file manager.',
        );
      }
      if (await file.exists()) await file.delete();
    }
    await store.saveDownloads(
      downloads.where((item) => item.videoId != song.videoId).toList(),
    );
    _notify();
  });

  Future<void> importSpotify(String url) async {
    if (importingSpotify) return;
    importingSpotify = true;
    _cancelSpotify = false;
    spotifyProgress = 0;
    spotifyTotal = 0;
    spotifyImportWarnings = const [];
    error = null;
    _notify();
    final importer = SpotifyImporter(repository: repository);
    try {
      final result = await importer.importPlaylist(
        url,
        onProgress: (done, total) {
          spotifyProgress = done;
          spotifyTotal = total;
          _notify();
        },
        isCancelled: () => _closed || _cancelSpotify,
      );
      spotifyImportWarnings = result.warnings;
      if (result.songs.isEmpty) {
        throw const ServiceException(
          'No matching YouTube Music tracks were found.',
        );
      }
      final playlist = await store.createPlaylist(
        result.title,
        songs: result.songs,
      );
      notice =
          'Imported ${result.songs.length} of ${result.totalTracks} tracks.';
      openPlaylist(playlist);
    } on Object catch (failure) {
      error = _message(failure);
    } finally {
      importer.close();
      importingSpotify = false;
      _notify();
    }
  }

  void cancelSpotifyImport() {
    _cancelSpotify = true;
  }

  Future<void> signInWithCookie(String cookie) => _action(() async {
    final intent = ++_authIntent;
    _resetAccountViews();
    try {
      final connected = await authStore.signIn(cookie, client);
      if (intent == _authIntent) account = connected;
    } finally {
      if (intent == _authIntent) _resetAccountViews();
    }
    if (intent == _authIntent) await loadHome();
  });

  Future<void> signOut() => _action(() async {
    final intent = ++_authIntent;
    account = null;
    _resetAccountViews();
    try {
      await authStore.signOut(client);
    } finally {
      if (intent == _authIntent) _resetAccountViews();
    }
    if (intent == _authIntent) await loadHome();
  });

  void _resetAccountViews() {
    _navigationGeneration++;
    _searchGeneration++;
    repository.clearCache();
    _invalidateStreams();
    homeFeed = const HomeFeed();
    exploreFeed = const HomeFeed();
    searchPage = const SearchPage();
    libraryPage = const LibraryPage();
    detailPage = null;
    _localPlaylistId = null;
    _back.clear();
    query = '';
    route = AppRoute.home;
    error = null;
    busy = false;
    _notify();
  }

  Future<void> setSetting(String key, dynamic value) => _action(() async {
    await store.setSetting(key, value);
    switch (key) {
      case 'volume':
        await playback.setVolume((value as num).toDouble());
      case 'autoplay':
        await playback.setAutoplay(value as bool);
      case 'crossfade_duration_ms':
        await playback.setCrossfade(
          Duration(milliseconds: (value as num).toInt()),
        );
      case 'equalizer_gains':
        await playback.setEqualizer(
          (value as List).map((n) => (n as num).toDouble()).toList(),
        );
      case 'content_language':
        client.language = value as String;
        repository.clearCache();
      case 'content_country':
        client.region = value as String;
        repository.clearCache();
      case 'audio_quality':
        final resolver = playback.resolver;
        if (resolver is YoutubeStreamResolver) {
          resolver.maxAudioKbps = value == 'LOW' ? 64 : null;
          resolver.clearCache();
        }
      case 'download_quality':
        final resolver = downloadService.resolver;
        if (resolver is YoutubeStreamResolver) {
          resolver.maxAudioKbps = value == 'LOW' ? 64 : null;
          resolver.clearCache();
        }
      case 'source_jiosaavn_enabled':
        _invalidateStreams();
      case 'synced_lyrics':
      case 'lyrics_providers':
        if (playback.currentSong != null) {
          unawaited(_loadLyrics(playback.currentSong!));
        }
      case 'content_prefer_music_only':
        if (value == true) await playback.setVideoEnabled(false);
      case 'filter_non_music_audio':
        unawaited(reloadLocalMusic());
      default:
        break;
    }
    _notify();
  });

  Future<void> importLegacyProfile() => _action(() async {
    final file = await openFile(
      acceptedTypeGroups: const [
        XTypeGroup(label: 'Java preferences XML', extensions: ['xml']),
      ],
    );
    if (file == null) return;
    final result = await store.importLegacy(File(file.path));
    await _applyPlaybackPreferences();
    notice =
        'Imported ${result.playlists} playlists, ${result.history} history entries, and ${result.settings} settings. The original profile is unchanged.';
    _notify();
  });

  void setSleepTimer(Duration? duration) {
    _sleepTimer?.cancel();
    sleepDeadline = duration == null ? null : DateTime.now().add(duration);
    if (duration != null) {
      _sleepTimer = Timer(duration, () {
        unawaited(playback.pause());
        sleepDeadline = null;
        _notify();
      });
    }
    _notify();
  }

  Future<void> openPiP() => _action(() async {
    if (onOpenPiP == null) {
      throw const ServiceException(
        'Picture-in-Picture is not available in this window.',
      );
    }
    await onOpenPiP!();
  });

  Future<void> toggleFullscreen() => _action(() async {
    await windowManager.setFullScreen(!await windowManager.isFullScreen());
  });

  Future<void> openExternal(String value) => _action(() async {
    final uri = Uri.tryParse(value);
    if (uri == null || !const {'https', 'http'}.contains(uri.scheme)) {
      throw const ServiceException('This link cannot be opened.');
    }
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      throw const ServiceException(
        'The system browser could not open this link.',
      );
    }
  });

  Future<void> addAddon(String url) => _action(() async {
    await addons.addSource(url);
    _invalidateStreams();
    _notify();
  });

  Future<void> removeAddon(String id) => _action(() async {
    await addons.removeSource(id);
    _invalidateStreams();
    _notify();
  });

  Future<void> toggleAddon(String id, bool enabled) => _action(() async {
    await addons.setEnabled(id, enabled);
    _invalidateStreams();
    _notify();
  });

  Future<void> reorderAddons(int from, int to) => _action(() async {
    await addons.reorder(from, to);
    _invalidateStreams();
    _notify();
  });

  void _invalidateStreams() {
    if (playback.resolver is YoutubeStreamResolver) {
      (playback.resolver as YoutubeStreamResolver).clearCache();
    }
    if (downloadService.resolver is YoutubeStreamResolver) {
      (downloadService.resolver as YoutubeStreamResolver).clearCache();
    }
  }

  Future<void> searchSources(String value) async {
    query = value.trim();
    final generation = ++_searchGeneration;
    final session = client.sessionGeneration;
    if (route != AppRoute.search) {
      _remember();
      route = AppRoute.search;
      _navigationGeneration++;
    }
    busy = true;
    error = null;
    _notify();
    try {
      final songs = await addons.search(
        query,
        quality: setting('audio_quality', 'LOSSLESS'),
      );
      if (generation != _searchGeneration ||
          _closed ||
          session != client.sessionGeneration) {
        return;
      }
      searchPage = SearchPage(
        results: songs.map((song) => SearchResult.track(song)).toList(),
      );
      if (addons.lastFailures.isNotEmpty) {
        notice =
            'Some sources could not be reached. Results from available sources are shown.';
      }
    } on Object catch (failure) {
      if (generation == _searchGeneration) error = _message(failure);
    } finally {
      if (generation == _searchGeneration) {
        busy = false;
        _notify();
      }
    }
  }

  void clearError() {
    error = null;
    notice = null;
    _notify();
  }

  void _onPlaybackChanged() {
    final song = playback.currentSong;
    final identity = song?.queueEntryId ?? song?.videoId;
    if (identity != _trackIdentity) {
      _trackIdentity = identity;
      if (song != null) {
        unawaited(
          store
              .recordHistory(song)
              .then(
                (_) => _notify(),
                onError: (Object failure) {
                  error = _message(failure);
                  _notify();
                },
              ),
        );
        unawaited(_loadLyrics(song));
      } else {
        lyrics = null;
        _lyricsGeneration++;
      }
      _notify();
    }
    if (playback.error != null && playback.error != _lastPlaybackError) {
      _lastPlaybackError = playback.error;
      error = playback.error;
      _notify();
    }
  }

  Future<void> _loadLyrics(Song song) async {
    final generation = ++_lyricsGeneration;
    lyrics = null;
    lyricsError = null;
    lyricsLoading = setting('synced_lyrics', true);
    _notify();
    if (!lyricsLoading) return;
    try {
      final providers = settings.containsKey('lyrics_providers')
          ? setting<List<dynamic>>('lyrics_providers', const []).cast<String>()
          : null;
      final embedded = song.isLocal
          ? await localMusic.embeddedLyrics(song)
          : null;
      final document = embedded?.trim().isNotEmpty == true
          ? LyricsService.parseProvider(embedded!, source: 'Embedded')
          : await repository.lyrics(song, providers: providers);
      if (generation == _lyricsGeneration) lyrics = document;
    } on Object catch (failure) {
      if (generation == _lyricsGeneration) lyricsError = _message(failure);
    } finally {
      if (generation == _lyricsGeneration) {
        lyricsLoading = false;
        _notify();
      }
    }
  }

  Future<void> translateLyrics({bool romanize = false}) async {
    final document = lyrics;
    if (document == null || document.lines.isEmpty || lyricsTransforming) {
      return;
    }
    final generation = _lyricsGeneration;
    lyricsTransforming = true;
    lyricsError = null;
    _notify();
    try {
      final configured = setting('translation_language', '').trim();
      final language = configured.isEmpty
          ? setting('content_language', 'en')
          : configured;
      final transformed = await repository.translateLyrics(
        document,
        language,
        romanize: romanize,
      );
      if (generation == _lyricsGeneration && !_closed) lyrics = transformed;
    } on Object catch (failure) {
      if (generation == _lyricsGeneration) lyricsError = _message(failure);
    } finally {
      lyricsTransforming = false;
      _notify();
    }
  }

  Future<void> resetLyrics() async {
    final song = playback.currentSong;
    if (song != null) await _loadLyrics(song);
  }

  void _onDownloadChanged() => _notify();

  Future<void> _pageRequest(Future<void> Function() work) async {
    final generation = _navigationGeneration;
    busy = true;
    error = null;
    _notify();
    try {
      await work();
    } on Object catch (failure) {
      if (generation == _navigationGeneration) error = _message(failure);
    } finally {
      if (generation == _navigationGeneration) {
        busy = false;
        _notify();
      }
    }
  }

  Future<void> _action(Future<void> Function() work) async {
    try {
      await work();
    } on Object catch (failure) {
      error = _message(failure);
      _notify();
    }
  }

  String _message(Object failure) {
    if (failure is ServiceException) return failure.message;
    if (failure is SocketException || failure is TimeoutException) {
      return 'The service could not be reached. Check your connection and try again.';
    }
    if (failure is FileSystemException) {
      return 'The file could not be read or saved. Check the folder and its permissions.';
    }
    // Avoid exposing cookies, signed streaming URLs, or local secret values.
    return 'The operation could not be completed. ${failure.runtimeType}';
  }

  void reportNotice(String message) {
    notice = message;
    _notify();
  }

  void _notify() {
    if (!_closed) notifyListeners();
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _cancelSpotify = true;
    _lyricsGeneration++;
    _searchGeneration++;
    _scanGeneration++;
    _sleepTimer?.cancel();
    _scanDebounce?.cancel();
    await _watcher?.cancel();
    playback.removeListener(_onPlaybackChanged);
    downloadService.removeListener(_onDownloadChanged);
    await downloadService.close();
    await playback.close();
    await store.flush();
    addons.close();
    repository.close();
  }
}

class _NavigationEntry {
  const _NavigationEntry(
    this.route,
    this.query,
    this.browseId,
    this.title,
    this.localPlaylistId,
  );
  final AppRoute route;
  final String query;
  final String? browseId, title, localPlaylistId;
}
