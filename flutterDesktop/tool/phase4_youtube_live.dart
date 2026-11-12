import 'dart:convert';
import '../lib/features/online/data/artwork_loader.dart';
import 'dart:io';
import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/data/youtube_resolver.dart';
import '../lib/features/online/data/source_broker.dart';
import '../lib/features/online/domain/catalog.dart';

// Anonymous opt-in check. No cookies, payloads, signed locations or ids logged.
// Args: absolute yt-dlp, optional absolute node, optional native probe + endpoint.
Future<void> main(List<String> args) async {
  final scope = RequestScope();
  final report = <String, Object?>{'authenticated': false};
  SourceBroker? broker;
  try {
    final provider = YouTubeMusic(
      const ProviderHttp(),
      resolver: args.isEmpty
          ? null
          : YouTubeResolver(
          args[0],
          node: args.length > 1 ? args[1] : null,
          poTokenPluginDirectory:
              Platform.environment['BITCHORD_YTDLP_POT_PLUGIN_DIR'],
          timeout: const Duration(seconds: 90),
        ),
    );
    final home = await provider.browse('FEmusic_home', scope);
    report['home'] = {
      'entries': home.entries.length,
      'shelves': home.shelves.length,
      'artwork': home.entries.where((e) => e.artwork != null).length,
    };
    final image = home.entries
        .where((e) => e.artwork != null)
        .firstOrNull
        ?.artwork;
    if (image != null) {
      try {
        report['first_artwork_bytes'] = (await ArtworkLoader().load(
          image,
          scope,
        )).length;
      } on ProviderFailure catch (e) {
        report['artwork_failure'] = {
          'failure': e.problem.name,
          'status': e.status,
        };
      }
    }
    final songs = await provider.search(
      'Beethoven',
      scope,
      filter: SearchFilter.songs,
    );
    report['songs'] = songs.entries.length;
    for (final filter in [
      SearchFilter.albums,
      SearchFilter.artists,
      SearchFilter.playlists,
    ]) {
      try {
        final page = await provider.search('Beethoven', scope, filter: filter);
        final target = page.entries
            .where((e) => e.kind != CatalogKind.track)
            .firstOrNull;
        report[filter.name] = {'entries': page.entries.length};
        if (target != null) {
          final detail = await provider.browse(
            target.id,
            scope,
            params: target.params,
          );
          report['${filter.name}_detail'] = {
            'entries': detail.entries.length,
            'shelves': detail.shelves.length,
            'header': detail.header != null,
            'continuation': detail.continuation != null,
          };
        }
      } on ProviderFailure catch (e) {
        report[filter.name] = {'failure': e.problem.name, 'status': e.status};
      }
    }
    if (args.isNotEmpty) {
      final media = await provider.resolve(
        songs.entries.firstWhere((e) => e.kind == CatalogKind.track),
        AudioQuality.high,
        scope,
      );
      report['media'] = 'RANGE_VERIFIED';
      if (args.length > 3) {
        broker = await SourceBroker.open(media);
        final trial = await Process.run(args[2], [
          '--timeout',
          '3',
          broker.path,
          args[3],
        ]);
        final native = jsonDecode('${trial.stdout}') as Map<String, dynamic>;
        report['native'] = {'exit': trial.exitCode, ...native};
      }
    }
  } on ProviderFailure catch (e) {
    report['failure'] = e.problem.name;
    report['http_status'] = e.status;
  } catch (e) {
    report['failure_type'] = e.runtimeType.toString();
  } finally {
    scope.cancel();
    await broker?.close();
  }
  stdout.writeln(jsonEncode(report));
}
