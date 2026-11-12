import 'dart:convert';
import 'dart:io';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/data/youtube_resolver.dart';
import '../lib/features/online/data/youtube_session.dart';
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
