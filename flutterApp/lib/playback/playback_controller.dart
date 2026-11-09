import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import '../domain/models.dart';
import 'audio_backend.dart';
import 'equalizer.dart';
import 'media_kit_backend.dart';
import 'playback_snapshot.dart';
import 'queue_timeline.dart';
import 'stream_resolver.dart';

export 'playback_snapshot.dart';
export 'stream_resolver.dart';

typedef RadioLoader = Future<List<Song>> Function(Song seed);
typedef PlaybackPersistence = Future<void> Function(PlaybackSnapshot snapshot);

/// Owns the entire playback lifecycle. Widgets borrow its video surface and
/// never construct, replace, or dispose a decoder when navigating between views.
class PlaybackController extends ChangeNotifier {
  PlaybackController({
    StreamResolver? resolver,
    AudioBackendFactory? backendFactory,
    this.radioLoader,
    this.onPersist,
    this.onTrackChanged,
    this.onPlaybackError,
    this.songNormalizer,
  }) : resolver = resolver ?? YoutubeStreamResolver(),
       _ownsResolver = resolver == null,
       _backendFactory =
           backendFactory ??
           (({bool video = false}) => MediaKitBackend(video: video));

  final StreamResolver resolver;
  final bool _ownsResolver;
  final AudioBackendFactory _backendFactory;
  final RadioLoader? radioLoader;
  final PlaybackPersistence? onPersist;
  final FutureOr<void> Function(Song song)? onTrackChanged;
  final void Function(String message)? onPlaybackError;

  /// Resolves a preferred audio version lazily for the active/next entry.
  /// Queue identity, manual priority and playback source survive replacement.
  final Future<Song> Function(Song song)? songNormalizer;

  PlaybackQueue _timeline = PlaybackQueue.empty();
  AudioBackend? _active;
  AudioBackend? _staged;
  AudioBackend? _video;
  String? _stagedEntryId;
  ResolvedStream? _stagedStream;
  ResolvedStream? _stream;
  StreamSubscription<BackendState>? _activeSubscription;
  StreamSubscription<BackendState>? _videoSubscription;
  Timer? _persistTimer;
  Timer? _fadeTimer;
  Timer? _videoSyncTimer;
  Timer? _sleepTimer;
  final Stopwatch _fadeClock = Stopwatch();
  Future<void> _writeChain = Future.value();
  Future<void>? _closeFuture;
  Future<void>? _radioFuture;
  final _opening = <AudioBackend>{};
  final _normalizing = <String, Future<Song>>{};
  Duration _fadeDuration = Duration.zero;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  Duration _furthestPosition = Duration.zero;
  Duration _crossfade = Duration.zero;
  String? _prematureEndEntryId;
  int _prematureEndRetries = 0;
  double _volume = 70;
  double _rate = 1;
  double _fadeProgress = 0;
  bool _playing = false;
  bool _buffering = false;
  bool _loading = false;
  bool _shuffle = false;
  bool _autoplay = true;
  bool _videoEnabled = true;
  bool _mixing = false;
  bool _staging = false;
  bool _radioLoading = false;
  bool _fadeTickBusy = false;
  bool _videoSyncBusy = false;
  bool _closed = false;
  bool _notifierDisposed = false;
  bool _endHandled = false;
  bool _intendedPlaying = false;
  bool _sleepAfterTrack = false;
  PlaybackRepeat _repeat = PlaybackRepeat.off;
  String? _error;
  String? _videoError;
  String? _persistenceError;
  String? _radioSeedId;
  String? _stageFailureEntryId;
  DateTime? _sleepAt;
  DateTime _lastCheckpoint = DateTime.fromMillisecondsSinceEpoch(0);
  int _generation = 0;
  int _stageEpoch = 0;
  int _videoEpoch = 0;
  int _maxVideoHeight = 1080;
  List<String> _originalOrder = [];
  List<double> _equalizer = const [0, 0, 0, 0, 0, 0, 0];
  EqualizerCurve _eqCurve = EqualizerCurve.flat;
  AudioDevice? _audioDevice;
  List<AudioDevice> _audioDevices = const [];

  List<Song> get queue => _timeline.songs;
  int get currentIndex => _timeline.index;
  Song? get currentSong => _timeline.current;
  Duration get position => _position;
  Duration get duration => _duration;
  bool get playing => _playing;
  bool get buffering => _buffering || _loading;
  bool get shuffle => _shuffle;
  bool get autoplay => _autoplay;
  bool get videoEnabled => _videoEnabled;
  bool get mixing => _mixing;
  double get volume => _volume;
  double get rate => _rate;
  Duration get crossfade => _crossfade;
  PlaybackRepeat get repeat => _repeat;
  VideoController? get videoController => _video?.videoController;
  List<AudioDevice> get audioDevices => _audioDevices;
  AudioDevice? get audioDevice => _audioDevice;
  List<double> get equalizer => List.unmodifiable(_equalizer);
  String? get error => _error;
  String? get videoError => _videoError;
  String? get persistenceError => _persistenceError;
  StreamFormat? get streamFormat => _stream?.format;
  String? get streamSource => _stream?.source;
  bool get sleepAfterTrack => _sleepAfterTrack;
  DateTime? get sleepAt => _sleepAt;
  Duration? get sleepTimerRemaining {
    final at = _sleepAt;
    if (at == null) return null;
    final left = at.difference(DateTime.now());
    return left.isNegative ? Duration.zero : left;
  }

