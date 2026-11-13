import 'dart:collection';
import 'dart:typed_data';

import '../domain/catalog.dart';
import 'provider_http.dart';

// 512 KiB spans, at most 4 MiB of spans plus a verified 16 KiB prefix.
// The optional prefix is an in-memory handoff, never a durable signed-URL cache.
final class RangeSource {
  RangeSource(this.media, this.http, this.scope, {this.refresh}) {
    final seed = media.warmup;
    if (seed != null) {
      seed.validateFor(media);
      total = seed.totalBytes;
      validator = seed.validator;
      _prefix = seed.prefix;
      if (total == _prefix!.length) _cache[0] = _prefix!;
    }
  }
  ResolvedMedia media;
  final ProviderHttp http;
  final RequestScope scope;
  final Future<ResolvedMedia> Function()? refresh;
  static const span = 512 * 1024, maxBytes = 8 * 1024 * 1024 * 1024;
  final _cache = LinkedHashMap<int, Uint8List>();
  Uint8List? _prefix;
  Uint8List? get prefix => _prefix;
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
    final base = index * span;
    if (index < 0 || (total != null && base >= total!)) {
      throw const ProviderFailure(ProviderProblem.integrity);
    }
    final prefix = index == 0 ? _prefix : null;
    final start = base + (prefix?.length ?? 0);
    final end = base + span - 1;
    HttpPayload response;
    try {
      media.check();
      response = await http.request(
        media.url,
        scope,
        headers: {
          ...media.headers,
          'Range': 'bytes=$start-$end',
          if (validator != null) 'If-Range': validator!,
        },
        maxBytes: end - start + 1,
        // Real YouTube media is HTTPS and validated before reaching this class.
        // Generic/loopback fixture transports retain their existing policy.
        targetAllowed:
            media.provider == 'youtube' && media.url.scheme == 'https'
            ? (uri) =>
                  uri.scheme == 'https' &&
                  uri.port == 443 &&
                  uri.userInfo.isEmpty &&
                  !uri.hasFragment &&
                  (uri.host == 'googlevideo.com' ||
                      uri.host.endsWith('.googlevideo.com'))
            : null,
      );
      if ([401, 403, 410].contains(response.status)) {
        throw ProviderFailure(ProviderProblem.expired, response.status);
      }
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
      final seed = replacement.warmup;
      if (seed != null) {
        seed.validateFor(replacement);
        if ((total != null && total != seed.totalBytes) ||
            (validator != null && validator != seed.validator) ||
            (_prefix != null && !_samePrefix(_prefix!, seed.prefix))) {
          throw const ProviderFailure(ProviderProblem.integrity);
        }
        _prefix = seed.prefix;
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
          hi != (end < size ? end : size - 1)) {
        throw const ProviderFailure(ProviderProblem.integrity);
      }
    } else if (response.status == 200 && start == 0) {
      size = response.bytes.length;
      if (response.headers['content-length'] != '$size') {
        throw const ProviderFailure(ProviderProblem.integrity);
      }
    } else {
      throw ProviderFailure(
        response.status == 200
            ? ProviderProblem.unsupported
            : ProviderProblem.unavailable,
        response.status,
      );
    }
    final etag = response.headers['etag'];
    final tag = etag != null && !etag.startsWith('W/')
        ? etag
        : response.headers['last-modified'];
    if (size <= 0 ||
        size > maxBytes ||
        (total != null && total != size) ||
        (validator != null && validator != tag)) {
      throw const ProviderFailure(ProviderProblem.integrity);
    }
    total = size;
    validator = tag;
    scope.check();
    final bytes = prefix == null
        ? response.bytes
        : (Uint8List(prefix.length + response.bytes.length)
            ..setRange(0, prefix.length, prefix)
            ..setRange(
              prefix.length,
              prefix.length + response.bytes.length,
              response.bytes,
            ));
    if (_cache.length == 8) _cache.remove(_cache.keys.first);
    _cache[index] = bytes;
    return bytes;
  }

  static bool _samePrefix(Uint8List left, Uint8List right) {
    if (left.length != right.length) return false;
    for (var i = 0; i < left.length; i++) {
      if (left[i] != right[i]) return false;
    }
    return true;
  }

  void close() {
    scope.cancel();
    http.close();
    _cache.clear();
    _prefix = null;
  }
}
