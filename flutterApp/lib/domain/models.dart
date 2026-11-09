import 'dart:collection';

enum QueueTier { userQueue, context, autoplay }

enum BrowseType { album, artist, playlist, other }

enum ShelfLayout { cards, list, video }

enum LikeStatus { like, dislike, indifferent }

enum PlaylistPrivacy { private, unlisted, public }

enum SearchFilter {
  all('All', null),
  songs('Songs', 'EgWKAQIIAWoKEAkQChAFEAMQBA=='),
  videos('Videos', 'EgWKAQIQAWoKEAkQChAFEAMQBA=='),
  albums('Albums', 'EgWKAQIYAWoKEAkQChAFEAMQBA=='),
  artists('Artists', 'EgWKAQIgAWoKEAkQChAFEAMQBA=='),
  playlists('Playlists', 'EgWKAQIoAWoKEAkQChAFEAMQBA==');

  const SearchFilter(this.label, this.params);
  final String label;
  final String? params;
}

class ArtistRef {
  const ArtistRef({required this.name, this.browseId});
  final String name;
  final String? browseId;
  Map<String, Object?> toJson() => {'name': name, 'browseId': browseId};
  factory ArtistRef.fromJson(Map<String, dynamic> json) => ArtistRef(
    name: json['name'] as String? ?? '',
    browseId: json['browseId'] as String?,
  );
}

/// Catalogue identity and queue-entry identity are deliberately separate.
/// Duplicate occurrences of a song in a queue/playlist must remain distinct.
class Song {
  const Song({
    required this.videoId,
    required this.title,
    required this.artist,
    this.thumbnailUrl,
    this.durationText,
    this.artistId,
    this.artists = const [],
    this.albumId,
    this.albumName,
    this.isVideo = false,
    bool? isVideoOrigin,
    this.setVideoId,
    this.queueTier = QueueTier.context,
    this.queueEntryId,
    this.radioName,
    this.localUri,
    this.localPath,
    this.downloadFormat,
    this.localDateAddedSeconds,
    this.localDateModifiedSeconds,
    this.sourceQuality,
    this.isExplicit,
    this.playbackSource,
    this.playbackSourceType,
    this.playbackSourceId,
    this.feedbackToken,
  }) : isVideoOrigin = isVideoOrigin ?? isVideo;

  final String videoId;
  final String title;
  final String artist;
  final String? thumbnailUrl;
  final String? durationText;
  final String? artistId;
  final List<ArtistRef> artists;
  final String? albumId;
  final String? albumName;
  final bool isVideo;
  final bool isVideoOrigin;
  final String? setVideoId;
  final QueueTier queueTier;
  final String? queueEntryId;
  final String? radioName;
  final String? localUri;
  final String? localPath;
  final String? downloadFormat;
  final int? localDateAddedSeconds;
  final int? localDateModifiedSeconds;
  final String? sourceQuality;
  final bool? isExplicit;
  final String? playbackSource;
  final String? playbackSourceType;
  final String? playbackSourceId;
  final String? feedbackToken;

  bool get isLocal => localPath != null || localUri != null;
  bool get isYouTube =>
      !isLocal && RegExp(r'^[A-Za-z0-9_-]{11}$').hasMatch(videoId);
  bool get fromAutoplay => queueTier == QueueTier.autoplay;
  Duration get duration => parseDuration(durationText);
  String? artworkAt(int pixels) => artworkUrlAt(thumbnailUrl, pixels);
  bool isSameTrackAs(Song? other) =>
      other != null && title == other.title && artist == other.artist;

