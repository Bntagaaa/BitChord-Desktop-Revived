// Deterministic regression tests for the production YouTube backend only.
// No real account, Google request, signed URL or actual user profile is used.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/youtube/accounts.dart';
import '../lib/features/online/data/youtube/catalog_parser.dart';
import '../lib/features/online/data/youtube/extractor.dart';
import '../lib/features/online/data/youtube/formats.dart';
import '../lib/features/online/data/youtube/runtime.dart';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/data/youtube_resolver.dart';
import '../lib/features/online/domain/catalog.dart';

var assertions = 0;
void check(bool condition, String reason) {
  assertions++;
  if (!condition) throw StateError(reason);
}

Future<void> fails(
  Future<Object?> Function() work,
  ProviderProblem expected,
) async {
  try {
    await work();
  } on ProviderFailure catch (error) {
    check(
      error.problem == expected,
      'Expected $expected; got ${error.problem}',
    );
    return;
  }
  throw StateError('Expected $expected');
}

const track = CatalogEntry(
  provider: 'youtube',
  id: 'aaaaaaaaaaa',
  title: 'Fixture',
);
Map<String, dynamic> media({
  String format = '140',
  String suffix = '',
  String id = 'aaaaaaaaaaa',
}) => {
  'id': id,
  'format_id': format,
  'protocol': 'https',
  'vcodec': 'none',
  'acodec': 'mp4a.40.2',
  'abr': 128,
  'url': 'https://r1.googlevideo.com/videoplayback?fixture=$suffix',
};

final class FakeExtractor implements YouTubeExtractor {
  FakeExtractor(this.work);
  final Future<Map<String, dynamic>> Function(
    YouTubeExtraction,
    RequestScope,
    int,
  )
  work;
  final calls = <YouTubeExtraction>[];
  @override
  Future<Map<String, dynamic>> extract(
    YouTubeExtraction request,
    RequestScope scope,
  ) {
    calls.add(request);
    return work(request, scope, calls.length);
  }
}

Future<void> untilCancelled(RequestScope scope) async {
  final done = Completer<void>();
  final detach = scope.onCancel(() {
    if (!done.isCompleted) done.complete();
  });
  try {
    await done.future;
    scope.check();
  } finally {
    detach();
  }
}

