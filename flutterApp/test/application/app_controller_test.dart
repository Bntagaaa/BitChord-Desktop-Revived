import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import 'package:bitchord_desktop/application/app_controller.dart';
import 'package:bitchord_desktop/application/download_service.dart';
import 'package:bitchord_desktop/application/local_music_service.dart';
import 'package:bitchord_desktop/data/addon_registry.dart';
import 'package:bitchord_desktop/data/auth_store.dart';
import 'package:bitchord_desktop/data/innertube_client.dart';
import 'package:bitchord_desktop/data/local_store.dart';
import 'package:bitchord_desktop/data/music_repository.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/playback/playback.dart';

const _savedSong = Song(
  videoId: 'saved-track',
  title: 'Saved track',
  artist: 'Artist',
  durationText: '3:00',
);
const _chosenSong = Song(
  videoId: 'chosen-track',
  title: 'User choice',
  artist: 'Artist',
  durationText: '3:00',
);
const _oldHome = HomeFeed(shelves: [HomeShelf(title: 'Old account home')]);
const _newHome = HomeFeed(shelves: [HomeShelf(title: 'Current account home')]);

void main() {
  late Directory directory;
  late LocalStore store;
  late InnertubeClient client;
  late _Repository repository;
  late _Addons addons;
  late PlaybackController playback;
  late AppController app;
  late List<_Backend> backends;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp('bitchord-app-test-');
    store = await LocalStore.open(directory: directory);
    await store.setSettings({'synced_lyrics': false, 'autoplay': false});
    client = InnertubeClient(
      httpClient: MockClient((http.Request request) async {
        throw StateError('Unexpected network request: ${request.url.path}');
      }),
    );
    repository = _Repository(client: client);
    addons = _Addons();
    backends = [];
    playback = PlaybackController(
      resolver: _Resolver(),
      backendFactory: ({bool video = false}) {
        final backend = _Backend();
        backends.add(backend);
        return backend;
      },
      onPersist: (snapshot) =>
          store.setSetting('playback_snapshot', snapshot.toJson()),
    );
    app = AppController(
      store: store,
      client: client,
      repository: repository,
      playback: playback,
      authStore: _Auth(),
      addons: addons,
      localMusic: LocalMusicService(Directory('${directory.path}/artwork')),
      downloadService: DownloadService(
        resolver: playback.resolver,
        directory: Directory('${directory.path}/downloads'),
      ),
    );
  });

  tearDown(() async {
    await app.close();
    playback.dispose();
    app.dispose();
    await directory.delete(recursive: true);
  });

  test('saved session volume takes precedence over legacy volume', () async {
    await store.setSettings({
      'volume': 88,
      'playback_snapshot': const PlaybackSnapshot(
        queue: [_savedSong],
        position: Duration(seconds: 43),
        volume: 23,
        autoplay: false,
        videoEnabled: false,
      ).toJson(),
    });

    await app.restoreLocalSession();

    expect(playback.volume, 23);
    expect(playback.currentSong?.videoId, _savedSong.videoId);
    expect(playback.position, const Duration(seconds: 43));
    expect(playback.videoEnabled, isFalse);
    expect(playback.playing, isFalse);
    expect(backends, isEmpty, reason: 'Restoration must not start a decoder.');
  });

  test(
    'legacy queue still uses legacy volume when no snapshot exists',
    () async {
      await store.setSetting('volume', 46);
      await store.saveQueue([
        _savedSong,
      ], position: const Duration(seconds: 19));

      await app.restoreLocalSession();

      expect(playback.volume, 46);
      expect(playback.currentSong?.videoId, _savedSong.videoId);
      expect(playback.position, const Duration(seconds: 19));
      expect(playback.playing, isFalse);
      expect(backends, isEmpty);
    },
  );

  test('late Home response cannot restore the signed-out account', () async {
    client.setCookie('SAPISID=old-account');
    final oldResponse = Completer<HomeFeed>();
    repository.onHome = () =>
        client.isSignedIn ? oldResponse.future : Future.value(_newHome);
    final pending = app.loadHome();

    await app.signOut();
    expect(app.signedIn, isFalse);
    expect(app.homeFeed.shelves.single.title, 'Current account home');

    oldResponse.complete(_oldHome);
    await pending;

    expect(app.homeFeed.shelves.single.title, 'Current account home');
  });

  test(
    'late Library response cannot replace a newly signed-in library',
    () async {
      client.setCookie('SAPISID=old-account');
      final oldResponse = Completer<LibraryPage>();
      repository.onLibrary = () => client.cookie == 'SAPISID=old-account'
          ? oldResponse.future
          : Future.value(const LibraryPage(likedSongs: [_chosenSong]));
      // Refresh is an awaitable user action that launches the normal Library load.
      app.route = AppRoute.library;
      final pending = app.refreshCurrent();

      await app.signInWithCookie('new-account');
      app.route = AppRoute.library;
      await app.refreshCurrent();
      expect(app.likedSongs.map((song) => song.videoId), [_chosenSong.videoId]);

      oldResponse.complete(const LibraryPage(likedSongs: [_savedSong]));
      await pending;

      expect(app.likedSongs.map((song) => song.videoId), [_chosenSong.videoId]);
      expect(app.isLiked(_savedSong), isFalse);
    },
  );

  test(
    'late detail response cannot restore old-account edit ownership',
    () async {
      client.setCookie('SAPISID=old-account');
      final oldResponse = Completer<DetailPage>();
      repository.onBrowse = (_) => oldResponse.future;
      final pending = app.openBrowse(
        const BrowseItem(
          browseId: 'VLold-private-playlist',
          title: 'Old private playlist',
          type: BrowseType.playlist,
        ),
      );

      await app.signOut();
      oldResponse.complete(
        const DetailPage(
          browseId: 'VLold-private-playlist',
          playlistId: 'old-private-playlist',
          title: 'Old private playlist',
          type: BrowseType.playlist,
          songs: [_savedSong],
          isOwned: true,
        ),
      );
      await pending;

      expect(app.detailPage, isNull);
    },
  );

  test('late service initialization preserves a newer playback choice', () async {
    await store.setSetting(
      'playback_snapshot',
      const PlaybackSnapshot(
        queue: [_savedSong],
        position: Duration(seconds: 43),
        volume: 23,
        autoplay: false,
        videoEnabled: false,
      ).toJson(),
    );
    addons.restoreGate = Completer<void>();

    // This is the startup boundary used before runApp: local restore completes
    // without waiting for a vault, account lookup, or network catalogue request.
    await app.restoreLocalSession();
    expect(playback.currentSong?.videoId, _savedSong.videoId);
    expect(playback.playing, isFalse);
    expect(backends, isEmpty);

    final startup = app.initialize();
    await addons.started.future;
    await app.playSong(_chosenSong);
    await playback.seek(const Duration(seconds: 17));
    expect(playback.playing, isTrue);

    addons.restoreGate!.complete();
    await startup;

    expect(app.homeFeed.shelves.single.title, 'Current account home');
    expect(app.error, isNull);
    expect(playback.currentSong?.videoId, _chosenSong.videoId);
    expect(playback.queue.map((song) => song.videoId), [_chosenSong.videoId]);
    expect(playback.position, const Duration(seconds: 17));
    expect(playback.playing, isTrue);
    expect(backends, hasLength(1));
    expect(backends.single.closed, isFalse);
  });
}

