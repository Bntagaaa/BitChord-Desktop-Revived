import 'dart:async';
import 'dart:convert';

import '../../domain/catalog.dart';
import '../provider_http.dart';
import '../youtube_session.dart';
import 'accounts.dart';
import 'catalog_parser.dart';

final class _CatalogContext {
  const _CatalogContext(
    this.version,
    this.visitor,
    this.generation,
    this.expires,
  );
  final String version;
  final String? visitor;
  final int generation;
  final DateTime expires;
}

// Only idempotent catalog reads retry; account mutations must not be replayed
// blindly. Guest visitor/client context is bootstrapped together and expires.
final class YouTubeCatalogClient {
  YouTubeCatalogClient(this.http, this.accounts, this.origin);
  final ProviderHttp http;
  final YouTubeAccounts accounts;
  final Uri origin;
  _CatalogContext? _context;

  // Reuse a healthy anonymous catalog context without fetching another shell
  // for every playback. Account changes/expiry cannot leak an old visitor.
  String? get guestVisitorData {
    final value = _context;
    if (accounts.signedIn ||
        value == null ||
        value.generation != accounts.generation ||
        !value.expires.isAfter(DateTime.now()))
      return null;
    return value.visitor;
  }

  Future<_CatalogContext> _bootstrap(
    RequestScope scope,
    YouTubeSession? session,
  ) async {
    final saved = _context;
    if (saved != null &&
        saved.generation == accounts.generation &&
        saved.expires.isAfter(DateTime.now()))
      return saved;
    if (session != null && origin.origin != YouTubeSession.origin) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final response = await http.request(
      origin,
      scope,
      headers: {
        'Accept-Language': 'en-US,en;q=0.9',
        if (session != null) ...session.headers(),
      },
      maxBytes: 3 * 1024 * 1024,
    );
    if (response.status == 429)
      throw const ProviderFailure(ProviderProblem.youtubeRateLimited, 429);
    if ([401, 403].contains(response.status))
      throw ProviderFailure(ProviderProblem.unauthorized, response.status);
    if (response.status != 200)
      throw ProviderFailure(
        ProviderProblem.youtubeUnavailable,
        response.status,
      );
    final html = utf8.decode(response.bytes);
    if (session != null &&
        RegExp(
              r'"LOGGED_IN"\s*:\s*(true|false)\b',
            ).firstMatch(html)?.group(1) !=
            'true') {
      throw const ProviderFailure(ProviderProblem.unauthorized);
    }
    final version = YouTubeSession.value(html, 'INNERTUBE_CLIENT_VERSION');
    if (version == null) throw const ProviderFailure(ProviderProblem.malformed);
    scope.check();
    final value = _CatalogContext(
      version,
      YouTubeSession.value(html, 'VISITOR_DATA'),
      accounts.generation,
      DateTime.now().add(const Duration(minutes: 30)),
    );
    _context = value;
    return value;
  }

  Future<CatalogPage> call(
    String endpoint,
    Map<String, Object?> body,
    RequestScope parent,
  ) async => YouTubeCatalogParser.parsePage(
    await request(endpoint, body, parent),
    browseId: body['browseId'] as String?,
    search: endpoint == 'search',
  );

