import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:bitchord_desktop/data/innertube_client.dart';
import 'package:bitchord_desktop/data/music_repository.dart';
import 'package:bitchord_desktop/domain/models.dart';

class RadioRepository extends MusicRepository {
  RadioRepository(this.candidates)
    : super(
        client: InnertubeClient(
          httpClient: MockClient((_) async => http.Response('{}', 200)),
        ),
      );
  final List<Song> candidates;
  int searches = 0;
  String? radioId;
  @override
  Future<SearchPage> search(
    String query, {
    SearchFilter filter = SearchFilter.all,
    String? continuation,
    bool typeahead = false,
  }) async {
    searches++;
    return SearchPage(results: candidates.map(SearchResult.track).toList());
  }

  @override
  Future<List<Song>> radio(String videoId, {bool preferVideo = false}) async {
    radioId = videoId;
    return candidates;
  }
}

void main() {
  test(
    'radio for a local recording matches the source catalogue once',
    () async {
      final repository = RadioRepository(const [
        Song(
          videoId: 'wrong000001',
          title: 'Song',
          artist: 'Cover artist',
          durationText: '3:20',
        ),
        Song(
          videoId: 'abcdefghijk',
          title: 'Song',
          artist: 'Artist',
          durationText: '3:20',
        ),
      ]);
      await repository.radioForSong(
        const Song(
          videoId: 'local:/file',
          title: 'Song',
          artist: 'Artist',
          localPath: '/file',
          durationText: '3:20',
        ),
      );
      expect(repository.searches, 1);
      expect(repository.radioId, 'abcdefghijk');
      repository.close();
    },
  );

  test(
    'YouTube radio preserves exact video identity without a search',
    () async {
      final repository = RadioRepository(const []);
      await repository.radioForSong(
        const Song(videoId: 'abcdefghijk', title: 'Song', artist: 'Artist'),
      );
      expect(repository.searches, 0);
      expect(repository.radioId, 'abcdefghijk');
      repository.close();
    },
  );

  test(
    'unmatched source radio reports failure without sending an opaque ID',
    () async {
      final repository = RadioRepository(const []);
      await expectLater(
        repository.radioForSong(
          const Song(
            videoId: 'jiosaavn:track',
            title: 'Song',
            artist: 'Artist',
          ),
        ),
        throwsA(isA<ServiceException>()),
      );
      expect(repository.searches, 1);
      expect(repository.radioId, isNull);
      repository.close();
    },
  );
  test(
    'cloud edits strip the browse prefix and preserve duplicate occurrence keys',
    () async {
      final bodies = <Map<String, dynamic>>[];
      final client = InnertubeClient(
        httpClient: MockClient((request) async {
          if (request.method == 'GET') return http.Response('{}', 200);
          bodies.add(jsonDecode(request.body) as Map<String, dynamic>);
          return http.Response('{"status":"STATUS_SUCCEEDED"}', 200);
        }),
      )..setCookie('SAPISID=test-session');
      final repository = MusicRepository(client: client);
      await repository.removeFromPlaylist('VLplaylist', [
        const Song(
          videoId: 'same-recording',
          title: 'Song',
          artist: 'Artist',
          setVideoId: 'second-occurrence',
        ),
      ]);
      expect(bodies.last['playlistId'], 'playlist');
      expect(bodies.last['actions'], [
        {
          'action': 'ACTION_REMOVE_VIDEO',
          'setVideoId': 'second-occurrence',
          'removedVideoId': 'same-recording',
        },
      ]);
      await repository.movePlaylistEntry(
        'VLplaylist',
        'second-occurrence',
        beforeSetVideoId: 'first-occurrence',
      );
      expect(bodies.last['actions'], [
        {
          'action': 'ACTION_MOVE_VIDEO_BEFORE',
          'setVideoId': 'second-occurrence',
          'movedSetVideoIdSuccessor': 'first-occurrence',
        },
      ]);
      await repository.movePlaylistEntry('VLplaylist', 'first-occurrence');
      expect(
        (bodies.last['actions'] as List).single.containsKey(
          'movedSetVideoIdSuccessor',
        ),
        isFalse,
      );
      repository.close();
    },
  );

  test(
    'HTTP200 refused edit fails; unavailable occurrence ID never sends removal',
    () async {
      var requests = 0;
      final client = InnertubeClient(
        httpClient: MockClient((request) async {
          if (request.method == 'GET') return http.Response('{}', 200);
          requests++;
          return http.Response('{"status":"STATUS_FAILED"}', 200);
        }),
      )..setCookie('SAPISID=test-session');
      final repository = MusicRepository(client: client);
      await expectLater(
        repository.renamePlaylist('VLplaylist', 'Rename'),
        throwsA(isA<ServiceException>()),
      );
      await expectLater(
        repository.removeFromPlaylist('VLplaylist', [
          const Song(videoId: 'recording', title: 'Song', artist: 'Artist'),
        ]),
        throwsA(isA<ServiceException>()),
      );
      expect(requests, 1);
      repository.close();
    },
  );

  test(
    'add-to-playlist returns minted per-entry IDs, create seeds one request',
    () async {
      final requests = <http.Request>[];
      final client = InnertubeClient(
        httpClient: MockClient((request) async {
          if (request.method == 'GET') return http.Response('{}', 200);
          requests.add(request);
          if (request.url.path.endsWith('/playlist/create')) {
            return http.Response('{"playlistId":"new-id"}', 200);
          }
          return http.Response(
            jsonEncode({
              'status': 'STATUS_SUCCEEDED',
              'playlistEditResults': [
                {
                  'playlistEditVideoAddedResultData': {
                    'videoId': 'recording',
                    'setVideoId': 'minted-id',
                  },
                },
              ],
            }),
            200,
          );
        }),
      )..setCookie('SAPISID=test-session');
      final repository = MusicRepository(client: client);
      expect(
        await repository.createPlaylist(
          'New',
          privacy: PlaylistPrivacy.unlisted,
          videoIds: ['recording'],
        ),
        'new-id',
      );
      expect(jsonDecode(requests.single.body)['videoIds'], ['recording']);
      expect(jsonDecode(requests.single.body)['privacyStatus'], 'UNLISTED');
      expect(await repository.addToPlaylist('VLnew-id', ['recording']), {
        'recording': 'minted-id',
      });
      repository.close();
    },
  );

  test(
    'playlist picker follows continuation and excludes generated collections',
    () async {
      Map<String, dynamic> item(String id) => {
        'musicTwoRowItemRenderer': {
          'title': {'simpleText': id},
          'navigationEndpoint': {
            'browseEndpoint': {'browseId': id},
          },
        },
      };
      var pages = 0;
      final client = InnertubeClient(
        httpClient: MockClient((request) async {
          if (request.method == 'GET') return http.Response('{}', 200);
          final body = jsonDecode(request.body);
          pages++;
          return http.Response(
            jsonEncode(
              body['continuation'] == null
                  ? {
                      'contents': [
                        item('VLfirst'),
                        item('VLLM'),
                        item('VLRDmix'),
                      ],
                      'continuations': [
                        {
                          'nextContinuationData': {
                            'continuation': 'second-page',
                          },
                        },
                      ],
                    }
                  : {
                      'contents': [item('VLsecond'), item('VLfirst')],
                    },
            ),
            200,
          );
        }),
      )..setCookie('SAPISID=test-session');
      final repository = MusicRepository(client: client);
      final choices = await repository.userPlaylists();
      expect(choices.map((p) => p.browseId), ['VLfirst', 'VLsecond']);
      expect(pages, 2);
      repository.close();
    },
  );

  test(
    'an account change during preparation cannot send a mutation as another user',
    () async {
      final shell = Completer<http.Response>();
      var posted = false;
      final client = InnertubeClient(
        httpClient: MockClient((request) async {
          if (request.method == 'GET') return shell.future;
          posted = true;
          return http.Response('{}', 200);
        }),
      )..setCookie('SAPISID=first');
      final pending = expectLater(
        client.post('playlist/delete', {'playlistId': 'one'}),
        throwsA(isA<ServiceException>()),
      );
      client.setCookie('SAPISID=second');
      shell.complete(http.Response('{}', 200));
      await pending;
      expect(posted, isFalse);
      client.close();
    },
  );
}
