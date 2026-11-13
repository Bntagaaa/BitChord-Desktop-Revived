"""Apply the reviewed runtime wiring after phase4_library_integrate.py."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def edit(path, before, after):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if before not in text:
        if after in text: return
        raise SystemExit('Unexpected integration source: ' + path)
    if text.count(before) != 1: raise SystemExit('Ambiguous integration source: ' + path)
    p.write_text(text.replace(before, after), encoding='utf-8')
def append(path, code):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if not text.endswith('}\n'): raise SystemExit('Unexpected class end: ' + path)
    p.write_text(text[:-2] + code + '}\n', encoding='utf-8')

edit('flutterDesktop/lib/features/online/domain/account_port.dart',
    'abstract interface class OnlineAccountManager {',
    'abstract interface class OnlineAccountManager {\n  int get accountGeneration;\n  Future<void> disconnectAccount(AccountService service, RequestScope scope);')
append('flutterDesktop/lib/features/online/data/account_store.dart', '''
  Future<void> deactivate(AccountService service, RequestScope scope) => _ordered(() async {
    await _load(scope);
    if (!_active.containsKey(service)) return;
    final active = Map<AccountService, String>.of(_active)..remove(service);
    await _commit(_sessions, active, scope);
  });
''')
edit('flutterDesktop/lib/features/online/data/account_coordinator.dart',
    'required this.clearYoutube});',
    'required this.clearYoutube, this.verifyYoutube});')
edit('flutterDesktop/lib/features/online/data/account_coordinator.dart',
    '  final ProviderHttp http;',
    '  final ProviderHttp http;\n  final Future<YouTubeSession> Function(String, RequestScope)? verifyYoutube;\n  Future<YouTubeSession> _verify(String cookie, RequestScope scope) => verifyYoutube?.call(cookie, scope) ?? YouTubeSession.verify(cookie, http, scope);')
edit('flutterDesktop/lib/features/online/data/account_coordinator.dart',
    '  int get generation => _generation;',
    '  int get generation => _generation;\n  @override\n  int get accountGeneration => _generation;')
edit('flutterDesktop/lib/features/online/data/account_coordinator.dart',
    'return YouTubeSession.verify(cookie, http, scope);', 'return _verify(cookie, scope);')
edit('flutterDesktop/lib/features/online/data/account_coordinator.dart',
    'youtube = await YouTubeSession.verify(cookie, http, child);', 'youtube = await _verify(cookie, child);')
append('flutterDesktop/lib/features/online/data/account_coordinator.dart', '''
  @override
  Future<void> disconnectAccount(AccountService service, RequestScope scope) => _ordered(scope, (child) async {
    await _restore(child);
    await store.deactivate(service, child);
    if (service == AccountService.youtube) clearYoutube();
    ++_generation;
  });
''')

path = 'flutterDesktop/lib/features/online/data/online_runtime.dart'
edit(path, "import 'addon_provider.dart';", """import 'addon_provider.dart';
import '../domain/account_port.dart';
import '../domain/library_port.dart';
import '../domain/collection_actions.dart';
import '../../../core/platform/accounts/os_account_vault.dart';
import '../../../core/platform/accounts/browser_session.dart';
import 'account_store.dart';
import 'account_coordinator.dart';
import 'library_service.dart';
import 'spotify_library.dart';
import 'spotify_matcher.dart';""")
edit(path, '        OnlinePlaybackIdentity {', '        OnlinePlaybackIdentity,\n        OnlineAccountManager,\n        OnlineLibrary {')
edit(path, """    _providers['jiosaavn'] = JioSaavn(
      const ProviderHttp(),
      NativeApi.bundledLibrary,
    );
""", """    _providers['jiosaavn'] = JioSaavn(
      const ProviderHttp(),
      NativeApi.bundledLibrary,
    );
    _accounts = AccountCoordinator(
      store: AccountStore(OsAccountVault(Directory('${downloads.parent.path}/accounts'))),
      browser: DesktopAccountBrowser(), http: const ProviderHttp(),
      adoptYoutube: _youtube.adoptVerified, clearYoutube: _youtube.signOut,
    );
    _spotify = SpotifyLibrary(const ProviderHttp(),
      credentials: _accounts.spotifyCredentials, generation: () => _accounts.generation);
    _library = LibraryService(_youtube, _accounts, _spotify);
    _spotifyMatcher = SpotifyRecordingMatcher(
      (query, scope) => _youtube.search(query, scope, filter: SearchFilter.songs),
      () => _youtube.accountGeneration,
    );
