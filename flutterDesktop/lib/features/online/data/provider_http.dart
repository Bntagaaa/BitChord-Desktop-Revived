import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import '../domain/catalog.dart';

final class HttpPayload {
  const HttpPayload(this.status, this.bytes, this.headers);
  final int status;
  final Uint8List bytes;
  final Map<String, String> headers;
}

// Per-request clients give cancellation a real socket owner. No implicit retries,
// cookie jar, proxy credential persistence, unbounded body, or URL-bearing errors.
final class ProviderHttp {
  const ProviderHttp({this.timeout = const Duration(seconds: 25)});
  final Duration timeout;
  static const userAgent =
      'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
  Future<HttpPayload> request(
    Uri uri,
    RequestScope scope, {
    String method = 'GET',
    Map<String, String> headers = const {},
    Object? jsonBody,
    int maxBytes = 4 * 1024 * 1024,
  }) async {
    scope.check();
    if (!['https', 'http'].contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final client = HttpClient()
      ..connectionTimeout = timeout
      ..autoUncompress = false;
    final detach = scope.onCancel(() => client.close(force: true));
    try {
      return await (() async {
        var target = uri;
        for (var redirects = 0; redirects <= 3; redirects++) {
          scope.check();
          final request = await client.openUrl(method, target);
          request.followRedirects = false;
          request.headers.set('User-Agent', userAgent);
          request.headers.set('Accept-Encoding', 'identity');
          headers.forEach(request.headers.set);
          if (jsonBody != null) {
            request.headers.contentType = ContentType.json;
            request.add(utf8.encode(jsonEncode(jsonBody)));
          }
          final response = await request.close();
          if ([301, 302, 303, 307, 308].contains(response.statusCode)) {
            final location = response.headers.value('location');
            if (location == null)
              throw const ProviderFailure(ProviderProblem.malformed);
            final next = target.resolve(location);
            // Credentialed redirects must stay in their exact origin. Reject HTTPS downgrade.
            if (!['http', 'https'].contains(next.scheme) ||
                next.userInfo.isNotEmpty ||
                (target.scheme == 'https' && next.scheme != 'https') ||
                (headers.keys.any(
                      (k) =>
                          ['authorization', 'cookie'].contains(k.toLowerCase()),
                    ) &&
                    next.origin != uri.origin)) {
              throw const ProviderFailure(ProviderProblem.unsupported);
            }
            target = next;
            await response.listen((_) {}).cancel();
            continue;
          }
          final data = BytesBuilder(copy: false);
          await for (final part in response) {
            scope.check();
            if (data.length + part.length > maxBytes)
              throw const ProviderFailure(ProviderProblem.unsupported);
            data.add(part);
          }
          scope.check();
          return HttpPayload(response.statusCode, data.takeBytes(), {
            for (final name in [
              'content-range',
              'content-length',
              'content-type',
              'etag',
              'last-modified',
              'retry-after',
            ])
              if (response.headers.value(name) case final String value)
                name: value,
          });
        }
        throw const ProviderFailure(ProviderProblem.unavailable);
      })().timeout(timeout);
    } on ProviderFailure {
      rethrow;
    } on TimeoutException {
      throw const ProviderFailure(ProviderProblem.timeout);
    } catch (_) {
      scope.check();
      throw const ProviderFailure(ProviderProblem.network);
    } finally {
      detach();
      client.close(force: true);
    }
  }

  Future<Map<String, dynamic>> json(
    Uri uri,
    RequestScope scope, {
    Object? body,
    Map<String, String> headers = const {},
  }) async {
    final response = await request(
      uri,
      scope,
      method: body == null ? 'GET' : 'POST',
      headers: headers,
      jsonBody: body,
    );
    if (response.status < 200 || response.status >= 300) {
      throw ProviderFailure(
        [401, 403].contains(response.status)
            ? ProviderProblem.unauthorized
            : ProviderProblem.unavailable,
        response.status,
      );
    }
    try {
      final decoded = jsonDecode(utf8.decode(response.bytes));
      if (decoded is! Map<String, dynamic> || decoded['error'] != null)
        throw const FormatException();
      return decoded;
    } catch (_) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
  }
}
