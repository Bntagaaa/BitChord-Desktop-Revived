import 'dart:convert';
import 'dart:io';

import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/data/youtube_resolver.dart';
import '../lib/features/online/data/youtube_session.dart';
import '../lib/features/online/data/recording_fallback.dart';
import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/domain/catalog.dart';

void require(bool value, String message) {
  if (!value) throw StateError(message);
}

Map<String, dynamic> card(String id, {bool overlay = false}) => {
  'musicTwoRowItemRenderer': {
    'title': {
      'runs': [
        {'text': 'Fixture $id'},
      ],
    },
    'navigationEndpoint': {
      'browseEndpoint': {'browseId': id, 'params': 'opaque params'},
    },
    if (overlay)
      'overlay': {
        'watchEndpoint': {'videoId': 'aaaaaaaaaaa'},
      },
    'thumbnailRenderer': {
      'musicThumbnailRenderer': {
        'thumbnail': {
          'thumbnails': [
            {
              'url': 'https://example.invalid/art.jpg',
              'width': 300,
              'height': 300,
            },
          ],
        },
      },
    },
  },
};
Map<String, dynamic> track(String id) => {
  'musicResponsiveListItemRenderer': {
    'playlistItemData': {'videoId': id},
    'flexColumns': [
      {
        'musicResponsiveListItemFlexColumnRenderer': {
          'text': {
            'runs': [
              {'text': 'Fixture $id'},
            ],
          },
        },
      },
    ],
  },
};

