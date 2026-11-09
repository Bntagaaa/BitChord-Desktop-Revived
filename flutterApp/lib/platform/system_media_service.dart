import 'dart:async';
import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/foundation.dart';

import '../playback/playback.dart';
import 'mpris_service.dart';

class SystemMediaService {
  SystemMediaService(this.playback);
  final PlaybackController playback;
  MprisService? _mpris;
  _DesktopAudioHandler? _handler;

  Future<void> start({
    required Future<void> Function() raise,
    required Future<void> Function() quit,
  }) async {
    if (Platform.isLinux) {
      _mpris = MprisService(playback, raise: raise, quit: quit);
      await _mpris!.start();
    } else if (Platform.isWindows || Platform.isMacOS) {
      final handler = _DesktopAudioHandler(playback);
      _handler = handler;
      await AudioService.init(
        builder: () => handler,
        config: const AudioServiceConfig(
          androidNotificationChannelId: 'com.music.bitchord.flutter.audio',
          androidNotificationChannelName: 'BitChord playback',
        ),
      );
      handler.start();
    }
  }

  Future<void> close() async {
    await _mpris?.close();
    _handler?.close();
  }
}

class _DesktopAudioHandler extends BaseAudioHandler {
  _DesktopAudioHandler(this.player);
  final PlaybackController player;
  String? _lastSong;
  Duration? _lastDuration;
  List<String> _lastQueue = const [];
  DateTime _lastTick = DateTime.fromMillisecondsSinceEpoch(0);
  bool? _lastPlaying;
  bool? _lastBuffering;

  void start() {
    player.addListener(_update);
    _update();
  }

  void _update() {
    final now = DateTime.now();
    final song = player.currentSong;
    final key = song?.queueEntryId ?? song?.videoId;
    if (key != _lastSong || player.duration != _lastDuration) {
      _lastSong = key;
      _lastDuration = player.duration;
      mediaItem.add(
        song == null
            ? null
            : MediaItem(
                id: key!,
                title: song.title,
                artist: song.artist,
                album: song.albumName,
                duration: player.duration > Duration.zero
                    ? player.duration
                    : song.duration,
                artUri: song.thumbnailUrl == null
                    ? null
                    : Uri.tryParse(song.artworkAt(544)!),
              ),
      );
    }
    final ids = player.queue
        .map((item) => item.queueEntryId ?? item.videoId)
        .toList();
    if (!listEquals(ids, _lastQueue)) {
      _lastQueue = ids;
      queue.add(
        player.queue
            .map(
              (item) => MediaItem(
                id: item.queueEntryId ?? item.videoId,
                title: item.title,
                artist: item.artist,
                album: item.albumName,
                duration: item.duration,
                artUri: item.thumbnailUrl == null
                    ? null
                    : Uri.tryParse(item.artworkAt(160)!),
              ),
            )
            .toList(),
      );
    }
    if (_lastPlaying == player.playing &&
        _lastBuffering == player.buffering &&
        now.difference(_lastTick) < const Duration(milliseconds: 500)) {
      return;
    }
    _lastTick = now;
    _lastPlaying = player.playing;
    _lastBuffering = player.buffering;
    playbackState.add(
      PlaybackState(
        controls: [
          MediaControl.skipToPrevious,
          player.playing ? MediaControl.pause : MediaControl.play,
          MediaControl.skipToNext,
          MediaControl.stop,
        ],
        systemActions: const {
          MediaAction.seek,
          MediaAction.seekForward,
          MediaAction.seekBackward,
        },
        processingState: song == null
            ? AudioProcessingState.idle
            : player.buffering
            ? AudioProcessingState.buffering
            : AudioProcessingState.ready,
        playing: player.playing,
        updatePosition: player.position,
        bufferedPosition: player.position,
        speed: player.rate,
        queueIndex: player.currentIndex < 0 ? null : player.currentIndex,
        repeatMode: switch (player.repeat) {
          PlaybackRepeat.off => AudioServiceRepeatMode.none,
          PlaybackRepeat.one => AudioServiceRepeatMode.one,
          PlaybackRepeat.all => AudioServiceRepeatMode.all,
        },
        shuffleMode: player.shuffle
            ? AudioServiceShuffleMode.all
            : AudioServiceShuffleMode.none,
      ),
    );
  }

  @override
  Future<void> play() => player.play();
  @override
  Future<void> pause() => player.pause();
  @override
  Future<void> stop() => player.stop();
  @override
  Future<void> seek(Duration position) => player.seek(position);
  @override
  Future<void> skipToNext() => player.next();
  @override
  Future<void> skipToPrevious() => player.previous();
  @override
  Future<void> skipToQueueItem(int index) => player.playAt(index);
  @override
  Future<void> fastForward() =>
      player.seek(player.position + const Duration(seconds: 10));
  @override
  Future<void> rewind() =>
      player.seek(player.position - const Duration(seconds: 10));
  @override
  Future<void> setSpeed(double speed) => player.setRate(speed);
  @override
  Future<void> setShuffleMode(AudioServiceShuffleMode mode) =>
      player.setShuffle(mode != AudioServiceShuffleMode.none);
  @override
  Future<void> setRepeatMode(AudioServiceRepeatMode mode) =>
      player.setRepeat(switch (mode) {
        AudioServiceRepeatMode.one => PlaybackRepeat.one,
        AudioServiceRepeatMode.all ||
        AudioServiceRepeatMode.group => PlaybackRepeat.all,
        _ => PlaybackRepeat.off,
      });

  void close() {
    player.removeListener(_update);
    playbackState.add(
      PlaybackState(processingState: AudioProcessingState.idle),
    );
    mediaItem.add(null);
    queue.add(const []);
  }
}
