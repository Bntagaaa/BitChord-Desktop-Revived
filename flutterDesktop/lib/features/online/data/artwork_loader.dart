import 'dart:async';
import 'dart:typed_data';
import '../domain/catalog.dart';
import 'provider_http.dart';

// Four credential-free transfers, capped bodies, no durable image cache.
final class ArtworkLoader {
  int _active = 0;
  final _waiting = <Completer<void>>[];
  Future<Uint8List> load(Uri url, RequestScope scope) async {
    if (url.scheme != 'https' || url.userInfo.isNotEmpty) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    while (_active >= 4) {
      final ready = Completer<void>();
      _waiting.add(ready);
      final detach = scope.onCancel(() {
        if (!ready.isCompleted) ready.complete();
      });
      try {
        await ready.future;
        scope.check();
      } finally {
        detach();
        _waiting.remove(ready);
      }
    }
    scope.check();
    _active++;
    try {
      final result = await const ProviderHttp().request(
        url,
        scope,
        maxBytes: 2 * 1024 * 1024,
      );
      if (result.status != 200 ||
          !(result.headers['content-type']?.startsWith('image/') ?? false)) {
        throw const ProviderFailure(ProviderProblem.unavailable);
      }
      return result.bytes;
    } finally {
      _active--;
      for (final ready in _waiting.take(4)) {
        if (!ready.isCompleted) ready.complete();
      }
    }
  }
}
