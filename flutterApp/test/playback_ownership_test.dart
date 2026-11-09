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
    durations.add(Duration(seconds: opened.length == 1 ? 100 : 200));
    states.add(true);
  }
  @override
  Future<void> playPause() async {}
  @override
  Future<void> seek(Duration value) async { positions.add(value); }
  @override
  Future<void> setVolume(double value) async {}
  @override
  Future<void> setRate(double value) async {}
  @override
  Future<void> close() async {
    await Future.wait([positions.close(), durations.close(), states.close(), ends.close(), failures.close()]);
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
  test('old native events during a track handover cannot advance the new queue or its lyrics', () async {
    final audio = _Audio(), lyrics = _Lyrics();
    final app = MusicApplication(settings: Settings.memory(), api: InnerTube(), audio: audio, lyrics: lyrics);
    final first = Song(id: 'local:first', title: 'First', artist: '', localUri: Uri.file('/first.flac'));
    final second = Song(id: 'local:second', title: 'Second', artist: '', localUri: Uri.file('/second.flac'));
    await app.play(first);
    expect(lyrics.requests.single, ('local:first', const Duration(seconds: 100)));
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
    expect(lyrics.requests.last, ('local:second', const Duration(seconds: 200)));
    await app.close();
  });
}
