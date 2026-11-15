// Actual service-isolate/FFI, SQLite and decoder/sink integration. No test port.
import 'dart:convert';
import 'dart:io';
import '../lib/core/domain/audio_contract.dart';
import '../lib/core/platform/audio/audio_service.dart';
import '../lib/features/local_data/data/sqlite_repository.dart';
import '../lib/features/local_music/data/local_files.dart';
import '../lib/features/playback/application/local_playback.dart';
import '../lib/features/playback/domain/listening_settings.dart';
import '../lib/features/playback/domain/sleep_timer.dart';

Future<void> main(List<String> arguments) async {
  if (arguments.length != 2)
    throw ArgumentError('native library and real audio fixture required');
  final root = await Directory.systemTemp.createTemp('bitchord-phase5-');
  final database = '${root.path}/settings.sqlite3';
  final audio = await AudioService.start(libraryPath: arguments[0]);
  final repository = SqliteLocalRepository(
    path: database,
    libraryPath: arguments[0],
  );
  final playback = LocalPlayback(repository, audio);
  try {
    await playback.initialize();
    if (!playback.supportsListening) throw StateError('DSP capability missing');
    final tuning = ListeningSettings(
      enabled: true,
      mode: EqualizerMode.manual,
      bands: [6, 4, 1.5, 0, 0, 0, 0],
      balance: .25,
      spatial: true,
    );
    await playback.configureListening(tuning);
    final reopened = await repository.read();
    if (reopened.settings.listening?.nativePayload != tuning.nativePayload)
      throw StateError('settings persistence mismatch');
    await playback.playLocal(await LocalFiles.entry(arguments[1]));
    final deadline = DateTime.now().add(const Duration(seconds: 15));
    late PlaybackSnapshot snapshot;
    while (true) {
      snapshot = await playback.refresh();
      if (snapshot.phase == AudioPhase.ended ||
          snapshot.phase == AudioPhase.failed)
        break;
      if (DateTime.now().isAfter(deadline))
        throw StateError('real audio timeout');
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
    if (snapshot.phase == AudioPhase.failed && snapshot.error != 105)
      throw StateError('real decoder/sink failure');
    if (snapshot.phase == AudioPhase.ended && snapshot.playedFrames == 0)
      throw StateError('no presented PCM');
    final baseline = snapshot;
    final trials = <Map<String, Object>>[];
    if (!playback.supportsTempo || !playback.supportsSleep)
      throw StateError('Native listener/sleep capabilities missing');
    if (baseline.error != 105) {
      for (final speed in [.25, .75, 1.25, 3.0]) {
        await playback.configureListening(tuning.copyWith(speed: speed));
        await playback.playLocal(await LocalFiles.entry(arguments[1]));
        await playback.configureSleep(const SleepRequest.afterTrack());
        final limit = DateTime.now().add(const Duration(seconds: 10));
        while (true) {
          snapshot = await playback.refresh();
          if (snapshot.error != 0) throw StateError('Advanced output failed');
          if (snapshot.phase == AudioPhase.paused &&
              snapshot.playedFrames > 0 &&
              !(await playback.sleepStatus()).active)
            break;
          if (DateTime.now().isAfter(limit))
            throw StateError('Native after-track/speed deadline');
          await Future<void>.delayed(const Duration(milliseconds: 10));
        }
        if (snapshot.playedFrames != snapshot.submittedFrames ||
            snapshot.positionUs != snapshot.durationUs)
          throw StateError('Advanced output/clock did not drain');
        if (speed < 1 && snapshot.playedFrames <= baseline.playedFrames ||
            speed > 1 && snapshot.playedFrames >= baseline.playedFrames)
          throw StateError('Listener speed did not change output duration');
        trials.add({
          'speed': speed,
          'played_frames': snapshot.playedFrames,
          'source_position_us': snapshot.positionUs,
          'native_after_track': 'passed',
        });
      }
      await playback.configureSleep(SleepRequest.minutes(5));
      if (!(await playback.sleepStatus()).active)
        throw StateError('Native sleep arm');
      await playback.configureSleep(const SleepRequest.cancel());
      if ((await playback.sleepStatus()).active)
        throw StateError('Native sleep cancel');
    }
    stdout.writeln(
      jsonEncode({
        'ffi_settings_persistence': 'passed',
        'sink_output': baseline.error == 105
            ? 'BLOCKED_NO_DEVICE'
            : 'presented_PCM',
        'submitted_frames': baseline.submittedFrames,
        'played_frames': baseline.playedFrames,
        'backend': baseline.backend,
        'advanced_native_sink': trials,
        'physical_audibility': 'UNVERIFIED',
      }),
    );
  } finally {
    await playback.close();
    await root.delete(recursive: true);
  }
}
