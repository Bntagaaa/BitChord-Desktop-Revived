import 'dart:convert';

import 'package:crypto/crypto.dart';

import '../domain/catalog.dart';
import 'provider_http.dart';

// Reference DesktopYouTubeAuth / DesktopYouTubeSession. Session-only: no
// preferences/SQLite/cookie file, browser scraping or plaintext fallback.
final class YouTubeSession {
  YouTubeSession._(
    this._cookie,
    this._secret,
    this.authUser,
    this.pageId,
    this.dataSyncId,
    this.visitorData,
    this.version,
  );
  final String _cookie, _secret;
  final String authUser, version;
  final String? pageId, dataSyncId, visitorData;
  static const origin = 'https://music.youtube.com';

  static String? value(String html, String key) =>
      RegExp('"$key"\\s*:\\s*"([^"\\r\\n]+)"').firstMatch(html)?.group(1);

  static String signingSecret(String cookie) {
    if (cookie.length > 32768 || RegExp(r'[\x00-\x1f\x7f]').hasMatch(cookie)) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    final jar = <String, String>{};
    for (final part in cookie.split(';')) {
      final at = part.indexOf('=');
      if (at > 0)
        jar[part.substring(0, at).trim()] = part.substring(at + 1).trim();
    }
    for (final key in ['SAPISID', '__Secure-3PAPISID', '__Secure-1PAPISID']) {
      if (jar[key]?.isNotEmpty ?? false) return jar[key]!;
    }
    throw const ProviderFailure(ProviderProblem.unauthorized);
  }

  static String signature(String secret, {int? seconds}) {
    final time = seconds ?? DateTime.now().millisecondsSinceEpoch ~/ 1000;
    return 'SAPISIDHASH ${time}_${sha1.convert(utf8.encode('$time $secret $origin'))}';
  }

  static Future<YouTubeSession> verify(
    String cookie,
    ProviderHttp http,
    RequestScope scope,
  ) async {
    final secret = signingSecret(cookie);
    final response = await http.request(
      Uri.parse(origin),
      scope,
      headers: {
        'Cookie': cookie,
        'Authorization': signature(secret),
        'Accept-Language': 'en-US,en;q=0.9',
      },
      maxBytes: 3 * 1024 * 1024,
    );
    if (response.status != 200)
      throw ProviderFailure(ProviderProblem.unauthorized, response.status);
    return fromVerifiedShell(cookie, utf8.decode(response.bytes));
  }

  static YouTubeSession fromVerifiedShell(String cookie, String html) {
    final secret = signingSecret(cookie);
    if (RegExp(
          r'"LOGGED_IN"\s*:\s*(true|false)\b',
        ).firstMatch(html)?.group(1) !=
        'true') {
      throw const ProviderFailure(ProviderProblem.unauthorized);
    }
    final version = value(html, 'INNERTUBE_CLIENT_VERSION');
    if (version == null) throw const ProviderFailure(ProviderProblem.malformed);
    return YouTubeSession._(
      cookie,
      secret,
      RegExp(r'"SESSION_INDEX"\s*:\s*"?(\d+)').firstMatch(html)?.group(1) ??
          '0',
      value(html, 'DELEGATED_SESSION_ID'),
      value(html, 'DATASYNC_ID')?.split('||').first,
      value(html, 'VISITOR_DATA'),
      version,
    );
  }

  String get cookie => _cookie;

  static YouTubeSession restore({
    required String cookie,
    required String authUser,
    required String version,
    String? pageId,
    String? dataSyncId,
    String? visitorData,
  }) {
    final secret = signingSecret(cookie);
    return YouTubeSession._(
      cookie,
      secret,
      authUser,
      pageId,
      dataSyncId,
      visitorData,
      version,
    );
  }

  Map<String, String> headers() => {
    'Cookie': _cookie,
    'Authorization': signature(_secret),
    'X-Goog-AuthUser': authUser,
    'X-Origin': origin,
    if (pageId != null) 'X-Goog-PageId': pageId!,
  };
  @override
  String toString() => 'YouTubeSession(redacted)';
}
