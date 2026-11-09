import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../domain/models.dart';
import 'innertube_client.dart';
import 'innertube_parser.dart';
import 'music_repository.dart';
import 'song_matcher.dart';

class SpotifyImportTrack {
  const SpotifyImportTrack({
    required this.title,
    required this.artist,
    this.duration,
    this.album,
  });
  final String title;
  final String artist;
  final Duration? duration;
  final String? album;
}

class SpotifyPlaylist {
  const SpotifyPlaylist({
    required this.title,
    required this.tracks,
    required this.totalTracks,
    this.thumbnailUrl,
    this.warnings = const [],
  });
  final String title;
  final List<SpotifyImportTrack> tracks;
  final int totalTracks;
  final String? thumbnailUrl;
  final List<String> warnings;
}

class SpotifyImportResult {
  const SpotifyImportResult({
    required this.title,
    required this.songs,
    required this.unmatched,
    required this.totalTracks,
    this.thumbnailUrl,
    this.warnings = const [],
  });
  final String title;
  final List<Song> songs;
  final List<SpotifyImportTrack> unmatched;
  final int totalTracks;
  final String? thumbnailUrl;
  final List<String> warnings;
}

class ImportCancelledException implements Exception {
  const ImportCancelledException();
  @override
  String toString() => 'Spotify import cancelled.';
}

class SpotifyImporter {
  SpotifyImporter({required this.repository, http.Client? httpClient})
    : _http = httpClient ?? http.Client(),
      _ownsClient = httpClient == null;
  final MusicRepository repository;
  final http.Client _http;
  final bool _ownsClient;
  static final _playlistId = RegExp(r'^[A-Za-z0-9]{22}$');
  static const _fetchHash =
      '19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d';

  static String? extractPlaylistId(String input) {
    final value = input.trim();
    if (_playlistId.hasMatch(value)) return value;
    if (value.startsWith('spotify:playlist:')) {
      final id = value
          .substring('spotify:playlist:'.length)
          .split(RegExp(r'[?/]'))
          .first;
      return _playlistId.hasMatch(id) ? id : null;
    }
    final uri = Uri.tryParse(value);
    if (uri == null || (uri.scheme != 'http' && uri.scheme != 'https')) {
      return null;
    }
    final host = uri.host.toLowerCase();
    if (host != 'spotify.com' && !host.endsWith('.spotify.com')) return null;
    final index = uri.pathSegments.indexOf('playlist');
    if (index < 0 || index + 1 >= uri.pathSegments.length) return null;
    final id = uri.pathSegments[index + 1];
    return _playlistId.hasMatch(id) ? id : null;
  }

  Future<SpotifyPlaylist> fetchPlaylist(
    String input, {
    bool Function()? isCancelled,
  }) async {
    final id = extractPlaylistId(input);
    if (id == null) {
      throw const ServiceException('Enter a valid Spotify playlist link.');
    }
    _checkCancelled(isCancelled);
    final response = await _http
        .get(
          Uri.https('open.spotify.com', '/embed/playlist/$id'),
          headers: {'User-Agent': InnertubeClient.userAgent},
        )
        .timeout(const Duration(seconds: 20));
    _checkCancelled(isCancelled);
    if (response.statusCode != 200) {
      throw ServiceException(
        'Spotify could not load this public playlist (HTTP ${response.statusCode}).',
      );
    }
    if (response.bodyBytes.length > 16 * 1024 * 1024) {
      throw const ServiceException(
        'Spotify returned an oversized playlist page.',
      );
    }
    final parsed = parseEmbed(response.body);
    final tracks = [...parsed.playlist.tracks];
    final warnings = <String>[];
    var total = parsed.playlist.totalTracks;
    if (tracks.length >= 100) {
      final token = parsed.token;
      if (token == null) {
        warnings.add(
          'Spotify supplied only its first ${tracks.length} tracks; this import may be incomplete.',
        );
      } else {
        var offset = tracks.length;
        try {
          while (true) {
            _checkCancelled(isCancelled);
            final uri = Uri.https(
              'api-partner.spotify.com',
              '/pathfinder/v1/query',
              {
                'operationName': 'fetchPlaylist',
                'variables': jsonEncode({
                  'uri': 'spotify:playlist:$id',
                  'offset': offset,
                  'limit': 100,
                  'enableWatchFeedEntrypoint': false,
                }),
                'extensions': jsonEncode({
                  'persistedQuery': {'version': 1, 'sha256Hash': _fetchHash},
                }),
              },
            );
            final page = await _http
                .get(
                  uri,
                  headers: {
                    'User-Agent': InnertubeClient.userAgent,
                    'Authorization': 'Bearer $token',
                    'App-platform': 'WebPlayer',
                    'Accept': 'application/json',
                  },
                )
                .timeout(const Duration(seconds: 20));
            _checkCancelled(isCancelled);
            if (page.statusCode != 200) {
              throw const ServiceException('Spotify pagination was refused.');
            }
            final root = jsonDecode(page.body);
            final content = InnertubeParser.object(
              InnertubeParser.at(root, ['data', 'playlistV2', 'content']),
            );
            if (content.isEmpty) {
              throw const ServiceException(
                'Spotify returned no next-page data.',
              );
            }
            final rows = InnertubeParser.array(content['items']);
            total = (content['totalCount'] as num?)?.toInt() ?? total;
            for (final row in rows) {
              final track = InnertubeParser.object(
                InnertubeParser.at(row, ['itemV2', 'data']),
              );
              final title = track['name'] as String? ?? '';
              if (title.isEmpty) continue;
              final artist =
                  InnertubeParser.array(
                        InnertubeParser.at(track, ['artists', 'items']),
                      )
                      .map(
                        (a) =>
                            InnertubeParser.at(a, ['profile', 'name'])
                                as String? ??
                            '',
                      )
                      .where((s) => s.isNotEmpty)
                      .join(', ');
              tracks.add(
                SpotifyImportTrack(
                  title: title,
                  artist: artist,
                  duration: track['duration'] is Map
                      ? Duration(
                          milliseconds:
                              (InnertubeParser.at(track, [
                                'duration',
                                'totalMilliseconds',
                              ]) as num?)?.toInt() ??
                              0,
                        )
                      : null,
                ),
              );
            }
            offset += rows.length;
            if (rows.isEmpty || offset >= total) break;
          }
        } on ImportCancelledException {
          rethrow;
        } on Exception {
          warnings.add(
            'Spotify stopped paging after ${tracks.length} tracks. The remaining tracks were not imported.',
          );
        }
      }
    }
    if (total > tracks.length && warnings.isEmpty) {
      warnings.add(
        'Spotify exposed ${tracks.length} of $total tracks; unavailable entries were omitted.',
      );
    }
    return SpotifyPlaylist(
      title: parsed.playlist.title,
      tracks: tracks,
      totalTracks: total < tracks.length ? tracks.length : total,
      thumbnailUrl: parsed.playlist.thumbnailUrl,
      warnings: warnings,
    );
  }

