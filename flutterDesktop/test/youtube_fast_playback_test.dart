import 'package:flutter_test/flutter_test.dart';

import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/youtube/extractor.dart';
import '../lib/features/online/data/youtube/history_tracker.dart';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/data/youtube_resolver.dart';
import '../lib/features/online/data/youtube_session.dart';
import '../lib/features/online/domain/catalog.dart';

final class MockExtractor implements YouTubeExtractor {
  MockExtractor(this.handler);
  final Future<Map<String, dynamic>> Function(
    YouTubeExtraction request,
    RequestScope scope,
  )
  handler;
  final calls = <YouTubeExtraction>[];

  @override
  Future<Map<String, dynamic>> extract(
    YouTubeExtraction request,
    RequestScope scope,
  ) {
    calls.add(request);
    return handler(request, scope);
  }
}

Map<String, dynamic> sampleMediaData({
  String format = '251',
  String videoId = 'testtrack123',
}) => {
  'videoDetails': {
    'videoId': videoId,
    'title': 'Test Song',
    'isLive': false,
    'isLiveContent': false,
  },
  'streamingData': {
    'adaptiveFormats': [
      {
        'itag': int.parse(format),
        'mimeType': 'audio/webm; codecs="opus"',
        'url':
            'https://rr1---sn-test.googlevideo.com/videoplayback?id=$videoId',
        'bitrate': 160000,
        'averageBitrate': 155000,
        'contentLength': '4000000',
      },
    ],
  },
};

void main() {
  group('YouTube Fast Playback & Reliability', () {
    const testTrack = CatalogEntry(
      provider: 'youtube',
      id: 'testtrack123',
      title: 'Fast Song',
      kind: CatalogKind.track,
    );

    const testCookie = 'SID=sid; SAPISID=sapisid-secret';
    final verifiedSession = YouTubeSession.fromVerifiedShell(
      testCookie,
      '{"LOGGED_IN":true,"INNERTUBE_CLIENT_VERSION":"1.0","SESSION_INDEX":"0"}',
    );

    test('1. Direct fast path resolves stream without extractor', () async {
      var directCalled = 0;
      var extractorCalled = 0;

      final directMock = MockExtractor((request, scope) async {
        directCalled++;
        return sampleMediaData();
      });

      final fallbackMock = MockExtractor((request, scope) async {
        extractorCalled++;
        return sampleMediaData();
      });

      final resolver = YouTubeResolver.testing(
        extractor: fallbackMock,
        direct: directMock,
        probe: (media, scope) async {},
      );

      final media = await resolver.resolve(
        testTrack,
        AudioQuality.high,
        RequestScope(),
        visitorData: 'visitor-snapshot',
      );

      expect(media.trackId, testTrack.id);
      expect(directCalled, 1);
      expect(
        extractorCalled,
        0,
        reason: 'Fast path should resolve without external extractor',
      );
      expect(resolver.lastReport['path'], 'direct');
    });

    test('2. Authenticated user receives fast resolution', () async {
      var directCalled = 0;
      var extractorCalled = 0;

      final directMock = MockExtractor((request, scope) async {
        directCalled++;
        return sampleMediaData();
      });

      final fallbackMock = MockExtractor((request, scope) async {
        extractorCalled++;
        return sampleMediaData();
      });

      final resolver = YouTubeResolver.testing(
        extractor: fallbackMock,
        direct: directMock,
        probe: (media, scope) async {},
      );

      final music = YouTubeMusic(const ProviderHttp(), resolver: resolver);

      final media = await music.resolve(
        testTrack,
        AudioQuality.high,
        RequestScope(),
      );

      expect(media.trackId, testTrack.id);
      expect(directCalled, 1);
      expect(extractorCalled, 0);
    });

    test('3. Restricted tracks fall back cleanly to extractor', () async {
      var directCalled = 0;
      var extractorCalled = 0;

      final directMock = MockExtractor((request, scope) async {
        directCalled++;
        throw const ProviderFailure(ProviderProblem.formatUnavailable);
      });

      final fallbackMock = MockExtractor((request, scope) async {
        extractorCalled++;
        return sampleMediaData();
      });

      final resolver = YouTubeResolver.testing(
        extractor: fallbackMock,
        direct: directMock,
        probe: (media, scope) async {},
      );

      final media = await resolver.resolve(
        testTrack,
        AudioQuality.high,
        RequestScope(),
        cookie: testCookie,
      );

      expect(media.trackId, testTrack.id);
      expect(directCalled, 1);
      expect(
        extractorCalled,
        1,
        reason: 'Fallback to extractor with cookie must succeed',
      );
      expect(fallbackMock.calls.single.cookie, testCookie);
    });

    test('4. YouTubeHistoryTracker operates non-blocking', () async {
      final cpn = YouTubeHistoryTracker.generateCpn();
      expect(cpn.length, 16);
      expect(RegExp(r'^[a-zA-Z0-9_-]{16}$').hasMatch(cpn), isTrue);

      final tracker = YouTubeHistoryTracker();

      expect(
        () => tracker.onPlaying(
          videoId: testTrack.id,
          session: verifiedSession,
        ),
        returnsNormally,
      );

      expect(
        () => tracker.onProgress(
          videoId: testTrack.id,
          positionSeconds: 30,
          session: verifiedSession,
        ),
        returnsNormally,
      );

      expect(
        () => tracker.onPlaybackFinished(
          videoId: testTrack.id,
          positionSeconds: 180,
          session: verifiedSession,
        ),
        returnsNormally,
      );
    });

    test('5. Renewal requires matching rendition and track identity', () async {
      final directMock = MockExtractor(
        (request, scope) async => sampleMediaData(),
      );
      final fallbackMock = MockExtractor(
        (request, scope) async => sampleMediaData(),
      );

      final resolver = YouTubeResolver.testing(
        extractor: fallbackMock,
        direct: directMock,
        probe: (media, scope) async {},
      );

      final music = YouTubeMusic(const ProviderHttp(), resolver: resolver);
      final initial = await music.resolve(
        testTrack,
        AudioQuality.high,
        RequestScope(),
      );

      final renewed = await music.renew(
        testTrack,
        AudioQuality.high,
        initial,
        RequestScope(),
        generation: music.accountGeneration,
      );
      expect(renewed.trackId, initial.trackId);
      expect(renewed.rendition, initial.rendition);

      expect(
        () => music.renew(
          testTrack,
          AudioQuality.high,
          initial,
          RequestScope(),
          generation: music.accountGeneration + 1,
        ),
        throwsA(isA<ProviderFailure>()),
      );
    });
  });
}
