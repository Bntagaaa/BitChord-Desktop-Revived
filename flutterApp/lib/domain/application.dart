import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import '../data/innertube.dart';
import '../data/lyrics.dart';
import '../data/local_music.dart';
import '../data/local_artwork.dart';
import '../data/renderer_parser.dart';
import '../data/settings.dart';
import '../data/stream_resolver.dart';
import '../platform/audio.dart';
import 'music.dart';
import 'queue.dart';
import 'lyrics_session.dart';

enum Destination {
  home('Home'),
  newMusic('New'),
  search('Search'),
  library('Library'),
  history('History'),
  downloads('Downloads'),
  localMusic('Local Music'),
  spotify('Spotify'),
  settings('Settings');

  const Destination(this.label);
  final String label;
}

enum RepeatMode { off, all, one }

class MusicApplication extends ChangeNotifier {
  MusicApplication({
    required this.settings,
    required this.api,
    required this.audio,
    LocalArtworkCache? localArtwork,
    LyricsRepository? lyrics,
  }) : resolver = StreamResolver(api) {
    lyricsRepository = lyrics ?? LyricsRepository();
    lyricsSession = LyricsSession(lyricsRepository.fetch)
      ..addListener(_lyricsChanged);
    localScanner = LocalMusicScanner(artworkCache: localArtwork);
    _subscriptions.addAll([
      audio.position.listen((value) => position.value = value),
      audio.duration.listen((value) {
        if (_audioGeneration == _playRequest) {
          duration.value = value;
          if (!_loadingAudio) lyricsSession.reportDuration(value);
        }
      }),
      audio.playing.listen((value) {
        playing = value;
        notifyListeners();
      }),
      audio.completed.listen((value) {
        if (value) unawaited(next(automatic: true));
      }),
      audio.errors.listen((value) {
        error = value;
        notifyListeners();
      }),
    ]);
  }
  final Settings settings;
  final InnerTube api;
  final AudioOutput audio;
  final StreamResolver resolver;
  late final LocalMusicScanner localScanner;
  late final LyricsRepository lyricsRepository;
  late final LyricsSession lyricsSession;
  final RendererParser parser = RendererParser();
  final QueueTimeline queue = QueueTimeline();
  final position = ValueNotifier(Duration.zero);
  final duration = ValueNotifier(Duration.zero);
  final List<StreamSubscription<dynamic>> _subscriptions = [];
  Destination destination = Destination.home;
  CatalogPage page = const CatalogPage();
  final List<Song> localSongs = [];
  final List<Song> history = [];
  List<LyricLine> get lyrics => lyricsSession.lines;
  void _lyricsChanged() {
    if (!_closed) notifyListeners();
  }

  bool busy = false;
  bool loadingTrack = false;
  bool playing = false;
  bool shuffle = false;
  RepeatMode repeat = RepeatMode.off;
  String? error;
  String query = '';
  SearchFilter filter = SearchFilter.songs;
  int _request = 0;
  int _playRequest = 0;
  int? _audioGeneration;
  bool _loadingAudio = false;
  bool _closed = false;
  String? browseId;
  Song? get song => queue.current;

  Future<void> navigate(Destination target) async {
    destination = target;
    browseId = null;
    query = '';
    error = null;
    _request++;
    busy = false;
    page = const CatalogPage();
    notifyListeners();
    switch (target) {
      case Destination.home:
        await _load(() => api.browse('FEmusic_home'), plainShelves: true);
      case Destination.newMusic:
        await _load(
          () => api.browse('FEmusic_new_releases'),
          plainShelves: true,
        );
      case Destination.library:
        if (api.signedIn) {
          await _load(() => api.browse('FEmusic_liked_playlists'));
        }
      case Destination.history:
        if (api.signedIn) {
          await _load(() => api.browse('FEmusic_history'));
        }
      case Destination.localMusic:
        await scanLocal(settings.get('local_music_folder', ''));
      case Destination.search:
      case Destination.downloads:
      case Destination.spotify:
      case Destination.settings:
        break;
    }
  }

  Future<void> search(String value) async {
    destination = Destination.search;
    query = value;
    if (value.trim().isEmpty) {
      _request++;
      busy = false;
      page = const CatalogPage();
      notifyListeners();
      return;
    }
    await _load(() => api.search(value, filter));
  }

  Future<void> browse(CatalogItem item) async {
    if (item.song != null) {
      await play(item.song!, origin: page.title);
      return;
    }
    final id = item.browseId;
    if (id == null) return;
    browseId = id;
    await _load(() => api.browse(id, params: item.params));
  }