  PlaybackSnapshot get snapshot => PlaybackSnapshot(
    queue: queue,
    currentIndex: currentIndex,
    position: position,
    volume: volume,
    shuffle: shuffle,
    repeat: repeat,
    autoplay: autoplay,
    crossfade: crossfade,
    rate: rate,
    videoEnabled: videoEnabled,
    equalizer: equalizer,
    originalOrder: _originalOrder,
  );

  /// Resume is opt-in: starting the application must not unexpectedly play audio.
  Future<void> restore(PlaybackSnapshot saved, {bool resume = false}) async {
    if (_closed) return;
    await stop();
    _timeline = PlaybackQueue.restored(saved.queue, saved.currentIndex);
    _position = saved.position;
    _duration = currentSong?.duration ?? Duration.zero;
    _volume = saved.volume.clamp(0.0, 100.0);
    _shuffle = saved.shuffle;
    _repeat = saved.repeat;
    _autoplay = saved.autoplay;
    _crossfade = saved.crossfade;
    _rate = saved.rate;
    _videoEnabled = saved.videoEnabled;
    _equalizer = saved.equalizer;
    _eqCurve = EqualizerCurve.manual(saved.equalizer);
    _originalOrder = saved.originalOrder.isEmpty
        ? _timeline.orderKeys
        : saved.originalOrder;
    _notify();
    if (resume && currentSong != null) {
      await _loadCurrent(start: _position, play: true);
    }
  }

  Future<void> playSongs(
    List<Song> songs, {
    int index = 0,
    bool play = true,
    String? source,
  }) async {
    if (_closed || songs.isEmpty) return;
    _timeline = _timeline.startContext(songs, at: index, source: source);
    _originalOrder = _timeline.orderKeys;
    if (_shuffle) _timeline = _timeline.shuffledAhead();
    _radioSeedId = null;
    await _loadCurrent(play: play);
  }

  Future<void> playAt(int index) async {
    if (_closed || index < 0 || index >= queue.length) return;
    if (index == currentIndex && _active != null) {
      await seek(Duration.zero);
      await play();
      return;
    }
    _timeline = _timeline.jump(index);
    await _loadCurrent(play: true);
  }

  Future<void> next() => _advance(automatic: false);

  Future<void> _advance({required bool automatic}) async {
    if (_closed || currentSong == null) return;
    if (automatic && _sleepAfterTrack) {
      _sleepAfterTrack = false;
      await pause();
      return;
    }
    if (automatic && _repeat == PlaybackRepeat.one) {
      await seek(Duration.zero);
      await play();
      return;
    }
    if (!_timeline.hasNext &&
        _repeat == PlaybackRepeat.all &&
        queue.isNotEmpty) {
      _timeline = PlaybackQueue(queue, index: 0).afterTransition();
    } else {
      final generation = _generation;
      if (!_timeline.hasNext && _autoplay) await _loadRadio();
      if (_closed || generation != _generation) return;
      if (!_timeline.hasNext) {
        await pause();
        _schedulePersist();
        return;
      }
      _timeline = _timeline.next();
    }
    await _loadCurrent(play: true);
  }

  Future<void> previous() async {
    if (_closed || currentSong == null) return;
    if (_position > const Duration(seconds: 3) || !_timeline.hasPrevious) {
      await seek(Duration.zero);
      return;
    }
    _timeline = _timeline.previous();
    await _loadCurrent(play: true);
  }

  Future<void> playPause() =>
      (_playing || (_loading && _intendedPlaying)) ? pause() : play();

  Future<void> play() async {
    if (_closed || currentSong == null) return;
    _intendedPlaying = true;
    if (_loading) return;
    if (_active == null) {
      await _loadCurrent(start: _position, play: true);
      return;
    }
    if (_endHandled || (_duration > Duration.zero && _position >= _duration)) {
      await seek(Duration.zero);
    }
    if (_closed || !_intendedPlaying) return;
    await _command(() async {
      await _active?.play();
      await _video?.play();
    });
  }

  Future<void> pause() async {
    if (_closed) return;
    final generation = _generation;
    _intendedPlaying = false;
    await _cancelBlend();
    if (_closed || generation != _generation || _intendedPlaying) return;
    await _command(() async {
      await _active?.pause();
      await _video?.pause();
    });
    _playing = false;
    _notify();
    _schedulePersist();
  }

