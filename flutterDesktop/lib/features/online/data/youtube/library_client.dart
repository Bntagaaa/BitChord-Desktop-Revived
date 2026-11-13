import '../../domain/catalog.dart';
import '../../domain/library_port.dart';
import '../../domain/collection_actions.dart';
import 'catalog_parser.dart';

typedef LibraryRequest = Future<Map<String, dynamic>> Function(String endpoint, Map<String, Object?> body, RequestScope scope);

// Endpoint/body semantics follow pinned Innertube.kt/YtMusicRepository.kt.
// Mutations go through a non-retrying transport and retain occurrence identity.
final class YouTubeLibrary {
  YouTubeLibrary({required this.read, required this.write, required this.signedIn});
  final LibraryRequest read, write;
  final bool Function() signedIn;
  static const feeds = <LibrarySection, String>{
    LibrarySection.playlists: 'FEmusic_liked_playlists',
    LibrarySection.songs: 'FEmusic_liked_videos',
    LibrarySection.liked: 'VLLM',
    LibrarySection.albums: 'FEmusic_liked_albums',
    LibrarySection.artists: 'FEmusic_library_corpus_track_artists',
    LibrarySection.subscriptions: 'FEmusic_library_corpus_artists',
    LibrarySection.podcasts: 'FEmusic_library_non_music_audio_list',
    LibrarySection.history: 'FEmusic_history',
  };
  void _account(RequestScope scope) {
    scope.check();
    if (!signedIn()) throw const ProviderFailure(ProviderProblem.loginRequired);
  }
  static String _id(String value) {
    final raw = value.startsWith('VL') ? value.substring(2) : value;
    if (!RegExp(r'^[A-Za-z0-9_-]{2,256}$').hasMatch(raw)) throw const ProviderFailure(ProviderProblem.unsupported);
    return raw;
  }
  static String _video(String value) {
    if (!RegExp(r'^[A-Za-z0-9_-]{11}$').hasMatch(value)) throw const ProviderFailure(ProviderProblem.unsupported);
    return value;
  }
  static String _title(String value) {
    final text = value.trim();
    if (text.isEmpty || text.length > 150 || RegExp(r'[\x00-\x1f]').hasMatch(text)) throw const ProviderFailure(ProviderProblem.unsupported);
    return text;
  }
  static String _opaque(String value) {
    if (value.isEmpty || value.length > 16384 || RegExp(r'[\x00-\x20]').hasMatch(value)) throw const ProviderFailure(ProviderProblem.unsupported);
    return value;
  }
  Future<Map<String, dynamic>> _write(String endpoint, Map<String, Object?> body, RequestScope scope) async {
    _account(scope);
    final result = await write(endpoint, body, scope);
    scope.check();
    if (result['error'] != null || (result['status'] != null && result['status'] != 'STATUS_SUCCEEDED')) throw const ProviderFailure(ProviderProblem.unavailable);
    return result;
  }

  Future<CatalogPage> page(LibrarySection section, RequestScope scope, {String? continuation}) async {
    _account(scope);
    final id = feeds[section]!;
    final response = await read('browse', continuation == null ? {'browseId': id} : {'continuation': _opaque(continuation)}, scope);
    return YouTubeCatalogParser.parsePage(response, browseId: id);
  }

