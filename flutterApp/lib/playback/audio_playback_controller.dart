import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:media_kit/media_kit.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart' as yt_lib;
import '../domain/song.dart';

/// Central native audio playback controller.
///
/// Features:
/// - Uses [Player] from `media_kit` (libmpv native audio decoder).
/// - Automatically resolves YouTube audio streams via [yt_lib.YoutubeExplode].
/// - Fallback search when a synthetic / query title is provided.
/// - Full lifecycle management: position, duration, buffering, play, pause, seek.
class AudioPlaybackController extends ChangeNotifier {
  AudioPlaybackController() {
    _initPlayer();
  }

  late final Player _player;
  final yt_lib.YoutubeExplode _yt = yt_lib.YoutubeExplode();
  final List<StreamSubscription<dynamic>> _subscriptions = [];

  Song? _currentSong;
  bool _isPlaying = false;
  bool _isLoading = false;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  double _volume = 0.8;
  bool _shuffle = false;
  String? _errorMessage;

  Song? get currentSong => _currentSong;
  bool get isPlaying => _isPlaying;
  bool get isLoading => _isLoading;
  Duration get position => _position;
  Duration get duration => _duration;
  double get volume => _volume;
  bool get shuffle => _shuffle;
  String? get errorMessage => _errorMessage;

  double get progress {
    if (_duration.inMilliseconds <= 0) return 0.0;
    return (_position.inMilliseconds / _duration.inMilliseconds).clamp(0.0, 1.0);
  }

  void _initPlayer() {
    _player = Player(
      configuration: const PlayerConfiguration(
        title: 'BitChord Desktop Revived',
        bufferSize: 8 * 1024 * 1024,
        logLevel: MPVLogLevel.error,
      ),
    );

    _subscriptions.add(
      _player.stream.position.listen((p) {
        _position = p;
        notifyListeners();
      }),
    );

    _subscriptions.add(
      _player.stream.duration.listen((d) {
        _duration = d;
        notifyListeners();
      }),
    );

    _subscriptions.add(
      _player.stream.playing.listen((p) {
        _isPlaying = p;
        notifyListeners();
      }),
    );

    _subscriptions.add(
      _player.stream.buffering.listen((b) {
        _isLoading = b;
        notifyListeners();
      }),
    );

    _subscriptions.add(
      _player.stream.completed.listen((completed) {
        if (completed) {
          _isPlaying = false;
          _position = Duration.zero;
          notifyListeners();
        }
      }),
    );

    _subscriptions.add(
      _player.stream.error.listen((err) {
        _errorMessage = err;
        _isLoading = false;
        notifyListeners();
      }),
    );

    _player.setVolume((_volume * 100.0).clamp(0.0, 100.0));
  }

  /// Plays a song by resolving its audio stream URL.
  Future<void> playSong(Song song) async {
    _currentSong = song;
    _isLoading = true;
    _errorMessage = null;
    _position = Duration.zero;
    notifyListeners();

    try {
      String? streamUrl;
      String targetId = song.videoId;

      // If videoId is a placeholder or title query, find on YouTube
      if (targetId.startsWith('track-') ||
          targetId.startsWith('album-') ||
          targetId == 'sample') {
        final query = '${song.title} ${song.artist}';
        final searchResults = await _yt.search.search(query).timeout(
              const Duration(seconds: 8),
            );
        if (searchResults.isNotEmpty) {
          targetId = searchResults.first.id.value;
        }
      }

      // Extract highest bitrate audio stream
      final manifest = await _yt.videos.streamsClient.getManifest(targetId).timeout(
            const Duration(seconds: 10),
          );
      final audioStreams = manifest.audioOnly;
      if (audioStreams.isNotEmpty) {
        final bestAudio = audioStreams.withHighestBitrate();
        streamUrl = bestAudio.url.toString();
      }

      if (streamUrl == null) {
        throw Exception('No playable audio stream available for "${song.title}"');
      }

      await _player.open(Media(streamUrl), play: true);
      _isLoading = false;
      notifyListeners();
    } catch (e) {
      _errorMessage = e.toString();
      _isLoading = false;
      // Keep UI playing status for feedback if in an offline sandbox
      notifyListeners();
    }
  }

  Future<void> togglePlayPause() async {
    if (_currentSong == null) return;
    await _player.playOrPause();
  }

  Future<void> seek(double fraction) async {
    if (_duration.inMilliseconds <= 0) return;
    final targetMs = (fraction * _duration.inMilliseconds).round();
    await _player.seek(Duration(milliseconds: targetMs));
  }

  Future<void> setVolume(double newVolume) async {
    _volume = newVolume.clamp(0.0, 1.0);
    await _player.setVolume(_volume * 100.0);
    notifyListeners();
  }

  void toggleShuffle() {
    _shuffle = !_shuffle;
    notifyListeners();
  }

  Future<void> stop() async {
    await _player.stop();
    _currentSong = null;
    _isPlaying = false;
    _position = Duration.zero;
    notifyListeners();
  }

  @override
  void dispose() {
    for (final s in _subscriptions) {
      s.cancel();
    }
    _subscriptions.clear();
    _player.dispose();
    _yt.close();
    super.dispose();
  }
}