/// Only service responses are controlled; the application, queue, preferences
/// and persistence exercised above are production implementations.
class _Repository extends MusicRepository {
  _Repository({required super.client});
  Future<HomeFeed> Function()? onHome;
  Future<LibraryPage> Function()? onLibrary;
  Future<DetailPage> Function(String)? onBrowse;

  @override
  Future<HomeFeed> home({bool refresh = false, String? continuation}) =>
      onHome?.call() ?? Future.value(_newHome);

  @override
  Future<LibraryPage> library() =>
      onLibrary?.call() ?? Future.value(const LibraryPage());

  @override
  Future<DetailPage> browse(
    String browseId, {
    String? continuation,
    String? params,
    bool refresh = false,
  }) =>
      onBrowse?.call(browseId) ??
      Future.value(DetailPage(browseId: browseId, title: browseId));

  @override
  Future<Account> account() async => const Account(name: 'Restored account');
}

class _Auth extends AuthStore {
  @override
  Future<void> restore(InnertubeClient client) async {}

  @override
  Future<Account> signIn(String value, InnertubeClient client) async {
    client.setCookie('SAPISID=$value');
    return Account(name: value);
  }

  @override
  Future<void> signOut(InnertubeClient client) async => client.setCookie(null);
}

class _Addons extends AddonRegistry {
  Completer<void>? restoreGate;
  final started = Completer<void>();

  @override
  Future<void> restore() async {
    if (!started.isCompleted) started.complete();
    await restoreGate?.future;
  }
}

class _Resolver implements StreamResolver {
  @override
  Future<ResolvedStream> resolve(
    Song song, {
    bool video = false,
    int maxVideoHeight = 1080,
    bool refresh = false,
    bool forceRefresh = false,
  }) async => ResolvedStream(uri: 'https://media.invalid/${song.videoId}');

  @override
  void invalidate(Song song) {}

  @override
  Future<void> close() async {}
}

/// Native decoder boundary: no libmpv, output device, or actual media is opened.
class _Backend implements AudioBackend {
  final events = StreamController<BackendState>.broadcast(sync: true);
  BackendState _state = const BackendState(duration: Duration(minutes: 3));
  bool closed = false;
  @override
  BackendState get state => _state;
  @override
  Stream<BackendState> get changes => events.stream;
  @override
  VideoController? get videoController => null;

  void _emit(BackendState value) {
    _state = value;
    if (!closed) events.add(value);
  }

  @override
  Future<void> open(
    ResolvedStream stream, {
    Duration start = Duration.zero,
  }) async => _emit(state.copyWith(position: start));
  @override
  Future<void> play() async => _emit(state.copyWith(playing: true));
  @override
  Future<void> pause() async => _emit(state.copyWith(playing: false));
  @override
  Future<void> seek(Duration position) async =>
      _emit(state.copyWith(position: position));
  @override
  Future<void> setVolume(double volume) async {}
  @override
  Future<void> setRate(double rate) async {}
  @override
  Future<void> setAudioDevice(AudioDevice device) async {}
  @override
  Future<void> setFilter(String filter) async {}
  @override
  Future<void> close() async {
    if (closed) return;
    closed = true;
    await events.close();
  }
}
