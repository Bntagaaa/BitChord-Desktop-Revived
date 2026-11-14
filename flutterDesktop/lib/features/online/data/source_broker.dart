import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:math';

import '../domain/catalog.dart';
import 'provider_http.dart';
import 'range_source.dart';

Map<String, Object?> _encode(ResolvedMedia m) => {
  'provider': m.provider,
  'track': m.trackId,
  'rendition': m.rendition,
  'url': m.url.toString(),
  'headers': m.headers,
  'expiry': m.expiresAt?.millisecondsSinceEpoch,
  if (m.warmup != null)
    'warmup': {
      'prefix': m.warmup!.prefix,
      'total': m.warmup!.totalBytes,
      'validator': m.warmup!.validator,
      'verified': m.warmup!.verifiedBytes,
    },
};
ResolvedMedia _decode(Map m) {
  final media = ResolvedMedia(
    provider: m['provider'] as String,
    trackId: m['track'] as String,
    rendition: m['rendition'] as String,
    url: Uri.parse(m['url'] as String),
    headers: (m['headers'] as Map).cast<String, String>(),
    expiresAt: m['expiry'] == null
        ? null
        : DateTime.fromMillisecondsSinceEpoch(m['expiry'] as int),
  );
  final seed = m['warmup'];
  if (seed is Map) {
    return media.withWarmup(
      MediaWarmup(
        media,
        (seed['prefix'] as List).cast<int>(),
        seed['total'] as int,
        seed['validator'] as String?,
        verifiedBytes: seed['verified'] as int,
      ),
    );
  }
  return media;
}

// A separate Dart IO isolate owns bounded asynchronous upstream range requests.
// FFmpeg receives an opaque loopback capability, never provider credentials.
// There is no Dart callback from native's render/decode thread.
final class SourceBroker {
  SourceBroker._(this.path, this._worker, this._commands, this._messages);
  final String path;
  final Isolate _worker;
  final SendPort _commands;
  final ReceivePort _messages;
  bool _closed = false;
  static Future<SourceBroker> open(
    ResolvedMedia media, {
    Future<ResolvedMedia> Function()? refresh,
  }) async {
    media.check();
    final messages = ReceivePort();
    final ready = Completer<List>();
    SourceBroker? broker;
    messages.listen((event) async {
      if (event is List && event.first == 'ready') {
        ready.complete(event);
      } else if (event is List &&
          event.first == 'error' &&
          !ready.isCompleted) {
        ready.completeError(const ProviderFailure(ProviderProblem.unavailable));
      } else if (event is List && event.first == 'refresh') {
        final reply = event[1] as SendPort;
        try {
          final replacement = refresh == null || broker?._closed == true
              ? null
              : await refresh();
          reply.send(
            broker?._closed == true || replacement == null
                ? null
                : _encode(replacement),
          );
        } catch (_) {
          reply.send(null);
        }
      }
    });
    final worker = await Isolate.spawn(
      _serve,
      [messages.sendPort, _encode(media)],
      onError: messages.sendPort,
      onExit: messages.sendPort,
    );
    try {
      final r = await ready.future.timeout(const Duration(seconds: 30));
      broker = SourceBroker._(
        r[1] as String,
        worker,
        r[2] as SendPort,
        messages,
      );
      return broker;
    } catch (_) {
      worker.kill(priority: Isolate.immediate);
      messages.close();
      rethrow;
    }
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    final done = ReceivePort();
    _commands.send(done.sendPort);
    try {
      await done.first.timeout(const Duration(seconds: 3));
    } finally {
      done.close();
      _worker.kill(priority: Isolate.immediate);
      _messages.close();
    }
  }

  static Future<void> _serve(List args) async {
    final parent = args[0] as SendPort, commands = ReceivePort();
    HttpServer? server;
    final scope = RequestScope();
    ProviderHttp? transport;
    try {
      final media = _decode(args[1] as Map);
      transport = media.provider == 'youtube'
          ? ProviderHttp.persistent()
          : const ProviderHttp();
      final source = RangeSource(
        media,
        transport,
        scope,
        refresh: () async {
          final answer = ReceivePort();
          try {
            parent.send(['refresh', answer.sendPort]);
            final data = await answer.first.timeout(
              const Duration(seconds: 25),
            );
            scope.check();
            if (data is! Map)
              throw const ProviderFailure(ProviderProblem.expired);
            return _decode(data);
          } finally {
            answer.close();
          }
        },
      );
      // A verified prefix already establishes length/validator. Do not fetch
      // another 512 KiB before granting native access to those same bytes.
      if (source.total == null) await source.chunk(0);
      source.media.check();
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final token = List.generate(
        32,
        (_) => Random.secure().nextInt(256).toRadixString(16).padLeft(2, '0'),
      ).join();
      var active = 0;
      commands.listen((reply) async {
        source.close();
        await server?.close(force: true);
        if (reply is SendPort) reply.send(true);
        commands.close();
      });
      server.listen((request) async {
        try {
          if (request.uri.path != '/$token' ||
              request.uri.hasQuery ||
              !['GET', 'HEAD'].contains(request.method)) {
            request.response.statusCode = 404;
            return;
          }
          if (active >= 2) {
            request.response.statusCode = 503;
            return;
          }
          active++;
          try {
            final range = request.headers.value('range');
            final match = RegExp(r'^bytes=(\d+)-(\d*)$')
                .firstMatch(range ?? '');
            if (range != null && match == null) {
              request.response.statusCode = 416;
              return;
            }
            final start = match == null ? 0 : int.parse(match[1]!);
            var end = match == null || match[2]!.isEmpty
                ? source.total! - 1
                : int.parse(match[2]!);
            if (end >= source.total!) end = source.total! - 1;
            if (start < 0 || start > end) {
              request.response.statusCode = 416;
              return;
            }
            request.response.statusCode = match == null ? 200 : 206;
            request.response.headers.set('Accept-Ranges', 'bytes');
            request.response.headers.set(
              'Content-Type',
              'application/octet-stream',
            );
            if (match != null)
              request.response.headers.set(
                'Content-Range',
                'bytes $start-$end/${source.total}',
              );
            request.response.contentLength = end - start + 1;
            if (request.method == 'HEAD') return;
            var offset = start;
            while (offset <= end) {
              scope.check();
              final prefix = source.prefix;
              final chunk = prefix != null && offset < prefix.length
                  ? prefix
                  : await source.chunk(offset ~/ RangeSource.span);
              final lo = offset % RangeSource.span;
              final count = (end - offset + 1) < chunk.length - lo
                  ? end - offset + 1
                  : chunk.length - lo;
              request.response.add(chunk.sublist(lo, lo + count));
              await request.response.flush();
              offset += count;
            }
          } finally {
            active--;
          }
        } catch (_) {
          // A short response is a transport failure, never a fabricated EOF.
          try {
            request.response.statusCode = 502;
          } catch (_) {}
        } finally {
          try {
            await request.response.close();
          } catch (_) {}
        }
      });
      parent.send([
        'ready',
        'http://127.0.0.1:${server.port}/$token',
        commands.sendPort,
      ]);
    } catch (_) {
      scope.cancel();
      transport?.close();
      await server?.close(force: true);
      commands.close();
      parent.send(['error']);
    }
  }
}