Future<void> youtubeContracts() async {
  final feed = YouTubeMusic.parsePage({
    'contents': {
      'sectionListRenderer': {
        'contents': [
          {
            'musicCarouselShelfRenderer': {
              'header': {
                'musicCarouselShelfBasicHeaderRenderer': {
                  'title': {
                    'runs': [
                      {
                        'text': 'Albums',
                        'navigationEndpoint': {
                          'browseEndpoint': {
                            'browseId': 'more',
                            'params': 'more params',
                          },
                        },
                      },
                    ],
                  },
                  'strapline': {
                    'runs': [
                      {'text': 'New releases'},
                    ],
                  },
                },
              },
              'contents': [card('MPREalbum', overlay: true), card('UCartist')],
            },
          },
          {
            'musicShelfRenderer': {
              'title': {'simpleText': 'Quick picks'},
              'contents': [track('aaaaaaaaaaa'), track('bbbbbbbbbbb')],
            },
          },
        ],
        'continuations': [
          {
            'nextContinuationData': {'continuation': 'feed-next'},
          },
        ],
      },
    },
  });
  require(
    feed.shelves.length == 2 && feed.entries.length == 4,
    'Shelf grouping/count',
  );
  require(
    feed.shelves.first.title == 'Albums' &&
        feed.shelves.first.subtitle == 'New releases',
    'Shelf title/strapline',
  );
  require(
    feed.shelves.first.moreId == 'more' &&
        feed.shelves.first.params == 'more params',
    'Shelf navigation',
  );
  require(
    feed.entries.first.kind == CatalogKind.album &&
        feed.entries[1].kind == CatalogKind.artist,
    'Browse targets must win over collection play overlays',
  );
  require(
    feed.entries.first.artwork != null && feed.continuation == 'feed-next',
    'Artwork/feed continuation',
  );
  require(feed.shelves.last.layout == ShelfLayout.tracks, 'Track shelf layout');
  final localToken = YouTubeMusic.parsePage({
    'contents': {
      'sectionListRenderer': {
        'contents': [
          {
            'musicCarouselShelfRenderer': {
              'contents': [card('MPRElocal')],
              'continuations': [
                {
                  'nextContinuationData': {'continuation': 'shelf-only'},
                },
              ],
            },
          },
        ],
      },
    },
  });
  require(
    localToken.continuation == null,
    'Carousel token must not page the feed',
  );
  final album = YouTubeMusic.parsePage({
    'contents': {
      'musicShelfRenderer': {
        'contents': [track('aaaaaaaaaaa')],
      },
      'musicCarouselShelfRenderer': {
        'contents': [card('MPRErecommendation')],
      },
    },
  }, browseId: 'MPREalbum');
  require(
    album.entries.length == 1 && album.entries.single.kind == CatalogKind.track,
    'Album primary songs must exclude related releases',
  );
  final playlist = YouTubeMusic.parsePage({
    'contents': {
      'musicPlaylistShelfRenderer': {
        'contents': [track('aaaaaaaaaaa'), track('aaaaaaaaaaa')],
      },
      'musicShelfRenderer': {
        'contents': [track('ccccccccccc')],
      },
    },
  });
  require(
    playlist.entries.length == 2 &&
        playlist.entries.every((e) => e.id == 'aaaaaaaaaaa'),
    'Keep playlist duplicates and exclude suggested songs',
  );

  const cookie = 'SID=fixture; SAPISID=fixture-secret';
  const signedShell =
      '"LOGGED_IN":true,"SESSION_INDEX":"2","INNERTUBE_CLIENT_VERSION":"fixture-version",'
      '"DATASYNC_ID":"fixture-account||session","DELEGATED_SESSION_ID":"fixture-channel","VISITOR_DATA":"fixture-visitor"';
  final session = YouTubeSession.fromVerifiedShell(cookie, signedShell);
  require(
    session.authUser == '2' &&
        session.pageId == 'fixture-channel' &&
        session.dataSyncId == 'fixture-account',
    'Account scoping',
  );
  require(
    session.headers()['Cookie'] == cookie &&
        !session.toString().contains('fixture'),
    'Session redaction',
  );
  for (final shell in [
    signedShell.replaceFirst('true', 'false'),
    '"LOGGED_IN":false, $signedShell',
    '"LOGGED_IN":false',
  ]) {
    try {
      YouTubeSession.fromVerifiedShell(cookie, shell);
      throw StateError('Signed-out shell accepted');
    } on ProviderFailure catch (e) {
      require(
        e.problem == ProviderProblem.unauthorized,
        'Signed-out classification',
      );
    }
  }
  try {
    YouTubeSession.signingSecret('SAPISID=x\r\nInjected: y');
    throw StateError('Header injection accepted');
  } on ProviderFailure catch (e) {
    require(e.problem == ProviderProblem.malformed, 'Cookie validation');
  }

  const selected = CatalogEntry(
    provider: 'youtube',
    id: 'aaaaaaaaaaa',
    title: 'Fixture',
  );
  final extracted = <String, dynamic>{
    'id': selected.id,
    'url': 'https://r1.googlevideo.com/videoplayback',
    'format_id': '251',
    'acodec': 'opus',
    'vcodec': 'none',
    'protocol': 'https',
    'http_headers': {
      'User-Agent': 'fixture-client',
      'Cookie': cookie,
      'Authorization': 'fixture-secret',
    },
  };
  final media = YouTubeResolver.parse(extracted, selected);
  require(
    media.headers.length == 1 &&
        media.headers['User-Agent'] == 'fixture-client',
    'CDN credential isolation',
  );
  for (final change in <Map<String, dynamic>>[
    {'id': 'wrong'},
    {'vcodec': 'avc1'},
    {'protocol': 'm3u8_native'},
    {'url': 'https://googlevideo.com.attacker.invalid/audio'},
    {'is_live': true},
  ]) {
    try {
      YouTubeResolver.parse({...extracted, ...change}, selected);
      throw StateError('Unsafe extraction accepted');
    } on ProviderFailure catch (e) {
      require(
        e.problem == ProviderProblem.unsupported,
        'Unsupported extraction classification',
      );
    }
  }

  // A trusted plugin must be selected explicitly. No ambient yt-dlp plugins,
  // config files, signed URLs or account credentials may be picked up.
  final unconfigured = YouTubeResolver('/path/to/yt-dlp');
  final baseArgs = unconfigured.arguments(selected, AudioQuality.high);
  require(
    baseArgs.contains('--no-plugin-dirs') &&
        !baseArgs.contains('--plugin-dirs') &&
        !baseArgs.contains('--extractor-args'),
    'No implicit PO Token plugin or extractor overrides',
  );
  final pluginDir = await Directory.systemTemp.createTemp('bitchord-pot-');
  try {
    final enabled = YouTubeResolver(
      '/path/to/yt-dlp',
      poTokenPluginDirectory: pluginDir.path,
    );
    final args = enabled.arguments(selected, AudioQuality.medium);
    final pluginAt = args.indexOf('--plugin-dirs');
    final extractorAt = args.indexOf('--extractor-args');
    require(
      pluginAt > args.indexOf('--no-plugin-dirs') &&
          args[pluginAt + 1] == pluginDir.path &&
          extractorAt > pluginAt &&
          args[extractorAt + 1] == 'youtube:player_client=mweb' &&
          args.contains('bestaudio[protocol=https][abr<=128]') &&
          !args.any((argument) => argument.contains('fixture-secret')),
      'Trusted mweb per-video token provider configuration',
    );
    require(
      !args.contains('--cookies-from-browser') && !args.contains('--cookies'),
      'No account secret import into external extractor',
    );
  } finally {
    await pluginDir.delete(recursive: true);
  }
  try {
    YouTubeResolver(
      '/path/to/yt-dlp',
      poTokenPluginDirectory: '../untrusted',
    ).arguments(selected, AudioQuality.high);
    throw StateError('Relative plugin directory accepted');
  } on ProviderFailure catch (e) {
    require(
      e.problem == ProviderProblem.unsupported,
      'Unsafe plugin directory classification',
    );
  }
  require(
    const ProviderFailure(
      ProviderProblem.mediaRefused,
      403,
    ).toString().contains('PO Token'),
    'Actionable redacted media 403 guidance',
  );

  final bundle = await Directory.systemTemp.createTemp('bitchord-bundle-');
  try {
    final binary = '${Platform.isWindows ? 'yt-dlp.exe' : 'yt-dlp'}';
    final nodeName = '${Platform.isWindows ? 'node.exe' : 'node'}';
    final portable = Directory('${bundle.path}/youtube_runtime');
    await Directory('${portable.path}/plugins').create(recursive: true);
    await Directory('${portable.path}/server/build').create(recursive: true);
    await Directory('${portable.path}/server/node_modules/bgutils-js')
        .create(recursive: true);
    require(
      YouTubeResolver.fromBundleDirectory(bundle) == null,
      'Partial resolver bundle must never be adopted',
    );
    await File('${portable.path}/$binary').writeAsString('fixture');
    await File('${portable.path}/$nodeName').writeAsString('fixture');
    await File('${portable.path}/server/build/generate_once.js')
        .writeAsString('fixture');
    await File('${portable.path}/server/node_modules/bgutils-js/package.json')
        .writeAsString('{}');
    final detected = YouTubeResolver.fromBundleDirectory(bundle);
    if (detected == null) {
      throw StateError('Bundled resolver was not discovered');
    }
    require(
      detected.executable.endsWith(binary) &&
          detected.node!.endsWith(nodeName) &&
          detected.poTokenServerHome != null &&
          detected.poTokenPluginDirectory != null,
      'Portable resolver should auto-configure without environment',
    );
    if (!Platform.isWindows) {
      await detected.prepareBundledRuntime();
      final first = File('${portable.path}/$binary');
      final second = File('${portable.path}/$nodeName');
      require(
        (first.statSync().mode & 0x40) != 0 &&
            (second.statSync().mode & 0x40) != 0,
        'Linux GitHub artifact executable permissions must self-repair',
      );
    }
    final builtArgs = detected.arguments(selected, AudioQuality.high);
    require(
      builtArgs.any(
        (arg) =>
            arg.contains('youtubepot-bgutilscript:server_home=') &&
            arg.contains('player_client=mweb'),
      ),
      'PO Token generation script must be selected automatically',
    );
    await File('${portable.path}/server/build/generate_once.js').delete();
    require(
      YouTubeResolver.fromBundleDirectory(bundle) == null,
      'Broken bundled generator must fail closed',
    );
  } finally {
    await bundle.delete(recursive: true);
  }

  const targetRecording = CatalogEntry(
    provider: 'youtube',
    id: 'aaaaaaaaaaa',
    title: 'Shape of You (Official Audio)',
    subtitle: 'Song • Ed Sheeran • Divide',
    durationSeconds: 233,
  );
  const sameRecording = CatalogEntry(
    provider: 'jiosaavn',
    id: 'matched-track',
    title: 'Shape of You',
    subtitle: 'Ed Sheeran',
    durationSeconds: 235,
  );
  require(
    RecordingFallback.match(targetRecording, [sameRecording])?.id ==
        'matched-track',
    'Conservative Kotlin-inspired source matching',
  );
  for (final other in <CatalogEntry>[
    const CatalogEntry(
      provider: 'jiosaavn',
      id: 'wrong-artist',
      title: 'Shape of You',
      subtitle: 'Other Artist',
      durationSeconds: 233,
    ),
    const CatalogEntry(
      provider: 'jiosaavn',
      id: 'wrong-version',
      title: 'Shape of You (Live)',
      subtitle: 'Ed Sheeran',
      durationSeconds: 233,
    ),
    const CatalogEntry(
      provider: 'jiosaavn',
      id: 'wrong-length',
      title: 'Shape of You',
      subtitle: 'Ed Sheeran',
      durationSeconds: 330,
    ),
  ]) {
    require(
      RecordingFallback.match(targetRecording, [other]) == null,
      'Never substitute a different recording',
    );
  }
  require(
    RecordingFallback.match(
          const CatalogEntry(
            provider: 'youtube',
            id: 'ccccccccccc',
            title: 'Shape of You',
            subtitle: 'Ed Sheeran',
            isVideo: true,
          ),
          [sameRecording],
        ) ==
        null,
    'Video sources must not be substituted with album audio',
  );
  require(
    RecordingFallback.match(
          const CatalogEntry(
            provider: 'youtube',
            id: 'ddddddddddd',
            title: 'Shape of You',
            subtitle: 'Ed Sheeran',
          ),
          [
            sameRecording,
            const CatalogEntry(
              provider: 'jiosaavn',
              id: 'ambiguous-cut',
              title: 'Shape of You',
              subtitle: 'Ed Sheeran',
              durationSeconds: 180,
            ),
          ],
        ) ==
        null,
    'Reject ambiguous cuts when duration is missing',
  );
  require(
    YouTubeResolver.classifyExtractorFailure(
          'ERROR: Sign in to confirm you are not a bot '
          'https://secret.invalid/?token=private',
        ).problem ==
        ProviderProblem.loginRequired,
    'Login-required extraction must not collapse to generic unavailable',
  );
  require(
    YouTubeResolver.classifyExtractorFailure(
          'ERROR: requested format is not available',
        ).problem ==
        ProviderProblem.formatUnavailable,
    'No progressive format must be reported explicitly',
  );
  require(
    !YouTubeResolver.classifyExtractorFailure(
      'ERROR: Sign in to confirm you are not a bot https://secret.invalid',
    ).toString().contains('secret.invalid'),
    'Never surface URL-bearing extractor diagnostics',
  );

  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  final bodies = <Map<String, dynamic>>[];
  server.listen((r) async {
    if (r.uri.path == '/') {
      r.response.write('"INNERTUBE_CLIENT_VERSION":"fixture-version"');
    } else {
      bodies.add(
        jsonDecode(await utf8.decoder.bind(r).join()) as Map<String, dynamic>,
      );
      r.response.write('{}');
    }
    await r.response.close();
  });
  final scope = RequestScope();
  try {
    final provider = YouTubeMusic(
      const ProviderHttp(),
      origin: Uri.parse('http://127.0.0.1:${server.port}'),
    );
    for (final filter in SearchFilter.values) {
      await provider.search('fixture query', scope, filter: filter);
    }
    await provider.search(
      'fixture query',
      scope,
      filter: SearchFilter.albums,
      continuation: 'fixture-next',
    );
    require(
      !bodies.first.containsKey('params'),
      'All search must be heterogeneous',
    );
    require(
      bodies[2]['params'] == 'EgWKAQIYAWoKEAkQChAFEAMQBA==',
      'Pinned album search filter',
    );
    require(
      bodies.last['continuation'] == 'fixture-next' &&
          !bodies.last.containsKey('query'),
      'Filtered continuation',
    );
  } finally {
    scope.cancel();
    await server.close(force: true);
  }
}

Future<void> main() async {
  await youtubeContracts();
  stdout.writeln(
    'YouTube catalog/session/resolver contracts PASS (synthetic fixtures only)',
  );
}