Future<void> resolverChecks() async {
  final fresh = FakeExtractor((_, scope, call) async => media(suffix: '$call'));
  var probes = 0;
  final resolver = YouTubeResolver.testing(
    extractor: fresh,
    probe: (candidate, scope) async {
      probes++;
      if (candidate.url.queryParameters['fixture'] == '1') {
        throw const ProviderFailure(ProviderProblem.expired, 403);
      }
    },
  );
  final recovered = await resolver.resolve(
    track,
    AudioQuality.high,
    RequestScope(),
  );
  check(
    recovered.url.queryParameters['fixture'] == '2',
    '403 must obtain a fresh URL',
  );
  check(
    fresh.calls.map((c) => c.client).join(',') == 'mweb,mweb',
    'Refresh same token client before alternate',
  );
  check(
    probes == 2 && resolver.lastReport['result'] == 'range_verified',
    'Real probe required',
  );
  check(
    !jsonEncode(resolver.lastReport).contains('googlevideo'),
    'Diagnostics contain no signed URLs',
  );

  final refused = FakeExtractor(
    (_, scope, call) async => media(suffix: '$call'),
  );
  final blocked = YouTubeResolver.testing(
    extractor: refused,
    probe: (_, scope) async {
      throw const ProviderFailure(ProviderProblem.expired, 403);
    },
  );
  await fails(
    () => blocked.resolve(track, AudioQuality.high, RequestScope()),
    ProviderProblem.mediaRefused,
  );
  check(
    refused.calls.map((c) => c.client).join(',') == 'mweb,mweb,android_vr',
    'Bounded explicit recovery ladder',
  );
  for (final terminal in [
    ProviderProblem.loginRequired,
    ProviderProblem.youtubeRateLimited,
    ProviderProblem.youtubeContentUnavailable,
  ]) {
    final fake = FakeExtractor(
      (_, scope, call) async => throw ProviderFailure(terminal),
    );
    final test = YouTubeResolver.testing(
      extractor: fake,
      probe: (_, scope) async {},
    );
    await fails(
      () => test.resolve(track, AudioQuality.high, RequestScope()),
      terminal,
    );
    check(
      fake.calls.length == 1,
      'Do not hammer upstream rights/auth/rate gates',
    );
  }
  var limitedProbes = 0;
  await fails(
    () => YouTubeResolver.firstPlayable(
      [
        YouTubeFormats.parse(media(), track),
        YouTubeFormats.parse(media(suffix: 'second'), track),
      ],
      (_) async {
        limitedProbes++;
        throw const ProviderFailure(ProviderProblem.unavailable, 429);
      },
    ),
    ProviderProblem.youtubeRateLimited,
  );
  check(limitedProbes == 1, 'CDN 429 stops candidate retries too');
  final never = FakeExtractor((_, scope, call) async {
    await untilCancelled(scope);
    return media();
  });
  final deadline = YouTubeResolver.testing(
    extractor: never,
    probe: (_, scope) async {},
    timeout: const Duration(milliseconds: 40),
  );
  final watch = Stopwatch()..start();
  await fails(
    () => deadline.resolve(track, AudioQuality.high, RequestScope()),
    ProviderProblem.timeout,
  );
  check(watch.elapsedMilliseconds < 1500, 'One end-to-end deadline');
  final parent = RequestScope();
  final cancelling = YouTubeResolver.testing(
    extractor: never,
    probe: (_, scope) async {},
  );
  final future = fails(
    () => cancelling.resolve(track, AudioQuality.high, parent),
    ProviderProblem.cancelled,
  );
  parent.cancel();
  await future;

  final formats = FakeExtractor(
    (_, scope, call) async => {
      ...media(format: '251'),
      'formats': [media(format: '140', suffix: 'same-rendition')],
    },
  );
  final pinned = YouTubeResolver.testing(
    extractor: formats,
    probe: (_, scope) async {},
  );
  final renewed = await pinned.resolve(
    track,
    AudioQuality.high,
    RequestScope(),
    requiredRendition: 'youtube:140',
  );
  check(
    renewed.rendition == 'youtube:140',
    'Never splice another encoding into an open file',
  );
  final youtube = YouTubeMusic(const ProviderHttp(), resolver: pinned);
  final generation = youtube.accountGeneration;
  youtube.signOut();
  await fails(
    () => youtube.renew(
      track,
      AudioQuality.high,
      renewed,
      RequestScope(),
      generation: generation,
    ),
    ProviderProblem.cancelled,
  );

  final authenticated = FakeExtractor((_, scope, call) async {
    if (call == 1)
      throw const ProviderFailure(ProviderProblem.formatUnavailable);
    return media();
  });
  final auth = YouTubeResolver.testing(
    extractor: authenticated,
    probe: (_, scope) async {},
  );
  await auth.resolve(
    track,
    AudioQuality.high,
    RequestScope(),
    cookie: 'synthetic',
  );
  check(
    authenticated.calls.map((c) => c.client).join(',') == 'mweb,web_music',
    'Authenticated recovery never downgrades to guest',
  );
  check(
    authenticated.calls.every((c) => c.cookie == 'synthetic'),
    'Account bound across attempts',
  );
}

