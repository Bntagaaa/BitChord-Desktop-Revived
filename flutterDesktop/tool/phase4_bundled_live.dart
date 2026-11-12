// Optional public-media integration check for the built-in portable runtime.
// Never logs video ids, URLs, tokens, extractor logs, cookies or audio data.
// A datacenter IP/media policy may refuse access even with valid PO Tokens.
import 'dart:convert';
import 'dart:io';

import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/data/youtube_resolver.dart';
import '../lib/features/online/domain/catalog.dart';

Future<void> main(List<String> args) async {
  final report = <String, Object?>{
    'mode': 'anonymous bundled runtime',
    'media': 'UNVERIFIED',
  };
  final scope = RequestScope();
  try {
    if (args.length != 1) throw const FormatException();
    final resolver = YouTubeResolver.fromBundleDirectory(
      Directory(args.single),
    );
    if (resolver == null) throw const FormatException();
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
    final track = search.entries
        .where((entry) => entry.kind == CatalogKind.track)
        .firstOrNull;
    if (track == null) {
      throw const ProviderFailure(ProviderProblem.unavailable);
    }
    // resolve() checks actual HTTP 206 ranges (including beyond 1 MiB) before
    // deeming the source valid. A successful extractor exit alone is not proof.
    await youtube.resolve(track, AudioQuality.medium, scope);
    report['media'] = 'RANGE_VERIFIED';
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
  stdout.writeln(jsonEncode(report));
}
