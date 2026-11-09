import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/playback/range_media_proxy.dart';
import 'package:bitchord_desktop/playback/stream_resolver.dart';

void main() {
  test(
    'seekable transport bounds every upstream range and returns exact bytes',
    () async {
      final bytes = List.generate(180000, (i) => i % 251);
      final requests = <String>[];
      final upstream = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      upstream.listen((request) async {
        final value = request.headers.value(HttpHeaders.rangeHeader)!;
        requests.add(value);
        final match = RegExp(r'^bytes=(\d+)-(\d+)$').firstMatch(value)!;
        final start = int.parse(match[1]!);
        final end = min(bytes.length - 1, int.parse(match[2]!));
        expect(end - start + 1, lessThanOrEqualTo(32768));
        request.response.statusCode = HttpStatus.partialContent;
        request.response.headers.set(
          HttpHeaders.contentRangeHeader,
          'bytes $start-$end/${bytes.length}',
        );
        request.response.contentLength = end - start + 1;
        request.response.add(bytes.sublist(start, end + 1));
        await request.response.close();
      });
      final proxy = RangeMediaProxy(
        ResolvedStream(
          uri: 'http://127.0.0.1:${upstream.port}/audio',
          windowedReads: true,
        ),
        windowBytes: 32768,
      );
      final client = HttpClient();
      try {
        final uri = Uri.parse(await proxy.open());
        final request = await client.getUrl(uri);
        request.headers.set(HttpHeaders.rangeHeader, 'bytes=54321-123456');
        final response = await request.close();
        expect(response.statusCode, HttpStatus.partialContent);
        expect(
          response.headers.value(HttpHeaders.contentRangeHeader),
          'bytes 54321-123456/180000',
        );
        final actual = await response.fold<List<int>>(
          [],
          (a, b) => a..addAll(b),
        );
        expect(actual, bytes.sublist(54321, 123457));
        expect(requests.length, greaterThan(2));
        final forbidden = await client.getUrl(uri.replace(path: '/other'));
        final denied = await forbidden.close();
        expect(denied.statusCode, HttpStatus.notFound);
        await denied.drain<void>();
      } finally {
        client.close(force: true);
        await proxy.close();
        await upstream.close(force: true);
      }
    },
  );

  test(
    'server that ignores range cannot allocate a whole media file',
    () async {
      final upstream = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      upstream.listen((request) async {
        request.response.contentLength = 200000;
        try {
          request.response.add(List.filled(200000, 1));
          await request.response.close();
        } on Object {
          /* The range limiter cancels this intentionally. */
        }
      });
      final proxy = RangeMediaProxy(
        ResolvedStream(uri: 'http://127.0.0.1:${upstream.port}/audio'),
        windowBytes: 32768,
      );
      try {
        await expectLater(proxy.open(), throwsA(isA<HttpException>()));
      } finally {
        await proxy.close();
        await upstream.close(force: true);
      }
    },
  );
}
