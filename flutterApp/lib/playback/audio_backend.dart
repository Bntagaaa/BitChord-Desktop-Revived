import 'dart:async';

import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import 'stream_resolver.dart';

class BackendState {
  const BackendState({
    this.position = Duration.zero,
    this.duration = Duration.zero,
    this.playing = false,
    this.buffering = false,
    this.completed = false,
    this.audioDevices = const [],
    this.audioDevice,
    this.error,
  });
  final Duration position;
  final Duration duration;
  final bool playing;
  final bool buffering;
  final bool completed;
  final List<AudioDevice> audioDevices;
  final AudioDevice? audioDevice;
  final String? error;

  BackendState copyWith({
    Duration? position,
    Duration? duration,
    bool? playing,
    bool? buffering,
    bool? completed,
    List<AudioDevice>? audioDevices,
    AudioDevice? audioDevice,
    String? error,
    bool clearError = false,
  }) => BackendState(
    position: position ?? this.position,
    duration: duration ?? this.duration,
    playing: playing ?? this.playing,
    buffering: buffering ?? this.buffering,
    completed: completed ?? this.completed,
    audioDevices: audioDevices ?? this.audioDevices,
    audioDevice: audioDevice ?? this.audioDevice,
    error: clearError ? null : error ?? this.error,
  );
}

/// Injectable native boundary. Domain/controller tests don't initialize libmpv.
abstract interface class AudioBackend {
  BackendState get state;
  Stream<BackendState> get changes;
  VideoController? get videoController;
  Future<void> open(ResolvedStream stream, {Duration start = Duration.zero});
  Future<void> play();
  Future<void> pause();
  Future<void> seek(Duration position);
  Future<void> setVolume(double volume);
  Future<void> setRate(double rate);
  Future<void> setAudioDevice(AudioDevice device);
  Future<void> setFilter(String filter);
  Future<void> close();
}

typedef AudioBackendFactory = AudioBackend Function({bool video});