  Future<void> seek(Duration target) async {
    if (_closed || currentSong == null) return;
    final generation = _generation;
    final millis = target.inMilliseconds.clamp(
      0,
      _duration > Duration.zero ? _duration.inMilliseconds : 7 * 86400000,
    );
    final at = Duration(milliseconds: millis);
    await _cancelBlend();
    if (_closed || generation != _generation) return;
    _endHandled = false;
    _position = at;
    _furthestPosition = at;
    _notify();
    if (_active != null) {
      await _command(() async {
        await _active?.seek(at);
        await _video?.seek(at);
      });
    }
    _schedulePersist();
  }

  Future<void> stop() async {
    if (_closed) return;
    ++_generation;
    _intendedPlaying = false;
    _playing = false;
    _loading = false;
    _buffering = false;
    _furthestPosition = Duration.zero;
    final subscription = _activeSubscription;
    _activeSubscription = null;
    final old = _active;
    _active = null;
    // Detach every outgoing resource before awaiting disposal. A new play
    // request must not have its decoder captured by this older stop request.
    final cancelBlend = _cancelBlend();
    final dropStaged = _dropStaged();
    final dropVideo = _dropVideo();
    await Future.wait([
      cancelBlend,
      dropStaged,
      dropVideo,
      if (subscription != null) subscription.cancel(),
      if (old != null) old.close(),
    ]);
    _notify();
    _schedulePersist();
  }

  Future<void> retry() async {
    if (_closed || currentSong == null) return;
    // An explicit Retry is a fresh attempt even after auto-recovery was used.
    _prematureEndRetries = 0;
    await _loadCurrent(start: _position, play: true, refresh: true);
  }

  Future<void> _loadCurrent({
    Duration start = Duration.zero,
    bool play = true,
    bool refresh = false,
  }) async {
    var song = currentSong;
    if (_closed || song == null) return;
    final generation = ++_generation;
    final entryId = song.queueEntryId ?? song.videoId;
    if (_prematureEndEntryId != entryId) {
      _prematureEndEntryId = entryId;
      _prematureEndRetries = 0;
    }
    _intendedPlaying = play;
    _position = start;
    _furthestPosition = start;
    _duration = song.duration;
    _error = null;
    _videoError = null;
    _playing = false;
    _buffering = false;
    _loading = true;
    _endHandled = false;
    _stageFailureEntryId = null;
    _notify();

    final prepared =
        !refresh &&
            _stagedEntryId == song.queueEntryId &&
            (_stagedStream?.isFresh(DateTime.now()) ?? false)
        ? _staged
        : null;
    final preparedStream = prepared == null ? null : _stagedStream;
    if (prepared != null) {
      _staged = null;
      _stagedEntryId = null;
      _stagedStream = null;
    }
    await _cancelBlend();
    if (_closed || generation != _generation) {
      await prepared?.close();
      return;
    }
    await _dropStaged();
    if (_closed || generation != _generation) {
      await prepared?.close();
      return;
    }
    await _dropVideo();
    if (_closed || generation != _generation) {
      await prepared?.close();
      return;
    }
    final oldSubscription = _activeSubscription;
    _activeSubscription = null;
    final old = _active;
    _active = null;
    await oldSubscription?.cancel();
    await old?.close();
    if (_closed || generation != _generation) {
      await prepared?.close();
      return;
    }

    AudioBackend? candidate = prepared;
    try {
      if (prepared == null) {
        song = await _normalizeSong(song, generation);
        if (_closed || generation != _generation) return;
        _duration = song.duration;
      }
      final resolved =
          preparedStream ?? await resolver.resolve(song, refresh: refresh);
      if (_closed || generation != _generation) {
        await candidate?.close();
        return;
      }
      candidate ??= _backendFactory(video: false);
      _opening.add(candidate);
      // Native ownership is attached before open, so stop/dispose can abort a
      // network open that has not returned yet.
      _active = candidate;
      final requestedStart = _position;
      if (prepared == null) {
        await candidate.open(resolved, start: requestedStart);
      } else if (requestedStart > Duration.zero) {
        await candidate.seek(requestedStart);
      }
      if (_closed || generation != _generation) {
        await candidate.close();
        return;
      }
      // Seeking while resolution/native open is pending wins over the older
      // load's resume position. No second decoder is needed for this handoff.
      if (_position != requestedStart) await candidate.seek(_position);
      await _configure(candidate);
      if (_closed || generation != _generation) {
        await candidate.close();
        return;
      }
      _stream = resolved;
      _loading = false;
      _bind(candidate, generation);
      _onBackendState(candidate, candidate.state, generation);
      if (_intendedPlaying) await candidate.play();
      if (_closed || generation != _generation) return;
      _trackChanged(song);
      _schedulePersist();
      _notify();
      _prefetch();
      if (_videoEnabled && song.isVideo) unawaited(_startVideo());
    } on Object catch (failure) {
      await candidate?.close();
      if (!_closed && generation == _generation) {
        if (identical(_active, candidate)) _active = null;
        _loading = false;
        _playing = false;
        _setError(_message(failure));
      }
    } finally {
      if (candidate != null) _opening.remove(candidate);
    }
  }