  Song copyWith({
    String? videoId,
    String? title,
    String? artist,
    String? thumbnailUrl,
    String? durationText,
    String? artistId,
    List<ArtistRef>? artists,
    String? albumId,
    String? albumName,
    bool? isVideo,
    bool? isVideoOrigin,
    String? setVideoId,
    QueueTier? queueTier,
    String? queueEntryId,
    String? radioName,
    String? localUri,
    String? localPath,
    String? downloadFormat,
    int? localDateAddedSeconds,
    int? localDateModifiedSeconds,
    String? sourceQuality,
    bool? isExplicit,
    String? playbackSource,
    String? playbackSourceType,
    String? playbackSourceId,
    String? feedbackToken,
  }) => Song(
    videoId: videoId ?? this.videoId,
    title: title ?? this.title,
    artist: artist ?? this.artist,
    thumbnailUrl: thumbnailUrl ?? this.thumbnailUrl,
    durationText: durationText ?? this.durationText,
    artistId: artistId ?? this.artistId,
    artists: artists ?? this.artists,
    albumId: albumId ?? this.albumId,
    albumName: albumName ?? this.albumName,
    isVideo: isVideo ?? this.isVideo,
    isVideoOrigin: isVideoOrigin ?? this.isVideoOrigin,
    setVideoId: setVideoId ?? this.setVideoId,
    queueTier: queueTier ?? this.queueTier,
    queueEntryId: queueEntryId ?? this.queueEntryId,
    radioName: radioName ?? this.radioName,
    localUri: localUri ?? this.localUri,
    localPath: localPath ?? this.localPath,
    downloadFormat: downloadFormat ?? this.downloadFormat,
    localDateAddedSeconds: localDateAddedSeconds ?? this.localDateAddedSeconds,
    localDateModifiedSeconds:
        localDateModifiedSeconds ?? this.localDateModifiedSeconds,
    sourceQuality: sourceQuality ?? this.sourceQuality,
    isExplicit: isExplicit ?? this.isExplicit,
    playbackSource: playbackSource ?? this.playbackSource,
    playbackSourceType: playbackSourceType ?? this.playbackSourceType,
    playbackSourceId: playbackSourceId ?? this.playbackSourceId,
    feedbackToken: feedbackToken ?? this.feedbackToken,
  );

  Map<String, Object?> toJson() => {
    'videoId': videoId,
    'title': title,
    'artist': artist,
    'thumbnailUrl': thumbnailUrl,
    'durationText': durationText,
    'artistId': artistId,
    'artists': artists.map((a) => a.toJson()).toList(),
    'albumId': albumId,
    'albumName': albumName,
    'isVideo': isVideo,
    'isVideoOrigin': isVideoOrigin,
    'setVideoId': setVideoId,
    'queueTier': queueTier.name,
    'queueEntryId': queueEntryId,
    'radioName': radioName,
    'localUri': localUri,
    'localPath': localPath,
    'downloadFormat': downloadFormat,
    'localDateAddedSeconds': localDateAddedSeconds,
    'localDateModifiedSeconds': localDateModifiedSeconds,
    'sourceQuality': sourceQuality,
    'isExplicit': isExplicit,
    'playbackSource': playbackSource,
    'playbackSourceType': playbackSourceType,
    'playbackSourceId': playbackSourceId,
    'feedbackToken': feedbackToken,
  };

  factory Song.fromJson(Map<String, dynamic> json) => Song(
    videoId: json['videoId'] as String,
    title: json['title'] as String? ?? '',
    artist: json['artist'] as String? ?? '',
    thumbnailUrl: json['thumbnailUrl'] as String?,
    durationText: json['durationText'] as String?,
    artistId: json['artistId'] as String?,
    artists: (json['artists'] as List? ?? const [])
        .whereType<Map<String, dynamic>>()
        .map(ArtistRef.fromJson)
        .toList(growable: false),
    albumId: json['albumId'] as String?,
    albumName: json['albumName'] as String?,
    isVideo: json['isVideo'] as bool? ?? false,
    isVideoOrigin: json['isVideoOrigin'] as bool?,
    setVideoId: json['setVideoId'] as String?,
    queueTier:
        QueueTier.values
            .where((v) => v.name == json['queueTier'])
            .firstOrNull ??
        QueueTier.context,
    queueEntryId: json['queueEntryId'] as String?,
    radioName: json['radioName'] as String?,
    localUri: json['localUri'] as String?,
    localPath: json['localPath'] as String?,
    downloadFormat: json['downloadFormat'] as String?,
    localDateAddedSeconds: (json['localDateAddedSeconds'] as num?)?.toInt(),
    localDateModifiedSeconds: (json['localDateModifiedSeconds'] as num?)
        ?.toInt(),
    sourceQuality: json['sourceQuality'] as String?,
    isExplicit: json['isExplicit'] as bool?,
    playbackSource: json['playbackSource'] as String?,
    playbackSourceType: json['playbackSourceType'] as String?,
    playbackSourceId: json['playbackSourceId'] as String?,
    feedbackToken: json['feedbackToken'] as String?,
  );
}