  Future<bool> _load(
    Future<JsonMap> Function() fetch, {
    bool plainShelves = false,
  }) async {
    final generation = ++_request;
    busy = true;
    error = null;
    notifyListeners();
    try {
      final response = await fetch();
      if (generation != _request || _closed) return false;
      page = parser.page(response, plainShelves: plainShelves);
      return true;
    } on Exception catch (failure) {
      if (generation == _request && !_closed) error = failure.toString();
      return false;
    } finally {
      if (generation == _request && !_closed) {
        busy = false;
        notifyListeners();
      }
    }
  }

  Future<void> more() async {
    final token = page.continuation;
    if (token == null || busy) return;
    final previous = page;
    final loaded = await _load(() => api.continuation(token));
    if (loaded) {
      page = CatalogPage(
        title: previous.title,
        artwork: previous.artwork,
        subtitle: previous.subtitle,
        songs: [...previous.songs, ...page.songs],
        items: [...previous.items, ...page.items],
        shelves: [...previous.shelves, ...page.shelves],
        continuation: page.continuation,
      );
      notifyListeners();
    }
  }

  Future<void> play(
    Song track, {
    List<Song>? context,
    int index = 0,
    String origin = 'Search',
  }) async {
    if (context == null) {
      queue.oneOff(track, origin);
    } else {
      queue.context(context, index, origin);
    }
    if (shuffle) queue.shuffleStartingOrder();
    await _openCurrent();
  }

  Future<void> _openCurrent() async {
    final selected = song;
    if (selected == null) return;
    final generation = ++_playRequest;
    loadingTrack = true;
    error = null;
    _audioGeneration = null;
    duration.value = Duration.zero;
    position.value = Duration.zero;
    lyricsSession.select(
      selected,
      enabled: settings.get('synced_lyrics', true),
    );
    notifyListeners();
    try {
      final stream = await resolver.resolve(selected);
      if (generation != _playRequest || _closed) return;
      _audioGeneration = generation;
      _loadingAudio = true;
      try {
        await audio.open(stream.uri, headers: stream.headers);
      } finally {
        if (generation == _playRequest) _loadingAudio = false;
      }
      if (generation != _playRequest || _closed) return;
      lyricsSession.reportDuration(duration.value);
      history.removeWhere((track) => track.id == selected.id);
      history.insert(0, selected);
      if (history.length > 50) history.removeLast();
    } on Exception catch (failure) {
      if (generation == _playRequest && !_closed) error = failure.toString();
    } finally {
      if (generation == _playRequest && !_closed) {
        loadingTrack = false;
        notifyListeners();
      }
    }
  }

  Future<void> next({bool automatic = false}) async {
    if (queue.entries.isEmpty) return;
    if (!automatic || repeat != RepeatMode.one) {
      final target = queue.index + 1;
      if (target >= queue.entries.length && repeat != RepeatMode.all) return;
      queue.index = target % queue.entries.length;
    }
    await _openCurrent();
  }

  Future<void> previous() async {
    if (position.value > const Duration(seconds: 10)) {
      await audio.seek(Duration.zero);
      return;
    }
    if (queue.index > 0) {
      queue.index--;
      await _openCurrent();
    }
  }

  void toggleShuffle() {
    shuffle = !shuffle;
    queue.setShuffle(shuffle);
    notifyListeners();
  }

  void cycleRepeat() {
    repeat = RepeatMode.values[(repeat.index + 1) % RepeatMode.values.length];
    notifyListeners();
  }

  Future<void> jumpTo(int index) async {
    if (index < 0 || index >= queue.entries.length) return;
    queue.jump(index);
    await _openCurrent();
  }

  Future<void> scanLocal(String path) async {
    final generation = ++_request;
    busy = true;
    error = null;
    notifyListeners();
    try {
      if (path.isNotEmpty) await settings.set('local_music_folder', path);
      final home =
          Platform.environment[Platform.isWindows ? 'USERPROFILE' : 'HOME'];
      final roots = path.isNotEmpty
          ? [path]
          : [
              if (home != null) '$home/Music',
              if (home != null) '$home/Downloads',
            ];
      final songs = await localScanner.scan(
        roots,
        filterNonMusic: settings.get('filter_non_music_audio', false),
        cancelled: () => generation != _request || _closed,
      );
      if (generation == _request && !_closed) {
        localSongs
          ..clear()
          ..addAll(songs);
      }
    } on Exception catch (failure) {
      if (generation == _request && !_closed) error = failure.toString();
    } finally {
      if (generation == _request && !_closed) {
        busy = false;
        notifyListeners();
      }
    }
  }

  Future<void> close() async {
    _closed = true;
    _request++;
    _playRequest++;
    for (final subscription in _subscriptions) {
      await subscription.cancel();
    }
    resolver.close();
    api.close();
    lyricsRepository.close();
    lyricsSession.dispose();
    await audio.close();
    position.dispose();
    duration.dispose();
    dispose();
  }
}
