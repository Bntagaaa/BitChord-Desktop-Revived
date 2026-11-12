// Integration with a real OS sink. CI supplies an isolated Pulse software sink.
// No synthetic sink is compiled into production and this proves no audibility.
import 'dart:convert';
import 'dart:io';
import '../lib/core/domain/audio_contract.dart';
import '../lib/core/platform/audio/audio_service.dart';

void check(bool ok, String reason) {
  if (!ok) throw StateError(reason);
}

Future<void> main(List<String> args) async {
  final service = await AudioService.start(libraryPath: args[0]);
  var id = 0, selection = 1;
  final adoptions = <String>[];
  Future<void> command(
    AudioOperation op, {
    int value = 0,
    String path = '',
    String entry = '',
  }) async {
    check(
      await service.submit(
            AudioRequest(
              commandId: ++id,
              selection: selection,
              queueRevision: 1,
              operation: op,
              value: value,
              path: path,
              entryId: entry,
              renditionId: entry.isEmpty ? '' : 'local:$entry',
            ),
          ) ==
          Admission.accepted,
      'Command rejected',
    );
  }

  Future<PlaybackSnapshot> wait(
    bool Function(PlaybackSnapshot) predicate,
  ) async {
    final deadline = DateTime.now().add(const Duration(seconds: 10));
    while (true) {
      final events = await service.events();
      for (final e in events) {
        check(
          e.error == 0 && e.outcome == 0,
          'Native terminal failure ${e.error}',
        );
        if (e.adopted) adoptions.add(e.entryId);
        await service.acknowledge(e.sequence);
      }
      final s = await service.snapshot();
      if (s.selection != selection) {
        await Future<void>.delayed(const Duration(milliseconds: 10));
        continue;
      }
      check(s.error == 0, 'Native sink/decoder error ${s.error}');
      if (predicate(s)) return s;
      check(
        DateTime.now().isBefore(deadline),
        'Native sink deadline: ${s.phase} ${s.entryId} ${s.positionUs} submitted=${s.submittedFrames} played=${s.playedFrames}',
      );
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
  }

  try {
    await command(AudioOperation.select, path: args[1], entry: 'first');
    if (args.length > 3) await command(AudioOperation.device, path: args[3]);
    await wait((s) => s.phase == AudioPhase.paused && s.backend != 0);
    await command(AudioOperation.play);
    final playing = await wait((s) => s.positionUs >= 100000);
    await command(AudioOperation.seek, value: 300000);
    await wait(
      (s) =>
          s.discontinuity > playing.discontinuity &&
          s.positionUs > 350000 &&
          s.playedFrames > 0,
    );
    await command(AudioOperation.pause);
    final pause = await wait((s) => s.phase == AudioPhase.paused);
    await Future<void>.delayed(const Duration(milliseconds: 150));
    check(
      (await service.snapshot()).positionUs == pause.positionUs,
      'Paused clock advanced',
    );
    await command(AudioOperation.seek, value: 1000000);
    await wait((s) => s.phase == AudioPhase.paused && s.positionUs == 1000000);
    await command(AudioOperation.rate, value: 2000000);
    await command(AudioOperation.play);
    final a = await wait((s) => s.positionUs >= 1100000);
    final b = await wait((s) => s.positionUs >= 1600000);
    final ratio =
        (b.positionUs - a.positionUs) *
        1000 /
        (b.sampledAtNativeNs - a.sampledAtNativeNs);
    check(ratio > 1.5 && ratio < 2.5, 'Rate/source clock mismatch $ratio');
    await command(AudioOperation.pause);
    await wait((s) => s.phase == AudioPhase.paused);
    await command(AudioOperation.rate, value: 1000000);
    await command(AudioOperation.seek, value: 2000000);
    final seek = await wait(
      (s) => s.phase == AudioPhase.paused && s.positionUs == 2000000,
    );
    await command(AudioOperation.nominate, path: args[2], entry: 'second');
    await Future<void>.delayed(const Duration(milliseconds: 100));
    await command(AudioOperation.play);
    final end = await wait((s) => s.phase == AudioPhase.ended);
    check(
      end.entryId == 'second' && end.positionUs == 500000,
      'Handoff clock/identity',
    );
    check(
      end.deviceEpoch == seek.deviceEpoch,
      'Same-format handoff reopened sink',
    );
    check(
      end.submittedFrames == 72000 && end.playedFrames == 72000,
      'Partial write/handoff exact frames',
    );
    check(
      adoptions.where((e) => e == 'second').length == 1,
      'Duplicate/lost adoption',
    );
    check(end.pcmHighWater <= 48000 * 2 * 4 * 4, 'Two-deck PCM bound');
    if (args.length > 3 && args[3].startsWith('pulse:')) {
      await command(
        AudioOperation.device,
        path: 'pulse:bitchord_phase3_missing_endpoint',
      );
      PlaybackSnapshot failed = await service.snapshot();
      for (var i = 0; i < 500 && failed.error == 0; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 10));
        failed = await service.snapshot();
      }
      check(
        failed.phase == AudioPhase.failed && failed.error != 0,
        'Missing endpoint did not fail explicitly: ${failed.phase} error=${failed.error}',
      );
      await Future<void>.delayed(const Duration(milliseconds: 100));
      check(
        (await service.snapshot()).positionUs == failed.positionUs,
        'Failed endpoint advanced simulated time',
      );
      await command(AudioOperation.device, path: args[3]);
      var applied = false;
      for (var i = 0; i < 200 && !applied; i++) {
        final terminal = await service.events();
        applied = terminal.any((e) => e.commandId == id && e.outcome == 0);
        if (terminal.isNotEmpty)
          await service.acknowledge(terminal.last.sequence);
        if (!applied)
          await Future<void>.delayed(const Duration(milliseconds: 5));
      }
      check(applied, 'Recovery endpoint command not applied');
      selection = 2;
      await command(
        AudioOperation.select,
        value: 1,
        path: args[2],
        entry: 'recovered',
      );
      await wait((s) => s.phase == AudioPhase.ended);
    }
    stdout.writeln(
      jsonEncode({
        'sink': end.backend,
        'pause_clock': 'held',
        'rate_ratio': ratio,
        'seek': 'passed',
        'same_format_handoff': '72000 exact frames',
        'device_reopens_at_handoff': 0,
        'pcm_high_water': end.pcmHighWater,
        'missing_endpoint_recovery':
            'explicit failure, held clock, successful reselect',
        'physical_audio': 'UNVERIFIED',
      }),
    );
  } finally {
    await service.close();
  }
}