  Future<void> _configure(AudioBackend backend, {bool silent = false}) async {
    await backend.setVolume(silent ? 0 : _volume);
    await backend.setRate(_rate);
    if (_audioDevice != null) await backend.setAudioDevice(_audioDevice!);
    await backend.setFilter(_eqCurve.nativeFilter);
  }

  void _bind(AudioBackend backend, int generation) {
    _activeSubscription = backend.changes.listen(
      (state) => _onBackendState(backend, state, generation),
      onError: (Object error) {
        if (!_closed && generation == _generation) _setError(_message(error));
      },
    );
  }

  void _onBackendState(
    AudioBackend backend,
    BackendState state,
    int generation,
  ) {
    if (_closed || generation != _generation || !identical(backend, _active)) {
      return;
    }
    _position = state.position;
    if (state.position > _furthestPosition) {
      _furthestPosition = state.position;
    }
    // A truncated segment can report its short decoder duration as though it
    // were the whole track. Keep a known catalogue duration for both the UI
    // and end-of-track/crossfade logic when the two are wildly different.
    final expected = currentSong?.duration ?? Duration.zero;
    if (state.duration > Duration.zero &&
        (expected == Duration.zero ||
            state.duration.inMilliseconds >= expected.inMilliseconds * .90 ||
            state.duration + const Duration(seconds: 8) >= expected)) {
      _duration = state.duration;
    }
    _playing = state.playing && _intendedPlaying;
    _buffering = state.buffering;
    _audioDevices = state.audioDevices;
    _audioDevice = state.audioDevice ?? _audioDevice;
    if (state.error != null) _setError(state.error!);
    _notify();
    final now = DateTime.now();
    if (now.difference(_lastCheckpoint) >= const Duration(seconds: 10)) {
      _lastCheckpoint = now;
      _schedulePersist();
    }
    if (state.completed &&
        !_endHandled &&
        !_mixing &&
        !_loading &&
        _intendedPlaying) {
      _endHandled = true;
      if (_endedPrematurely(expected)) {
        if (_prematureEndRetries == 0) {
          _prematureEndRetries++;
          // Resolve a new signed media URL and continue this track at its
          // last known good position, rather than jumping to the next song.
          unawaited(
            _loadCurrent(start: _furthestPosition, play: true, refresh: true),
          );
        } else {
          _intendedPlaying = false;
          _playing = false;
          _buffering = false;
          _setError(
            'Playback stream ended before the song finished. '
            'The connection or media URL may have expired. Press Retry to resume.',
          );
          _schedulePersist();
        }
        return;
      }
      unawaited(_advance(automatic: true));
      return;
    }
    if (_playing && !_buffering && !_loading) {
      _considerTransition();
      if (_autoplay && queue.length - currentIndex <= 3) {
        unawaited(_loadRadio());
      }
    }
  }

  bool _endedPrematurely(Duration expected) {
    if (expected < const Duration(seconds: 20)) return false;
    // Allow normal stream/container duration differences and coarse position
    // ticks, without treating a 3-second decoder EOF as a finished song.
    final toleranceMs = (expected.inMilliseconds * .06).round().clamp(
      4000,
      12000,
    );
    return _furthestPosition.inMilliseconds + toleranceMs <
        expected.inMilliseconds;
  }

  void _prefetch() {
    if (_closed) return;
    final next = _nextSong;
    if (next != null) unawaited(_prefetchSong(next, _generation));
  }

  Future<void> _prefetchSong(Song song, int generation) async {
    try {
      final normalized = await _normalizeSong(song, generation);
      if (!_closed &&
          generation == _generation &&
          _nextSong?.queueEntryId == song.queueEntryId) {
        await resolver.resolve(normalized);
      }
    } on Object {
      // Prefetch failure is retried visibly when this entry is selected.
    }
  }

  Future<Song> _normalizeSong(Song song, int generation) async {
    final normalize = songNormalizer;
    final entryId = song.queueEntryId;
    if (normalize == null || entryId == null) return song;
    final key = '$entryId:${song.videoId}';
    final future = _normalizing[key] ??= normalize(song);
    try {
      final replacement = await future;
      if (_closed || generation != _generation) return song;
      _timeline = _timeline.replaceEntry(entryId, replacement);
      final resolved = queue
          .where((item) => item.queueEntryId == entryId)
          .firstOrNull;
      if (resolved == null) return song;
      if (resolved.videoId != song.videoId) {
        _notify();
        _schedulePersist();
      }
      return resolved;
    } finally {
      if (identical(_normalizing[key], future)) {
        unawaited(_normalizing.remove(key));
      }
    }
  }