Duration parseDuration(String? value) {
  if (value == null) return Duration.zero;
  final parts = value.trim().split(':');
  if (parts.length < 2 || parts.length > 3) return Duration.zero;
  var seconds = 0;
  for (final part in parts) {
    final n = int.tryParse(part.trim());
    if (n == null || n < 0) return Duration.zero;
    seconds = seconds * 60 + n;
  }
  return Duration(seconds: seconds);
}

String formatDuration(Duration value) {
  final seconds = value.inSeconds.clamp(0, 1 << 31);
  final minutes = seconds ~/ 60;
  final s = (seconds % 60).toString().padLeft(2, '0');
  return minutes >= 60
      ? '${minutes ~/ 60}:${(minutes % 60).toString().padLeft(2, '0')}:$s'
      : '$minutes:$s';
}

String? artworkUrlAt(String? url, int pixels) =>
    url?.replaceAll(RegExp(r'w\d+-h\d+'), 'w$pixels-h$pixels');

class BrowseItem {
  const BrowseItem({
    required this.browseId,
    required this.title,
    this.subtitle = '',
    this.thumbnailUrl,
    this.type = BrowseType.other,
  });
  final String browseId;
  final String title;
  final String subtitle;
  final String? thumbnailUrl;
  final BrowseType type;
}

class SearchResult {
  const SearchResult.track(Song value, {this.isTopResult = false})
    : song = value,
      item = null;
  const SearchResult.browse(BrowseItem value)
    : song = null,
      item = value,
      isTopResult = false;
  final Song? song;
  final BrowseItem? item;
  final bool isTopResult;
  String get title => song?.title ?? item!.title;
  String get subtitle => song?.artist ?? item!.subtitle;
  String? get thumbnailUrl => song?.thumbnailUrl ?? item?.thumbnailUrl;
  String get key =>
      song != null ? 'song:${song!.videoId}' : 'browse:${item!.browseId}';
}

class SearchPage {
  const SearchPage({this.results = const [], this.continuation});
  final List<SearchResult> results;
  final String? continuation;
  List<Song> get songs => results.map((e) => e.song).whereType<Song>().toList();
}

class ShelfItem {
  const ShelfItem({
    required this.title,
    this.subtitle = '',
    this.thumbnailUrl,
    this.videoId,
    this.browseId,
    this.isVideo = false,
    this.type = BrowseType.other,
    this.track,
  });
  final String title;
  final String subtitle;
  final String? thumbnailUrl;
  final String? videoId;
  final String? browseId;
  final bool isVideo;
  final BrowseType type;

  /// Full catalogue metadata where the shelf used a responsive track row.
  final Song? track;
  Song? get song =>
      track ??
      (videoId == null
          ? null
          : Song(
              videoId: videoId!,
              title: title,
              artist: subtitle,
              thumbnailUrl: thumbnailUrl,
              isVideo: isVideo,
            ));
}

class HomeShelf {
  const HomeShelf({
    required this.title,
    this.items = const [],
    this.subtitle = '',
    this.moreBrowseId,
    this.moreParams,
    this.layout = ShelfLayout.cards,
  });
  final String title;
  final List<ShelfItem> items;
  final String subtitle;
  final String? moreBrowseId;
  final String? moreParams;
  final ShelfLayout layout;
}

class HomeFeed {
  const HomeFeed({this.shelves = const [], this.continuation});
  final List<HomeShelf> shelves;
  final String? continuation;
}

class DetailPage {
  const DetailPage({
    required this.browseId,
    required this.title,
    this.subtitle = '',
    this.thumbnailUrl,
    this.type = BrowseType.other,
    this.songs = const [],
    this.shelves = const [],
    this.suggestedSongs = const [],
    this.continuation,
    this.description,
    this.playlistId,
    this.isOwned = false,
  });
  final String browseId;
  final String title;
  final String subtitle;
  final String? thumbnailUrl;
  final BrowseType type;
  final List<Song> songs;
  final List<HomeShelf> shelves;
  final List<Song> suggestedSongs;
  final String? continuation;
  final String? description;
  final String? playlistId;
  final bool isOwned;
}

