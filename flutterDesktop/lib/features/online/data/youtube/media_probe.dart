import 'dart:math' as math;

import '../../domain/catalog.dart';
import '../provider_http.dart';

// Verify small real ranges rather than downloading two 512 KiB blocks before
// playback. Keep the verified prefix for the broker instead of discarding it.
final class YouTubeMediaProbe {
  static const prefixBytes = 16 * 1024;
  static const maxMediaBytes = 8 * 1024 * 1024 * 1024;

  static bool allowedTarget(Uri uri) =>
      uri.scheme == 'https' &&
      uri.port == 443 &&
      uri.userInfo.isEmpty &&
      !uri.hasFragment &&
      (uri.host == 'googlevideo.com' || uri.host.endsWith('.googlevideo.com'));

  static String? validator(HttpPayload response) {
    final etag = response.headers['etag'];
    return etag != null && !etag.startsWith('W/')
        ? etag
        : response.headers['last-modified'];
  }

  static int validate(HttpPayload response, int start, int end) {
    if ([401, 403, 410].contains(response.status)) {
      throw ProviderFailure(ProviderProblem.expired, response.status);
    }
    if (response.status == 429) {
      throw const ProviderFailure(ProviderProblem.youtubeRateLimited, 429);
    }
    if (response.status != 200 && response.status != 206) {
      throw ProviderFailure(
        ProviderProblem.youtubeUnavailable,
        response.status,
      );
    }
    if (!(response.headers['content-type'] ?? '').toLowerCase().startsWith(
          'audio/',
        ) ||
        response.bytes.isEmpty ||
        response.bytes.length > end - start + 1) {
      throw const ProviderFailure(ProviderProblem.integrity);
    }
    final range = RegExp(
      r'^bytes (\d+)-(\d+)/(\d+)$',
    ).firstMatch(response.headers['content-range'] ?? '');
    if (response.status == 200 &&
        start == 0 &&
        response.headers['content-length'] == '${response.bytes.length}') {
      return response.bytes.length;
    }
    if (response.status != 206 || range == null) {
      throw const ProviderFailure(ProviderProblem.integrity);
    }
    final low = int.parse(range[1]!), high = int.parse(range[2]!);
    final total = int.parse(range[3]!);
    if (total <= 0 ||
        total > maxMediaBytes ||
        low != start ||
        high != math.min(end, total - 1) ||
        response.bytes.length != high - low + 1) {
      throw const ProviderFailure(ProviderProblem.integrity);
    }
    return total;
  }

  static Future<ResolvedMedia> prepare(
    ResolvedMedia media,
    RequestScope scope, {
    // Tests supply a loopback-only policy; production retains CDN-only HTTPS.
    bool Function(Uri)? targetAllowed,
  }) async {
    media.check();
    final allowed = targetAllowed ?? allowedTarget;
    if (!allowed(media.url)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final http = ProviderHttp.persistent(timeout: const Duration(seconds: 6));
    try {
      final first = await http.request(
        media.url,
        scope,
        headers: {...media.headers, 'Range': 'bytes=0-${prefixBytes - 1}'},
        targetAllowed: allowed,
        maxBytes: prefixBytes,
      );
      final total = validate(first, 0, prefixBytes - 1);
      final tag = validator(first);
      if (total > 1024 * 1024) {
        const start = 1024 * 1024;
        final end = math.min(start + prefixBytes - 1, total - 1);
        final later = await http.request(
          media.url,
          scope,
          headers: {
            ...media.headers,
            'Range': 'bytes=$start-$end',
            if (tag != null) 'If-Range': tag,
          },
          targetAllowed: allowed,
          maxBytes: prefixBytes,
        );
        if (validate(later, start, end) != total ||
            (tag != null && validator(later) != tag)) {
          throw const ProviderFailure(ProviderProblem.integrity);
        }
      }
      scope.check();
      media.check();
      return media.withWarmup(
        MediaWarmup(
          media,
          first.bytes,
          total,
          tag,
          verifiedBytes:
              first.bytes.length +
              (total > 1024 * 1024
                  ? math.min(prefixBytes, total - 1024 * 1024)
                  : 0),
        ),
      );
    } finally {
      http.close();
    }
  }
}