  static ({SpotifyPlaylist playlist, String? token}) parseEmbed(String html) {
    final script = RegExp(
      r'<script\b(?=[^>]*\bid=["\x27]__NEXT_DATA__["\x27])[^>]*>([\s\S]*?)</script>',
      caseSensitive: false,
    ).firstMatch(html)?[1];
    if (script == null) {
      throw const ServiceException(
        'Spotify playlist metadata is unavailable. Make sure the playlist is public.',
      );
    }
    final root = jsonDecode(script);
    final props = InnertubeParser.object(
      InnertubeParser.at(root, ['props', 'pageProps']),
    );
    final entity = InnertubeParser.object(
      InnertubeParser.at(props, ['state', 'data', 'entity']) ?? props['entity'],
    );
    if (entity.isEmpty) {
      throw const ServiceException(
        'This Spotify playlist is private or unavailable.',
      );
    }
    final title =
        entity['name'] as String? ??
        entity['title'] as String? ??
        'Imported Spotify Playlist';
    final rawTracks = InnertubeParser.array(
      entity['trackList'] ?? entity['tracks'],
    );
    final tracks = <SpotifyImportTrack>[];
    for (final raw in rawTracks) {
      final track = InnertubeParser.object(raw);
      final name = track['title'] as String? ?? track['name'] as String? ?? '';
      if (name.trim().isEmpty) continue;
      final artist =
          track['subtitle'] as String? ??
          InnertubeParser.array(track['artists'])
              .map((a) => InnertubeParser.object(a)['name'] as String? ?? '')
              .join(', ');
      tracks.add(
        SpotifyImportTrack(
          title: name.trim(),
          artist: artist.trim(),
          duration: track['duration'] is num
              ? Duration(milliseconds: (track['duration'] as num).toInt())
              : null,
          album: track['album'] is String ? track['album'] as String : null,
        ),
      );
    }
    if (tracks.isEmpty) {
      throw const ServiceException(
        'No tracks were found in this public Spotify playlist.',
      );
    }
    return (
      playlist: SpotifyPlaylist(
        title: title,
        tracks: tracks,
        totalTracks:
            (entity['trackCount'] as num?)?.toInt() ??
            (entity['totalCount'] as num?)?.toInt() ??
            tracks.length,
        thumbnailUrl: InnertubeParser.findString(entity['coverArt'], 'url'),
      ),
      token: InnertubeParser.at(props, [
        'state',
        'settings',
        'session',
        'accessToken',
      ]) as String?,
    );
  }

  Future<SpotifyImportResult> importPlaylist(
    String input, {
    void Function(int completed, int total)? onProgress,
    bool Function()? isCancelled,
  }) async {
    final playlist = await fetchPlaylist(input, isCancelled: isCancelled);
    final songs = List<Song?>.filled(playlist.tracks.length, null);
    final failures = <int>{};
    var next = 0;
    var completed = 0;
    onProgress?.call(0, playlist.tracks.length);
    Future<void> worker() async {
      while (next < playlist.tracks.length) {
        _checkCancelled(isCancelled);
        final index = next++;
        final track = playlist.tracks[index];
        try {
          final results = await repository.search(
            '${track.title} ${track.artist}',
            filter: SearchFilter.songs,
            typeahead: true,
          );
          songs[index] = SongMatcher.best(
            results.songs,
            title: track.title,
            artist: track.artist,
            duration: track.duration,
            album: track.album,
          );
        } on Exception {
          failures.add(index);
        }
        _checkCancelled(isCancelled);
        onProgress?.call(++completed, playlist.tracks.length);
      }
    }

    await Future.wait(List.generate(4, (_) => worker()));
    final warnings = [...playlist.warnings];
    if (failures.isNotEmpty) {
      warnings.add(
        '${failures.length} lookups failed because the music service could not be reached.',
      );
    }
    return SpotifyImportResult(
      title: playlist.title,
      songs: songs.whereType<Song>().toList(),
      unmatched: [
        for (var i = 0; i < songs.length; i++)
          if (songs[i] == null) playlist.tracks[i],
      ],
      totalTracks: playlist.totalTracks,
      thumbnailUrl: playlist.thumbnailUrl,
      warnings: warnings,
    );
  }

  static void _checkCancelled(bool Function()? isCancelled) {
    if (isCancelled?.call() == true) throw const ImportCancelledException();
  }

  void close() {
    if (_ownsClient) _http.close();
  }
}
