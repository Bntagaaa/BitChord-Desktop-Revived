// Controlled production-path regressions. All network traffic is loopback;
// googlevideo URLs in parser fixtures are never fetched.
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';

import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/source_broker.dart';
import '../lib/features/online/data/youtube/direct_player.dart';
import '../lib/features/online/data/youtube/extractor.dart';
import '../lib/features/online/data/youtube/formats.dart';
import '../lib/features/online/data/youtube/media_probe.dart';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/data/youtube_resolver.dart';
import '../lib/features/online/domain/catalog.dart';
import 'youtube_rewrite_check.dart' as baseline;

var _checks = 0;
void check(bool value, String reason) {
  _checks++;
  if (!value) throw StateError(reason);
}

Future<void> fails(
  Future<Object?> Function() work,
  ProviderProblem problem,
) async {
  try {
    await work();
  } on ProviderFailure catch (failure) {
    check(
      failure.problem == problem,
      'Expected $problem, got ${failure.problem}',
    );
    return;
  }
  throw StateError('Missing expected $problem failure');
}

Future<void> resolverChecks() async {
  final direct = baseline.FakeExtractor(
    (_, scope, call) async => baseline.media(),
  );
  final fallback = baseline.FakeExtractor(
    (_, scope, call) async => baseline.media(suffix: 'fallback'),
  );
  final resolver = YouTubeResolver.testing(
    extractor: fallback,
    direct: direct,
    probe: (_, scope) async {},
  );
  final media = await resolver.resolve(
    baseline.track,
    AudioQuality.high,
    RequestScope(),
    visitorData: 'fixture-visitor',
  );
  check(media.trackId == baseline.track.id, 'Direct source retains identity');
  check(
    direct.calls.length == 1 && fallback.calls.isEmpty,
    'Direct success spawns zero extractors',
  );
  check(
    direct.calls.single.client == 'visionos' &&
        direct.calls.single.cookie == null &&
        direct.calls.single.visitorData == 'fixture-visitor',
    'Warm visitor, no account credentials',
  );
  check(
    resolver.lastReport['path'] == 'direct' &&
        resolver.lastReport['attempts'] == 0,
    'Report direct path separately from extraction',
  );
  check(
    !jsonEncode(resolver.lastReport).contains('googlevideo') &&
        !jsonEncode(resolver.lastReport).contains('fixture-visitor'),
    'Timing report redacts context and URLs',
  );

  final unsupported = baseline.FakeExtractor(
    (_, scope, call) async => {'id': baseline.track.id, 'formats': <Object>[]},
  );
  final recovery = YouTubeResolver.testing(
    extractor: fallback,
    direct: unsupported,
    probe: (_, scope) async {},
  );
  await recovery.resolve(baseline.track, AudioQuality.high, RequestScope());
  check(
    fallback.calls.length == 1 && unsupported.calls.length == 1,
    'One direct attempt then maintained extraction',
  );
  check(
    recovery.lastReport['path'] == 'extractor' &&
        recovery.lastReport['direct_failure'] == 'formatUnavailable',
    'Fallback records the direct failure',
  );

  for (final problem in [
    ProviderProblem.loginRequired,
    ProviderProblem.youtubeRateLimited,
    ProviderProblem.youtubeContentUnavailable,
  ]) {
    final gate = baseline.FakeExtractor(
      (_, scope, call) async => throw ProviderFailure(problem),
    );
    final never = baseline.FakeExtractor(
      (_, scope, call) async => baseline.media(),
    );
    final blocked = YouTubeResolver.testing(
      extractor: never,
      direct: gate,
      probe: (_, scope) async {},
    );
    await fails(
      () => blocked.resolve(baseline.track, AudioQuality.high, RequestScope()),
      problem,
    );
    check(
      never.calls.isEmpty,
      'Do not bypass explicit login, rights, or rate gates',
    );
  }

  final stuck = baseline.FakeExtractor((_, scope, call) async {
    await baseline.untilCancelled(scope);
    return baseline.media();
  });
  final afterTimeout = baseline.FakeExtractor(
    (_, scope, call) async => baseline.media(),
  );
  final deadline = YouTubeResolver.testing(
    extractor: afterTimeout,
    direct: stuck,
    probe: (_, scope) async {},
    fastTimeout: const Duration(milliseconds: 40),
  );
  final parent = RequestScope();
  await deadline.resolve(baseline.track, AudioQuality.high, parent);
  check(
    afterTimeout.calls.length == 1 && !parent.cancelled,
    'Fast budget cancels only its own operation, allowing bounded fallback',
  );
  check(
    deadline.lastReport['direct_failure'] == 'timeout',
    'Direct timeout is diagnosed',
  );

  final cancelFallback = baseline.FakeExtractor(
    (_, scope, call) async => baseline.media(),
  );
  final cancel = YouTubeResolver.testing(
    extractor: cancelFallback,
    direct: stuck,
    probe: (_, scope) async {},
  );
  final cancellation = RequestScope();
  final pending = fails(
    () => cancel.resolve(baseline.track, AudioQuality.high, cancellation),
    ProviderProblem.cancelled,
  );
  cancellation.cancel();
  await pending;
  check(
    cancelFallback.calls.isEmpty,
    'Cancellation never starts recovery extraction',
  );

  final accountDirect = baseline.FakeExtractor(
    (_, scope, call) async => baseline.media(),
  );
  final accountExtractor = baseline.FakeExtractor(
    (_, scope, call) async => baseline.media(),
  );
  await YouTubeResolver.testing(
    extractor: accountExtractor,
    direct: accountDirect,
    probe: (_, scope) async {},
  ).resolve(
    baseline.track,
    AudioQuality.high,
    RequestScope(),
    cookie: 'synthetic-account',
  );
  check(
    accountDirect.calls.isEmpty &&
        accountExtractor.calls.single.cookie == 'synthetic-account',
    'Signed-in playback is not silently downgraded to a guest',
  );

  final multiple = baseline.FakeExtractor(
    (_, scope, call) async => {
      ...baseline.media(format: '251'),
      'formats': [baseline.media(format: '140')],
    },
  );
  final pinned =
      await YouTubeResolver.testing(
        extractor: fallback,
        direct: multiple,
        probe: (_, scope) async {},
      ).resolve(
        baseline.track,
        AudioQuality.high,
        RequestScope(),
        requiredRendition: 'youtube:140',
      );
  check(
    pinned.rendition == 'youtube:140',
    'Renewal pins exact rendition in direct path too',
  );

  final direct403 = baseline.FakeExtractor(
    (_, scope, call) async => baseline.media(suffix: 'refused'),
  );
  final fresh = baseline.FakeExtractor(
    (_, scope, call) async => baseline.media(suffix: 'fresh'),
  );
  final recovered = await YouTubeResolver.testing(
    extractor: fresh,
    direct: direct403,
    probe: (media, scope) async {
      if (media.url.queryParameters['fixture'] == 'refused') {
        throw const ProviderFailure(ProviderProblem.expired, 403);
      }
    },
  ).resolve(baseline.track, AudioQuality.high, RequestScope());
  check(
    recovered.url.queryParameters['fixture'] == 'fresh' &&
        fresh.calls.length == 1,
    'Media refusal obtains a new independently verified source',
  );
}

