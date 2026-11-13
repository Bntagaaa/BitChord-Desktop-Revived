import '../domain/catalog.dart';
import 'provider_http.dart';
import 'youtube/accounts.dart';
import 'youtube/catalog_client.dart';
import 'youtube/catalog_parser.dart';
import 'youtube_resolver.dart';
import 'youtube_session.dart';

// Public provider boundary retained for the existing UI. Catalog, account
// lifecycle, extraction, deployment and format policy no longer share one class.
final class YouTubeMusic implements CatalogProvider {
  YouTubeMusic(this.http, {Uri? origin, this.resolver})
    : origin = origin ?? Uri.parse(YouTubeSession.origin) {
    _catalog = YouTubeCatalogClient(http, _accounts, this.origin);
  }
  final ProviderHttp http;
  final Uri origin;
  final YouTubeResolver? resolver;
  final _accounts = YouTubeAccounts();
  late final YouTubeCatalogClient _catalog;
  @override
  String get id => 'youtube';
  bool get signedIn => _accounts.signedIn;
  int get accountGeneration => _accounts.generation;

  Future<void> signIn(String cookie, RequestScope scope) {
    if (origin.origin != YouTubeSession.origin) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    return _accounts.signIn(cookie, http, scope);
  }

  void signOut() => _accounts.signOut();

  @override
  Future<CatalogPage> search(
    String query,
    RequestScope scope, {
    String? continuation,
    SearchFilter filter = SearchFilter.all,
  }) => _catalog.call('search', {
    if (continuation == null) 'query': query.trim(),
    if (continuation == null && filter != SearchFilter.all)
      'params': switch (filter) {
        SearchFilter.songs => 'EgWKAQIIAWoKEAkQChAFEAMQBA==',
        SearchFilter.albums => 'EgWKAQIYAWoKEAkQChAFEAMQBA==',
        SearchFilter.artists => 'EgWKAQIgAWoKEAkQChAFEAMQBA==',
        SearchFilter.playlists => 'EgWKAQIoAWoKEAkQChAFEAMQBA==',
        SearchFilter.all => '',
      },
    if (continuation != null) 'continuation': continuation,
  }, scope);

  @override
  Future<CatalogPage> browse(
    String browseId,
    RequestScope scope, {
    String? continuation,
    String? params,
  }) => _catalog.call('browse', {
    if (continuation == null) 'browseId': browseId,
    if (params != null) 'params': params,
    if (continuation != null) 'continuation': continuation,
  }, scope);

  @override
  Future<ResolvedMedia> resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope scope,
  ) => _resolve(track, quality, scope);

  // A renewed URL must identify exactly the same encoded rendition. Selecting
  // a different format halfway through a ranged file corrupts decoder input.
  Future<ResolvedMedia> renew(
    CatalogEntry track,
    AudioQuality quality,
    ResolvedMedia previous,
    RequestScope scope, {
    required int generation,
  }) {
    if (generation != accountGeneration)
      throw const ProviderFailure(ProviderProblem.cancelled);
    if (previous.provider != id || previous.trackId != track.id) {
      throw const ProviderFailure(ProviderProblem.integrity);
    }
    return _resolve(track, quality, scope, rendition: previous.rendition);
  }

  Future<ResolvedMedia> _resolve(
    CatalogEntry track,
    AudioQuality quality,
    RequestScope parent, {
    String? rendition,
  }) => _accounts.run(parent, (scope, session) async {
    final resolver = this.resolver;
    if (resolver == null)
      throw const ProviderFailure(ProviderProblem.youtubeRuntimeUnavailable);
    // A delegated/multi-account context must not be silently switched to the
    // cookie jar's default account. Explicit primary-account media only until
    // authenticated multi-account selection has its own verified contract.
    if (session != null &&
        (session.authUser != '0' || session.pageId != null)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    return resolver.resolve(
      track,
      quality,
      scope,
      cookie: session?.headers()['Cookie'],
      requiredRendition: rendition,
    );
  });

  // Compatibility entrypoints for reference fixtures and existing consumers.
  static Iterable<Map<String, dynamic>> nodes(Object? value, String key) =>
      YouTubeCatalogParser.nodes(value, key);
  static String text(Object? value) => YouTubeCatalogParser.text(value);
  static CatalogPage parsePage(Map<String, dynamic> data, {String? browseId}) =>
      YouTubeCatalogParser.parsePage(data, browseId: browseId);
}
