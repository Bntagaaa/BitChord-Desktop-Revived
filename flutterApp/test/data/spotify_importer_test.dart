import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/data/innertube_client.dart';
import 'package:bitchord_desktop/data/music_repository.dart';
import 'package:bitchord_desktop/data/song_matcher.dart';
import 'package:bitchord_desktop/data/spotify_importer.dart';

const playlistId = '1234567890123456789012';
String embed(List<String> titles) =>
    '<script type="application/json" id="__NEXT_DATA__">${jsonEncode({
      'props': {
        'pageProps': {
          'state': {
            'data': {
              'entity': {
                'name': 'Imported playlist',
                'trackCount': titles.length,
                'trackList': titles.map((title) => {'title': title, 'subtitle': 'Artist', 'duration': 200000}).toList(),
              },
            },
          },
        },
      },
    })}</script>';

class SearchRepository extends MusicRepository {
  SearchRepository()
    : super(
        client: InnertubeClient(
          httpClient: MockClient((_) async => http.Response('{}', 200)),
        ),
      );
  @override
  Future<SearchPage> search(
    String query, {
    SearchFilter filter = SearchFilter.all,
    String? continuation,
    bool typeahead = false,
  }) async {
    final title = query.replaceFirst(RegExp(r' Artist$'), '');
    if (title == 'Second') {
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    if (title == 'Unavailable') return const SearchPage();
    return SearchPage(
      results: [
        SearchResult.track(
          Song(
            videoId: 'id-$title',
            title: title,
            artist: 'Artist',
            durationText: '3:20',
          ),
        ),
      ],
    );
  }
}

void main() {
  test('playlist links require Spotify origin and a complete playlist ID', () {
    expect(
      SpotifyImporter.extractPlaylistId(
        'https://open.spotify.com/playlist/$playlistId?si=abc',
      ),
      playlistId,
    );
    expect(
      SpotifyImporter.extractPlaylistId('spotify:playlist:$playlistId'),
      playlistId,
    );
    expect(
      SpotifyImporter.extractPlaylistId(
        'https://open.spotify.com.evil.invalid/playlist/$playlistId',
      ),
      isNull,
    );
    expect(
      SpotifyImporter.extractPlaylistId(
        'https://open.spotify.com/playlist/short',
      ),
      isNull,
    );
  });

  test(
    'embed metadata returns real track durations and public playlist title',
    () {
      final parsed = SpotifyImporter.parseEmbed(embed(['First', 'Second']));
      expect(parsed.playlist.title, 'Imported playlist');
      expect(
        parsed.playlist.tracks.first.duration,
        const Duration(seconds: 200),
      );
      expect(parsed.playlist.totalTracks, 2);
      expect(parsed.token, isNull);
    },
  );

  test(
    'matching rejects other recordings, covers and implausible duration gaps',
    () {
      const songs = [
        Song(
          videoId: 'live',
          title: 'Song (Live)',
          artist: 'Artist',
          durationText: '3:20',
        ),
        Song(
          videoId: 'cover',
          title: 'Song',
          artist: 'Cover singer',
          durationText: '3:20',
        ),
        Song(
          videoId: 'short',
          title: 'Song',
          artist: 'Artist',
          durationText: '0:30',
        ),
        Song(
          videoId: 'correct',
          title: 'Song (Official Audio)',
          artist: 'Artist',
          durationText: '3:20',
        ),
      ];
      expect(
        SongMatcher.best(
          songs,
          title: 'Song',
          artist: 'Artist',
          duration: const Duration(seconds: 200),
        )?.videoId,
        'correct',
      );
      expect(
        SongMatcher.best(
          songs.take(3),
          title: 'Song',
          artist: 'Artist',
          duration: const Duration(seconds: 200),
        ),
        isNull,
      );
      expect(SongMatcher.normalize('Björk • Édith • Łódź'), 'bjork edith lodz');
    },
  );

  test(
    'parallel import preserves order and duplicates and reports unmatched tracks',
    () async {
      final repo = SearchRepository();
      final importer = SpotifyImporter(
        repository: repo,
        httpClient: MockClient(
          (_) async => http.Response(
            embed(['First', 'Second', 'First', 'Unavailable']),
            200,
          ),
        ),
      );
      final progress = <int>[];
      final result = await importer.importPlaylist(
        playlistId,
        onProgress: (completed, _) => progress.add(completed),
      );
      expect(result.songs.map((s) => s.title), ['First', 'Second', 'First']);
      expect(result.unmatched.single.title, 'Unavailable');
      expect(result.totalTracks, 4);
      expect(progress.first, 0);
      expect(progress.last, 4);
      importer.close();
      repo.close();
    },
  );

  test(
    'cancellation stops before any request when already requested',
    () async {
      final repo = SearchRepository();
      final importer = SpotifyImporter(
        repository: repo,
        httpClient: MockClient((_) async => throw StateError('Must not fetch')),
      );
      await expectLater(
        importer.importPlaylist(playlistId, isCancelled: () => true),
        throwsA(isA<ImportCancelledException>()),
      );
      importer.close();
      repo.close();
    },
  );
}
