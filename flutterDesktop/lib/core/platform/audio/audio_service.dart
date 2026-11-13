import 'dart:async';
import 'dart:ffi';
import 'dart:isolate';

import '../../domain/audio_contract.dart';
import 'bindings.g.dart';
import 'native_api.dart';

final class AudioService implements LocalAudioPort {
  AudioService._(this._port, this._isolate);
  final SendPort _port;
  final Isolate _isolate;
  int _pending = 0;
  bool _closing = false;
  Future<void>? _close;
  static Future<AudioService> start({String? libraryPath}) async {
    final ready = ReceivePort();
    final isolate = await Isolate.spawn(_worker, [
      ready.sendPort,
      libraryPath ?? NativeApi.bundledLibrary,
    ]);
    final result = await ready.first;
    ready.close();
    if (result is! SendPort) {
      isolate.kill(priority: Isolate.immediate);
      throw StateError('Native service initialization failed');
    }
    return AudioService._(result, isolate);
  }

  Future<Object?> _call(String operation, [Object? data]) async {
    if (_pending >= 120) throw StateError('Audio service admission busy');
    if (_closing && operation != 'close')
      throw StateError('Audio service closing');
    _pending++;
    final reply = ReceivePort();
    try {
      _port.send([operation, data, reply.sendPort]);
      final response =
          await reply.first.timeout(const Duration(seconds: 15)) as List;
      if (response[0] != true)
        throw StateError('Native service operation failed: ${response[1]}');
      return response[1];
    } finally {
      reply.close();
      _pending--;
    }
  }

  @override
  Future<Admission> submit(AudioRequest r) async =>
      Admission.values[await _call('submit', {
        'id': r.commandId,
        'selection': r.selection,
        'revision': r.queueRevision,
        'type': r.operation.index + 1,
        'value': r.value,
        'path': r.path,
        'entry': r.entryId,
        'rendition': r.renditionId,
      }) as int];
  @override
  Future<PlaybackSnapshot> snapshot() async {
    final s = await _call('snapshot') as Map;
    return PlaybackSnapshot(
      phase: AudioPhase.values[s['phase'] as int],
      selection: s['selection'] as int,
      queueRevision: s['revision'] as int,
      sequence: s['sequence'] as int,
      eventWatermark: s['watermark'] as int,
      renditionId: s['rendition'] as String,
      positionUs: s['position'] as int,
      durationUs: s['duration'] as int,
      sampledAtNativeNs: s['ns'] as int,
      discontinuity: s['discontinuity'] as int,
      entryId: s['entry'] as String,
      awaitingAudio: s['awaiting'] as bool,
      error: s['error'] as int,
      submittedFrames: s['submitted'] as int,
      playedFrames: s['played'] as int,
      deviceEpoch: s['deviceEpoch'] as int,
      pcmBytes: s['pcm'] as int,
      pcmHighWater: s['highWater'] as int,
      quiescent: s['quiescent'] as bool,
      timingQuality: s['timing'] as int,
      backend: s['backend'] as int,
      outputRate: s['rate'] as int,
      outputChannels: s['channels'] as int,
      precisionBits: s['bits'] as int,
    );
  }

  @override
  Future<List<AudioEvent>> events() async => (await _call('events') as List)
      .map(
        (dynamic e) => AudioEvent(
          sequence: e['sequence'] as int,
          commandId: e['id'] as int,
          selection: e['selection'] as int,
          queueRevision: e['revision'] as int,
          entryId: e['entry'] as String,
          outcome: e['outcome'] as int,
          error: e['error'] as int,
          adopted: e['adopted'] as bool,
        ),
      )
      .toList();
  @override
  Future<void> acknowledge(int sequence) async {
    await _call('ack', sequence);
  }

  @override
  Future<int> monotonicNs() async => await _call('clock') as int;
  @override
  Future<void> close() => _close ??= _shutdown();
  Future<void> _shutdown() async {
    _closing = true;
    await _call('close');
    // Native quiescence/destruction has completed; only now retire the isolate.
    _isolate.kill();
  }
}

