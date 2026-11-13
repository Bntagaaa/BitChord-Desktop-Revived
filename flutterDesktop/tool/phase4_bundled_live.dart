// Anonymous integration evidence: multiple catalog selections plus repeat and
// exact-rendition renewal. No credentials, signed URLs, ids or raw stderr saved.
// An upstream datacenter/bot refusal is recorded as a failure, never green audio.
import 'dart:convert';
import 'dart:io';

import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/data/youtube_resolver.dart';
import '../lib/features/online/domain/catalog.dart';

Future<void> main(List<String> args) async {
  final report = <String, Object?>{
    'mode': 'anonymous bundled rewrite',
    'media': 'UNVERIFIED',
  };
  final scope = RequestScope();
  final trials = <Map<String, Object?>>[];
  try {
    if (args.length != 1) throw const FormatException();
    final detected = YouTubeResolver.fromBundleDirectory(
      Directory(args.single),
    );
    if (detected == null) throw const FormatException();
    final resolver = YouTubeResolver(
      detected.executable,
      node: detected.node,
      poTokenPluginDirectory: detected.poTokenPluginDirectory,
      poTokenServerHome: detected.poTokenServerHome,
      bundled: true,
      timeout: const Duration(seconds: 30),
    );
    report['bundle'] = 'DETECTED';
    final youtube = YouTubeMusic(
      const ProviderHttp(timeout: Duration(seconds: 15)),
      resolver: resolver,
    );
    final search = await youtube.search(
      'Beethoven',
      scope,
      filter: SearchFilter.songs,
    );
    report['catalog_entries'] = search.entries.length;
    final tracks = search.entries
        .where((entry) => entry.kind == CatalogKind.track)
        .take(3)
        .toList();
    if (tracks.isEmpty)
      throw const ProviderFailure(ProviderProblem.youtubeUnavailable);
    for (var index = 0; index < tracks.length; index++) {
      final trial = <String, Object?>{
        'selection': index,
        'result': 'UNVERIFIED',
      };
      try {
        final first = await youtube.resolve(
          tracks[index],
          AudioQuality.medium,
          scope,
        );
        trial['result'] = 'RANGE_VERIFIED';
        trial['resolver'] = resolver.lastReport;
        if (index == 0) {
          // Simulate an expired location request without changing media bytes.
          final next = await youtube.renew(
            tracks[index],
            AudioQuality.medium,
            first,
            scope,
            generation: youtube.accountGeneration,
          );
          trial['same_rendition_renewal'] = next.rendition == first.rendition;
        }
      } on ProviderFailure catch (failure) {
        trial['result'] = 'REFUSED_OR_UNAVAILABLE';
        trial['failure'] = failure.problem.name;
        trial['resolver'] = resolver.lastReport;
        if (failure.status != null) trial['http_status'] = failure.status;
      }
      trials.add(trial);
    }
    report['media'] = trials.every((t) => t['result'] == 'RANGE_VERIFIED')
        ? 'MULTI_TRACK_RANGES_VERIFIED'
        : 'REFUSED_OR_PARTIAL';
    report['native_audibility'] = 'NOT_TESTED';
  } on ProviderFailure catch (failure) {
    report['media'] = 'REFUSED_OR_UNAVAILABLE';
    report['failure'] = failure.problem.name;
    if (failure.status != null) report['http_status'] = failure.status;
  } catch (error) {
    report['media'] = 'TOOL_UNAVAILABLE';
    report['failure_type'] = error.runtimeType.toString();
  } finally {
    scope.cancel();
  }
  report['trials'] = trials;
  stdout.writeln(jsonEncode(report));
}