Future<void> formatChecks() async {
  final selected = media();
  final data = {
    'id': track.id,
    'formats': [
      {...selected, 'url': null},
      {...selected, 'url': 'https://googlevideo.com.attacker.invalid/x'},
      {...selected, 'has_drm': true},
      {...selected, 'protocol': 'm3u8_native'},
      {...selected, 'acodec': null},
      {...selected, 'url': 'https://r1.googlevideo.com:9443/x'},
      {...selected, 'url': 'https://user:secret@r1.googlevideo.com/x'},
      {...selected, 'url': 'https://r1.googlevideo.com/x?expire=1'},
      {
        ...selected,
        'format_id': '250',
        'abr': 'missing',
        'url': 'https://r1.googlevideo.com/valid',
      },
    ],
  };
  final accepted = YouTubeFormats.candidates(data, track, AudioQuality.low);
  check(
    accepted.length == 1 && accepted.first.rendition == 'youtube:250',
    'Validate format fields independently, tolerate absent ABR',
  );
  final headers = YouTubeFormats.parse({
    ...selected,
    'http_headers': {
      'Cookie': 'secret',
      'Authorization': 'secret',
      'Origin': 'https://bad.invalid',
      'User-Agent': 'safe',
      'Referer': 'https://www.youtube.com/',
      'Accept': 'x\r\ny',
    },
  }, track).headers;
  check(
    headers.length == 2 && headers['User-Agent'] == 'safe',
    'CDN credential/header isolation',
  );
  final many = {
    'id': track.id,
    'formats': List.generate(40, (i) => media(format: '$i', suffix: '$i')),
  };
  check(
    YouTubeFormats.candidates(many, track, AudioQuality.high).length == 3,
    'Bound candidate probes',
  );
  check(YouTubeCatalogParser.duration('1:02:03') == 3723, 'Duration parsing');
  check(
    YouTubeCatalogParser.duration('not a duration') == null,
    'No fabricated duration',
  );
  YouTubeMusic.parsePage({
    'contents': [
      {
        'musicTwoRowItemRenderer': {
          'title': 42,
          'navigationEndpoint': true,
          'thumbnail': false,
        },
      },
    ],
  });
  assertions++;
}

