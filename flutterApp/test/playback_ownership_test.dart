import 'dart:async';
import 'package:bitchord/data/innertube.dart';
import 'package:bitchord/data/lyrics.dart';
import 'package:bitchord/data/settings.dart';
import 'package:bitchord/domain/application.dart';
import 'package:bitchord/domain/music.dart';
import 'package:bitchord/platform/audio.dart';
import 'package:flutter_test/flutter_test.dart';

// Test-only native event boundary. Production always uses NativeAudioOutput.
class _Audio implements AudioOutput {
  final positions = StreamController<Duration>.broadcast(sync: true);
  final durations = StreamController<Duration>.broadcast(sync: true);
  final states = StreamController<bool>.broadcast(sync: true);
  final ends = StreamController<bool>.broadcast(sync: true);
  final failures = StreamController<String>.broadcast(sync: true);
  @override
  Stream<Duration> get position => positions.stream;
  @override
  Stream<Duration> get duration => durations.stream;
  @override
  Stream<bool> get playing => states.stream;
  @override
  Stream<bool> get completed => ends.stream;
  @override
  Stream<String> get errors => failures.stream;
  final opened = <Uri>[];
  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {
    opened.add(uri);
    scheduleMicrotask(() {
      durations.add(Duration(seconds: opened.length == 1 ? 100 : 200));
      states.add(true);
    });
  }

  @override
  Future<void> playPause() async {}
  @override
  Future<void> seek(Duration value) async {
    positions.add(value);
  }

  @override
  Future<void> setVolume(double value) async {}
  @override
  Future<void> setRate(double value) async {}
  @override
  Future<void> close() async {
    await Future.wait([
      positions.close(),
      durations.close(),
      states.close(),
      ends.close(),
      failures.close(),
    ]);
  }
}

class _ControllableAudio extends _Audio {
  Completer<void>? openCompleter;

  @override
  Future<void> open(Uri uri, {Map<String, String> headers = const {}}) async {
    opened.add(uri);
    if (openCompleter != null) {
      await openCompleter!.future;
    }
    scheduleMicrotask(() {
      durations.add(const Duration(seconds: 120));
      states.add(true);
    });
  }
}

class _Lyrics extends LyricsRepository {
  final requests = <(String, Duration)>[];
  @override
  Future<List<LyricLine>> fetch(Song song, Duration duration) async {
    requests.add((song.id, duration));
    return [];
  }
}