  Future<String> create(String title, PlaylistPrivacy privacy, RequestScope scope) async {
    final result = await _write('playlist/create', {'title': _title(title), 'description': '', 'privacyStatus': privacy.name.toUpperCase()}, scope);
    for (final node in YouTubeCatalogParser.nodes(result)) {
      final id = node['playlistId'];
      if (id is String && id.isNotEmpty) return _id(id);
    }
    throw const ProviderFailure(ProviderProblem.malformed);
  }
  Future<void> rename(String playlistId, String title, RequestScope scope) async {
    await _write('browse/edit_playlist', {'playlistId': _id(playlistId), 'actions': [{'action': 'ACTION_SET_PLAYLIST_NAME', 'playlistName': _title(title)}]}, scope);
  }
  Future<void> delete(String playlistId, RequestScope scope) async {
    await _write('playlist/delete', {'playlistId': _id(playlistId)}, scope);
  }
  Future<void> add(String playlistId, List<String> ids, RequestScope scope) async {
    if (ids.isEmpty || ids.length > 100) throw const ProviderFailure(ProviderProblem.unsupported);
    await _write('browse/edit_playlist', {'playlistId': _id(playlistId), 'actions': [for (final id in ids) {'action': 'ACTION_ADD_VIDEO', 'addedVideoId': _video(id)}]}, scope);
  }
  Future<void> remove(String playlistId, List<PlaylistOccurrence> entries, RequestScope scope) async {
    if (entries.isEmpty || entries.length > 100 || entries.map((row) => row.setVideoId).toSet().length != entries.length) throw const ProviderFailure(ProviderProblem.unsupported);
    await _write('browse/edit_playlist', {'playlistId': _id(playlistId), 'actions': [for (final row in entries) {'action': 'ACTION_REMOVE_VIDEO', 'setVideoId': _opaque(row.setVideoId), 'removedVideoId': _video(row.videoId)}]}, scope);
  }
  Future<void> move(String playlistId, String setVideoId, String? before, RequestScope scope) async {
    if (before == setVideoId) return;
    await _write('browse/edit_playlist', {'playlistId': _id(playlistId), 'actions': [{'action': 'ACTION_MOVE_VIDEO_BEFORE', 'setVideoId': _opaque(setVideoId), if (before != null) 'movedSetVideoIdSuccessor': _opaque(before)}]}, scope);
  }
  Future<void> rate(String videoId, TrackRating rating, RequestScope scope) async {
    await _write(switch (rating) {TrackRating.like => 'like/like', TrackRating.dislike => 'like/dislike', TrackRating.none => 'like/removelike'}, {'target': {'videoId': _video(videoId)}}, scope);
  }
  Future<void> save(String playlistId, bool saved, RequestScope scope) async {
    await _write(saved ? 'like/like' : 'like/removelike', {'target': {'playlistId': _id(playlistId)}}, scope);
  }
  Future<void> subscribe(String channelId, bool subscribed, RequestScope scope) async {
    if (!RegExp(r'^UC[A-Za-z0-9_-]{22}$').hasMatch(channelId)) throw const ProviderFailure(ProviderProblem.unsupported);
    await _write(subscribed ? 'subscription/subscribe' : 'subscription/unsubscribe', {'channelIds': [channelId]}, scope);
  }
  Future<void> feedback(String token, RequestScope scope) async {
    await _write('feedback', {'feedbackTokens': [_opaque(token)]}, scope);
  }

  Future<SongActions> song(String videoId, RequestScope scope) async {
    _account(scope);
    final result = await read('next', {'videoId': _video(videoId), 'isAudioOnly': true, 'playlistId': 'RDAMVM$videoId'}, scope);
    return parseSong(result, videoId);
  }

  static Map _map(Object? value) => value is Map ? value : const {};
  static String? _string(Object? value) => value is String && value.isNotEmpty ? value : null;
  static String? _token(Map toggle, String key) => _string(_map(_map(toggle[key])['feedbackEndpoint'])['feedbackToken']);

