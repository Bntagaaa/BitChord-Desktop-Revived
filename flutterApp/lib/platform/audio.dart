import 'dart:async';
import 'package:media_kit/media_kit.dart';

abstract interface class AudioOutput {
  Stream<Duration> get position;
  Stream<Duration> get duration;
  Stream<bool> get playing;
  Stream<bool> get completed;
  Stream<String> get errors;
  Future<void> open(Uri uri, {Map<String, String> headers = const {}});
  Future<void> playPause();
  Future<void> seek(Duration position);
  Future<void> setVolume(double value);
  Future<void> setRate(double value);
  Future<void> close();
}

/// Owns one real native playback process. Never substitutes timers for audio.
class NativeAudioOutput implements AudioOutput {
  NativeAudioOutput({Player? player})
    : _player =
          player ??
          Player(
            configuration: const PlayerConfiguration(
              bufferSize: 32 * 1024 * 1024,
              logLevel: MPVLogLevel.error,
            ),
          ) {
    _applyNativeProperties();
  }

  final Player _player;

  void _applyNativeProperties() {
    try {
      final platform = _player.platform;
      if (platform is NativePlayer) {
        unawaited(
          platform.setProperty(
            'demuxer-lavf-o',
            'reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5',
          ),
        );
        unawaited(platform.setProperty('demuxer-readahead-secs', '25'));
      }
    } catch (_) {
      // Safe fallback if NativePlayer or setProperty is unavailable on the platform.
    }
  }

  @override
  Stream<Duration> get position => _player.stream.position;
  @override
  Stream<Duration> get duration => _player.stream.duration;
  @override
  Stream<bool> get playing => _player.stream.playing;
  @override
  Stream<bool> get completed => _player.stream.completed;
  @override
  Stream<String> get errors => _player.stream.error;
  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {
    try {
      final platform = _player.platform;
      if (platform is NativePlayer) {
        await platform.setProperty(
          'demuxer-lavf-o',
          'reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5',
        );
        await platform.setProperty('demuxer-readahead-secs', '25');
      }
    } catch (_) {
      // Safe fallback.
    }
    await _player.open(Media(uri.toString(), httpHeaders: headers));
  }

  @override
  Future<void> playPause() => _player.playOrPause();
  @override
  Future<void> seek(Duration position) => _player.seek(position);
  @override
  Future<void> setVolume(double value) =>
      _player.setVolume(value.clamp(0, 1).toDouble() * 100);
  @override
  Future<void> setRate(double value) =>
      _player.setRate(value.clamp(.25, 3).toDouble());
  @override
  Future<void> close() => _player.dispose();
}
