import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/domain/models.dart';

void main() {
  test('YouTube identity rejects opaque source keys and local file copies', () {
    const song = Song(videoId: 'AbCdEf_12-3', title: 'Song', artist: 'Artist');
    expect(song.isYouTube, isTrue);
    expect(song.copyWith(localPath: '/music/song.mp3').isYouTube, isFalse);
    expect(song.copyWith(videoId: 'jiosaavn:AbCdEf_12-3').isYouTube, isFalse);
    expect(song.copyWith(videoId: 'short').isYouTube, isFalse);
  });
  test('queue occurrences retain identity and rich metadata on round trip', () {
    const original = Song(
      videoId: 'recording',
      title: 'Déjà vu',
      artist: 'Artist',
      artists: [ArtistRef(name: 'Artist', browseId: 'UCartist')],
      queueEntryId: 'occurrence-two',
      setVideoId: 'playlist-occurrence',
      queueTier: QueueTier.userQueue,
      isVideo: true,
      isVideoOrigin: true,
      durationText: '1:03:04',
      albumId: 'MPREalbum',
      albumName: 'Album',
      localPath: '/music/one.flac',
      sourceQuality: 'LOSSLESS',
    );
    final restored = Song.fromJson(original.toJson());
    expect(restored.toJson(), original.toJson());
    expect(restored.duration, const Duration(hours: 1, minutes: 3, seconds: 4));
    expect(restored.isLocal, isTrue);
    expect(restored.copyWith(queueEntryId: 'three').videoId, original.videoId);
    expect(restored.copyWith(queueEntryId: 'three').queueEntryId, 'three');
  });

  test('lyrics lookup applies document offset and handles prelude', () {
    const lyrics = LyricsDocument(
      isSynced: true,
      offset: Duration(milliseconds: 250),
      lines: [
        LyricLine(text: 'First', start: Duration(seconds: 1)),
        LyricLine(text: 'Second', start: Duration(seconds: 4)),
      ],
    );
    expect(lyrics.activeIndex(Duration.zero), -1);
    expect(lyrics.activeIndex(const Duration(milliseconds: 750)), 0);
    expect(lyrics.activeIndex(const Duration(milliseconds: 3750)), 1);
    expect(lyrics.activeIndex(const Duration(hours: 1)), 1);
    expect(const LyricsDocument().activeIndex(Duration.zero), -1);
  });
}
