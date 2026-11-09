// Port source: shared/.../innertube/Innertube.kt @ 348b613a.
import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import '../domain/music.dart';
import 'renderer_parser.dart';

class MusicApiException implements Exception {
  const MusicApiException(this.message, [this.status]);
  final String message;
  final int? status;
  @override
  String toString() => message;
}

class InnerTube {
  InnerTube({http.Client? client}) : _client = client ?? http.Client();
  final http.Client _client;
  static const origin = 'https://music.youtube.com';
  static const fallbackVersion = '1.20250101.01.00';
  static const userAgent =
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';
  String _language = 'en';
  String get language => switch (_language.toLowerCase()) {
    'iw' => 'he',
    'in' => 'id',
    'ji' => 'yi',
    _ => _language,
  };
  set language(String value) => _language = value;
  String get _acceptLanguage => language == 'en'
      ? 'en-US,en;q=0.9'
      : '$language,en-US;q=0.8,en;q=0.7';
  String region = 'US';
  String? _cookie;
  String? visitorData;
  String? _version;
  String? _pageId;
  String? _dataSyncId;
  String _authUser = '0';
  Future<void>? _shell;
  int _generation = 0;

  set cookie(String? value) {
    if (_cookie == value) return;
    _cookie = value;
    _generation++;
    _shell = null;
    visitorData = null;
    _version = null;
    _pageId = null;
    _dataSyncId = null;
    _authUser = '0';
  }

  bool get signedIn => _cookie?.isNotEmpty ?? false;

  static String? sapisid(String cookie) {
    final jar = <String, String>{};
    for (final pair in cookie.split(';')) {
      final separator = pair.indexOf('=');
      if (separator > 0) {
        jar[pair.substring(0, separator).trim()] = pair
            .substring(separator + 1)
            .trim();
      }
    }
    for (final name in ['SAPISID', '__Secure-3PAPISID', '__Secure-1PAPISID']) {
      final value = jar[name];
      if (value != null && value.isNotEmpty) return value;
    }
    return null;
  }

  static String authorization(String value, int seconds) =>
      'SAPISIDHASH ${seconds}_${sha1.convert(utf8.encode('$seconds $value $origin'))}';

  Future<void> initialize() => _shell ??= _readShell(_generation);
  Future<void> _readShell(int generation) async {
    final session = _cookie;
    final sid = session == null ? null : sapisid(session);
    final response = await _client
        .get(
          Uri.parse('$origin/'),
          headers: {
            'User-Agent': userAgent,
            'Accept-Language': _acceptLanguage,
            'Cookie': ?session,
            if (sid != null)
              'Authorization': authorization(
                sid,
                DateTime.now().millisecondsSinceEpoch ~/ 1000,
              ),
          },
        )
        .timeout(const Duration(seconds: 20));
    if (response.statusCode != 200) {
      throw MusicApiException(
        'YouTube Music: HTTP ${response.statusCode}',
        response.statusCode,
      );
    }
    if (generation != _generation) return;
    String? config(String name) =>
        RegExp('"$name"\\s*:\\s*"([^"]+)"').firstMatch(response.body)?.group(1);
    _version = config('INNERTUBE_CLIENT_VERSION');
    visitorData = config('VISITOR_DATA');
    if (RegExp(r'"LOGGED_IN"\s*:\s*true').hasMatch(response.body)) {
      _pageId = config('DELEGATED_SESSION_ID');
      _dataSyncId = _pageId ?? config('DATASYNC_ID')?.split('||').first;
      _authUser =
          RegExp(
            r'"SESSION_INDEX"\s*:\s*"?(\d+)',
          ).firstMatch(response.body)?.group(1) ??
          '0';
    }
  }

  Future<JsonMap> post(
    String endpoint,
    JsonMap extra, {
    Map<String, String> query = const {},
  }) async {
    // As in the source, failure to refresh shell context must not invent an account identity.
    try {
      await initialize();
    } on Exception {
      _shell = null;
    }
    final version = _version ?? fallbackVersion;
    final generation = _generation;
    final session = _cookie;
    final sid = session == null ? null : sapisid(session);
    final response = await _client
        .post(
          Uri.parse('$origin/youtubei/v1/$endpoint').replace(
            queryParameters: {'prettyPrint': 'false', 'hl': language, ...query},
          ),
          headers: {
            'Content-Type': 'application/json',
            'User-Agent': userAgent,
            'Origin': origin,
            'X-Origin': origin,
            'Referer': '$origin/',
            'Accept-Language': _acceptLanguage,
            'X-YouTube-Client-Name': '67',
            'X-YouTube-Client-Version': version,
            if (visitorData != null) 'X-Goog-Visitor-Id': visitorData!,
            'Cookie': ?session,
            if (session != null) 'X-Goog-AuthUser': _authUser,
            if (_pageId != null) 'X-Goog-PageId': _pageId!,
            if (sid != null)
              'Authorization': authorization(
                sid,
                DateTime.now().millisecondsSinceEpoch ~/ 1000,
              ),
          },
          body: jsonEncode({
            'context': {
              'client': {
                'clientName': 'WEB_REMIX',
                'clientVersion': version,
                'hl': language,
                'gl': region,
                if (visitorData != null) 'visitorData': visitorData,
              },
              'user': {
                'lockedSafetyMode': false,
                if (_dataSyncId != null) 'onBehalfOfUser': _dataSyncId,
              },
              'request': {'useSsl': true},
            },
            ...extra,
          }),
        )
        .timeout(const Duration(seconds: 30));
    if (response.statusCode != 200) {
      throw MusicApiException(
        'YouTube Music: HTTP ${response.statusCode}',
        response.statusCode,
      );
    }
    final root = object(jsonDecode(response.body));
    if (root['error'] != null) {
      throw MusicApiException(
        object(root['error'])['message']?.toString() ??
            'YouTube Music rejected the request',
      );
    }
    if (generation != _generation) {
      throw const MusicApiException('The account changed. Retry the request.');
    }
    visitorData ??= at(root, ['responseContext', 'visitorData']) as String?;
    return root;
  }

  Future<JsonMap> browse(String id, {String? params}) =>
      post('browse', {'browseId': id, 'params': ?params});
  Future<JsonMap> continuation(String token) => post(
    'browse',
    {'continuation': token},
    query: {'ctoken': token, 'continuation': token, 'type': 'next'},
  );
  Future<JsonMap> search(String query, SearchFilter filter) => post('search', {
    'query': query.trim(),
    if (filter.params != null) 'params': filter.params,
  });
  Future<JsonMap> next(String id) => post('next', {'videoId': id});
  Future<void> like(String id, bool liked) async {
    if (!signedIn) {
      throw const MusicApiException('Sign in to change your library');
    }
    await post(liked ? 'like/like' : 'like/removelike', {
      'target': {'videoId': id},
    });
  }

  void close() => _client.close();
}
