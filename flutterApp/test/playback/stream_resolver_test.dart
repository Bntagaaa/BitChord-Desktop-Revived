import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/playback/playback_snapshot.dart';
import 'package:bitchord_desktop/playback/stream_resolver.dart';

void main() {
  test(
    'unavailable external source never falls through to YouTube account',
    () async {
      var youtubeCalls = 0;
      final resolver = YoutubeStreamResolver(
        alternateSource: (_) async => null,
        authenticatedPlayer: (_) async {
          youtubeCalls++;
          return <String, dynamic>{};
        },
      );
      try {
        await expectLater(
          resolver.resolve(
            const Song(
              videoId: 'saavn:missing',
              title: 'External recording',
              artist: 'Artist',
            ),
          ),
          throwsA(isA<PlaybackResolutionException>()),
        );
        expect(youtubeCalls, 0);
      } finally {
        await resolver.close();
      }
    },
  );
  test(
    'provider container controls download extension independently of codec',
    () {
      expect(
        const ResolvedStream(
          uri: 'https://media.example/audio',
          container: 'mp4',
          format: StreamFormat(codec: 'aac'),
        ).extension,
        'm4a',
      );
      expect(
        const ResolvedStream(
          uri: 'https://media.example/audio',
          container: 'audio/mp4; codecs="mp4a.40.2"',
        ).extension,
        'm4a',
      );
      expect(
        const ResolvedStream(
          uri: 'https://media.example/audio',
          container: 'flac',
        ).extension,
        'flac',
      );
      expect(
        const ResolvedStream(
          uri: 'https://media.example/audio',
          container: 'audio/wav',
        ).extension,
        'wav',
      );
      expect(
        const ResolvedStream(
          uri: 'https://media.example/audio',
          container: 'mp3',
        ).extension,
        'mp3',
      );
      expect(
        const ResolvedStream(
          uri: 'https://media.example/audio',
          container: 'webm',
          format: StreamFormat(codec: 'opus'),
        ).extension,
        'webm',
      );
      expect(
        const ResolvedStream(
          uri: 'https://media.example/video',
          container: 'mp4',
          videoHeight: 1080,
        ).extension,
        'mp4',
      );
    },
  );
  test('direct player parser honors audio quality ceiling', () {
    final stream = directStreamFromPlayer({
      'streamingData': {
        'adaptiveFormats': [
          {
            'url': 'https://media.example/audio-low',
            'mimeType': 'audio/mp4',
            'bitrate': 96000,
          },
          {
            'url': 'https://media.example/audio-high',
            'mimeType': 'audio/webm',
            'bitrate': 160000,
          },
          {
            'url': 'https://media.example/video',
            'mimeType': 'video/mp4',
            'height': 1080,
            'bitrate': 3000000,
          },
        ],
      },
    }, maxAudioKbps: 128);
    expect(stream!.uri, 'https://media.example/audio-low');
  });
  test('video quality cap does not accidentally select audio', () {
    final stream = directStreamFromPlayer(
      {
        'streamingData': {
          'adaptiveFormats': [
            {
              'url': 'https://media.example/4k',
              'mimeType': 'video/mp4',
              'height': 2160,
            },
            {
              'url': 'https://media.example/hd',
              'mimeType': 'video/mp4',
              'height': 720,
            },
            {
              'url': 'https://media.example/audio',
              'mimeType': 'audio/mp4',
              'bitrate': 256000,
            },
          ],
        },
      },
      video: true,
      maxVideoHeight: 1080,
    );
    expect(stream!.uri, 'https://media.example/hd');
    expect(stream.videoHeight, 720);
  });
  test(
    'cipher and non-HTTPS URLs require extractor, never fabricated URLs',
    () {
      expect(
        directStreamFromPlayer({
          'streamingData': {
            'adaptiveFormats': [
              {'signatureCipher': 's=encrypted', 'mimeType': 'audio/mp4'},
              {'url': 'file:///etc/passwd', 'mimeType': 'audio/mp4'},
            ],
          },
        }),
        isNull,
      );
    },
  );
  test(
    'snapshot bounds restored controls and preserves no signed URL fields',
    () {
      final restored = PlaybackSnapshot.fromJson({
        'volume': 200,
        'rate': -1,
        'positionMs': -1,
        'equalizer': [double.infinity, 0, 0, 0, 0, 0, 0],
      });
      expect(restored.volume, 100);
      expect(restored.rate, .25);
      expect(restored.position, Duration.zero);
      expect(restored.equalizer, [0, 0, 0, 0, 0, 0, 0]);
      expect(restored.toJson().containsKey('streamUrl'), isFalse);
    },
  );
}
