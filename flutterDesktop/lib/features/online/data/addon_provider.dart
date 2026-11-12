import '../domain/catalog.dart';
import 'provider_http.dart';

// Kotlin reference: DesktopAddon.kt's three GET endpoints. No JS host or
// guessed matching of unrelated recordings is hidden behind this adapter.
final class AddonProvider implements CatalogProvider {
  AddonProvider(this.id, Uri base, this.http) : base = normalize(base);
  @override
  final String id;
  final Uri base;
  final ProviderHttp http;
  static Uri normalize(Uri input) {
    if (!['http', 'https'].contains(input.scheme) ||
        input.host.isEmpty ||
        input.userInfo.isNotEmpty ||
        input.hasFragment ||
        input.hasQuery) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    var path = input.path.replaceFirst(RegExp(r'/+$'), '');
    if (path.split('/').last.toLowerCase().endsWith('.json'))
      path = path.substring(0, path.lastIndexOf('/'));
    return input.replace(path: '$path/');
  }

  Map<String, dynamic>? _manifest;
  Uri _endpoint(List<String> parts, [Map<String, String> query = const {}]) =>
      base.replace(
        pathSegments: [
          ...base.pathSegments.where((s) => s.isNotEmpty),
          ...parts,
        ],
        queryParameters: query.isEmpty ? null : query,
      );
  Future<Map<String, dynamic>> manifest(RequestScope scope) async {
    final value =
        _manifest ?? await http.json(_endpoint(['manifest.json']), scope);
    scope.check();
    _manifest = value;
    return value;
  }

  Future<Map<String, String>> _settings(
    AudioQuality quality,
    RequestScope scope,
  ) async {
    final m = await manifest(scope);
    final settings = (m['settings'] as List? ?? []).whereType<Map>();
    return {
      for (final s in settings)
        if (s['key'] is String && s['default'] != null)
          s['key'] as String: '${s['default']}',
      'quality': quality.name.toUpperCase(),
    };
  }

  @override
  Future<CatalogPage> search(
    String query,
    RequestScope scope, {
    String? continuation,
  }) async {
    if (continuation != null)
      throw const ProviderFailure(ProviderProblem.unsupported);
    final m = await manifest(scope);
    final resources = m['resources'] as List? ?? [];
    if (resources.isNotEmpty &&
        !resources.any((r) => '$r'.toLowerCase() == 'search'))
      throw const ProviderFailure(ProviderProblem.unsupported);
    final data = await http.json(
      _endpoint(
        ['search'],
        {...await _settings(AudioQuality.high, scope), 'q': query.trim()},
      ),
      scope,
    );
    final rows = data['tracks'];
    if (rows is! List) throw const ProviderFailure(ProviderProblem.malformed);
    return CatalogPage(
      rows
          .whereType<Map>()
          .where((r) => r['id'] is String && r['title'] is String)
          .take(400)
          .map(
            (r) => CatalogEntry(
              provider: id,
              id: r['id'] as String,
              title: r['title'] as String,
              subtitle: r['artist'] as String? ?? '',
              durationSeconds: (r['duration'] as num?)?.toInt(),
              artwork: Uri.tryParse(
                '${r['artworkURL'] ?? r['albumArtworkURL'] ?? ''}',
              ),
            ),
          ),
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
      _endpoint(['stream', track.id], await _settings(quality, scope)),
      scope,
    );
    if (data['encrypted'] != null &&
        ![false, 'false', 'none', ''].contains(data['encrypted']))
      throw const ProviderFailure(ProviderProblem.unsupported);
    final url = Uri.tryParse(data['url'] as String? ?? '');
    if (url == null || url.host.isEmpty)
      throw const ProviderFailure(ProviderProblem.unavailable);
    final m = await manifest(scope);
    final stream = ResolvedMedia(
      provider: id,
      trackId: track.id,
      rendition: '$id:${quality.name}',
      url: url,
      downloadsAllowed: ![false, 0, '0'].contains(m['allowDownloads']),
      manifest: ['hls', 'dash', 'm3u8', 'mpd'].contains(
        '${data['manifest'] ?? data['mediaType'] ?? data['format']}'
            .toLowerCase(),
      ),
    );
    stream.check();
    return stream;
  }
}