""")
edit(path, '  final DownloadStore store;', '''  final DownloadStore store;
  late final AccountCoordinator _accounts;
  late final SpotifyLibrary _spotify;
  late final LibraryService _library;
  late final SpotifyRecordingMatcher _spotifyMatcher;''')
edit(path, """  }) => _provider(
    'youtube',
  ).browse(id, scope, continuation: continuation, params: params);""", """  }) async {
    if (id.startsWith('spotify:playlist:')) {
      await _accounts.restore(scope);
      return _spotify.tracks(id, scope, continuation: continuation);
    }
    // Guest music remains usable without a keyring. Account-dependent library
    // calls use the explicit library port and must surface restoration failures.
    try { await _accounts.restore(scope); }
    on AccountFailure { scope.check(); }
    on ProviderFailure { scope.check(); }
    return _youtube.browse(id, scope, continuation: continuation, params: params);
  }""")
edit(path, """  ) async {
    final provider = _provider(entry.provider);
    // A selected YouTube source""", """  ) async {
    if (entry.provider == 'spotify') {
      final matched = await _spotifyMatcher.resolve(entry, scope);
      return play(matched, quality, scope);
    }
    final provider = _provider(entry.provider);
    // A selected YouTube source""")
edit(path, """    void Function(int, int) progress,
  ) async {
    final provider = _provider(entry.provider);""", """    void Function(int, int) progress,
  ) async {
    if (entry.provider == 'spotify') {
      final matched = await _spotifyMatcher.resolve(entry, scope);
      // Store the actual matched YouTube source identity, never mislabel it as
      // downloaded Spotify media or weaken the store's source/rendition checks.
      return download(matched, quality, scope, progress);
    }
    final provider = _provider(entry.provider);""")
edit(path, """    _closed = true;
    _youtube.signOut();""", """    _closed = true;
    await _accounts.close();
    _youtube.signOut();""")
append(path, '''
  @override
  int get accountGeneration => _accounts.generation;
  @override
  Future<List<AccountSummary>> accounts(RequestScope scope) => _accounts.accounts(scope);
  @override
  Future<void> connectAccount(AccountService service, String label, RequestScope scope, void Function(String) progress) => _accounts.connectAccount(service, label, scope, progress);
  @override
  Future<void> activateAccount(String id, RequestScope scope) => _accounts.activateAccount(id, scope);
  @override
  Future<void> removeAccount(String id, RequestScope scope) => _accounts.removeAccount(id, scope);
  @override
  Future<void> refreshAccount(AccountService service, RequestScope scope) => _accounts.refreshAccount(service, scope);
  @override
  Future<void> disconnectAccount(AccountService service, RequestScope scope) => _accounts.disconnectAccount(service, scope);
  @override
  Future<CatalogPage> library(LibrarySection section, RequestScope scope, {String? continuation}) => _library.library(section, scope, continuation: continuation);
  @override
  Future<CatalogPage> spotifyLibrary(RequestScope scope, {String? continuation}) => _library.spotifyLibrary(scope, continuation: continuation);
  @override
  Future<SongActions> songActions(String videoId, RequestScope scope) => _library.songActions(videoId, scope);
  @override
  Future<CollectionActions> collectionActions(String browseId, RequestScope scope) => _library.collectionActions(browseId, scope);
  @override
  Future<String> createPlaylist(String title, PlaylistPrivacy privacy, RequestScope scope) => _library.createPlaylist(title, privacy, scope);
  @override
  Future<void> renamePlaylist(String playlistId, String title, RequestScope scope) => _library.renamePlaylist(playlistId, title, scope);
  @override
  Future<void> deletePlaylist(String playlistId, RequestScope scope) => _library.deletePlaylist(playlistId, scope);
  @override
  Future<void> addToPlaylist(String playlistId, List<String> videoIds, RequestScope scope) => _library.addToPlaylist(playlistId, videoIds, scope);
  @override
  Future<void> removeFromPlaylist(String playlistId, List<PlaylistOccurrence> entries, RequestScope scope) => _library.removeFromPlaylist(playlistId, entries, scope);
  @override
  Future<void> movePlaylistItem(String playlistId, String setVideoId, String? beforeSetVideoId, RequestScope scope) => _library.movePlaylistItem(playlistId, setVideoId, beforeSetVideoId, scope);
  @override
  Future<void> rateTrack(String videoId, TrackRating rating, RequestScope scope) => _library.rateTrack(videoId, rating, scope);
  @override
  Future<void> saveCollection(String playlistId, bool saved, RequestScope scope) => _library.saveCollection(playlistId, saved, scope);
  @override
  Future<void> subscribeArtist(String channelId, bool subscribed, RequestScope scope) => _library.subscribeArtist(channelId, subscribed, scope);
  @override
  Future<void> sendLibraryFeedback(String token, RequestScope scope) => _library.sendLibraryFeedback(token, scope);
''')
print('Connected account and library ports; existing YouTube/native byte path retained.')
