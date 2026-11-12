// Controlled HTTP integration against the real production data adapters.
// No server fixtures are installed into application bootstrap.
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import '../lib/features/online/data/addon_provider.dart';
import '../lib/core/platform/audio/native_api.dart';
import '../lib/features/online/data/jiosaavn.dart';
import '../lib/features/online/data/download_store.dart';
import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/range_source.dart';
import '../lib/features/online/data/source_broker.dart';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/domain/catalog.dart';

void check(bool value, String reason) {
  if (!value) throw StateError(reason);
}

Future<void> fails(
  Future<Object?> Function() work,
  ProviderProblem expected,
) async {
  try {
    await work();
  } on ProviderFailure catch (e) {
    check(e.problem == expected, 'Expected $expected, got ${e.problem}');
    return;
  }
  throw StateError('Expected $expected failure');
}

Future<void> main(List<String> arguments) async {
  final directory = await Directory.systemTemp.createTemp('bitchord-phase4-');
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  final base = Uri.parse('http://127.0.0.1:${server.port}/');
  final bytes = Uint8List(44 + 384000 * 4);
  final wav = ByteData.sublistView(bytes);
  void ascii(int offset, String text) =>
      bytes.setRange(offset, offset + text.length, asciiCodec.encode(text));
  ascii(0, 'RIFF');
  wav.setUint32(4, bytes.length - 8, Endian.little);
  ascii(8, 'WAVEfmt ');
  wav.setUint32(16, 16, Endian.little);
  wav.setUint16(20, 1, Endian.little);
  wav.setUint16(22, 2, Endian.little);
  wav.setUint32(24, 48000, Endian.little);
  wav.setUint32(28, 192000, Endian.little);
  wav.setUint16(32, 4, Endian.little);
  wav.setUint16(34, 16, Endian.little);
  ascii(36, 'data');
  wav.setUint32(40, bytes.length - 44, Endian.little);
  for (var i = 44; i < bytes.length; i += 2) {
    wav.setInt16(i, 256, Endian.little);
  }
  var tag = '"revision-1"', failAt = -1, maxRequested = 0, refreshes = 0;
  final requests = <int>[];
  const http = ProviderHttp(timeout: Duration(seconds: 3));
  server.listen((r) async {
    try {
      final path = r.uri.path;
      if (path == '/slow') {
        await Future<void>.delayed(const Duration(seconds: 1));
        r.response.write('{}');
        return;
      }
      if (path == '/huge') {
        r.response.add(Uint8List(600000));
        return;
      }
      if (path == '/denied') {
        r.response.statusCode = 403;
        return;
      }
      if (path == '/saavn') {
        if (r.uri.queryParameters['__call'] == 'search.getResults') {
          r.response.write(
            jsonEncode({
              'results': [
                {
                  'id': 'saavn-1',
                  'title': 'Public fixture',
                  'more_info': {
                    'duration': '8',
                    'artistMap': {
                      'primary_artists': [
                        {'name': 'Fixture'},
                      ],
                    },
                  },
                },
              ],
            }),
          );
        } else {
          r.response.write(
            jsonEncode({
              'songs': [
                {
                  'id': 'saavn-1',
                  'more_info': {
                    'encrypted_media_url':
                        'ID2ieOjCrwdZENEJmV3zZuhhnpvt7Ia7q/VoyjRcbOUcO7UvWvBrag==',
                    '320kbps': 'true',
                  },
                },
              ],
            }),
          );
        }
        return;
      }
      if (path == '/manifest.json') {
        r.response.write(
          jsonEncode({
            'id': 'fixture',
            'resources': ['search'],
            'allowDownloads': true,
          }),
        );
        return;
      }
      if (path == '/search') {
        check(r.uri.queryParameters['q'] == 'a & b', 'Search query encoding');
        r.response.write(
          jsonEncode({
            'tracks': [
              {
                'id': 'track/a',
                'title': 'Test recording',
                'artist': 'Fixture',
                'duration': 8.0,
              },
            ],
          }),
        );
        return;
      }
      if (path == '/stream/track/a' || path == '/stream/track%2Fa') {
        r.response.write(
          jsonEncode({
            'url': base.resolve('media').toString(),
            'format': 'wav',
          }),
        );
        return;
      }
      if (path == '/media' || path == '/wrong-range' || path == '/changed') {
        final match = RegExp(
          r'^bytes=(\d+)-(\d+)$',
        ).firstMatch(r.headers.value('range') ?? '');
        check(match != null, 'Bounded upstream Range required');
        final start = int.parse(match![1]!), askedEnd = int.parse(match[2]!);
        requests.add(start);
        maxRequested = askedEnd - start + 1 > maxRequested
            ? askedEnd - start + 1
            : maxRequested;
        if (start == failAt) {
          r.response.statusCode = 503;
          return;
        }
        final end = askedEnd < bytes.length ? askedEnd : bytes.length - 1;
        r.response.statusCode = 206;
        r.response.headers.set('ETag', tag);
        r.response.headers.set(
          'Content-Range',
          'bytes ${path == '/wrong-range' ? start + 1 : start}-$end/${bytes.length}',
        );
        r.response.contentLength = end - start + 1;
        r.response.add(bytes.sublist(start, end + 1));
        return;
      }
      r.response.statusCode = 404;
    } finally {
      try {
        await r.response.close();
      } catch (_) {}
    }
  });
  ResolvedMedia media([String path = 'media']) => ResolvedMedia(
    provider: 'addon',
    trackId: 'track/a',
    rendition: 'wav',
    url: base.resolve(path),
  );
  RangeSource source([String path = 'media']) =>
      RangeSource(media(path), http, RequestScope());
  final report = <String, Object?>{};
  try {
    final provider = AddonProvider(
      'addon',
      base.resolve('manifest.json'),
      http,
    );
    final scope = RequestScope();
    final page = await provider.search('a & b', scope);
    check(page.entries.single.id == 'track/a', 'Typed provider identity');
    final resolved = await provider.resolve(
      page.entries.single,
      AudioQuality.lossless,
      scope,
    );
    check(resolved.url == base.resolve('media'), 'Resolved stream');
    if (arguments.length > 1) {
      final api = NativeApi(arguments[1]);
      check(
        api.saavnUrl(
              'ID2ieOjCrwdZENEJmV3zZuhhnpvt7Ia7q/VoyjRcbOUcO7UvWvBrag==',
            ) ==
            'https://example.invalid/test_320.mp4',
        'DES/ECB/PKCS5 oracle',
      );
      try {
        api.saavnUrl('AAAAAAAAAAA=');
        throw StateError('Invalid padding accepted');
      } on FormatException {
        /* expected */
      }
      final saavn = JioSaavn(
        http,
        arguments[1],
        endpoint: base.resolve('saavn'),
      );
      final results = await saavn.search('fixture', RequestScope());
      final media = await saavn.resolve(
        results.entries.single,
        AudioQuality.high,
        RequestScope(),
      );
      check(media.url.host == 'example.invalid', 'JioSaavn fixture resolver');
      report['jiosaavn_protocol'] =
          'PASS: catalog, URL decoding, padding rejection, resolution (controlled fixture)';
    }
    report['addon_protocol'] = 'PASS: manifest, encoded query/id, stream';
    final parsed = YouTubeMusic.parsePage({
      'contents': {
        'musicShelfRenderer': {
          'contents': [
            {
              'musicResponsiveListItemRenderer': {
                'playlistItemData': {'videoId': 'aaaaaaaaaaa'},
                'flexColumns': [
                  {
                    'musicResponsiveListItemFlexColumnRenderer': {
                      'text': {
                        'runs': [
                          {'text': 'Recording'},
                        ],
                      },
                    },
                  },
                  {
                    'musicResponsiveListItemFlexColumnRenderer': {
                      'text': {
                        'runs': [
                          {'text': 'Artist'},
                        ],
                      },
                    },
                  },
                ],
              },
            },
            {
              'musicResponsiveListItemRenderer': {
                'playlistItemData': {'videoId': 'aaaaaaaaaaa'},
                'flexColumns': [
                  {
                    'musicResponsiveListItemFlexColumnRenderer': {
                      'text': {
                        'runs': [
                          {'text': 'Recording'},
                        ],
                      },
                    },
                  },
                ],
              },
            },
          ],
          'continuations': [
            {
              'nextContinuationData': {'continuation': 'next-token'},
            },
          ],
        },
      },
    });
    check(
      parsed.entries.length == 2 && parsed.continuation == 'next-token',
      'Keep duplicate catalog rows and continuation',
    );
    report['youtube_parser'] =
        'PASS: rows, duplicates, continuation (synthetic fixture, not live parity)';
    final range = source();
    check((await range.chunk(0)).length == RangeSource.span, 'Span size');
    await range.chunk(0);
    check(requests.length == 1, 'Cache hit');
    tag = '"changed"';
    await fails(() => range.chunk(1), ProviderProblem.integrity);
    range.close();
    tag = '"revision-1"';
    final wrong = source('wrong-range');
    await fails(() => wrong.chunk(0), ProviderProblem.integrity);
    wrong.close();
    final renewal = RangeSource(
      media('denied'),
      http,
      RequestScope(),
      refresh: () async {
        refreshes++;
        return media();
      },
    );
    await renewal.chunk(0);
    check(refreshes == 1, 'Bounded source renewal');
    renewal.close();
    await fails(
      () => http.request(base.resolve('huge'), RequestScope(), maxBytes: 32),
      ProviderProblem.unsupported,
    );
    final cancel = RequestScope();
    final pending = http.request(base.resolve('slow'), cancel);
    Timer(const Duration(milliseconds: 30), cancel.cancel);
    await fails(() => pending, ProviderProblem.cancelled);
    await fails(
      () => const ProviderHttp(
        timeout: Duration(milliseconds: 30),
      ).request(base.resolve('slow'), RequestScope()),
      ProviderProblem.timeout,
    );
    report['transport'] =
        'PASS: cache, bounded spans/body, validator/range corruption, expiry renewal, cancellation, timeout';
    final store = DownloadStore(Directory('${directory.path}/downloads'));
    final entry = page.entries.single;
    failAt = RangeSource.span;
    var s = source();
    await fails(() => store.save(entry, s), ProviderProblem.unavailable);
    s.close();
    check((await store.list()).isEmpty, 'Partial file not published');
    failAt = -1;
    requests.clear();
    s = source();
    final downloaded = await store.save(entry, s);
    s.close();
    check(
      requests.where((n) => n == 0).length == 1,
      'Resume probes first span once',
    );
    check(
      downloaded.bytes == bytes.length && (await store.list()).length == 1,
      'Resumed download verified',
    );
    final reopened = DownloadStore(Directory('${directory.path}/downloads'));
    check((await reopened.list()).length == 1, 'Offline restart');
    final input = await File(
      downloaded.path,
    ).open(mode: FileMode.writeOnlyAppend);
    await input.setPosition(50);
    await input.writeByte(99);
    await input.close();
    check(
      (await reopened.list()).isEmpty,
      'Corruption never returned as offline audio',
    );
    await reopened.remove(entry);
    check((await reopened.list()).isEmpty, 'Removal');
    report['downloads'] =
        'PASS: interruption/resume, unpublished partials, offline restart, corruption rejection, app-owned removal';
    final broker = await SourceBroker.open(media());
    try {
      final brokerScope = RequestScope();
      final client = HttpClient();
      try {
        final request = await client.getUrl(Uri.parse(broker.path));
        request.headers.set('Range', 'bytes=0-43');
        final response = await request.close();
        final data = await response.fold<List<int>>([], (a, b) => a..addAll(b));
        check(
          response.statusCode == 206 && data.length == 44 && data[0] == 82,
          'Broker range delivery',
        );
        final denied = await client.getUrl(
          Uri.parse(broker.path).replace(path: '/wrong-token'),
        );
        check(
          (await denied.close()).statusCode == 404,
          'Broker capability isolation',
        );
      } finally {
        client.close(force: true);
        brokerScope.cancel();
      }
      if (arguments.isNotEmpty) {
        final native = await Process.run(arguments[0], [broker.path]);
        check(
          native.exitCode == 0,
          'Native stream PCM failed: exit ${native.exitCode} ${native.stdout} ${native.stderr}',
        );
        report['native_stream'] = jsonDecode('${native.stdout}'.trim());
      }
    } finally {
      await broker.close();
    }
    check(maxRequested <= RangeSource.span, 'Bounded upstream requests');
    report['broker'] =
        'PASS: isolated IO worker, capability isolation, range delivery and shutdown';
    stdout.writeln(jsonEncode(report));
  } finally {
    await server.close(force: true);
    await directory.delete(recursive: true);
  }
}

const asciiCodec = AsciiCodec();
