import 'dart:collection';
import 'dart:typed_data';

import '../domain/catalog.dart';
import 'provider_http.dart';

// 512 KiB spans, at most 4 MiB retained. Requests/reads never run in native's
// real-time sink callback. One source owns its own expiry/validator/cache.
final class RangeSource {
  RangeSource(this.media, this.http, this.scope, {this.refresh});
  ResolvedMedia media;
  final ProviderHttp http;
  final RequestScope scope;
  final Future<ResolvedMedia> Function()? refresh;
  static const span = 512 * 1024, maxBytes = 8 * 1024 * 1024 * 1024;
  final _cache = LinkedHashMap<int, Uint8List>();
  int? total;
  String? validator;
  bool _renewed = false;
  Future<Uint8List> chunk(int index) async {
    scope.check();
    final cached = _cache.remove(index);
    if (cached != null) {
      _cache[index] = cached;
      return cached;
    }
    final start = index * span;
    if (index < 0 || (total != null && start >= total!))
      throw const ProviderFailure(ProviderProblem.integrity);
    HttpPayload response;
    try {
      media.check();
      response = await http.request(
        media.url,
        scope,
        headers: {
          ...media.headers,
          'Range': 'bytes=$start-${start + span - 1}',
          if (validator != null) 'If-Range': validator!,
        },
        maxBytes: span,
      );
      if ([401, 403, 410].contains(response.status))
        throw const ProviderFailure(ProviderProblem.expired);
    } on ProviderFailure catch (e) {
      if (e.problem != ProviderProblem.expired || refresh == null || _renewed)
        rethrow;
      _renewed = true;
      final replacement = await refresh!();
      scope.check();
      if (replacement.trackId != media.trackId ||
          replacement.provider != media.provider ||
          replacement.rendition != media.rendition) {
        throw const ProviderFailure(ProviderProblem.integrity);
      }
      media = replacement;
      return chunk(index);
    }
    final range = RegExp(
      r'^bytes (\d+)-(\d+)/(\d+)$',
    ).firstMatch(response.headers['content-range'] ?? '');
    int size;
    if (response.status == 206 && range != null) {
      final lo = int.parse(range[1]!), hi = int.parse(range[2]!);
      size = int.parse(range[3]!);
      if (lo != start ||
          hi != start + response.bytes.length - 1 ||
          hi != (start + span - 1 < size ? start + span - 1 : size - 1)) {
        throw const ProviderFailure(ProviderProblem.integrity);
      }
    } else if (response.status == 200 && start == 0) {
      // Small non-range files are safe; large ignored-Range bodies hit the cap.
      size = response.bytes.length;
      if (response.headers['content-length'] != '$size')
        throw const ProviderFailure(ProviderProblem.integrity);
    } else {
      throw ProviderFailure(
        response.status == 200
            ? ProviderProblem.unsupported
            : ProviderProblem.unavailable,
        response.status,
      );
    }
    final tag = response.headers['etag'] ?? response.headers['last-modified'];
    if (size <= 0 ||
        size > maxBytes ||
        (total != null && total != size) ||
        (validator != null && validator != tag)) {
      throw const ProviderFailure(ProviderProblem.integrity);
    }
    total = size;
    validator = tag;
    scope.check();
    if (_cache.length == 8) _cache.remove(_cache.keys.first);
    _cache[index] = response.bytes;
    return response.bytes;
  }

  void close() {
    scope.cancel();
    _cache.clear();
  }
}