  Future<Map<String, dynamic>> request(
    String endpoint,
    Map<String, Object?> body,
    RequestScope parent,
  ) async {
    if (![
      'search',
      'browse',
      'music/get_search_suggestions',
    ].contains(endpoint))
      throw const ProviderFailure(ProviderProblem.unsupported);
    return accounts.run(parent, (scope, session) async {
      try {
        for (var attempt = 0; attempt < 2; attempt++) {
          HttpPayload response;
          try {
            final context = await _bootstrap(scope, session);
            response = await http.request(
              origin.resolve('/youtubei/v1/$endpoint?prettyPrint=false'),
              scope,
              method: 'POST',
              headers: {
                'Origin': origin.origin,
                'X-YouTube-Client-Name': '67',
                'X-YouTube-Client-Version': context.version,
                if (context.visitor != null)
                  'X-Goog-Visitor-Id': context.visitor!,
                if (session != null) ...session.headers(),
              },
              jsonBody: {
                'context': {
                  'client': {
                    'clientName': 'WEB_REMIX',
                    'clientVersion': context.version,
                    'hl': 'en',
                    'gl': 'US',
                    if (context.visitor != null) 'visitorData': context.visitor,
                  },
                  if (session?.dataSyncId != null)
                    'user': {'onBehalfOfUser': session!.dataSyncId},
                },
                ...body,
              },
            );
          } on ProviderFailure catch (failure) {
            if (attempt == 0 &&
                ([
                      ProviderProblem.network,
                      ProviderProblem.timeout,
                    ].contains(failure.problem) ||
                    (failure.status ?? 0) >= 500)) {
              _context = null;
              await Future<void>.delayed(const Duration(milliseconds: 200));
              scope.check();
              continue;
            }
            rethrow;
          }
          scope.check();
          if (response.status == 429)
            throw const ProviderFailure(
              ProviderProblem.youtubeRateLimited,
              429,
            );
          if ([401, 403].contains(response.status))
            throw ProviderFailure(
              ProviderProblem.unauthorized,
              response.status,
            );
          if (attempt == 0 &&
              (response.status == 400 || response.status >= 500)) {
            _context = null;
            await Future<void>.delayed(const Duration(milliseconds: 200));
            scope.check();
            continue;
          }
          if (response.status != 200)
            throw ProviderFailure(
              ProviderProblem.youtubeUnavailable,
              response.status,
            );
          Object? decoded;
          try {
            decoded = jsonDecode(utf8.decode(response.bytes));
          } catch (_) {
            throw const ProviderFailure(ProviderProblem.malformed);
          }
          if (decoded is! Map<String, dynamic> || decoded['error'] != null) {
            throw const ProviderFailure(ProviderProblem.malformed);
          }
          return decoded;
        }
        throw const ProviderFailure(ProviderProblem.youtubeUnavailable);
      } on ProviderFailure catch (failure) {
        if (failure.problem == ProviderProblem.unauthorized &&
            session != null) {
          accounts.signOut();
          _context = null;
        }
        rethrow;
      }
    });
  }

  /// A non-idempotent, account-bound write. Unlike search/browse, mutations
  /// are never retried: replaying a playlist/create or an add could duplicate
  /// records even when the first response was lost.
  Future<Map<String, dynamic>> mutate(
    String endpoint,
    Map<String, Object?> body,
    RequestScope parent,
  ) {
    if (![
      'playlist/create',
      'playlist/delete',
      'browse/edit_playlist',
    ].contains(endpoint)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    if (origin.origin != YouTubeSession.origin || !accounts.signedIn) {
      throw const ProviderFailure(ProviderProblem.unauthorized);
    }
    return accounts.run(parent, (scope, session) async {
      if (session == null) {
        throw const ProviderFailure(ProviderProblem.unauthorized);
      }
      final context = await _bootstrap(scope, session);
      scope.check();
      final response = await http.request(
        origin.resolve('/youtubei/v1/$endpoint?prettyPrint=false'),
        scope,
        method: 'POST',
        headers: {
          'Origin': origin.origin,
          'X-YouTube-Client-Name': '67',
          'X-YouTube-Client-Version': context.version,
          if (context.visitor != null)
            'X-Goog-Visitor-Id': context.visitor!,
          ...session.headers(),
        },
        jsonBody: {
          'context': {
            'client': {
              'clientName': 'WEB_REMIX',
              'clientVersion': context.version,
              'hl': 'en',
              'gl': 'US',
              if (context.visitor != null) 'visitorData': context.visitor,
            },
            if (session.dataSyncId != null)
              'user': {'onBehalfOfUser': session.dataSyncId},
          },
          ...body,
        },
      );
      scope.check();
      if (response.status == 429) {
        throw const ProviderFailure(ProviderProblem.youtubeRateLimited, 429);
      }
      if ([401, 403].contains(response.status)) {
        accounts.signOut();
        _context = null;
        throw ProviderFailure(ProviderProblem.unauthorized, response.status);
      }
      if (response.status != 200) {
        throw ProviderFailure(ProviderProblem.unavailable, response.status);
      }
      Object? decoded;
      try {
        decoded = jsonDecode(utf8.decode(response.bytes));
      } catch (_) {
        throw const ProviderFailure(ProviderProblem.malformed);
      }
      if (decoded is! Map<String, dynamic> || decoded['error'] != null) {
        throw const ProviderFailure(ProviderProblem.unavailable);
      }
      final status = decoded['status'];
      if (status != null && status != 'STATUS_SUCCEEDED') {
        throw const ProviderFailure(ProviderProblem.unavailable);
      }
      return decoded;
    });
  }

}