  Song? get _nextSong => _timeline.hasNext
      ? queue[currentIndex + 1]
      : _repeat == PlaybackRepeat.all && queue.length > 1
      ? queue.first
      : null;

  void _considerTransition() {
    if (_crossfade == Duration.zero ||
        _repeat == PlaybackRepeat.one ||
        _sleepAfterTrack ||
        _mixing ||
        _duration <= Duration.zero ||
        (currentSong?.isVideoOrigin ?? true)) {
      return;
    }
    final next = _nextSong;
    if (next == null || next.isVideoOrigin) return;
    final remaining = _duration - _position;
    if (remaining <= _crossfade + const Duration(seconds: 4) &&
        remaining > Duration.zero &&
        !_staging &&
        _staged == null &&
        _stageFailureEntryId != next.queueEntryId) {
      unawaited(_prepareNext(next));
    }
    if (remaining <= _crossfade &&
        remaining > const Duration(milliseconds: 100) &&
        _staged != null &&
        _stagedEntryId == next.queueEntryId) {
      unawaited(_beginBlend(remaining));
    }
  }

  Future<void> _prepareNext(Song song) async {
    if (_closed || _staging || _staged != null) return;
    _staging = true;
    final epoch = ++_stageEpoch;
    final generation = _generation;
    AudioBackend? candidate;
    try {
      final normalized = await _normalizeSong(song, generation);
      if (_closed || epoch != _stageEpoch || generation != _generation) return;
      final stream = await resolver.resolve(normalized);
      if (_closed ||
          epoch != _stageEpoch ||
          generation != _generation ||
          _nextSong?.queueEntryId != song.queueEntryId) {
        return;
      }
      candidate = _backendFactory(video: false);
      _opening.add(candidate);
      await candidate.open(stream);
      await _configure(candidate, silent: true);
      if (_closed ||
          epoch != _stageEpoch ||
          generation != _generation ||
          _nextSong?.queueEntryId != song.queueEntryId) {
        await candidate.close();
        return;
      }
      _staged = candidate;
      _stagedEntryId = song.queueEntryId;
      _stagedStream = stream;
      _considerTransition();
    } on Object {
      await candidate?.close();
      if (epoch == _stageEpoch) _stageFailureEntryId = song.queueEntryId;
      // A failed prefetch cannot interrupt the audible deck. The normal next
      // action gets a fresh attempt and presents a real error if it also fails.
    } finally {
      if (candidate != null) _opening.remove(candidate);
      if (epoch == _stageEpoch) _staging = false;
    }
  }

  Future<void> _beginBlend(Duration remaining) async {
    final incoming = _staged;
    final outgoing = _active;
    if (_closed || _mixing || incoming == null || outgoing == null) return;
    _mixing = true;
    _fadeProgress = 0;
    _fadeDuration = remaining < _crossfade ? remaining : _crossfade;
    final generation = _generation;
    try {
      await incoming.setVolume(0);
      await incoming.play();
      if (_closed ||
          generation != _generation ||
          !_mixing ||
          !identical(incoming, _staged)) {
        return;
      }
      _fadeClock
        ..reset()
        ..start();
      _fadeTimer = Timer.periodic(const Duration(milliseconds: 40), (_) {
        if (!_fadeTickBusy) {
          unawaited(_blendTick(generation, outgoing, incoming));
        }
      });
      _notify();
    } on Object {
      await _cancelBlend();
    }
  }

  Future<void> _blendTick(
    int generation,
    AudioBackend outgoing,
    AudioBackend incoming,
  ) async {
    if (_closed || generation != _generation || !_mixing) return;
    _fadeTickBusy = true;
    try {
      if (!_intendedPlaying ||
          outgoing.state.buffering ||
          incoming.state.buffering ||
          incoming.state.error != null) {
        await _cancelBlend();
        return;
      }
      _fadeProgress =
          (_fadeClock.elapsedMicroseconds / _fadeDuration.inMicroseconds).clamp(
            0.0,
            1.0,
          );
      final gains = crossfadeGains(_fadeProgress);
      await Future.wait([
        outgoing.setVolume(_volume * gains.outgoing),
        incoming.setVolume(_volume * gains.incoming),
      ]);
      if (!_closed &&
          generation == _generation &&
          _mixing &&
          _fadeProgress >= 1) {
        await _completeBlend(outgoing, incoming);
      }
    } on Object {
      await _cancelBlend();
    } finally {
      _fadeTickBusy = false;
    }
  }