Future<void> catalogChecks() async {
  final continued = YouTubeCatalogParser.parsePage({
    'contents': {
      'singleColumnBrowseResultsRenderer': {'tabs': []},
    },
    'continuationContents': {
      'sectionListContinuation': {
        'contents': [
          {
            'musicCarouselShelfRenderer': {
              'header': {
                'musicCarouselShelfBasicHeaderRenderer': {
                  'title': {'simpleText': 'Next shelf'},
                },
              },
              'contents': [
                {
                  'musicTwoRowItemRenderer': {
                    'title': {'simpleText': 'Next album'},
                    'navigationEndpoint': {
                      'browseEndpoint': {'browseId': 'MPREnext'},
                    },
                  },
                },
              ],
              'continuations': [
                {
                  'nextContinuationData': {'continuation': 'wrong-shelf-token'},
                },
              ],
            },
          },
        ],
        'continuations': [
          {
            'nextContinuationData': {'continuation': 'next-home'},
          },
        ],
      },
    },
  });
  check(
    continued.entries.single.id == 'MPREnext' &&
        continued.shelves.single.title == 'Next shelf',
    'Home continuation content takes precedence over empty tab shell',
  );
  check(
    continued.continuation == 'next-home',
    'Continue outer Home feed, never a carousel',
  );
  final searched = YouTubeCatalogParser.parsePage({
    'contents': {
      'tabbedSearchResultsRenderer': {
        'tabs': [
          {
            'tabRenderer': {
              'content': {
                'sectionListRenderer': {
                  'contents': [
                    {
                      'musicShelfRenderer': {
                        'contents': [],
                        'continuations': [
                          {
                            'nextContinuationData': {
                              'continuation': 'search-next',
                            },
                          },
                        ],
                      },
                    },
                  ],
                },
              },
            },
          },
        ],
      },
    },
  }, search: true);
  check(
    searched.continuation == 'search-next',
    'Filtered Search continues its result shelf',
  );
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  final origin = Uri.parse('http://127.0.0.1:${server.port}');
  var shells = 0, calls = 0, mode = 'refresh';
  final seen = <Map<String, dynamic>>[];
  server.listen((request) async {
    try {
      if (request.uri.path == '/') {
        shells++;
        request.response.write(
          '"INNERTUBE_CLIENT_VERSION":"version-$shells","VISITOR_DATA":"visitor-$shells"',
        );
      } else {
        calls++;
        seen.add(
          jsonDecode(await utf8.decoder.bind(request).join())
              as Map<String, dynamic>,
        );
        if (request.uri.path == '/youtubei/v1/music/get_search_suggestions') {
          check(seen.last['input'] == 'fixture', 'Suggestion input contract');
          request.response.write(
            jsonEncode({
              'contents': [
                {
                  'searchSuggestionRenderer': {
                    'suggestion': {
                      'runs': [
                        {'text': 'display'},
                        {'text': ' text'},
                      ],
                    },
                    'navigationEndpoint': {
                      'searchEndpoint': {'query': 'fixture completion'},
                    },
                  },
                },
                {
                  'searchSuggestionRenderer': {
                    'suggestion': {
                      'runs': [
                        {'text': 'fixture'},
                        {'text': ' artist'},
                      ],
                    },
                  },
                },
                {
                  'searchSuggestionRenderer': {
                    'navigationEndpoint': {
                      'searchEndpoint': {'query': 'fixture completion'},
                    },
                  },
                },
                {
                  'musicResponsiveListItemRenderer': {
                    'title': {'simpleText': 'Not a query'},
                  },
                },
              ],
            }),
          );
        } else if (mode == 'refresh' && calls == 1) {
          request.response.statusCode = 400;
        } else if (mode == 'limited') {
          request.response.statusCode = 429;
        } else if (mode == 'cancel') {
          await Future<void>.delayed(const Duration(milliseconds: 150));
          request.response.write('{}');
        } else {
          request.response.write('{}');
        }
      }
      await request.response.close();
    } catch (_) {} // A deliberately cancelled fixture closes its socket.
  });
  final youtube = YouTubeMusic(const ProviderHttp(), origin: origin);
  try {
    await youtube.search('fixture', RequestScope());
    check(
      shells == 2 && calls == 2,
      'Stale client context refreshes exactly once',
    );
    check(
      (seen.last['context'] as Map)['client']['visitorData'] == 'visitor-2',
      'Keep visitor and client version together',
    );
    final suggestions = await youtube.suggestions('fixture', RequestScope());
    check(
      suggestions.join('|') == 'fixture completion|fixture artist',
      'Suggestion order, endpoint precedence, run joining and deduplication',
    );
    final beforeEmpty = calls;
    check(
      (await youtube.suggestions('  ', RequestScope())).isEmpty &&
          calls == beforeEmpty,
      'Blank input makes no provider request',
    );
    mode = 'limited';
    final before = calls;
    await fails(
      () => youtube.browse('FEmusic_home', RequestScope()),
      ProviderProblem.youtubeRateLimited,
    );
    check(calls == before + 1, '429 is not blindly retried');
    mode = 'cancel';
    final scope = RequestScope();
    final pending = fails(
      () => youtube.search('fixture', scope),
      ProviderProblem.cancelled,
    );
    await Future<void>.delayed(const Duration(milliseconds: 20));
    youtube.signOut();
    await pending;
    final accounts = YouTubeAccounts();
    final ready = Completer<void>();
    final owned = fails(
      () => accounts.run(RequestScope(), (scope, session) async {
        ready.complete();
        await untilCancelled(scope);
        return null;
      }),
      ProviderProblem.cancelled,
    );
    await ready.future;
    accounts.signOut();
    await owned;
  } finally {
    await server.close(force: true);
  }
}

