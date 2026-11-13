import 'dart:convert';

import '../../domain/catalog.dart';
import '../provider_http.dart';
import 'extractor.dart';

// Independent direct-player adapter using the lightweight VISIONOS profile
// observed in the earlier Flutter baseline. It does not implement deciphering,
// invent tokens, or send a signed-in account through a guest-only client.
final class YouTubeDirectExtractor implements YouTubeExtractor {
  YouTubeDirectExtractor()
    : http = const ProviderHttp(timeout: Duration(seconds: 4)),
      origin = Uri.parse('https://music.youtube.com');

  // HTTP fixture seam. Application bootstrap always uses the fixed origin.
  YouTubeDirectExtractor.testing(this.http, this.origin);
  final ProviderHttp http;
  final Uri origin;
  static const userAgent =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 '
      '(KHTML, like Gecko) Version/17.5 Safari/605.1.15';

  @override
  Future<Map<String, dynamic>> extract(
    YouTubeExtraction request,
    RequestScope scope,
  ) async {
    scope.check();
    if (request.client != 'visionos' ||
        request.cookie != null ||
        !RegExp(r'^[a-zA-Z0-9_-]{11}$').hasMatch(request.videoId)) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final visitor = request.visitorData;
    if (visitor != null &&
        (visitor.length > 4096 || RegExp(r'[\r\n\x00]').hasMatch(visitor))) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    final response = await http.request(
      origin.resolve('/youtubei/v1/player?prettyPrint=false'),
      scope,
      method: 'POST',
      targetAllowed: (target) => target.origin == origin.origin,
      headers: {
        'User-Agent': userAgent,
        'Origin': origin.origin,
        'X-Origin': origin.origin,
        'Referer': '${origin.origin}/',
        'X-YouTube-Client-Name': '101',
        'X-YouTube-Client-Version': '0.1',
        'X-Goog-Api-Format-Version': '1',
        if (visitor != null) 'X-Goog-Visitor-Id': visitor,
      },
      jsonBody: {
        'context': {
          'client': {
            'clientName': 'VISIONOS',
            'clientVersion': '0.1',
            'osName': 'VISION_OS',
            'osVersion': '1.3',
            'deviceMake': 'Apple',
            'deviceModel': 'RealityDevice14,1',
            'platform': 'MOBILE',
            'hl': 'en',
            'gl': 'US',
            if (visitor != null) 'visitorData': visitor,
          },
          'user': <String, Object>{},
        },
        'videoId': request.videoId,
        'contentCheckOk': true,
        'racyCheckOk': true,
      },
      maxBytes: 2 * 1024 * 1024,
    );
    if (response.status == 429) {
      throw const ProviderFailure(ProviderProblem.youtubeRateLimited, 429);
    }
    if (response.status == 401) {
      throw const ProviderFailure(ProviderProblem.loginRequired, 401);
    }
    if (response.status != 200) {
      throw ProviderFailure(
        ProviderProblem.youtubeUnavailable,
        response.status,
      );
    }
    Object? decoded;
    try {
      decoded = jsonDecode(utf8.decode(response.bytes));
    } catch (_) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    scope.check();
    if (decoded is! Map<String, dynamic>) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    return normalize(decoded, request.videoId);
  }

  static Map<String, dynamic> normalize(Map<String, dynamic> data, String id) {
    final status = data['playabilityStatus'];
    if (status is! Map) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    if (status['status'] == 'LOGIN_REQUIRED') {
      throw const ProviderFailure(ProviderProblem.loginRequired);
    }
    if (status['status'] != 'OK') {
      final reason = status['reason'];
      final failure = YouTubeProcessExtractor.failure(
        reason is String ? reason : '',
      );
      if ([
        ProviderProblem.loginRequired,
        ProviderProblem.youtubeRateLimited,
        ProviderProblem.youtubeContentUnavailable,
      ].contains(failure.problem)) {
        throw failure;
      }
      // A client may not offer this otherwise public item. Let the maintained
      // extractor handle unsupported client formats; do not retry rights gates.
      throw const ProviderFailure(ProviderProblem.formatUnavailable);
    }
    final details = data['videoDetails'];
    final streaming = data['streamingData'];
    if (details is! Map || details['videoId'] != id || streaming is! Map) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    if (details['isLive'] == true || details['isLiveContent'] == true) {
      throw const ProviderFailure(ProviderProblem.unsupported);
    }
    final rawFormats = streaming['adaptiveFormats'];
    final formats = <Map<String, dynamic>>[];
    if (rawFormats is List) {
      for (final item in rawFormats.take(128)) {
        if (item is! Map) continue;
        final mime = item['mimeType'], itag = item['itag'], url = item['url'];
        if (mime is! String ||
            url is! String ||
            itag is! int ||
            itag <= 0 ||
            item['has_drm'] == true ||
            item['drmFamilies'] != null)
          continue;
        final codec = RegExp(
          r'^audio/(?:mp4|webm|ogg);\s*codecs="(mp4a\.[0-9.]+|opus|vorbis)"$',
        ).firstMatch(mime)?.group(1);
        if (codec == null) continue;
        final audioTrack = item['audioTrack'];
        if (audioTrack is Map && audioTrack['audioIsDefault'] == false)
          continue;
        final bitrate = item['bitrate'];
        formats.add({
          'format_id': '$itag',
          'url': url,
          'protocol': 'https',
          'vcodec': 'none',
          'acodec': codec,
          if (bitrate is num && bitrate.isFinite && bitrate > 0)
            'abr': bitrate / 1000,
        });
      }
    }
    // URL/CDN/expiry checks and quality selection still go through the common
    // YouTubeFormats validator. Cipher-only entries are deliberately omitted.
    return {
      'id': id,
      'is_live': false,
      'http_headers': {'User-Agent': userAgent},
      'formats': formats,
    };
  }
}