  Future<void> _completeBlend(
    AudioBackend outgoing,
    AudioBackend incoming,
  ) async {
    final previousGeneration = _generation;
    if (_nextSong?.queueEntryId != _stagedEntryId) {
      await _cancelBlend();
      return;
    }
    _fadeTimer?.cancel();
    _fadeTimer = null;
    _fadeClock.stop();
    _mixing = false;
    await _activeSubscription?.cancel();
    if (_closed ||
        previousGeneration != _generation ||
        !identical(_active, outgoing)) {
      return;
    }
    _activeSubscription = null;
    _timeline = _timeline.hasNext
        ? _timeline.next()
        : PlaybackQueue(queue, index: 0).afterTransition();
    _active = incoming;
    _stream = _stagedStream;
    _staged = null;
    _stagedEntryId = null;
    _stagedStream = null;
    final generation = ++_generation;
    _endHandled = false;
    _position = incoming.state.position;
    _furthestPosition = incoming.state.position;
    final newSong = currentSong!;
    _prematureEndEntryId = newSong.queueEntryId ?? newSong.videoId;
    _prematureEndRetries = 0;
    _duration = incoming.state.duration;
    _bind(incoming, generation);
    _onBackendState(incoming, incoming.state, generation);
    await outgoing.close();
    if (_closed || generation != _generation) return;
    _trackChanged(currentSong!);
    _schedulePersist();
    _prefetch();
    _notify();
  }

  Future<void> _cancelBlend() async {
    final outgoing = _active;
    final generation = _generation;
    _fadeTimer?.cancel();
    _fadeTimer = null;
    _fadeClock.stop();
    if (!_mixing) return;
    _mixing = false;
    _fadeProgress = 0;
    await _dropStaged();
    if (!_closed && generation == _generation && identical(_active, outgoing)) {
      await outgoing?.setVolume(_volume);
      if (outgoing?.state.completed == true &&
          !_endHandled &&
          !_loading &&
          _intendedPlaying) {
        // Completion during an aborted blend needs the same validation as
        // an ordinary decoder EOF: never bypass premature-end protection.
        _onBackendState(outgoing!, outgoing.state, generation);
      }
    }
  }

  Future<void> _dropStaged() async {
    ++_stageEpoch;
    _staging = false;
    final staged = _staged;
    _staged = null;
    _stagedEntryId = null;
    _stagedStream = null;
    await staged?.close();
  }

  Future<void> _loadRadio() {
    if (_radioFuture != null) return _radioFuture!;
    final future = _loadRadioImpl();
    _radioFuture = future;
    return future.whenComplete(() {
      if (identical(_radioFuture, future)) _radioFuture = null;
    });
  }

  Future<void> _loadRadioImpl() async {
    final seed = currentSong;
    if (_closed ||
        !_autoplay ||
        _radioLoading ||
        radioLoader == null ||
        seed == null ||
        seed.isLocal ||
        _radioSeedId == seed.queueEntryId) {
      return;
    }
    _radioLoading = true;
    _radioSeedId = seed.queueEntryId;
    final generation = _generation;
    try {
      final songs = await radioLoader!(seed);
      if (!_closed && _autoplay && generation == _generation) {
        _timeline = _timeline.appendAutoplay(songs.take(30));
        _notify();
        _schedulePersist();
        _prefetch();
      }
    } on Object {
      // Recommendation failure is separate from the playing stream. Manual
      // playback continues, and the next seed permits a fresh request.
    } finally {
      _radioLoading = false;
    }
  }

  Future<void> addToQueue(Song song) async {
    if (_closed) return;
    final empty = queue.isEmpty;
    _timeline = _timeline.enqueue(song);
    await _queueEdited();
    if (empty) await _loadCurrent(play: true);
  }

  Future<void> playNext(Song song) async {
    if (_closed) return;
    final empty = queue.isEmpty;
    _timeline = _timeline.enqueue(song, next: true);
    await _queueEdited();
    if (empty) await _loadCurrent(play: true);
  }

  Future<void> removeAt(int index) async {
    if (_closed) return;
    _timeline = _timeline.removeAt(index);
    await _queueEdited();
  }

  Future<void> moveQueue(int from, int to) async {
    if (_closed) return;
    _timeline = _timeline.move(from, to);
    await _queueEdited();
  }

  Future<void> clearUserQueue() async {
    if (_closed) return;
    _timeline = _timeline.withoutUserQueue();
    await _queueEdited();
  }

  Future<void> _queueEdited() async {
    if (_nextSong?.queueEntryId != _stagedEntryId) {
      await _cancelBlend();
      await _dropStaged();
    }
    _notify();
    _schedulePersist();
    _prefetch();
  }