class LibraryPage {
  const LibraryPage({
    this.likedSongs = const [],
    this.librarySongs = const [],
    this.shelves = const [],
    this.likedContinuation,
    this.warnings = const [],
  });
  final List<Song> likedSongs;
  final List<Song> librarySongs;
  final List<HomeShelf> shelves;
  final String? likedContinuation;
  final List<String> warnings;
}

class SongMenu {
  const SongMenu({
    this.likeStatus,
    this.inLibrary = false,
    this.addToLibraryToken,
    this.removeFromLibraryToken,
  });
  final LikeStatus? likeStatus;
  final bool inLibrary;
  final String? addToLibraryToken;
  final String? removeFromLibraryToken;
}

class MoodGenre {
  const MoodGenre({
    required this.title,
    required this.browseId,
    this.params,
    this.thumbnailUrl,
    this.stripeColor,
  });
  final String title;
  final String browseId;
  final String? params;
  final String? thumbnailUrl;
  final int? stripeColor;
}

class MoodGenreSection {
  const MoodGenreSection({required this.title, required this.items});
  final String title;
  final List<MoodGenre> items;
}

class LocalPlaylist {
  LocalPlaylist({
    required this.id,
    required this.title,
    List<Song> songs = const [],
    this.pinned = false,
  }) : songs = UnmodifiableListView(songs);
  final String id;
  final String title;
  final List<Song> songs;
  final bool pinned;
  LocalPlaylist copyWith({String? title, List<Song>? songs, bool? pinned}) =>
      LocalPlaylist(
        id: id,
        title: title ?? this.title,
        songs: songs ?? this.songs,
        pinned: pinned ?? this.pinned,
      );
  Map<String, Object?> toJson() => {
    'id': id,
    'title': title,
    'songs': songs.map((s) => s.toJson()).toList(),
    'pinned': pinned,
  };
  factory LocalPlaylist.fromJson(Map<String, dynamic> json) => LocalPlaylist(
    id: json['id'] as String,
    title: json['title'] as String,
    songs: (json['songs'] as List? ?? const [])
        .cast<Map<String, dynamic>>()
        .map(Song.fromJson)
        .toList(),
    pinned: json['pinned'] as bool? ?? false,
  );
}

class Account {
  const Account({required this.name, this.email = '', this.thumbnailUrl});
  final String name;
  final String email;
  final String? thumbnailUrl;
}

class AccountChannel {
  const AccountChannel({
    required this.name,
    this.subtitle = '',
    this.thumbnailUrl,
    this.pageId,
    this.dataSyncId,
    this.authUser,
    this.activeOnWeb = false,
  });
  final String name;
  final String subtitle;
  final String? thumbnailUrl;
  final String? pageId;
  final String? dataSyncId;
  final String? authUser;
  final bool activeOnWeb;
  String get key => pageId ?? dataSyncId ?? name;
}

class LyricWord {
  const LyricWord({required this.text, required this.start, required this.end});
  final String text;
  final Duration start;
  final Duration end;
}

enum LyricAlignment { start, end }

class LyricLine {
  const LyricLine({
    required this.text,
    this.start = Duration.zero,
    this.end,
    this.translation,
    this.romanization,
    this.words = const [],
    this.alignment = LyricAlignment.start,
  });
  final String text;
  final Duration start;
  final Duration? end;
  final String? translation;
  final String? romanization;
  final List<LyricWord> words;
  final LyricAlignment alignment;
  bool get isWordSynced => words.isNotEmpty;
}

class LyricsDocument {
  const LyricsDocument({
    this.lines = const [],
    this.isSynced = false,
    this.isInstrumental = false,
    this.source = '',
    this.offset = Duration.zero,
  });
  final List<LyricLine> lines;
  final bool isSynced;
  final bool isInstrumental;
  final String source;
  final Duration offset;
  int activeIndex(Duration position) {
    if (!isSynced || lines.isEmpty) return -1;
    var low = 0;
    var high = lines.length;
    final target = position + offset;
    while (low < high) {
      final mid = (low + high) ~/ 2;
      if (lines[mid].start <= target) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low - 1;
  }
}

class ServiceException implements Exception {
  const ServiceException(
    this.message, {
    this.statusCode,
    this.retryable = false,
  });
  final String message;
  final int? statusCode;
  final bool retryable;
  @override
  String toString() => message;
}