void main() {
  test(
    'old native events during a track handover cannot advance the new queue or its lyrics',
    () async {
      final audio = _Audio(), lyrics = _Lyrics();
      final app = MusicApplication(
        settings: Settings.memory(),
        api: InnerTube(),
        audio: audio,
        lyrics: lyrics,
      );
      final first = Song(
        id: 'local:first',
        title: 'First',
        artist: '',
        localUri: Uri.file('/first.flac'),
      );
      final second = Song(
        id: 'local:second',
        title: 'Second',
        artist: '',
        localUri: Uri.file('/second.flac'),
      );
      await app.play(first);
      expect(lyrics.requests.last, (
        'local:first',
        const Duration(seconds: 100),
      ));
      audio.positions.add(const Duration(seconds: 90));
      expect(app.position.value.inSeconds, 90);
      final handover = app.play(second);
      // These readings belong to the old native stream while the next URI is
      // being resolved. They must not become metadata/completion for the new song.
      audio.positions.add(const Duration(seconds: 99));
      audio.durations.add(const Duration(seconds: 100));
      audio.ends.add(true);
      audio.failures.add('Previous stream failed');
      expect(app.position.value, Duration.zero);
      expect(app.duration.value, Duration.zero);
      expect(app.error, isNull);
      await handover;
      expect(audio.opened.length, 2);
      expect(app.song?.id, 'local:second');
      expect(lyrics.requests.last, (
        'local:second',
        const Duration(seconds: 200),
      ));
      await app.close();
    },
  );

  test(
    'completion threshold guard ignores premature socket EOF and advances only near track end',
    () async {
      final audio = _Audio(), lyrics = _Lyrics();
      final app = MusicApplication(
        settings: Settings.memory(),
        api: InnerTube(),
        audio: audio,
        lyrics: lyrics,
      );
      final first = Song(
        id: 'local:first',
        title: 'First',
        artist: '',
        localUri: Uri.file('/first.flac'),
      );
      final second = Song(
        id: 'local:second',
        title: 'Second',
        artist: '',
        localUri: Uri.file('/second.flac'),
      );
      await app.play(first, context: [first, second]);
      expect(app.song?.id, 'local:first');
      expect(audio.opened.length, 1);

      // Simulate playing for 5 seconds into a 100-second song (95s remaining > 2.5s)
      audio.positions.add(const Duration(seconds: 5));
      expect(app.position.value.inSeconds, 5);

      // Premature EOF event from network drop / socket disconnect:
      audio.ends.add(true);
      await Future<void>.delayed(Duration.zero);

      // Playback MUST NOT advance to the second track.
      expect(app.song?.id, 'local:first');
      expect(app.queue.index, 0);
      expect(audio.opened.length, 1);

      // Boundary check: at 97,499ms (remaining: 2501ms > 2500ms), must NOT advance
      audio.positions.add(const Duration(milliseconds: 97499));
      audio.ends.add(true);
      await Future<void>.delayed(Duration.zero);
      expect(app.song?.id, 'local:first');
      expect(app.queue.index, 0);

      // Now simulate reaching near the end (98s of 100s, remaining: 2000ms <= 2500ms):
      audio.positions.add(const Duration(seconds: 98));
      audio.ends.add(true);
      await Future<void>.delayed(Duration.zero);

      // Genuine completion fires next track.
      expect(app.song?.id, 'local:second');
      expect(app.queue.index, 1);
      expect(audio.opened.length, 2);

      await app.close();
    },
  );

  test(
    'events arriving while audio.open() is in-flight for a new track cannot skip or poison the track',
    () async {
      final audio = _ControllableAudio(), lyrics = _Lyrics();
      final app = MusicApplication(
        settings: Settings.memory(),
        api: InnerTube(),
        audio: audio,
        lyrics: lyrics,
      );
      final track1 = Song(
        id: 'local:track1',
        title: 'Track 1',
        artist: '',
        localUri: Uri.file('/track1.flac'),
      );
      final track2 = Song(
        id: 'local:track2',
        title: 'Track 2',
        artist: '',
        localUri: Uri.file('/track2.flac'),
      );
      final track3 = Song(
        id: 'local:track3',
        title: 'Track 3',
        artist: '',
        localUri: Uri.file('/track3.flac'),
      );

      // 1. Play track 1
      await app.play(track1, context: [track1, track2, track3]);
      expect(app.song?.id, 'local:track1');

      // 2. Prepare controllable open for track 2
      audio.openCompleter = Completer<void>();
      final switchFuture = app.play(
        track2,
        context: [track1, track2, track3],
        index: 1,
      );

      // Wait for resolver to resolve and audio.open() to be invoked
      await Future<void>.delayed(Duration.zero);
      expect(audio.opened.length, 2);

      // 3. While audio.open() is still in-flight, old native engine emits EOF and error
      audio.ends.add(true);
      audio.failures.add('Decoder teardown error');
      await Future<void>.delayed(Duration.zero);

      // Verify: generation has not yet switched, so EOF did not skip to track 3, and error is not set
      expect(app.queue.index, 1);
      expect(app.error, isNull);

      // 4. Now complete audio.open() for track 2
      audio.openCompleter!.complete();
      await switchFuture;

      // Track 2 is active, healthy, and queue index is 1
      expect(app.song?.id, 'local:track2');
      expect(app.queue.index, 1);
      expect(app.error, isNull);

      await app.close();
    },
  );
}