  Future<void> setShuffle(bool value) async {
    if (_closed || value == _shuffle) return;
    _shuffle = value;
    if (value) {
      _originalOrder = _timeline.orderKeys;
      _timeline = _timeline.shuffledAhead();
    } else {
      _timeline = _timeline.inOriginalOrder(_originalOrder);
    }
    await _queueEdited();
  }

  Future<void> setRepeat(PlaybackRepeat value) async {
    if (_closed) return;
    _repeat = value;
    await _cancelBlend();
    await _dropStaged();
    _notify();
    _schedulePersist();
    _prefetch();
  }

  Future<void> setAutoplay(bool value) async {
    if (_closed) return;
    _autoplay = value;
    if (!value) {
      _timeline = _timeline.withoutAutoplay();
    } else {
      _radioSeedId = null;
    }
    await _queueEdited();
    if (value && queue.length - currentIndex <= 3) await _loadRadio();
  }

  Future<void> setVolume(double value) async {
    if (_closed || !value.isFinite) return;
    _volume = value.clamp(0.0, 100.0);
    await _command(() async {
      final gains = _mixing
          ? crossfadeGains(_fadeProgress)
          : (outgoing: 1.0, incoming: 0.0);
      await _active?.setVolume(_volume * gains.outgoing);
      await _staged?.setVolume(_volume * gains.incoming);
    });
    _notify();
    _schedulePersist();
  }

  Future<void> setRate(double value) async {
    if (_closed || !value.isFinite) return;
    _rate = value.clamp(.25, 3.0);
    await _cancelBlend();
    await _command(() async {
      await _active?.setRate(_rate);
      await _staged?.setRate(_rate);
      await _video?.setRate(_rate);
    });
    _notify();
    _schedulePersist();
  }

  Future<void> setAudioDevice(AudioDevice device) async {
    if (_closed) return;
    await _cancelBlend();
    await _command(() async {
      await _active?.setAudioDevice(device);
      await _staged?.setAudioDevice(device);
    });
    _audioDevice = device;
    _notify();
    _schedulePersist();
  }

  Future<void> setEqualizer(List<double> gains) async {
    if (_closed) return;
    final curve = EqualizerCurve.manual(gains);
    _equalizer = List.unmodifiable(gains.map((v) => v.clamp(-12.0, 12.0)));
    _eqCurve = curve;
    await _command(() async {
      await _active?.setFilter(curve.nativeFilter);
      await _staged?.setFilter(curve.nativeFilter);
    });
    _notify();
    _schedulePersist();
  }

  Future<void> setTone(int x, int y, {bool focused = false}) async {
    if (_closed) return;
    _eqCurve = EqualizerCurve.tone(x, y, focused: focused);
    await _command(() async {
      await _active?.setFilter(_eqCurve.nativeFilter);
      await _staged?.setFilter(_eqCurve.nativeFilter);
    });
    _notify();
  }

  Future<void> setCrossfade(Duration value) async {
    if (_closed) return;
    _crossfade = Duration(milliseconds: value.inMilliseconds.clamp(0, 12000));
    await _cancelBlend();
    await _dropStaged();
    _notify();
    _schedulePersist();
  }

  Future<void> setVideoEnabled(bool value) async {
    if (_closed) return;
    _videoEnabled = value;
    _videoError = null;
    if (!value) {
      await _dropVideo();
    } else if (_active != null) {
      await _startVideo();
    }
    _notify();
    _schedulePersist();
  }

  Future<void> setVideoQuality(int height) async {
    if (_closed || height < 144 || height > 4320) return;
    _maxVideoHeight = height;
    if (_videoEnabled && _video != null) await _startVideo();
  }

