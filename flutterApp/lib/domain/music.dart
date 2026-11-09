// Port source: shared/src/commonMain/.../data/model/Models.kt @ 348b613a.
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

class CatalogItem {
  const CatalogItem({
    required this.title,
    this.subtitle = '',
    this.artwork,
    this.browseId,
    this.params,
    this.song,
  });
  final String title;
  final String subtitle;
  final String? artwork;
  final String? browseId;
  final String? params;
  final Song? song;
}

class Shelf {
  const Shelf(this.title, this.items);
  final String title;
  final List<CatalogItem> items;
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

class LyricLine {
  const LyricLine(this.time, this.text);
  final Duration time;
  final String text;
}