Map<String, dynamic> playerResponse({String id = 'aaaaaaaaaaa'}) => {
  'playabilityStatus': {'status': 'OK'},
  'videoDetails': {'videoId': id},
  'streamingData': {
    'adaptiveFormats': [
      {
        'itag': 140,
        'mimeType': 'audio/mp4; codecs="mp4a.40.2"',
        'bitrate': 128000,
        'url': 'https://r1.googlevideo.com/videoplayback?fixture=140',
      },
      {
        'itag': 251,
        'mimeType': 'audio/webm; codecs="opus"',
        'bitrate': 160000,
        'url': 'https://r1.googlevideo.com/videoplayback?fixture=251',
      },
      {
        'itag': 18,
        'mimeType': 'video/mp4; codecs="mp4a.40.2"',
        'bitrate': 1000000,
        'url': 'https://r1.googlevideo.com/video',
      },
      {
        'itag': 999,
        'mimeType': 'audio/webm; codecs="opus"',
        'bitrate': 500000,
        'signatureCipher': 'synthetic-not-deciphered',
      },
    ],
  },
};

Future<void> catalogAndApiChecks() async {
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  final base = Uri.parse('http://127.0.0.1:${server.port}/');
  var shells = 0;
  final visitors = <String?>[];
  final bodies = <Map>[];
  var leakedCredentials = false;
  server.listen((request) async {
    try {
      request.response.headers.contentType = ContentType.json;
      if (request.uri.path == '/') {
        shells++;
        request.response.write(
          '{"INNERTUBE_CLIENT_VERSION":"1.fixture", "VISITOR_DATA":"warm-visitor"}',
        );
      } else if (request.uri.path == '/youtubei/v1/player') {
        visitors.add(request.headers.value('X-Goog-Visitor-Id'));
        leakedCredentials |=
            request.headers.value('cookie') != null ||
            request.headers.value('authorization') != null;
        bodies.add(jsonDecode(await utf8.decoder.bind(request).join()) as Map);
        request.response.write(jsonEncode(playerResponse()));
      } else {
        await request.drain<void>();
        request.response.write(
          '{"contents":{"sectionListRenderer":{"contents":[]}}}',
        );
      }
    } finally {
      await request.response.close();
    }
  });
  try {
    final fallback = baseline.FakeExtractor(
      (_, scope, call) async => baseline.media(),
    );
    final resolver = YouTubeResolver.testing(
      extractor: fallback,
      direct: YouTubeDirectExtractor.testing(const ProviderHttp(), base),
      probe: (_, scope) async {},
    );
    final youtube = YouTubeMusic(
      const ProviderHttp(),
      origin: base,
      resolver: resolver,
    );
    await youtube.search('fixture', RequestScope());
    final first = await youtube.resolve(
      baseline.track,
      AudioQuality.medium,
      RequestScope(),
    );
    await youtube.resolve(baseline.track, AudioQuality.high, RequestScope());
    check(
      shells == 1 &&
          visitors.length == 2 &&
          visitors.every((v) => v == 'warm-visitor'),
      'Playback reuses healthy catalog context without another shell fetch',
    );
    check(
      !leakedCredentials && fallback.calls.isEmpty,
      'Real direct API adapter is guest-only and avoids extractor',
    );
    final client = (bodies.first['context'] as Map)['client'] as Map;
    check(
      client['clientName'] == 'VISIONOS' &&
          client['clientVersion'] == '0.1' &&
          bodies.first['videoId'] == baseline.track.id,
      'Expected direct player protocol',
    );
    check(
      first.rendition == 'youtube:140',
      'Quality mapping uses the common independent format validator',
    );
    final generation = youtube.accountGeneration;
    final renewed = await youtube.renew(
      baseline.track,
      AudioQuality.high,
      first,
      RequestScope(),
      generation: generation,
    );
    check(
      renewed.rendition == first.rendition,
      'Provider renewal retains format despite changed quality',
    );
    youtube.signOut();
    await youtube.resolve(baseline.track, AudioQuality.high, RequestScope());
    check(
      visitors.last == null,
      'Account-generation changes invalidate the old visitor',
    );
    await fails(
      () => youtube.renew(
        baseline.track,
        AudioQuality.high,
        first,
        RequestScope(),
        generation: generation,
      ),
      ProviderProblem.cancelled,
    );
    final normalized = YouTubeDirectExtractor.normalize(
      playerResponse(),
      baseline.track.id,
    );
    final options = YouTubeFormats.candidates(
      normalized,
      baseline.track,
      AudioQuality.high,
    );
    check(
      options.length == 2,
      'Video and cipher-only formats cannot reach the player',
    );
    await fails(
      () async => YouTubeDirectExtractor.normalize(
        playerResponse(id: 'bbbbbbbbbbb'),
        baseline.track.id,
      ),
      ProviderProblem.malformed,
    );
    await fails(
      () => YouTubeDirectExtractor.testing(const ProviderHttp(), base).extract(
        const YouTubeExtraction(
          'aaaaaaaaaaa',
          'visionos',
          visitorData: 'bad\nheader',
        ),
        RequestScope(),
      ),
      ProviderProblem.malformed,
    );
  } finally {
    await server.close(force: true);
  }
}