  Future<void> _startVideo() async {
    final song = currentSong;
    if (_closed || !_videoEnabled || song == null || _active == null) return;
    final generation = _generation;
    await _dropVideo();
    if (_closed || generation != _generation || !_videoEnabled) return;
    final epoch = ++_videoEpoch;
    AudioBackend? candidate;
    try {
      final stream = await resolver.resolve(
        song,
        video: true,
        maxVideoHeight: _maxVideoHeight,
      );
      if (_closed ||
          epoch != _videoEpoch ||
          generation != _generation ||
          !_videoEnabled) {
        return;
      }
      candidate = _backendFactory(video: true);
      _opening.add(candidate);
      _video = candidate;
      await candidate.open(stream, start: _position);
      await candidate.setRate(_rate);
      await candidate.setVolume(0);
      if (_closed ||
          epoch != _videoEpoch ||
          generation != _generation ||
          !_videoEnabled) {
        await candidate.close();
        return;
      }
      await candidate.seek(_position);
      if (_playing) await candidate.play();
      _videoSubscription = candidate.changes.listen((state) {
        if (!_closed && epoch == _videoEpoch && state.error != null) {
          _videoError = state.error;
          _notify();
        }
      });
      _videoSyncTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
        if (!_videoSyncBusy) unawaited(_syncVideo(epoch));
      });
      _notify();
    } on Object catch (failure) {
      await candidate?.close();
      if (!_closed && epoch == _videoEpoch) {
        if (identical(candidate, _video)) _video = null;
        _videoError = _message(failure);
        _notify();
      }
    } finally {
      if (candidate != null) _opening.remove(candidate);
    }
  }

  Future<void> _syncVideo(int epoch) async {
    final video = _video;
    if (_closed || epoch != _videoEpoch || video == null) return;
    _videoSyncBusy = true;
    try {
      final shouldPlay = _playing && !_buffering;
      if (video.state.playing != shouldPlay) {
        if (shouldPlay) {
          await video.play();
        } else {
          await video.pause();
        }
      }
      if ((video.state.position - _position).inMilliseconds.abs() > 200) {
        await video.seek(_position);
      }
    } on Object catch (failure) {
      if (!_closed && epoch == _videoEpoch) {
        _videoError = _message(failure);
        _notify();
      }
    } finally {
      _videoSyncBusy = false;
    }
  }

  Future<void> _dropVideo() async {
    ++_videoEpoch;
    _videoSyncTimer?.cancel();
    _videoSyncTimer = null;
    final subscription = _videoSubscription;
    _videoSubscription = null;
    final old = _video;
    _video = null;
    await subscription?.cancel();
    await old?.close();
  }

  Future<void> setSleepTimer(Duration? delay) async {
    if (_closed) return;
    _sleepTimer?.cancel();
    _sleepTimer = null;
    _sleepAfterTrack = false;
    _sleepAt = delay == null ? null : DateTime.now().add(delay);
    if (delay != null) {
      _sleepTimer = Timer(delay.isNegative ? Duration.zero : delay, () {
        _sleepAt = null;
        unawaited(pause());
      });
    }
    _notify();
  }

  Future<void> setSleepAfterTrack(bool value) async {
    if (_closed) return;
    _sleepTimer?.cancel();
    _sleepTimer = null;
    _sleepAt = null;
    _sleepAfterTrack = value;
    if (value) {
      await _cancelBlend();
      await _dropStaged();
    }
    _notify();
  }

  void clearError() {
    _error = null;
    _notify();
  }

  void _trackChanged(Song song) {
    if (onTrackChanged != null) {
      unawaited(
        Future<void>.sync(
          () => onTrackChanged!(song),
        ).catchError((Object _) {}),
      );
    }
  }

  Future<void> _command(Future<void> Function() operation) async {
    try {
      await operation();
    } on Object catch (failure) {
      if (!_closed) _setError(_message(failure));
    }
  }

  void _setError(String message) {
    if (_closed || _error == message) return;
    _error = message;
    onPlaybackError?.call(message);
    _notify();
  }

  void _notify() {
    if (!_closed && !_notifierDisposed) notifyListeners();
  }

  void _schedulePersist() {
    if (_closed || onPersist == null) return;
    _persistTimer?.cancel();
    _persistTimer = Timer(const Duration(milliseconds: 400), () {
      unawaited(flush());
    });
  }

  Future<void> flush() {
    _persistTimer?.cancel();
    _persistTimer = null;
    final write = onPersist;
    if (write == null) return Future.value();
    final saved = snapshot;
    _writeChain = _writeChain.then((_) => write(saved)).catchError((Object _) {
      _persistenceError =
          'Playback could not be saved. Check available storage and file permissions.';
      _notify();
    });
    return _writeChain;
  }

  Future<void> close() => _closeFuture ??= _close();

  Future<void> _close() async {
    if (_closed) return;
    _closed = true;
    ++_generation;
    ++_stageEpoch;
    ++_videoEpoch;
    _persistTimer?.cancel();
    _sleepTimer?.cancel();
    _fadeTimer?.cancel();
    _videoSyncTimer?.cancel();
    _fadeClock.stop();
    await _activeSubscription?.cancel();
    await _videoSubscription?.cancel();
    final decks = {
      _active,
      _staged,
      _video,
      ..._opening,
    }.whereType<AudioBackend>().toList();
    _active = null;
    _staged = null;
    _video = null;
    _opening.clear();
    await Future.wait(decks.map((deck) => deck.close()));
    if (_ownsResolver) await resolver.close();
    await flush();
  }

  @override
  void dispose() {
    if (_notifierDisposed) return;
    _notifierDisposed = true;
    unawaited(close());
    super.dispose();
  }
}

String _message(Object error) {
  if (error is PlaybackResolutionException) return error.message;
  if (error is TimeoutException) {
    return 'Playback timed out. Check your connection and try again.';
  }
  final message = error.toString().replaceAll(
    RegExp(r'https?://\S+'),
    '[media URL]',
  );
  return message.length > 300 ? '${message.substring(0, 300)}…' : message;
}
