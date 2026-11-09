import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/data/innertube_client.dart';

void main() {
  group('InnertubeClient', () {
    test('ShelfItem.cleanArtist strips prefix labels and view tallies', () {
      expect(ShelfItem.cleanArtist('Single • Maman Fvndy'), 'Maman Fvndy');
      expect(ShelfItem.cleanArtist('Album • Denny Caknan'), 'Denny Caknan');
      expect(ShelfItem.cleanArtist('EP • Jeyma Monica'), 'Jeyma Monica');
      expect(ShelfItem.cleanArtist('Jeyma Monica • 4.1M views'), 'Jeyma Monica');
      expect(ShelfItem.cleanArtist('Ed Sheeran'), 'Ed Sheeran');
    });

    test('ShelfItem converts to Song with valid title and clean artist', () {
      const item = ShelfItem(
        title: 'DIRANTAI DIGELANGI RINDU',
        subtitle: 'Single • Jeyma Monica',
        thumbnailUrl: 'https://i.ytimg.com/vi/AStEe82Se1Y/hqdefault.jpg',
        videoId: 'AStEe82Se1Y',
      );

      final song = item.toSong();
      expect(song.title, 'DIRANTAI DIGELANGI RINDU');
      expect(song.artist, 'Jeyma Monica');
      expect(song.videoId, 'AStEe82Se1Y');
      expect(song.thumbnailUrl, isNotNull);
    });
  });
}
