class Song {
  final String videoId;
  final String title;
  final String artist;
  final String? thumbnailUrl;
  final int durationSeconds;

  const Song({
    required this.videoId,
    required this.title,
    required this.artist,
    this.thumbnailUrl,
    this.durationSeconds = 0,
  });
}