  static SongActions parseSong(Map<String, dynamic> result, String videoId) {
    Map<String, dynamic>? row;
    for (final node in YouTubeCatalogParser.nodes(result)) {
      final candidate = node['playlistPanelVideoRenderer'];
      if (candidate is Map<String, dynamic> && candidate['videoId'] == videoId) { row = candidate; break; }
    }
    if (row == null) throw const ProviderFailure(ProviderProblem.malformed);
    TrackRating? rating;
    String? add, remove, history;
    for (final node in YouTubeCatalogParser.nodes(row)) {
      final like = node['likeButtonRenderer'];
      if (like is Map) rating = switch (like['likeStatus']) {'LIKE' => TrackRating.like, 'DISLIKE' => TrackRating.dislike, 'INDIFFERENT' => TrackRating.none, _ => null};
      final toggle = node['toggleMenuServiceItemRenderer'];
      if (toggle is Map) {
        final icon = _map(toggle['defaultIcon'])['iconType'], toggledIcon = _map(toggle['toggledIcon'])['iconType'];
        const libraryIcons = ['LIBRARY_ADD', 'LIBRARY_REMOVE', 'LIBRARY_SAVED'];
        if (libraryIcons.contains(icon) || libraryIcons.contains(toggledIcon)) {
          final defaultAdds = icon == 'LIBRARY_ADD';
          add = _token(toggle, defaultAdds ? 'defaultServiceEndpoint' : 'toggledServiceEndpoint');
          remove = _token(toggle, defaultAdds ? 'toggledServiceEndpoint' : 'defaultServiceEndpoint');
        }
      }
      final item = node['menuServiceItemRenderer'];
      if (item is Map && _map(item['icon'])['iconType'] == 'DELETE') history = _token(item, 'serviceEndpoint');
    }
    return SongActions(rating: rating, addToken: add, removeToken: remove, historyToken: history);
  }

  Future<CollectionActions> collection(String browseId, RequestScope scope) async {
    _account(scope);
    final result = await read('browse', {'browseId': browseId}, scope);
    return parseCollection(result, browseId);
  }

  static CollectionActions parseCollection(Map<String, dynamic> root, String browseId) {
    Map<String, dynamic>? header;
    var editable = false;
    String? description;
    for (final node in YouTubeCatalogParser.nodes(root)) {
      if (node['musicEditablePlaylistDetailHeaderRenderer'] is Map) editable = true;
      for (final name in ['musicResponsiveHeaderRenderer', 'musicDetailHeaderRenderer', 'musicImmersiveHeaderRenderer', 'musicVisualHeaderRenderer']) {
        if (header == null && node[name] is Map<String, dynamic>) header = node[name] as Map<String, dynamic>;
      }
      if (node['musicDescriptionShelfRenderer'] is Map) description ??= _string(YouTubeCatalogParser.text(_map(node['musicDescriptionShelfRenderer'])['description']));
    }
    if (header == null) return CollectionActions(description: description);
    String? playlistId, channelId;
    bool? saved, subscribed;
    var ownerMenu = false;
    final buttons = header['buttons'] is List ? header['buttons'] as List : const [];
    for (final button in buttons) {
      final toggle = _map(_map(button)['toggleButtonRenderer']);
      if ((_map(toggle['defaultIcon'])['iconType'] == 'BOOKMARK_BORDER' || _map(toggle['toggledIcon'])['iconType'] == 'BOOKMARK') && toggle['isDisabled'] != true) saved = toggle['isToggled'] == true;
      final play = _map(_map(_map(button)['musicPlayButtonRenderer'])['playNavigationEndpoint']);
      playlistId ??= _string(_map(play['watchPlaylistEndpoint'])['playlistId']) ?? _string(_map(play['watchEndpoint'])['playlistId']);
    }
    for (final node in YouTubeCatalogParser.nodes(header)) {
      final item = node['menuNavigationItemRenderer'];
      if (item is Map && ['DELETE', 'EDIT'].contains(_map(item['icon'])['iconType'])) ownerMenu = true;
      final button = node['subscribeButtonRenderer'];
      if (button is Map) {
        final ids = button['channelId'] ?? button['channelIds'];
        channelId = ids is String ? ids : ids is List && ids.length == 1 ? _string(ids.single) : null;
        if (button['subscribed'] is bool) subscribed = button['subscribed'] as bool;
      }
    }
    final owned = browseId.startsWith('VL') ? editable || ownerMenu || (buttons.isNotEmpty && saved == null) : null;
    return CollectionActions(playlistId: playlistId ?? (browseId.startsWith('VL') ? browseId.substring(2) : null), owned: owned, saved: saved, channelId: channelId, subscribed: subscribed, description: description ?? _string(YouTubeCatalogParser.text(header['description'])));
  }
}
