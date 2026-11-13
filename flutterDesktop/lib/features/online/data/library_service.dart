import '../domain/catalog.dart';
import '../domain/collection_actions.dart';
import '../domain/library_port.dart';
import 'account_coordinator.dart';
import 'spotify_library.dart';
import 'youtube_music.dart';

// Application-facing library boundary. Account restoration is separate from
// ordinary guest playback; a locked keyring must never replace that fast path.
final class LibraryService implements OnlineLibrary {
  LibraryService(this.youtube, this.accounts, this.spotify);
  final YouTubeMusic youtube;
  final AccountCoordinator accounts;
  final SpotifyLibrary spotify;

  Future<T> _youtube<T>(RequestScope scope, Future<T> Function() work) async {
    await accounts.restore(scope);
    scope.check();
    return work();
  }

  @override
  Future<CatalogPage> library(LibrarySection section, RequestScope scope, {String? continuation}) => _youtube(scope, () => youtube.library.page(section, scope, continuation: continuation));
  @override
  Future<CatalogPage> spotifyLibrary(RequestScope scope, {String? continuation}) async {
    await accounts.restore(scope);
    return spotify.playlists(scope, continuation: continuation);
  }
  @override
  Future<SongActions> songActions(String videoId, RequestScope scope) => _youtube(scope, () => youtube.library.song(videoId, scope));
  @override
  Future<CollectionActions> collectionActions(String browseId, RequestScope scope) => _youtube(scope, () => youtube.library.collection(browseId, scope));
  @override
  Future<String> createPlaylist(String title, PlaylistPrivacy privacy, RequestScope scope) => _youtube(scope, () => youtube.library.create(title, privacy, scope));
  @override
  Future<void> renamePlaylist(String playlistId, String title, RequestScope scope) => _youtube(scope, () => youtube.library.rename(playlistId, title, scope));
  @override
  Future<void> deletePlaylist(String playlistId, RequestScope scope) => _youtube(scope, () => youtube.library.delete(playlistId, scope));
  @override
  Future<void> addToPlaylist(String playlistId, List<String> videoIds, RequestScope scope) => _youtube(scope, () => youtube.library.add(playlistId, videoIds, scope));
  @override
  Future<void> removeFromPlaylist(String playlistId, List<PlaylistOccurrence> entries, RequestScope scope) => _youtube(scope, () => youtube.library.remove(playlistId, entries, scope));
  @override
  Future<void> movePlaylistItem(String playlistId, String setVideoId, String? beforeSetVideoId, RequestScope scope) => _youtube(scope, () => youtube.library.move(playlistId, setVideoId, beforeSetVideoId, scope));
  @override
  Future<void> rateTrack(String videoId, TrackRating rating, RequestScope scope) => _youtube(scope, () => youtube.library.rate(videoId, rating, scope));
  @override
  Future<void> saveCollection(String playlistId, bool saved, RequestScope scope) => _youtube(scope, () => youtube.library.save(playlistId, saved, scope));
  @override
  Future<void> subscribeArtist(String channelId, bool subscribed, RequestScope scope) => _youtube(scope, () => youtube.library.subscribe(channelId, subscribed, scope));
  @override
  Future<void> sendLibraryFeedback(String token, RequestScope scope) => _youtube(scope, () => youtube.library.feedback(token, scope));
}
