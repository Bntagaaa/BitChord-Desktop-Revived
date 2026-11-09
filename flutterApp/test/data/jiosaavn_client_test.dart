import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/data/jiosaavn_client.dart';

// Fixed independently generated OpenSSL DES/ECB/PKCS5 vector. Not a live URL.
const encoded =
    'ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDybBb0DqbfB4hwBZyXWiXMbxw7tS9a8Gtq';
const decoded = 'https://aac.saavncdn.com/example/song_96.mp4';

void main() {
  test('legacy URL decoder matches the independent DES vector and validates padding', () {
    expect(JioSaavnClient.decodeMediaUrl(encoded), decoded);
    expect(JioSaavnClient.decodeMediaUrl('invalid'), isNull);
    final corrupt = base64.decode(encoded)..[47] ^= 1;
    expect(JioSaavnClient.decodeMediaUrl(base64.encode(corrupt)), isNull);
  });

  test('bitrate upgrade preserves signed query and only upgrades advertised tracks', () {
    final best = JioSaavnClient.bestUrl('$decoded?token=private', true);
    expect(
      best.url,
      'https://aac.saavncdn.com/example/song_320.mp4?token=private',
    );
    expect(best.kbps, 320);
    expect(JioSaavnClient.bestUrl(decoded, false).kbps, 96);
  });

  test(
    'catalogue and stream request follow source headers and metadata shape',
    () async {
      final requests = <http.Request>[];
      final raw = {
        'id': 'saavn-id',
        'title': 'Song &amp; Song',
        'image': 'http://images.invalid/150x150.jpg',
        'explicit_content': '1',
        'more_info': {
          'album': 'Album',
          'duration': '200',
          '320kbps': 'true',
          'encrypted_media_url': encoded,
          'artistMap': {
            'primary_artists': [
              {'name': 'Artist'},
            ],
          },
        },
      };
      final client = JioSaavnClient(
        httpClient: MockClient((request) async {
          requests.add(request);
          return http.Response(
            jsonEncode(
              request.url.queryParameters['__call'] == 'search.getResults'
                  ? {
                      'results': [raw],
                    }
                  : {
                      'songs': [raw],
                    },
            ),
            200,
          );
        }),
      );
      final songs = await client.search('Song Artist');
      expect(songs.single.title, 'Song & Song');
      expect(songs.single.videoId, 'jiosaavn:saavn-id');
      expect(songs.single.thumbnailUrl, 'https://images.invalid/500x500.jpg');
      expect(songs.single.duration, const Duration(seconds: 200));
      expect(songs.single.isExplicit, isTrue);
      expect(requests.single.headers['cookie'], 'explicit_content=1');
      final stream = await client.resolve(songs.single);
      expect(stream?.uri, endsWith('_320.mp4'));
      expect(stream?.kbps, 320);
      expect(stream?.sourceId, 'jiosaavn');
      expect(stream?.format, 'mp4');
      client.close();
    },
  );

  test(
    'low and medium YouTube-origin playback does not query JioSaavn',
    () async {
      final client = JioSaavnClient(
        httpClient: MockClient((_) async => throw StateError('Must not fetch')),
      );
      const song = Song(videoId: 'youtube', title: 'Song', artist: 'Artist');
      expect(await client.resolve(song, quality: 'LOW'), isNull);
      expect(await client.resolve(song, quality: 'MEDIUM'), isNull);
      client.close();
    },
  );
}
