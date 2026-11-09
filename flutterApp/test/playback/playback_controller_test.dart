import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/playback/playback.dart';

Song song(String id) =>
    Song(videoId: id, title: id, artist: 'Artist', durationText: '2:00');

Future<void> settle() async {
  for (var i = 0; i < 20; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  test('restoring session is paused and allocates no decoder', () async {
    final rig = _Rig();
    await rig.controller.restore(
      PlaybackSnapshot(
        queue: [song('one')],
        position: const Duration(seconds: 30),
      ),
    );
    expect(rig.backends, isEmpty);
    expect(rig.controller.currentSong!.videoId, 'one');
    expect(rig.controller.position, const Duration(seconds: 30));
    expect(rig.controller.playing, isFalse);
    await rig.controller.play();
    expect(rig.backends.single.openedAt, const Duration(seconds: 30));
    expect(rig.controller.playing, isTrue);
    await rig.close();
  });

  test('late stream resolution cannot replace a newer selection', () async {
    final rig = _Rig();
    final slow = Completer<ResolvedStream>();
    rig.resolver.pending['slow'] = slow;
    final first = rig.controller.playSongs([song('slow')]);
    await settle();
    await rig.controller.playSongs([song('fast')]);
    slow.complete(const ResolvedStream(uri: 'https://media.example/slow'));
    await first;
    expect(rig.controller.currentSong!.videoId, 'fast');
    expect(rig.backends.where((b) => !b.closed).length, 1);
    expect(rig.backends.single.stream!.uri, 'https://media.example/fast');
    await rig.close();
  });

  test('disposal aborts a decoder that is still opening', () async {
    final gate = Completer<void>();
    final rig = _Rig(openGate: gate);
    final loading = rig.controller.playSongs([song('one')]);
    await settle();
    expect(rig.backends.length, 1);
    await rig.controller.close();
    expect(rig.backends.single.closed, isTrue);
    gate.complete();
    await loading;
    expect(rig.backends.single.playCalls, 0);
    rig.controller.dispose();
  });

  test(
    'pause during resolution is retained when the stream becomes ready',
    () async {
      final rig = _Rig();
      final slow = Completer<ResolvedStream>();
      rig.resolver.pending['one'] = slow;
      final loading = rig.controller.playSongs([song('one')]);
      await settle();
      await rig.controller.pause();
      slow.complete(const ResolvedStream(uri: 'https://media.example/one'));
      await loading;
      expect(rig.controller.playing, isFalse);
      expect(rig.backends.single.playCalls, 0);
      await rig.close();
    },
  );

  test('Next skips repeat-one while natural completion repeats it', () async {
    final rig = _Rig();
    await rig.controller.playSongs([song('one'), song('two')]);
    await rig.controller.setRepeat(PlaybackRepeat.one);
    rig.backends.last.emit(
      rig.backends.last.state.copyWith(
        completed: true,
        position: const Duration(minutes: 2),
        playing: false,
      ),
    );
    await settle();
    expect(rig.controller.currentSong!.videoId, 'one');
    expect(rig.controller.position, Duration.zero);
    await rig.controller.next();
    expect(rig.controller.currentSong!.videoId, 'two');
    await rig.close();
  });

  test(
    'seek while resolution is pending becomes native initial position',
    () async {
      final rig = _Rig();
      final slow = Completer<ResolvedStream>();
      rig.resolver.pending['one'] = slow;
      final loading = rig.controller.playSongs([song('one')]);
      await settle();
      await rig.controller.seek(const Duration(seconds: 47));
      slow.complete(const ResolvedStream(uri: 'https://media.example/one'));
      await loading;
      expect(rig.backends.single.openedAt, const Duration(seconds: 47));
      expect(rig.controller.position, const Duration(seconds: 47));
      await rig.close();
    },
  );

  test('lazy audio normalization preserves entry metadata and reaches automatic next', () async {
    final normalized = <String>[];
    final rig = _Rig(
      songNormalizer: (item) async {
        normalized.add(item.videoId);
        return item.isVideo ? song('${item.videoId}-audio') : item;
      },
    );
    final video = song('video').copyWith(isVideo: true, isVideoOrigin: true);
    await rig.controller.playSongs([
      song('one'),
      video,
      song('untouched'),
    ], source: 'Album');
    final originalId = rig.controller.queue[1].queueEntryId;
    await settle();
    expect(rig.controller.queue[1].videoId, 'video-audio');
    expect(rig.controller.queue[1].queueEntryId, originalId);
    expect(rig.controller.queue[1].playbackSource, 'Album');
    expect(rig.controller.queue[1].isVideoOrigin, isTrue);
    expect(normalized, isNot(contains('untouched')));
    rig.backends.last.emit(
      rig.backends.last.state.copyWith(completed: true, playing: false),
    );
    await settle();
    expect(rig.controller.currentSong!.videoId, 'video-audio');
    expect(rig.controller.currentSong!.queueEntryId, originalId);
    expect(rig.backends.last.stream!.uri, 'https://media.example/video-audio');
    await rig.close();
  });

  test(
    'late audio version lookup cannot replace newer queue selection',
    () async {
      final lookup = Completer<Song>();
      final rig = _Rig(
        songNormalizer: (item) =>
            item.videoId == 'slow' ? lookup.future : Future.value(item),
      );
      final slow = rig.controller.playSongs([song('slow')]);
      await settle();
      await rig.controller.playSongs([song('new')]);
      lookup.complete(song('converted'));
      await slow;
      expect(rig.controller.currentSong!.videoId, 'new');
      expect(rig.backends.single.stream!.uri, 'https://media.example/new');
      await rig.close();
    },
  );

  test(
    'manual queue remains ahead of context and active removal is ignored',
    () async {
      final rig = _Rig();
      await rig.controller.playSongs([song('one'), song('two')]);
      await rig.controller.addToQueue(song('requested'));
      await rig.controller.removeAt(0);
      expect(rig.controller.currentSong!.videoId, 'one');
      await rig.controller.next();
      expect(rig.controller.currentSong!.videoId, 'requested');
      await rig.controller.next();
      expect(rig.controller.currentSong!.videoId, 'two');
      expect(rig.controller.queue.map((s) => s.videoId), ['one', 'two']);
      await rig.close();
    },
  );

  test('sleep after track pauses before queue advancement', () async {
    final rig = _Rig();
    await rig.controller.playSongs([song('one'), song('two')]);
    await rig.controller.setSleepAfterTrack(true);
    rig.backends.last.emit(
      rig.backends.last.state.copyWith(
        completed: true,
        position: const Duration(minutes: 2),
        playing: false,
      ),
    );
    await settle();
    expect(rig.controller.currentSong!.videoId, 'one');
    expect(rig.controller.playing, isFalse);
    expect(rig.controller.sleepAfterTrack, isFalse);
    await rig.close();
  });

  test(
    'seek invalidates completion and does not create a new decoder',
    () async {
      final rig = _Rig();
      await rig.controller.playSongs([song('one')]);
      await rig.controller.seek(const Duration(seconds: 70));
      expect(rig.controller.position, const Duration(seconds: 70));
      expect(rig.backends.length, 1);
      await rig.controller.seek(const Duration(hours: 10));
      expect(rig.controller.position, const Duration(minutes: 2));
      await rig.close();
    },
  );

  test('save writes are serialized and latest position is retained', () async {
    final writes = <PlaybackSnapshot>[];
    final gate = Completer<void>();
    var first = true;
    final rig = _Rig(
      onPersist: (value) async {
        if (first) {
          first = false;
          await gate.future;
        }
        writes.add(value);
      },
    );
    await rig.controller.playSongs([song('one')]);
    final save1 = rig.controller.flush();
    await rig.controller.seek(const Duration(seconds: 40));
    final save2 = rig.controller.flush();
    gate.complete();
    await Future.wait([save1, save2]);
    expect(writes.last.position, const Duration(seconds: 40));
    await rig.close();
  });

  test(
    'resolver errors do not expose signed URLs or leave buffering stuck',
    () async {
      final rig = _Rig();
      rig.resolver.fail = true;
      await rig.controller.playSongs([song('one')]);
      expect(rig.controller.error, contains('[media URL]'));
      expect(rig.controller.error, isNot(contains('secret')));
      expect(rig.controller.buffering, isFalse);
      expect(rig.controller.playing, isFalse);
      await rig.close();
    },
  );
}

class _Rig {
  _Rig({
    Completer<void>? openGate,
    PlaybackPersistence? onPersist,
    Future<Song> Function(Song)? songNormalizer,
  }) {
    controller = PlaybackController(
      resolver: resolver,
      backendFactory: ({bool video = false}) {
        final backend = _Backend(openGate: openGate);
        backends.add(backend);
        return backend;
      },
      onPersist: onPersist,
      songNormalizer: songNormalizer,
    );
  }
  final resolver = _Resolver();
  final backends = <_Backend>[];
  late final PlaybackController controller;
  Future<void> close() async {
    await controller.close();
    controller.dispose();
  }
}

class _Resolver implements StreamResolver {
  final pending = <String, Completer<ResolvedStream>>{};
  bool fail = false;
  @override
  Future<ResolvedStream> resolve(
    Song song, {
    bool video = false,
    int maxVideoHeight = 1080,
    bool refresh = false,
    bool forceRefresh = false,
  }) async {
    if (fail) {
      throw Exception('Denied https://media.example/secret?token=secret');
    }
    if (pending.containsKey(song.videoId)) return pending[song.videoId]!.future;
    return ResolvedStream(uri: 'https://media.example/${song.videoId}');
  }

  @override
  void invalidate(Song song) {}
  @override
  Future<void> close() async {}
}

class _Backend implements AudioBackend {
  _Backend({this.openGate});
  final Completer<void>? openGate;
  final events = StreamController<BackendState>.broadcast(sync: true);
  bool closed = false;
  int playCalls = 0;
  Duration openedAt = Duration.zero;
  ResolvedStream? stream;
  BackendState _state = const BackendState(duration: Duration(minutes: 2));
  @override
  BackendState get state => _state;
  @override
  Stream<BackendState> get changes => events.stream;
  @override
  VideoController? get videoController => null;
  void emit(BackendState value) {
    _state = value;
    if (!closed) events.add(value);
  }

  @override
  Future<void> open(
    ResolvedStream source, {
    Duration start = Duration.zero,
  }) async {
    stream = source;
    openedAt = start;
    await openGate?.future;
    emit(state.copyWith(position: start, completed: false));
  }

  @override
  Future<void> play() async {
    if (closed) return;
    playCalls++;
    emit(state.copyWith(playing: true, completed: false));
  }

  @override
  Future<void> pause() async {
    emit(state.copyWith(playing: false));
  }

  @override
  Future<void> seek(Duration at) async {
    emit(state.copyWith(position: at, completed: false));
  }

  @override
  Future<void> setVolume(double value) async {}
  @override
  Future<void> setRate(double value) async {}
  @override
  Future<void> setAudioDevice(AudioDevice device) async {
    emit(state.copyWith(audioDevice: device));
  }

  @override
  Future<void> setFilter(String filter) async {}
  @override
  Future<void> close() async {
    if (closed) return;
    closed = true;
    await events.close();
  }
}
