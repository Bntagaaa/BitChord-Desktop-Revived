import 'dart:async';

import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import 'audio_backend.dart';
import 'range_media_proxy.dart';
import 'stream_resolver.dart';

/// Each deck owns its native player, listeners, and optional range transport.
class MediaKitBackend implements AudioBackend {
  MediaKitBackend({this.video = false}) {
    MediaKit.ensureInitialized();
    _player = Player(
      configuration: PlayerConfiguration(
        title: 'BitChord Desktop Revived',
        muted: video,
        bufferSize: (video ? 16 : 8) * 1024 * 1024,
        logLevel: MPVLogLevel.error,
      ),
    );
    if (video) {
      _videoController = VideoController(
        _player,
        configuration: const VideoControllerConfiguration(
          enableHardwareAcceleration: true,
        ),
      );
    }
    _listen(_player.stream.position, (v) => _state.copyWith(position: v));
    _listen(_player.stream.duration, (v) => _state.copyWith(duration: v));
    _listen(_player.stream.playing, (v) => _state.copyWith(playing: v));
    _listen(_player.stream.buffering, (v) => _state.copyWith(buffering: v));
    _listen(_player.stream.completed, (v) => _state.copyWith(completed: v));
    _listen(
      _player.stream.audioDevices,
      (v) => _state.copyWith(audioDevices: List.unmodifiable(v)),
    );
    _listen(_player.stream.audioDevice, (v) => _state.copyWith(audioDevice: v));
    _listen(_player.stream.error, (v) => _state.copyWith(error: _redact(v)));
  }

  final bool video;
  late final Player _player;
  VideoController? _videoController;
  final _events = StreamController<BackendState>.broadcast(sync: true);
  final _subscriptions = <StreamSubscription<dynamic>>[];
  BackendState _state = const BackendState();
  RangeMediaProxy? _proxy;
  bool _closed = false;
  Future<void>? _closeFuture;

  @override
  BackendState get state => _state;
  @override
  Stream<BackendState> get changes => _events.stream;
  @override
  VideoController? get videoController => _videoController;

  void _listen<T>(Stream<T> stream, BackendState Function(T) transform) {
    _subscriptions.add(
      stream.listen((value) {
        if (_closed) return;
        _state = transform(value);
        _events.add(_state);
      }),
    );
  }

  @override
  Future<void> open(
    ResolvedStream stream, {
    Duration start = Duration.zero,
  }) async {
    if (_closed) throw StateError('Native deck is closed.');
    var uri = stream.uri;
    if (stream.windowedReads) {
      final proxy = RangeMediaProxy(stream);
      _proxy = proxy;
      uri = await proxy.open();
    }
    if (_closed) throw StateError('Native deck was closed while opening.');
    final native = _player.platform;
    if (native is NativePlayer) {
      // Video is a silent decoder that follows the audio clock. Audio-only
      // decks must not accidentally decode a muxed video's pictures.
      await native.setProperty(video ? 'aid' : 'vid', 'no');
    }
    await _player.open(
      Media(
        uri,
        httpHeaders: stream.windowedReads ? null : stream.headers,
        start: start > Duration.zero ? start : null,
      ),
      play: false,
    );
    if (_closed) return;
    _state = _state.copyWith(
      position: start,
      completed: false,
      clearError: true,
    );
    _events.add(_state);
  }

  @override
  Future<void> play() => _closed ? Future.value() : _player.play();
  @override
  Future<void> pause() => _closed ? Future.value() : _player.pause();
  @override
  Future<void> seek(Duration position) =>
      _closed ? Future.value() : _player.seek(position);
  @override
  Future<void> setVolume(double volume) =>
      _closed ? Future.value() : _player.setVolume(video ? 0 : volume);
  @override
  Future<void> setRate(double rate) =>
      _closed ? Future.value() : _player.setRate(rate);
  @override
  Future<void> setAudioDevice(AudioDevice device) =>
      _closed ? Future.value() : _player.setAudioDevice(device);
  @override
  Future<void> setFilter(String filter) async {
    if (_closed) return;
    final native = _player.platform;
    if (native is! NativePlayer) {
      if (filter.isNotEmpty) {
        throw UnsupportedError(
          'The native equalizer is unavailable on this backend.',
        );
      }
      return;
    }
    await native.setProperty('af', filter);
  }

  @override
  Future<void> close() => _closeFuture ??= _close();

  Future<void> _close() async {
    if (_closed) return;
    _closed = true;
    for (final subscription in _subscriptions) {
      await subscription.cancel();
    }
    _subscriptions.clear();
    // Closing transport aborts pending I/O before native dispose waits for it.
    await _proxy?.close();
    _proxy = null;
    try {
      await _player.dispose();
    } finally {
      await _events.close();
      _videoController = null;
    }
  }
}

String _redact(String input) => input
    .replaceAll(RegExp(r'https?://\S+'), '[media URL]')
    .replaceAll(
      RegExp(r'(authorization|cookie):[^\r\n]+', caseSensitive: false),
      '[redacted]',
    );
