// Port source: shared/src/commonMain/.../data/model/Models.kt @ 348b613a.
export 'lyrics.dart';

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

class Song {
  const Song({
    required this.id,
    required this.title,
    required this.artist,
    this.artwork,
    this.durationText,
    this.artistId,
    this.albumId,
    this.albumName,
    this.playlistSetVideoId,
    this.localUri,
    this.isExplicit = false,
    this.isVideo = false,
  });
  final String id;
  final String title;
  final String artist;
  final String? artwork;
  final String? durationText;
  final String? artistId;
  final String? albumId;
  final String? albumName;
  final String? playlistSetVideoId;
  final Uri? localUri;
  final bool isExplicit;
  final bool isVideo;

  Duration? get duration {
    final parts = durationText?.split(':').map(int.tryParse).toList();
    if (parts == null || parts.any((part) => part == null)) return null;
    var seconds = 0;
    for (final part in parts) {
      seconds = seconds * 60 + part!;
    }
    return Duration(seconds: seconds);
  }
}

enum ShelfLayout { cards, list, video, hero }

class CatalogItem {
  const CatalogItem({
    required this.title,
    this.subtitle = '',
    this.artwork,
    this.browseId,
    this.params,
    this.song,
    this.isVideo = false,
    this.videoId,
    this.durationText,
    this.isExplicit = false,
  });
  final String title;
  final String subtitle;
  final String? artwork;
  final String? browseId;
  final String? params;
  final Song? song;
  final bool isVideo;
  final String? videoId;
  final String? durationText;
  final bool isExplicit;
}

class Shelf {
  const Shelf(
    this.title,
    this.items, {
    this.subtitle,
    this.moreBrowseId,
    this.moreParams,
    this.layout = ShelfLayout.cards,
  });
  final String title;
  final List<CatalogItem> items;
  final String? subtitle;
  final String? moreBrowseId;
  final String? moreParams;
  final ShelfLayout layout;
}

ShelfLayout shelfLayoutFor({
  required String title,
  String? subtitle,
  required List<CatalogItem> items,
  bool fromList = false,
}) {
  if (items.isEmpty) return ShelfLayout.cards;
  final first = items.first;
  if (fromList && (first.videoId != null || first.song?.id != null)) {
    return ShelfLayout.list;
  }
  final lowerTitle = title.trim().toLowerCase();
  if (lowerTitle == 'listen again' ||
      lowerTitle == 'quick picks' ||
      lowerTitle == 'recents') {
    return ShelfLayout.list;
  }
  final videoCount = items.where((it) => it.isVideo).length;
  if (videoCount * 2 > items.length) {
    return ShelfLayout.video;
  }
  return ShelfLayout.cards;
}

class CatalogPage {
  const CatalogPage({
    this.title = '',
    this.subtitle = '',
    this.artwork,
    this.shelves = const [],
    this.songs = const [],
    this.items = const [],
    this.continuation,
  });
  final String title;
  final String subtitle;
  final String? artwork;
  final List<Shelf> shelves;
  final List<Song> songs;
  final List<CatalogItem> items;
  final String? continuation;
}
