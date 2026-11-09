import 'dart:async';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'stream_resolver.dart';

/// A private, per-deck loopback transport for Google's finite range responses.
/// The native decoder sees a seekable file; every upstream request is bounded.
/// This never downloads a whole track into memory or exposes a remote URL API.
class RangeMediaProxy {
  RangeMediaProxy(
    this.source, {
    this.windowBytes = 512 * 1024,
    this.timeout = const Duration(seconds: 20),
  }) {
    if (windowBytes < 1 || windowBytes > 2 * 1024 * 1024) {
      throw ArgumentError.value(windowBytes, 'windowBytes');
    }
    _client.connectionTimeout = const Duration(seconds: 12);
    _client.idleTimeout = const Duration(seconds: 20);
    _client.autoUncompress = false;
  }
  final ResolvedStream source;
  final int windowBytes;
  final Duration timeout;
  final _client = HttpClient();
  final _active = <HttpClientRequest>{};
  final _clients = <HttpResponse>{};
  HttpServer? _server;
  bool _closed = false;
  int? _length;
  String? _mime;
  _RangeWindow? _first;
  final String _token = List.generate(
    6,
    (_) => Random.secure().nextInt(1 << 32).toRadixString(16),
  ).join();

  Future<String> open() async {
    if (_closed) throw StateError('The range transport is closed.');
    final first = await _read(0, windowBytes - 1);
    if (_closed) throw StateError('The range transport is closed.');
    _length = first.total;
    _first = first;
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    if (_closed) {
      await server.close(force: true);
      throw StateError('Closed.');
    }
    _server = server;
    server.listen((request) {
      unawaited(_serve(request));
    });
    return 'http://127.0.0.1:${server.port}/$_token';
  }

  Future<void> _serve(HttpRequest request) async {
    final response = request.response;
    _clients.add(response);
    try {
      if (_closed || request.uri.path != '/$_token') {
        response.statusCode = HttpStatus.notFound;
        return;
      }
      if (request.method != 'GET' && request.method != 'HEAD') {
        response.statusCode = HttpStatus.methodNotAllowed;
        response.headers.set(HttpHeaders.allowHeader, 'GET, HEAD');
        return;
      }
      final total = _length!;
      var start = 0;
      var end = total - 1;
      final rangeHeader = request.headers.value(HttpHeaders.rangeHeader);
      if (rangeHeader != null) {
        final match = RegExp(r'^bytes=(\d*)-(\d*)$')
            .firstMatch(rangeHeader.trim());
        if (match == null || (match[1]!.isEmpty && match[2]!.isEmpty)) {
          response.statusCode = HttpStatus.requestedRangeNotSatisfiable;
          response.headers.set(
            HttpHeaders.contentRangeHeader,
            'bytes */$total',
          );
          return;
        }
        if (match[1]!.isEmpty) {
          final suffix = int.tryParse(match[2]!) ?? 0;
          if (suffix <= 0) {
            response.statusCode = HttpStatus.requestedRangeNotSatisfiable;
            return;
          }
          start = max(0, total - suffix);
        } else {
          start = int.tryParse(match[1]!) ?? total;
          if (match[2]!.isNotEmpty) {
            end = min(end, int.tryParse(match[2]!) ?? -1);
          }
        }
        if (start >= total || end < start) {
          response.statusCode = HttpStatus.requestedRangeNotSatisfiable;
          response.headers.set(
            HttpHeaders.contentRangeHeader,
            'bytes */$total',
          );
          return;
        }
        response.statusCode = HttpStatus.partialContent;
        response.headers.set(
          HttpHeaders.contentRangeHeader,
          'bytes $start-$end/$total',
        );
      }
      response.headers.set(HttpHeaders.acceptRangesHeader, 'bytes');
      response.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
      response.headers.set(
        HttpHeaders.contentTypeHeader,
        _mime ?? 'application/octet-stream',
      );
      response.contentLength = end - start + 1;
      if (request.method == 'HEAD') return;
      var cursor = start;
      while (!_closed && cursor <= end) {
        final last = min(end, cursor + windowBytes - 1);
        final first = _first;
        final chunk =
            first != null &&
                cursor >= first.start &&
                last < first.start + first.bytes.length
            ? _RangeWindow(
                cursor,
                total,
                Uint8List.sublistView(
                  first.bytes,
                  cursor - first.start,
                  last - first.start + 1,
                ),
              )
            : await _read(cursor, last);
        if (_closed) break;
        response.add(chunk.bytes);
        await response.flush().timeout(timeout);
        cursor += chunk.bytes.length;
      }
    } on Exception {
      // A seek closes the decoder's old request. That is ordinary cancellation.
      // Closing a failed response also lets libmpv report its network failure.
    } finally {
      _clients.remove(response);
      try {
        await response.close();
      } on Exception {
        /* Disconnected decoder. */
      }
    }
  }

  Future<_RangeWindow> _read(int start, int end) async {
    if (_closed) throw StateError('Closed range transport.');
    final request = await _client
        .getUrl(Uri.parse(source.uri))
        .timeout(timeout);
    _active.add(request);
    try {
      source.headers.forEach((key, value) => request.headers.set(key, value));
      request.headers.set(HttpHeaders.rangeHeader, 'bytes=$start-$end');
      request.headers.set(HttpHeaders.acceptEncodingHeader, 'identity');
      final response = await request.close().timeout(timeout);
      _mime ??= response.headers.value(HttpHeaders.contentTypeHeader);
      if (response.statusCode != HttpStatus.partialContent &&
          response.statusCode != HttpStatus.ok) {
        throw HttpException(
          'Media server refused a range (${response.statusCode}).',
        );
      }
      final stated = response.headers.value(HttpHeaders.contentRangeHeader);
      final range = stated == null
          ? null
          : RegExp(r'^bytes (\d+)-(\d+)/(\d+)$').firstMatch(stated);
      final capacity = end - start + 1;
      if (response.contentLength > capacity) {
        request.abort();
        throw const HttpException(
          'Media server ignored its bounded range request.',
        );
      }
      if (response.statusCode == HttpStatus.partialContent &&
          (range == null ||
              int.parse(range[1]!) != start ||
              int.parse(range[2]!) > end)) {
        throw const HttpException('Media server returned an invalid range.');
      }
      if (response.statusCode == HttpStatus.ok && start != 0) {
        throw const HttpException('Media server did not support seeking.');
      }
      final bytes = BytesBuilder(copy: false);
      await for (final chunk in response.timeout(timeout)) {
        if (_closed || bytes.length + chunk.length > capacity) {
          request.abort();
          throw const HttpException('Media range was cancelled or oversized.');
        }
        bytes.add(chunk);
      }
      final result = bytes.takeBytes();
      final total = range == null ? result.length : int.parse(range[3]!);
      if (result.isEmpty ||
          total < start + result.length ||
          (range != null &&
              int.parse(range[2]!) != start + result.length - 1)) {
        throw const HttpException('Media server returned a truncated range.');
      }
      if (_length != null && _length != total) {
        throw const HttpException('Media length changed while decoding.');
      }
      return _RangeWindow(start, total, result);
    } finally {
      _active.remove(request);
    }
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    for (final request in _active.toList()) {
      request.abort();
    }
    _active.clear();
    _client.close(force: true);
    await _server?.close(force: true);
    _server = null;
    for (final response in _clients.toList()) {
      try {
        await response.close();
      } on Exception {
        /* Already closed. */
      }
    }
    _clients.clear();
    _first = null;
  }
}

class _RangeWindow {
  _RangeWindow(this.start, this.total, this.bytes);
  final int start;
  final int total;
  final Uint8List bytes;
}