Future<void> _worker(List<Object> arguments) async {
  final ready = arguments[0] as SendPort;
  late NativeApi api;
  var handle = 0;
  try {
    api = NativeApi(arguments[1] as String);
    final p = api.allocate<Uint64>(8);
    try {
      NativeApi.check(api.bindings.bc_create(p));
      handle = p.value;
    } finally {
      api.free(p);
    }
  } catch (_) {
    ready.send(false);
    return;
  }
  final requests = ReceivePort();
  ready.send(requests.sendPort);
  final b = api.bindings;
  await for (final dynamic message in requests) {
    final reply = message[2] as SendPort;
    try {
      Object? result;
      switch (message[0]) {
        case 'submit':
          final data = message[1] as Map;
          final c = api.allocate<bc_command>(sizeOf<bc_command>());
          try {
            c.ref.struct_size = sizeOf<bc_command>();
            c.ref.abi = BC_ABI;
            c.ref.id = data['id'] as int;
            c.ref.selection = data['selection'] as int;
            c.ref.revision = data['revision'] as int;
            c.ref.type = data['type'] as int;
            c.ref.value = data['value'] as int;
            NativeApi.put(c.ref.path, data['path'] as String, 4096);
            NativeApi.put(c.ref.entry, data['entry'] as String, 128);
            NativeApi.put(c.ref.rendition, data['rendition'] as String, 128);
            result = b.bc_submit(handle, c);
          } finally {
            api.free(c);
          }
        case 'snapshot':
          final s = api.allocate<bc_snapshot>(sizeOf<bc_snapshot>());
          try {
            s.ref.struct_size = sizeOf<bc_snapshot>();
            var status = b.bc_read(handle, s);
            while (status == BC_RETRY) {
              await Future<void>.delayed(const Duration(milliseconds: 1));
              status = b.bc_read(handle, s);
            }
            NativeApi.check(status);
            result = {
              'phase': s.ref.phase,
              'selection': s.ref.selection,
              'revision': s.ref.revision,
              'sequence': s.ref.sequence,
              'watermark': s.ref.watermark,
              'rendition': NativeApi.text(s.ref.rendition, 128),
              'position': s.ref.position_us,
              'duration': s.ref.duration_us,
              'ns': s.ref.sampled_ns,
              'discontinuity': s.ref.discontinuity,
              'entry': NativeApi.text(s.ref.entry, 128),
              'awaiting': s.ref.awaiting_audio != 0,
              'error': s.ref.error,
              'submitted': s.ref.submitted_frames,
              'played': s.ref.played_frames,
              'deviceEpoch': s.ref.device_epoch,
              'pcm': s.ref.pcm_bytes,
              'highWater': s.ref.pcm_high_water,
              'quiescent': s.ref.quiescent != 0,
              'timing': s.ref.timing_quality,
              'backend': s.ref.backend,
              'rate': s.ref.rate,
              'channels': s.ref.channels,
              'bits': s.ref.precision_bits,
            };
          } finally {
            api.free(s);
          }
        case 'events':
          final events = api.allocate<bc_event>(sizeOf<bc_event>() * 256);
          final count = api.allocate<Uint32>(4);
          try {
            NativeApi.check(b.bc_poll(handle, events, 256, count));
            result = [
              for (var i = 0; i < count.value; i++)
                {
                  'sequence': events[i].sequence,
                  'id': events[i].command_id,
                  'selection': events[i].selection,
                  'revision': events[i].revision,
                  'entry': NativeApi.text(events[i].entry, 128),
                  'outcome': events[i].outcome,
                  'error': events[i].error,
                  'adopted': events[i].kind == BC_EVENT_ADOPTED,
                },
            ];
          } finally {
            api.free(count);
            api.free(events);
          }
        case 'ack':
          NativeApi.check(b.bc_ack(handle, message[1] as int));
        case 'clock':
          result = b.bc_monotonic_ns();
        case 'close':
          NativeApi.check(b.bc_shutdown(handle));
          final s = api.allocate<bc_snapshot>(sizeOf<bc_snapshot>());
          try {
            final deadline = DateTime.now().add(const Duration(seconds: 10));
            while (true) {
              s.ref.struct_size = sizeOf<bc_snapshot>();
              if (b.bc_read(handle, s) == BC_OK && s.ref.quiescent != 0) break;
              if (DateTime.now().isAfter(deadline))
                throw StateError('Shutdown pending; native ownership retained');
              await Future<void>.delayed(const Duration(milliseconds: 5));
            }
            NativeApi.check(b.bc_destroy(handle));
          } finally {
            api.free(s);
          }
          reply.send([true, null]);
          requests.close();
          return;
        default:
          throw ArgumentError('Unknown native operation');
      }
      reply.send([true, result]);
    } catch (error) {
      // Stable categories only. Never return source paths/URLs/credentials.
      reply.send([
        false,
        error is FormatException ? 'invalid argument' : 'native failure',
      ]);
    }
  }
}
