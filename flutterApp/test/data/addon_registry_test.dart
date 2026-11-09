import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/data/addon_client.dart';

void main() {
  test(
    'addon URL keeps private query parameters and encodes opaque track IDs',
    () {
      final client = AddonClient(
        'https://example.invalid/private/manifest.json?token=private',
      );
      final uri = client.endpoint(
        ['stream', 'track/with?reserved'],
        {'quality': 'FLAC'},
      );
      expect(uri.queryParameters['token'], 'private');
      expect(uri.queryParameters['quality'], 'FLAC');
      expect(uri.pathSegments, ['private', 'stream', 'track/with?reserved']);
      expect(uri.toString(), contains('track%2Fwith%3Freserved'));
      client.close();
    },
  );

  test(
    'manifest downloads policy accepts the existing numeric/string encodings',
    () {
      expect(
        AddonManifest.fromJson({'id': 'one', 'allowDownloads': 0})
            .allowDownloads,
        isFalse,
      );
      expect(
        AddonManifest.fromJson({'id': 'one', 'allowDownloads': 'false'})
            .allowDownloads,
        isFalse,
      );
      expect(
        () => AddonManifest.fromJson({
          'id': 'one',
          'resources': ['metadata'],
        }),
        throwsA(isA<ServiceException>()),
      );
    },
  );

  test(
    'stream metadata converts quality units and rejects encrypted responses',
    () {
      final stream = AddonStream.fromJson(
        {
          'url': 'https://example.invalid/audio.flac',
          'format': 'flac',
          'sampleRate': '96',
          'bitDepth': 24,
          'bitrate': 4608000,
          'headers': {
            'X-Stream-Key': 'private',
            'Bad': 'value\r\ninjected',
            'Host': 'wrong',
          },
        },
        'source',
        false,
      );
      expect(stream.sampleRateHz, 96000);
      expect(stream.kbps, 4608);
      expect(stream.bitDepth, 24);
      expect(stream.allowDownloads, isFalse);
      expect(stream.headers, {'X-Stream-Key': 'private'});
      expect(
        () => AddonStream.fromJson(
          {'url': 'https://example.invalid/encrypted', 'encrypted': 'widevine'},
          'source',
          true,
        ),
        throwsA(isA<ServiceException>()),
      );
    },
  );

  test('addon requests map quality options, forward defaults and enforce playback-only', () async {
    final requests = <http.Request>[];
    final client = AddonClient(
      'https://example.invalid/addon',
      httpClient: MockClient((request) async {
        requests.add(request);
        if (request.url.path.endsWith('/manifest.json')) {
          return http.Response(
            jsonEncode({
              'id': 'test',
              'name': 'Test',
              'allowDownloads': false,
              'settings': [
                {
                  'key': 'quality',
                  'default': 'MP3',
                  'options': [
                    {'value': 'MP3'},
                    {'value': 'FLAC'},
                  ],
                },
                {'key': 'mode', 'default': 'stereo'},
              ],
            }),
            200,
          );
        }
        if (request.url.path.endsWith('/search')) {
          return http.Response(
            jsonEncode({
              'tracks': [
                {
                  'id': 'track',
                  'title': 'Song',
                  'artist': 'Artist',
                  'duration': 222,
                  'format': 'flac',
                },
              ],
            }),
            200,
          );
        }
        return http.Response(
          '{"url":"https://example.invalid/audio.flac","format":"flac"}',
          200,
        );
      }),
    );
    final tracks = await client.search('Song Artist');
    expect(
      tracks.single.asSong('source').duration,
      const Duration(seconds: 222),
    );
    expect(requests.last.url.queryParameters, {
      'quality': 'FLAC',
      'mode': 'stereo',
      'q': 'Song Artist',
    });
    final stream = await client.stream('track', 'source');
    expect(stream.allowDownloads, isFalse);
    final count = requests.length;
    await expectLater(
      client.stream('track', 'source', forDownload: true),
      throwsA(isA<ServiceException>()),
    );
    expect(
      requests.length,
      count,
    ); // Policy rejected before another stream request.
    client.close();
  });

  test(
    '429 creates a bounded quiet period instead of hammering the server',
    () async {
      var calls = 0;
      final client = AddonClient(
        'https://example.invalid',
        httpClient: MockClient((_) async {
          calls++;
          return http.Response(
            'rate limited',
            429,
            headers: {'retry-after': '60'},
          );
        }),
      );
      await expectLater(client.manifest(), throwsA(isA<ServiceException>()));
      await expectLater(client.manifest(), throwsA(isA<ServiceException>()));
      expect(calls, 1);
      client.close();
    },
  );

  test(
    'addon recording keys round trip without confusing separators in track IDs',
    () {
      final key = AddonTrackReference.encode('source', 'track/with:characters');
      final parsed = AddonTrackReference.parse(key)!;
      expect(parsed.sourceId, 'source');
      expect(parsed.trackId, 'track/with:characters');
      expect(AddonTrackReference.parse('youtube-id'), isNull);
    },
  );
}
