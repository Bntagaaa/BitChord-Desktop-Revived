// Opt-in live provider/stream test. No catalog payloads or signed URLs logged.
import 'dart:convert';
import 'dart:io';
import '../lib/features/online/data/jiosaavn.dart';
import '../lib/features/online/data/download_store.dart';
import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/range_source.dart';
import '../lib/features/online/data/source_broker.dart';
import '../lib/features/online/domain/catalog.dart';

Future<void> main(List<String> args) async {
  if (args.isEmpty) throw ArgumentError('Native library required');
  final scope = RequestScope();
  final provider = JioSaavn(const ProviderHttp(), args[0]);
  final report = <String, Object?>{
    'provider': 'jiosaavn',
    'authenticated': false,
  };
  SourceBroker? broker;
  try {
    final page = await provider.search('Beethoven', scope);
    report['catalog_entries'] = page.entries.length;
    final track = page.entries.first;
    final media = await provider.resolve(track, AudioQuality.high, scope);
    final source = RangeSource(media, const ProviderHttp(), scope);
    report['first_range_bytes'] = (await source.chunk(0)).length;
    report['media_bytes'] = source.total;
    report['validator'] = source.validator != null;
    // Optional actual native playback into an explicitly supplied test endpoint.
    if (args.length > 2) {
      broker = await SourceBroker.open(
        media,
        refresh: () => provider.resolve(track, AudioQuality.high, scope),
      );
      final trial = await Process.run(args[1], [
        '--timeout',
        '1',
        broker.path,
        args[2],
      ]);
      final native = jsonDecode('${trial.stdout}'.trim()) as Map;
      report['native_probe_exit'] = trial.exitCode;
      report['native'] = native;
      report['stream_trial'] =
          trial.exitCode == 4 &&
              native['error'] == 0 &&
              (native['played_frames'] as num) > 0
          ? 'NATIVE_SOFTWARE_SINK_OUTPUT'
          : 'FAILED_OR_BLOCKED';
    }
    if (args.contains('--download') && args.length > 2) {
      final temp = await Directory.systemTemp.createTemp(
        'bitchord-live-download-',
      );
      final downloadScope = RequestScope();
      try {
        final downloadMedia = await provider.resolve(
          track,
          AudioQuality.high,
          downloadScope,
        );
        final data = RangeSource(
          downloadMedia,
          const ProviderHttp(),
          downloadScope,
        );
        await data.chunk(0);
        if (data.total! > 32 * 1024 * 1024)
          throw const ProviderFailure(ProviderProblem.unsupported);
        final store = DownloadStore(temp);
        final saved = await store.save(track, data);
        data.close();
        final offline = await DownloadStore(temp).list();
        report['download_bytes'] = saved.bytes;
        report['offline_reopen_verified'] = offline.length == 1;
        final trial = await Process.run(args[1], [
          '--timeout',
          '1',
          saved.path,
          args[2],
        ]);
        final native = jsonDecode('${trial.stdout}'.trim()) as Map;
        report['offline_native'] = native;
        report['offline_trial'] =
            trial.exitCode == 4 &&
                native['error'] == 0 &&
                (native['played_frames'] as num) > 0
            ? 'NATIVE_SOFTWARE_SINK_OUTPUT'
            : 'FAILED_OR_BLOCKED';
      } finally {
        downloadScope.cancel();
        await temp.delete(recursive: true);
      }
    }
  } on ProviderFailure catch (e) {
    report['failure'] = e.problem.name;
    report['http_status'] = e.status;
  } finally {
    scope.cancel();
    await broker?.close();
  }
  stdout.writeln(jsonEncode(report));
}
