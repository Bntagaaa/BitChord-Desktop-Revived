import 'dart:isolate';
import '../../../core/platform/audio/native_api.dart';
import '../domain/catalog.dart';
import 'provider_http.dart';

final class JioSaavn implements CatalogProvider {
  JioSaavn(this.http, this.library, {Uri? endpoint})
    : endpoint = endpoint ?? Uri.parse('https://www.jiosaavn.com/api.php');
  final ProviderHttp http;
  final String library;
  final Uri endpoint;
  @override
  String get id => 'jiosaavn';
  Uri _url(String call, Map<String, String> args) => endpoint.replace(
    queryParameters: {
      '__call': call,
      '_format': 'json',
      '_marker': '0',
      'api_version': '4',
      'ctx': 'android',
      ...args,
    },
  );
  static String _decodeText(String value) => value
      .replaceAll('&amp;', '&')
      .replaceAll('&#039;', "'")
      .replaceAll('&quot;', '"');
  @override
  Future<CatalogPage> search(
    String query,
    RequestScope scope, {
    String? continuation,
    SearchFilter filter = SearchFilter.all,
  }) async {
    if (filter != SearchFilter.all && filter != SearchFilter.songs)
      throw const ProviderFailure(ProviderProblem.unsupported);
    final page = continuation == null ? 1 : int.tryParse(continuation);
    if (page == null || page < 1 || page > 100)
      throw const ProviderFailure(ProviderProblem.malformed);
    final data = await http.json(
      _url('search.getResults', {'q': query.trim(), 'p': '$page', 'n': '25'}),
      scope,
      headers: {
        'Cookie': 'explicit_content=1',
        'Accept-Language': 'en-IN,en;q=0.9',
      },
    );
    final rows = data['results'];
    if (rows is! List) throw const ProviderFailure(ProviderProblem.malformed);
    final entries = <CatalogEntry>[];
    for (final row in rows.whereType<Map>()) {
      final info = row['more_info'] as Map? ?? {};
      if (row['id'] is! String || row['title'] is! String) continue;
      final artists =
          (info['artistMap'] as Map?)?['primary_artists'] as List? ?? [];
      entries.add(
        CatalogEntry(
          provider: id,
          id: row['id'] as String,
          title: _decodeText(row['title'] as String),
          subtitle: artists
              .whereType<Map>()
              .map((a) => _decodeText('${a['name'] ?? ''}'))
              .join(', '),
          durationSeconds: int.tryParse('${info['duration']}'),
          artwork: Uri.tryParse('${row['image'] ?? ''}'),
        ),
      );
    }
    return CatalogPage(
      entries,
      continuation: rows.length == 25 ? '${page + 1}' : null,
    );
  }

  @override
  Future<CatalogPage> browse(
    String browseId,
    RequestScope scope, {
    String? continuation,
    String? params,
  }) async => throw const ProviderFailure(ProviderProblem.unsupported);
  @override
  Future<ResolvedMedia> resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope scope,
  ) async {
    if (track.provider != id)
      throw const ProviderFailure(ProviderProblem.unsupported);
    final data = await http.json(
      _url('song.getDetails', {'pids': track.id}),
      scope,
      headers: {'Cookie': 'explicit_content=1'},
    );
    final row = data['songs'] is List
        ? (data['songs'] as List).firstOrNull
        : data.values.whereType<Map>().firstOrNull;
    if (row is! Map || row['more_info'] is! Map)
      throw const ProviderFailure(ProviderProblem.malformed);
    final info = row['more_info'] as Map;
    final encrypted = info['encrypted_media_url'];
    if (encrypted is! String || encrypted.length > 8192)
      throw const ProviderFailure(ProviderProblem.malformed);
    String url;
    try {
      url = await Isolate.run(() => NativeApi(library).saavnUrl(encrypted));
    } catch (_) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    scope.check();
    final supports320 = '${info['320kbps']}'.toLowerCase() == 'true';
    if (supports320 &&
        [AudioQuality.high, AudioQuality.lossless].contains(quality)) {
      url = url.replaceFirstMapped(
        RegExp(r'_(48|96|160|320)\.(mp4|aac|mp3)$'),
        (m) => '_320.${m[2]}',
      );
    }
    final media = ResolvedMedia(
      provider: id,
      trackId: track.id,
      rendition: 'jiosaavn:${quality.name}',
      url: Uri.parse(url),
    );
    media.check();
    return media;
  }
}
