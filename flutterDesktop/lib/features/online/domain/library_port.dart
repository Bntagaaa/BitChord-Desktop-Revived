import 'catalog.dart';

enum LibrarySection { playlists, songs, liked, albums, artists, subscriptions, podcasts, history }
enum PlaylistPrivacy { private, unlisted, public }
enum TrackRating { like, dislike, none }

final class PlaylistOccurrence {
  const PlaylistOccurrence(this.setVideoId, this.videoId);
  final String setVideoId, videoId;
}

final class SongActions {
  const SongActions({this.rating, this.addToken, this.removeToken, this.historyToken});
  final TrackRating? rating;
  final String? addToken, removeToken, historyToken;
  @override
  String toString() => 'SongActions(redacted)';
}

// A separate optional port keeps guest-only providers honest. Write operations
// require verified account context and are never replayed after network errors.
abstract interface class OnlineLibrary {
  Future<CatalogPage> library(LibrarySection section, RequestScope scope, {String? continuation});
  Future<CatalogPage> spotifyLibrary(RequestScope scope, {String? continuation});
  Future<SongActions> songActions(String videoId, RequestScope scope);
  Future<String> createPlaylist(String title, PlaylistPrivacy privacy, RequestScope scope);
  Future<void> renamePlaylist(String playlistId, String title, RequestScope scope);
  Future<void> deletePlaylist(String playlistId, RequestScope scope);
  Future<void> addToPlaylist(String playlistId, List<String> videoIds, RequestScope scope);
  Future<void> removeFromPlaylist(String playlistId, List<PlaylistOccurrence> entries, RequestScope scope);
  Future<void> movePlaylistItem(String playlistId, String setVideoId, String? beforeSetVideoId, RequestScope scope);
  Future<void> rateTrack(String videoId, TrackRating rating, RequestScope scope);
  Future<void> saveCollection(String playlistId, bool saved, RequestScope scope);
  Future<void> subscribeArtist(String channelId, bool subscribed, RequestScope scope);
  Future<void> sendLibraryFeedback(String token, RequestScope scope);
}