Future<void> prefixAndConnectionChecks() async {
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  final base = Uri.parse('http://127.0.0.1:${server.port}/media');
  final bytes = Uint8List.fromList(
    List.generate(2 * 1024 * 1024, (i) => i % 251),
  );
  final ranges = <List<int>>[];
  final ports = <int>[];
  var denyLater = false, badValidator = false, badMime = false;
  server.listen((request) async {
    try {
      final match = RegExp(
        r'^bytes=(\d+)-(\d+)$',
      ).firstMatch(request.headers.value('range') ?? '');
      if (match == null) {
        request.response.statusCode = 400;
        return;
      }
      final start = int.parse(match[1]!),
          end = math.min(int.parse(match[2]!), bytes.length - 1);
      ranges.add([start, end]);
      ports.add(request.connectionInfo!.remotePort);
      if (denyLater && start >= 1024 * 1024) {
        request.response.statusCode = 403;
        return;
      }
      request.response.statusCode = 206;
      request.response.headers.set(
        'content-type',
        badMime ? 'text/html' : 'audio/webm',
      );
      request.response.headers.set(
        'content-range',
        'bytes $start-$end/${bytes.length}',
      );
      request.response.headers.set(
        'etag',
        badValidator && start >= 1024 * 1024 ? '"changed"' : '"fixture"',
      );
      request.response.contentLength = end - start + 1;
      request.response.add(Uint8List.sublistView(bytes, start, end + 1));
    } finally {
      await request.response.close();
    }
  });
  final raw = ResolvedMedia(
    provider: 'youtube',
    trackId: 'aaaaaaaaaaa',
    rendition: 'youtube:140',
    url: base,
  );
  bool allow(Uri target) => target.origin == base.origin;
  SourceBroker? broker;
  try {
    final prepared = await YouTubeMediaProbe.prepare(
      raw,
      RequestScope(),
      targetAllowed: allow,
    );
    check(
      ranges.length == 2 && ranges.every((r) => r[1] - r[0] + 1 == 16384),
      'Large-file preflight fetches only two 16 KiB ranges',
    );
    check(ranges[1][0] == 1048576, 'Keep validation beyond 1 MiB');
    check(ports[0] == ports[1], 'Preflight reuses its scoped HTTP connection');
    check(
      prepared.warmup!.verifiedBytes == 32768 &&
          prepared.warmup!.prefix.length == 16384,
      'Prefix and byte-budget accounting',
    );
    check(
      raw.warmup == null,
      'Preparing a candidate does not mutate shared media',
    );
    await fails(
      () async => ResolvedMedia(
        provider: 'youtube',
        trackId: raw.trackId,
        rendition: 'youtube:251',
        url: base,
      ).withWarmup(prepared.warmup!),
      ProviderProblem.integrity,
    );

    broker = await SourceBroker.open(prepared);
    check(
      ranges.length == 2,
      'Broker startup makes zero duplicate upstream requests',
    );
    const http = ProviderHttp();
    final prefix = await http.request(
      Uri.parse(broker.path),
      RequestScope(),
      headers: {'Range': 'bytes=0-4095'},
    );
    check(
      prefix.status == 206 &&
          prefix.bytes.length == 4096 &&
          prefix.bytes.every((b) => b < 251) &&
          ranges.length == 2,
      'Native can obtain the validated prefix without waiting for another media read',
    );
    final next = await http.request(
      Uri.parse(broker.path),
      RequestScope(),
      headers: {'Range': 'bytes=16384-32767'},
    );
    check(
      ranges.length == 3 && ranges.last[0] == 16384 && ranges.last[1] == 524287,
      'Remainder begins after the prefix, not from byte zero',
    );
    check(
      next.bytes.length == 16384 &&
          List.generate(
            next.bytes.length,
            (i) => next.bytes[i] == bytes[16384 + i],
          ).every((v) => v),
      'Prefix and remaining media reconstruct exact bytes',
    );
    final nextBlock = await http.request(
      Uri.parse(broker.path),
      RequestScope(),
      headers: {'Range': 'bytes=524288-524543'},
    );
    check(
      nextBlock.bytes.length == 256 && ports[2] == ports[3],
      'YouTube broker reuses connections across media spans',
    );
    await broker.close();
    broker = null;
    denyLater = true;
    await fails(
      () =>
          YouTubeMediaProbe.prepare(raw, RequestScope(), targetAllowed: allow),
      ProviderProblem.expired,
    );
    denyLater = false;
    badValidator = true;
    await fails(
      () =>
          YouTubeMediaProbe.prepare(raw, RequestScope(), targetAllowed: allow),
      ProviderProblem.integrity,
    );
    badValidator = false;
    badMime = true;
    await fails(
      () =>
          YouTubeMediaProbe.prepare(raw, RequestScope(), targetAllowed: allow),
      ProviderProblem.integrity,
    );
  } finally {
    await broker?.close();
    await server.close(force: true);
  }
}

