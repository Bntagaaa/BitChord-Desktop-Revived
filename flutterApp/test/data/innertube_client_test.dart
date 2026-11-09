import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/data/innertube_client.dart';
import 'package:bitchord_desktop/data/cookie_input.dart';
import 'package:bitchord_desktop/data/music_repository.dart';

const shell =
    '''<script>ytcfg.set({"INNERTUBE_CLIENT_VERSION":"1.live.01", "VISITOR_DATA":"visitor", "LOGGED_IN":true,"DATASYNC_ID":"personal||brand", "SESSION_INDEX":2});</script>''';

void main() {
  test(
    'SAPISID alternatives and active delegated identity follow source contract',
    () {
      expect(
        InnertubeClient.sapisidFrom(
          'other=x; __Secure-3PAPISID=third; SAPISID=primary',
        ),
        'primary',
      );
      expect(InnertubeClient.sapisidFrom('__Secure-1PAPISID=first'), 'first');
      expect(InnertubeClient.normalizeDataSyncId('personal||brand'), 'brand');
      expect(InnertubeClient.normalizeDataSyncId('personal||'), 'personal');
      expect(InnertubeClient.normalizeDataSyncId('||'), isNull);
      final date = DateTime.fromMillisecondsSinceEpoch(1700000000000);
      final digest = sha1.convert(
        utf8.encode('1700000000 session https://music.youtube.com'),
      );
      expect(
        InnertubeClient.sapisidHash('session', date),
        'SAPISIDHASH 1700000000_$digest',
      );
    },
  );

  test('live shell metadata authenticates requests while typeahead stays anonymous', () async {
    final requests = <http.Request>[];
    final client = InnertubeClient(
      httpClient: MockClient((request) async {
        requests.add(request);
        return http.Response(request.method == 'GET' ? shell : '{}', 200);
      }),
      clock: () => DateTime.fromMillisecondsSinceEpoch(1700000000000),
    );
    client.setCookie('__Secure-3PAPISID=session');
    await client.browse('FEmusic_home');
    final signed = requests.last;
    final body = jsonDecode(signed.body) as Map<String, dynamic>;
    expect(signed.headers['cookie'], '__Secure-3PAPISID=session');
    expect(
      signed.headers['authorization'],
      startsWith('SAPISIDHASH 1700000000_'),
    );
    expect(signed.headers['x-goog-authuser'], '2');
    expect(signed.headers['x-youtube-client-version'], '1.live.01');
    expect(body['context']['user']['onBehalfOfUser'], 'brand');
    await client.post('search', {'query': 'typing'}, authenticated: false);
    expect(requests.last.headers['cookie'], isNull);
    expect(requests.last.headers['authorization'], isNull);
    expect(
      jsonDecode(requests.last.body)['context']['user']['onBehalfOfUser'],
      isNull,
    );
    expect(requests.where((r) => r.method == 'GET'), hasLength(1));
    client.close();
  });

  test(
    'HTTP 200 service errors are rejected and mutations are never retried',
    () async {
      var mutationCalls = 0;
      final client = InnertubeClient(
        httpClient: MockClient((request) async {
          if (request.method == 'GET') return http.Response(shell, 200);
          if (request.url.path.endsWith('/like/like')) {
            mutationCalls++;
            return http.Response('upstream failure', 503);
          }
          return http.Response(
            '{"error":{"message":"Denied","code":403}}',
            200,
          );
        }),
      );
      client.setCookie('SAPISID=session');
      await expectLater(
        client.browse('private'),
        throwsA(
          isA<ServiceException>().having((e) => e.statusCode, 'status', 403),
        ),
      );
      await expectLater(
        client.post('like/like', {
          'target': {'videoId': 'track'},
        }),
        throwsA(isA<ServiceException>()),
      );
      expect(mutationCalls, 1);
      client.close();
    },
  );

  test('only idempotent reads retry a transient HTTP failure once', () async {
    var reads = 0;
    final client = InnertubeClient(
      httpClient: MockClient((request) async {
        if (request.method == 'GET') return http.Response(shell, 200);
        reads++;
        return http.Response(
          reads == 1 ? 'busy' : '{}',
          reads == 1 ? 503 : 200,
        );
      }),
    );
    expect(await client.browse('home'), isEmpty);
    expect(reads, 2);
    client.close();
  });

  test(
    'saved-state comes from library toggle instead of an unrelated icon',
    () async {
      final client = InnertubeClient(
        httpClient: MockClient((request) async {
          if (request.method == 'GET') return http.Response(shell, 200);
          return http.Response(
            jsonEncode({
              'playlistPanelVideoRenderer': {
                'videoId': 'track',
                'icon': {'iconType': 'MUSIC_EXPLICIT_BADGE'},
                'menu': {
                  'toggleMenuServiceItemRenderer': {
                    'defaultIcon': {'iconType': 'LIBRARY_REMOVE'},
                    'defaultServiceEndpoint': {
                      'feedbackEndpoint': {'feedbackToken': 'remove'},
                    },
                    'toggledServiceEndpoint': {
                      'feedbackEndpoint': {'feedbackToken': 'add'},
                    },
                  },
                },
              },
            }),
            200,
          );
        }),
      );
      final repo = MusicRepository(client: client);
      final menu = await repo.songMenu('track');
      expect(menu.inLibrary, isTrue);
      expect(menu.removeFromLibraryToken, 'remove');
      expect(menu.addToLibraryToken, 'add');
      repo.close();
    },
  );

  test('Netscape cookie import rejects other origins and expired cookies', () {
    final parsed = CookieInput.parse('''# Netscape HTTP Cookie File
#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\tsession
.youtube.com\tTRUE\t/\tTRUE\t1\tEXPIRED\told
.notyoutube.com\tTRUE\t/\tTRUE\t0\tEVIL\tbad
music.youtube.com\tFALSE\t/\tTRUE\t0\tPREF\tf6=400
''');
    expect(parsed, 'SAPISID=session; PREF=f6=400');
  });
}