Future<void> subprocessChecks() async {
  final temp = await Directory.systemTemp.createTemp(
    'bitchord-extractor-fixture-',
  );
  final source = File('${temp.path}/extractor.dart');
  final executable = '${temp.path}/fixture${Platform.isWindows ? '.exe' : ''}';
  await source.writeAsString(r'''
import 'dart:async';
import 'dart:convert';
import 'dart:io';
Future<void> main(List<String> args) async {
  final id=Uri.parse(args.last).queryParameters['v'];
  if(id=='bbbbbbbbbbb') { await Future<void>.delayed(const Duration(seconds:30)); return; }
  if(id=='ccccccccccc') { stdout.add(List.filled(9*1024*1024,65)); await stdout.flush(); return; }
  final input=await utf8.decoder.bind(stdin).join();
  final result=jsonEncode({'id':id,'title':'Beyonc\u00e9'.replaceAll(r'\u00e9','\u00e9'),
    'home':Platform.environment['HOME'],'cache':Platform.environment['XDG_CACHE_HOME'],
    'pipe':input.contains('synthetic-never-real'), 'argsHaveSecret':args.join().contains('synthetic-never-real')});
  final bytes=utf8.encode(result);
  for(final b in bytes) { stdout.add([b]); await stdout.flush(); }
  await stdout.flush();
}
''');
  try {
    final compiled = await Process.run(Platform.resolvedExecutable, [
      'compile',
      'exe',
      source.path,
      '-o',
      executable,
    ]);
    check(
      compiled.exitCode == 0,
      'Compile subprocess test with the actual pinned SDK',
    );
    final plugins = await Directory('${temp.path}/plugins').create();
    await File(
      '${plugins.path}/bitchord-session.zip',
    ).writeAsString('only a fixture capability marker');
    final extractor = YouTubeProcessExtractor(
      YouTubeRuntime(executable, pluginDirectory: plugins.path),
    );
    final first = await extractor.extract(
      const YouTubeExtraction(
        'aaaaaaaaaaa',
        'mweb',
        cookie: 'SAPISID=synthetic-never-real',
      ),
      RequestScope(),
    );
    check(
      first['pipe'] == true && first['argsHaveSecret'] == false,
      'Secrets reach stdin, never process arguments',
    );
    check(
      first['title'] == 'Beyonc\u00e9',
      'UTF-8 metadata survives byte-wise pipe writes',
    );
    check(first['home'] == first['cache'], 'BgUtils uses isolated owned cache');
    check(
      !Directory(first['home'] as String).existsSync(),
      'Owned scratch removed after success',
    );
    final second = await extractor.extract(
      const YouTubeExtraction('aaaaaaaaaaa', 'mweb'),
      RequestScope(),
    );
    check(
      first['home'] != second['home'],
      'Each extraction obtains fresh token context',
    );
    final slow = RequestScope();
    final queued = RequestScope();
    final one = fails(
      () => extractor.extract(
        const YouTubeExtraction('bbbbbbbbbbb', 'mweb'),
        slow,
      ),
      ProviderProblem.cancelled,
    );
    final two = fails(
      () => extractor.extract(
        const YouTubeExtraction('aaaaaaaaaaa', 'mweb'),
        queued,
      ),
      ProviderProblem.cancelled,
    );
    queued.cancel();
    await two;
    await Future<void>.delayed(const Duration(milliseconds: 40));
    slow.cancel();
    await one;
    final after = await extractor
        .extract(const YouTubeExtraction('aaaaaaaaaaa', 'mweb'), RequestScope())
        .timeout(const Duration(seconds: 6));
    check(
      after['id'] == 'aaaaaaaaaaa',
      'Cancelled queue and child cannot wedge later playback',
    );
    await fails(
      () => extractor.extract(
        const YouTubeExtraction('ccccccccccc', 'mweb'),
        RequestScope(),
      ),
      ProviderProblem.malformed,
    );
  } finally {
    await temp.delete(recursive: true);
  }
}

Future<void> youtubeRewriteChecks() async {
  await resolverChecks();
  await formatChecks();
  await catalogChecks();
  await subprocessChecks();
  stdout.writeln(
    'YouTube rewrite contracts PASS ($assertions assertions; synthetic/offline only)',
  );
}

Future<void> main() => youtubeRewriteChecks();