Future<void> transportChecks() async {
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  final other = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  var crossed = false;
  other.listen((r) async {
    crossed = true;
    await r.response.close();
  });
  final base = Uri.parse('http://127.0.0.1:${server.port}/');
  server.listen((r) async {
    try {
      if (r.uri.path == '/redirect') {
        r.response.statusCode = 302;
        r.response.headers.set('location', 'http://127.0.0.1:${other.port}/');
      } else if (r.uri.path == '/slow') {
        await Future<void>.delayed(const Duration(milliseconds: 600));
        r.response.write('slow');
      } else {
        r.response.write('ok');
      }
      await r.response.close();
    } catch (_) {
      /* cancellation intentionally closes the request socket */
    }
  });
  final http = ProviderHttp.persistent(
    timeout: const Duration(milliseconds: 200),
  );
  try {
    await fails(
      () => http.request(
        base.resolve('/redirect'),
        RequestScope(),
        targetAllowed: (target) => target.origin == base.origin,
      ),
      ProviderProblem.unsupported,
    );
    check(
      !crossed,
      'Explicit media target policy applies before following redirects',
    );
    await fails(
      () => http.request(base.resolve('/slow'), RequestScope()),
      ProviderProblem.timeout,
    );
    final recovered = await http.request(base, RequestScope());
    check(
      utf8.decode(recovered.bytes) == 'ok',
      'Timeout discards connection without poisoning future requests',
    );
    http.close();
    await fails(
      () => http.request(base, RequestScope()),
      ProviderProblem.cancelled,
    );
  } finally {
    http.close();
    await server.close(force: true);
    await other.close(force: true);
  }
}

Future<void> youtubeFastPathChecks() async {
  _checks = 0;
  await resolverChecks();
  await catalogAndApiChecks();
  await prefixAndConnectionChecks();
  await transportChecks();
  stdout.writeln(
    'YouTube fast-path contracts PASS ($_checks assertions; synthetic/loopback only)',
  );
}

Future<void> main() => youtubeFastPathChecks();
