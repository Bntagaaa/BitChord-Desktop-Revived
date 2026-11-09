import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import '../data/innertube.dart';
import '../data/lyrics.dart';
import '../data/renderer_parser.dart';
import '../data/settings.dart';
import '../data/stream_resolver.dart';
import '../platform/audio.dart';
import 'music.dart';
import 'queue.dart';

enum Destination {
  home('Home'), newMusic('New'), search('Search'), library('Library'),
  history('History'), downloads('Downloads'), localMusic('Local Music'),
  spotify('Spotify'), settings('Settings');
  const Destination(this.label);
  final String label;
}
enum RepeatMode { off, all, one }

class MusicApplication extends ChangeNotifier {
  MusicApplication({required this.settings, required this.api, required this.audio})
      : resolver = StreamResolver(api) {
    _subscriptions.addAll([
      audio.position.listen((value) => position.value = value),
      audio.duration.listen((value) { duration.value = value; }),
      audio.playing.listen((value) { playing = value; notifyListeners(); }),
      audio.completed.listen((value) { if (value) unawaited(next()); }),
      audio.errors.listen((value) { error = value; notifyListeners(); }),
    ]);
  }
  final Settings settings;
  final InnerTube api;
  final AudioOutput audio;
  final StreamResolver resolver;
  final LyricsRepository lyricsRepository = LyricsRepository();
  final RendererParser parser = RendererParser();
  final QueueTimeline queue = QueueTimeline();
  final position = ValueNotifier(Duration.zero);
  final duration = ValueNotifier(Duration.zero);
  final List<StreamSubscription<dynamic>> _subscriptions = [];
  Destination destination = Destination.home;
  CatalogPage page = const CatalogPage();
  final List<Song> localSongs = [];
  final List<Song> history = [];
  List<LyricLine> lyrics = [];
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
  bool _closed = false;
  String? browseId;
  Song? get song => queue.current;

  Future<void> navigate(Destination target) async {
    destination = target; browseId = null; query = ''; error = null;
    _request++;
    page = const CatalogPage();
    notifyListeners();
    switch (target) {
      case Destination.home: await _load(() => api.browse('FEmusic_home'));
      case Destination.newMusic: await _load(() => api.browse('FEmusic_new_releases'));
      case Destination.library:
        if (api.signedIn) { await _load(() => api.browse('FEmusic_liked_playlists')); }
      case Destination.history:
        if (api.signedIn) { await _load(() => api.browse('FEmusic_history')); }
      case Destination.search:
      case Destination.downloads:
      case Destination.localMusic:
      case Destination.spotify:
      case Destination.settings:
        break;
    }
  }
  Future<void> search(String value) async {
    destination = Destination.search; query = value;
    if (value.trim().isEmpty) { _request++; page = const CatalogPage(); notifyListeners(); return; }
    await _load(() => api.search(value, filter));
  }
  Future<void> browse(CatalogItem item) async {
    if (item.song != null) { await play(item.song!, origin: page.title); return; }
    final id = item.browseId; if (id == null) return;
    browseId = id;
    await _load(() => api.browse(id, params: item.params));
  }
  Future<void> _load(Future<JsonMap> Function() fetch) async {
    final generation = ++_request;
    busy = true; error = null; notifyListeners();
    try {
      final response = await fetch();
      if (generation == _request && !_closed) page = parser.page(response);
    } on Exception catch (failure) {
      if (generation == _request && !_closed) error = failure.toString();
    } finally {
      if (generation == _request && !_closed) { busy = false; notifyListeners(); }
    }
  }
  Future<void> more() async {
    final token = page.continuation; if (token == null || busy) return;
    final previous = page;
    await _load(() => api.continuation(token));
    if (error == null) {
      page = CatalogPage(title: previous.title, artwork: previous.artwork,
        subtitle: previous.subtitle, songs: [...previous.songs, ...page.songs],
        shelves: [...previous.shelves, ...page.shelves], continuation: page.continuation);
      notifyListeners();
    }
  }
  Future<void> play(Song track, {List<Song>? context, int index = 0, String origin = 'Search'}) async {
    if (context == null) { queue.oneOff(track, origin); }
    else { queue.context(context, index, origin); }
    await _openCurrent();
  }
  Future<void> _openCurrent() async {
    final selected = song; if (selected == null) return;
    final generation = ++_playRequest;
    loadingTrack = true; error = null; lyrics = []; notifyListeners();
    try {
      final stream = await resolver.resolve(selected);
      if (generation != _playRequest || _closed) return;
      await audio.open(stream.uri, headers: stream.headers);
      if (generation != _playRequest || _closed) return;
      history.removeWhere((track) => track.id == selected.id);
      history.insert(0, selected); if (history.length > 50) history.removeLast();
      unawaited(_loadLyrics(selected, generation));
    } on Exception catch (failure) {
      if (generation == _playRequest && !_closed) error = failure.toString();
    } finally {
      if (generation == _playRequest && !_closed) { loadingTrack = false; notifyListeners(); }
    }
  }
  Future<void> _loadLyrics(Song selected, int generation) async {
    try {
      final found = await lyricsRepository.fetch(selected, selected.duration ?? duration.value);
      if (generation == _playRequest && !_closed) { lyrics = found; notifyListeners(); }
    } on Exception { /* Absence of provider lyrics never invents text or stops playback. */ }
  }
  Future<void> next() async {
    if (queue.entries.isEmpty) return;
    if (repeat != RepeatMode.one) {
      final target = queue.index + 1;
      if (target >= queue.entries.length && repeat == RepeatMode.off) return;
      queue.index = target % queue.entries.length;
    }
    await _openCurrent();
  }
  Future<void> previous() async {
    if (position.value > const Duration(seconds: 3)) { await audio.seek(Duration.zero); return; }
    if (queue.index > 0) { queue.index--; await _openCurrent(); }
  }
  void toggleShuffle() {
    shuffle = !shuffle; queue.setShuffle(shuffle); notifyListeners();
  }
  void cycleRepeat() {
    repeat = RepeatMode.values[(repeat.index + 1) % RepeatMode.values.length]; notifyListeners();
  }
  Future<void> jumpTo(int index) async {
    if (index < 0 || index >= queue.entries.length) return;
    queue.index = index; await _openCurrent();
  }
  Future<void> scanLocal(String path) async {
    final generation = ++_request;
    busy = true; error = null; notifyListeners();
    try {
      final songs = <Song>[];
      await for (final item in Directory(path).list(recursive: true, followLinks: false)) {
        if (item is! File || !RegExp(r'\.(mp3|flac|wav|m4a|ogg|opus|aac)$', caseSensitive: false).hasMatch(item.path)) continue;
        final file = item.uri.pathSegments.last;
        songs.add(Song(id: item.uri.toString(), title: file.replaceFirst(RegExp(r'\.[^.]+$'), ''),
          artist: 'Local Music', localUri: item.uri));
      }
      songs.sort((a, b) => a.title.compareTo(b.title));
      if (generation == _request && !_closed) { localSongs..clear()..addAll(songs); }
    } on Exception catch (failure) { error = failure.toString(); }
    finally { if (generation == _request && !_closed) { busy = false; notifyListeners(); } }
  }
  Future<void> close() async {
    _closed = true; _request++; _playRequest++;
    for (final subscription in _subscriptions) { await subscription.cancel(); }
    resolver.close(); api.close(); lyricsRepository.close(); await audio.close();
    position.dispose(); duration.dispose(); dispose();
  }
}
