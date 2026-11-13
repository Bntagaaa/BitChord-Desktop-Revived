"""Reviewed source integration; staging only, removed before main handoff."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def edit(path, before, after):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if before not in text:
        if after in text: return
        raise SystemExit('Unexpected source: ' + path)
    if text.count(before) != 1: raise SystemExit('Ambiguous edit: ' + path)
    p.write_text(text.replace(before, after), encoding='utf-8')

def append_method(path, method):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if method.strip() in text: return
    if not text.endswith('}\n'): raise SystemExit('Unexpected class end: ' + path)
    p.write_text(text[:-2] + method + '}\n', encoding='utf-8')

for path in ['flutterDesktop/test/account_store_test.dart', 'flutterDesktop/test/account_browser_protocol_test.dart']:
    p = ROOT / path
    p.write_text(p.read_text(encoding='utf-8').replace("import 'dart:async';\n", ''), encoding='utf-8')

edit('flutterDesktop/lib/features/online/domain/catalog.dart',
    '  youtubeRateLimited,\n}', '  youtubeRateLimited,\n  rateLimited,\n}')
edit('flutterDesktop/lib/features/online/domain/catalog.dart',
    "    if (problem == ProviderProblem.youtubeRateLimited) {",
    "    if (problem == ProviderProblem.rateLimited) {\n      return 'This service is rate-limiting requests. Wait before trying again.';\n    }\n    if (problem == ProviderProblem.youtubeRateLimited) {")
edit('flutterDesktop/lib/features/online/domain/catalog.dart',
    '    this.isVideo = false,\n', '    this.isVideo = false,\n    this.setVideoId,\n')
edit('flutterDesktop/lib/features/online/domain/catalog.dart',
    '  final bool isVideo;\n', '  final bool isVideo;\n  // Playlist occurrence identity: duplicates must not be removed by video id.\n  final String? setVideoId;\n')
edit('flutterDesktop/lib/features/online/data/youtube/catalog_parser.dart',
    '      isVideo: browseId == null && isVideo,\n',
    "      isVideo: browseId == null && isVideo,\n      setVideoId: string(map(row['playlistItemData'])?['playlistSetVideoId']) ?? string(map(row['playlistItemData'])?['setVideoId']),\n")
edit('flutterDesktop/lib/features/online/domain/library_port.dart',
    "import 'catalog.dart';", "import 'catalog.dart';\nimport 'collection_actions.dart';")
edit('flutterDesktop/lib/features/online/domain/library_port.dart',
    'this.rating, this.addToken, this.removeToken, this.historyToken',
    'this.rating, this.inLibrary, this.addToken, this.removeToken, this.historyToken')
edit('flutterDesktop/lib/features/online/domain/library_port.dart',
    '  final TrackRating? rating;', '  final TrackRating? rating;\n  final bool? inLibrary;')
edit('flutterDesktop/lib/features/online/domain/library_port.dart',
    '  Future<SongActions> songActions(String videoId, RequestScope scope);',
    '  Future<SongActions> songActions(String videoId, RequestScope scope);\n  Future<CollectionActions> collectionActions(String browseId, RequestScope scope);')

edit('flutterDesktop/lib/features/online/data/account_coordinator.dart',
    'final scope = RequestScope(), detach = parent.onCancel(() {});\n      detach();',
    'final scope = RequestScope();')
edit('flutterDesktop/lib/features/online/data/account_coordinator.dart',
    'try { final value = await work(scope); scope.check(); return value; }\n      finally',
    'try { final value = await work(scope); scope.check(); return value; }\n      catch (_) { if (scope.cancelled) _restored = false; rethrow; }\n      finally')
edit('flutterDesktop/lib/features/online/data/account_coordinator.dart',
    '// Probe protected storage before opening the browser. Do not complete a\n    // login only to silently fall back to unprotected cookies on disk.',
    '// Storage availability is checked on load and again on commit. No\n    // unprotected fallback is permitted if it becomes locked during login.')

edit('flutterDesktop/lib/features/online/data/youtube/accounts.dart',
    '  Future<void> signIn(\n',
    '  // The coordinator verifies the server session before secure persistence.\n  void adoptVerified(YouTubeSession session) {\n    signOut();\n    _session = session;\n  }\n\n  Future<void> signIn(\n')
edit('flutterDesktop/lib/features/online/data/youtube_music.dart',
    "import 'youtube/catalog_parser.dart';",
    "import 'youtube/catalog_parser.dart';\nimport 'youtube/library_client.dart';")
edit('flutterDesktop/lib/features/online/data/youtube_music.dart',
    '    _catalog = YouTubeCatalogClient(http, _accounts, this.origin);',
    '    _catalog = YouTubeCatalogClient(http, _accounts, this.origin);\n    library = YouTubeLibrary(read: _catalog.request, write: _catalog.mutate, signedIn: () => signedIn);')
edit('flutterDesktop/lib/features/online/data/youtube_music.dart',
    '  late final YouTubeCatalogClient _catalog;',
    '  late final YouTubeCatalogClient _catalog;\n  late final YouTubeLibrary library;\n  void adoptVerified(YouTubeSession session) => _accounts.adoptVerified(session);')
edit('flutterDesktop/lib/features/online/data/youtube/catalog_client.dart',
    "import '../../domain/catalog.dart';",
    "import '../../domain/catalog.dart';\nimport '../../domain/collection_actions.dart';")
edit('flutterDesktop/lib/features/online/data/youtube/catalog_client.dart',
    "      'music/get_search_suggestions',\n", "      'music/get_search_suggestions',\n      'next',\n      'account/account_menu',\n      'account/accounts_list',\n")
append_method('flutterDesktop/lib/features/online/data/youtube/catalog_client.dart', r'''
  // User-authorized account writes are never replayed automatically. A network
  // failure after transmission is an uncertain outcome, not permission to retry
  // a playlist creation/add/remove against a possibly already-mutated library.
  Future<Map<String, dynamic>> mutate(
    String endpoint,
    Map<String, Object?> body,
    RequestScope parent,
  ) async {
    const writes = {'playlist/create', 'playlist/delete', 'browse/edit_playlist',
      'like/like', 'like/dislike', 'like/removelike', 'feedback',
      'subscription/subscribe', 'subscription/unsubscribe'};
    if (!writes.contains(endpoint) || body.containsKey('context') ||
        origin.origin != YouTubeSession.origin || origin.userInfo.isNotEmpty) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    return accounts.run(parent, (scope, session) async {
      if (session == null) throw const ProviderFailure(ProviderProblem.loginRequired);
      final context = await _bootstrap(scope, session);
      HttpPayload response;
      try {
        response = await http.request(
          origin.resolve('/youtubei/v1/$endpoint?prettyPrint=false'), scope,
          method: 'POST',
          headers: {'Origin': origin.origin, 'X-YouTube-Client-Name': '67',
            'X-YouTube-Client-Version': context.version,
            if (context.visitor != null) 'X-Goog-Visitor-Id': context.visitor!,
            ...session.headers()},
          targetAllowed: (uri) => uri.origin == origin.origin && uri.path == '/youtubei/v1/$endpoint' && uri.userInfo.isEmpty,
          jsonBody: {...body, 'context': {
            'client': {'clientName': 'WEB_REMIX', 'clientVersion': context.version,
              'hl': 'en', 'gl': 'US', if (context.visitor != null) 'visitorData': context.visitor},
            if (session.dataSyncId != null) 'user': {'onBehalfOfUser': session.dataSyncId},
          }},
        );
      } on ProviderFailure catch (failure) {
        if (failure.problem == ProviderProblem.cancelled) rethrow;
        throw const LibraryWriteUnconfirmed();
      }
      scope.check();
      if ([401, 403].contains(response.status)) {
        accounts.signOut(); _context = null;
        throw ProviderFailure(ProviderProblem.unauthorized, response.status);
      }
      if (response.status == 429) throw const ProviderFailure(ProviderProblem.youtubeRateLimited, 429);
      if (response.status >= 500) throw const LibraryWriteUnconfirmed();
      if (response.status != 200) throw ProviderFailure(ProviderProblem.unavailable, response.status);
      Object? decoded;
      try { decoded = jsonDecode(utf8.decode(response.bytes)); }
      catch (_) { throw const LibraryWriteUnconfirmed(); }
      if (decoded is! Map<String, dynamic>) throw const LibraryWriteUnconfirmed();
      if (decoded['error'] != null || (decoded['status'] != null && decoded['status'] != 'STATUS_SUCCEEDED')) {
        throw const ProviderFailure(ProviderProblem.unavailable);
      }
      return decoded;
    });
  }
''')

p = ROOT / 'flutterDesktop/lib/features/online/data/youtube/library_client.dart'
text = p.read_text(encoding='utf-8')
text = text.replace('YouTubeCatalogParser.nodes(', '_maps(')
text = text.replace('    TrackRating? rating;\n', '    TrackRating? rating;\n    bool? inLibrary;\n')
text = text.replace("          final defaultAdds = icon == 'LIBRARY_ADD';", "          final defaultAdds = icon == 'LIBRARY_ADD';\n          inLibrary = !defaultAdds;")
text = text.replace('return SongActions(rating: rating,', 'return SongActions(rating: rating, inLibrary: inLibrary,')
text = text.replace('    bool? saved, subscribed;\n', '    bool? saved, subscribed;\n    var hasBookmark = false;\n')
text = text.replace("      if ((_map(toggle['defaultIcon'])['iconType'] == 'BOOKMARK_BORDER' || _map(toggle['toggledIcon'])['iconType'] == 'BOOKMARK') && toggle['isDisabled'] != true) saved = toggle['isToggled'] == true;", "      if (_map(toggle['defaultIcon'])['iconType'] == 'BOOKMARK_BORDER' || _map(toggle['toggledIcon'])['iconType'] == 'BOOKMARK') {\n        hasBookmark = true;\n        if (toggle['isDisabled'] != true) saved = toggle['isToggled'] == true;\n      }")
text = text.replace('(buttons.isNotEmpty && saved == null)', '(buttons.isNotEmpty && !hasBookmark)')
# Library traversal is bounded and explicit; CatalogParser.nodes requires a
# renderer key and must not be misused as an unbounded all-node visitor.
method = r'''
  static Iterable<Map<String, dynamic>> _maps(Object? root) sync* {
    final stack = <({Object? value, int depth})>[(value: root, depth: 0)];
    var visited = 0;
    while (stack.isNotEmpty) {
      final current = stack.removeLast();
      if (++visited > 30000 || current.depth > 64) throw const ProviderFailure(ProviderProblem.malformed);
      final value = current.value;
      if (value is Map<String, dynamic>) {
        yield value;
        for (final child in value.values.toList().reversed) {
          if (child is Map || child is List) stack.add((value: child, depth: current.depth + 1));
        }
      } else if (value is List) {
        for (final child in value.reversed) {
          if (child is Map || child is List) stack.add((value: child, depth: current.depth + 1));
        }
      }
    }
  }
'''
if not text.endswith('}\n'): raise SystemExit('Unexpected library class end')
p.write_text(text[:-2] + method + '}\n', encoding='utf-8')
print('Integrated authenticated library contracts; no source/audio resolver changes.')
