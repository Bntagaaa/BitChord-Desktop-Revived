import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;

import '../domain/models.dart';
import 'innertube_parser.dart';

class InnertubeClient {
  InnertubeClient({
    http.Client? httpClient,
    this.language = 'en',
    this.region = 'US',
    DateTime Function()? clock,
  }) : _http = httpClient ?? http.Client(),
       _ownsClient = httpClient == null,
       _clock = clock ?? DateTime.now;

  final http.Client _http;
  final bool _ownsClient;
  final DateTime Function() _clock;
  String language;
  String region;
  String? _cookie;
  String? get cookie => _cookie;
  String? _visitorData;
  String _clientVersion = '1.20250101.01.00';
  String? _dataSyncId;
  String? _pageId;
  String _authUser = '0';
  bool _ready = false;
  Future<void>? _initializing;
  int _sessionGeneration = 0;
  bool _closed = false;
  bool get isSignedIn => _cookie != null && sapisidFrom(_cookie!) != null;
  int get sessionGeneration => _sessionGeneration;
  static const musicOrigin = 'https://music.youtube.com';
  static const userAgent =
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
      'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';

  void setCookie(String? value) {
    final normalized = value?.trim();
    if (normalized != null &&
        (normalized.contains('\r') || normalized.contains('\n'))) {
      throw ArgumentError('Cookie headers must be a single line.');
    }
    _cookie = normalized?.isNotEmpty == true ? normalized : null;
    _visitorData = null;
    _dataSyncId = null;
    _pageId = null;
    _authUser = '0';
    _ready = false;
    _initializing = null;
    _sessionGeneration++;
  }

  void setChannel(AccountChannel? channel) {
    _pageId = channel?.pageId;
    _dataSyncId = channel?.dataSyncId;
    _authUser = channel?.authUser ?? '0';
    _sessionGeneration++;
  }

  Future<void> prepareSession() {
    if (_ready) return Future.value();
    return _initializing ??= _loadShell();
  }

  Future<void> _loadShell() async {
    final generation = _sessionGeneration;
    try {
      final response = await _request(
        'GET',
        Uri.parse('$musicOrigin/'),
        headers: _headers(authenticated: true),
        maxBytes: 8 * 1024 * 1024,
      );
      if (generation != _sessionGeneration) return;
      final html = response.body;
      String? config(String key) =>
          RegExp('"$key"\\s*:\\s*"([^"\\r\\n]+)"').firstMatch(html)?.group(1);
      _clientVersion = config('INNERTUBE_CLIENT_VERSION') ?? _clientVersion;
      _visitorData = config('VISITOR_DATA') ?? _visitorData;
      final loggedIn = RegExp(r'"LOGGED_IN"\s*:\s*true').hasMatch(html);
      if (loggedIn && _cookie != null) {
        _pageId = config('DELEGATED_SESSION_ID');
        final raw = config('DATASYNC_ID');
        // DATASYNC_ID is account||delegated; the second half is active.
        _dataSyncId = _pageId ?? normalizeDataSyncId(raw);
        _authUser =
            RegExp(r'"SESSION_INDEX"\s*:\s*"?(\d+)')
                .firstMatch(html)
                ?.group(1) ??
            '0';
      }
      _ready = true;
    } finally {
      if (generation == _sessionGeneration) _initializing = null;
    }
  }

  Map<String, String> _headers({required bool authenticated}) {
    final headers = <String, String>{
      'Content-Type': 'application/json',
      'User-Agent': userAgent,
      'Origin': musicOrigin,
      'X-Origin': musicOrigin,
      'Referer': '$musicOrigin/',
      'Accept-Language': language == 'en'
          ? 'en-US,en;q=0.9'
          : '$language,en;q=0.8',
      'X-YouTube-Client-Name': '67',
      'X-YouTube-Client-Version': _clientVersion,
      'X-Goog-Visitor-Id': ?_visitorData,
    };
    if (authenticated && _cookie != null) {
      headers['Cookie'] = _cookie!;
      headers['X-Goog-AuthUser'] = _authUser;
      if (_pageId != null) headers['X-Goog-PageId'] = _pageId!;
      final sid = sapisidFrom(_cookie!);
      if (sid != null) headers['Authorization'] = sapisidHash(sid, _clock());
    }
    return headers;
  }

  Future<JsonMap> post(
    String endpoint,
    JsonMap payload, {
    bool authenticated = true,
  }) async {
    if (_closed) throw StateError('The music client has been closed.');
    if (!RegExp(r'^[a-z_]+(?:/[a-z_]+)*$').hasMatch(endpoint)) {
      throw ArgumentError.value(endpoint, 'endpoint');
    }
    final generation = _sessionGeneration;
    await prepareSession();
    if (generation != _sessionGeneration) {
      throw const ServiceException(
        'The account changed before this request was sent.',
      );
    }
    final body = jsonEncode({
      'context': {
        'client': {
          'clientName': 'WEB_REMIX',
          'clientVersion': _clientVersion,
          'hl': language,
          'gl': region,
          if (_visitorData != null) 'visitorData': _visitorData,
        },
        'user': {
          'lockedSafetyMode': false,
          if (authenticated && _dataSyncId != null)
            'onBehalfOfUser': _dataSyncId,
        },
        'request': {'useSsl': true},
      },
      ...payload,
    });
    final uri = Uri.parse('$musicOrigin/youtubei/v1/$endpoint')
        .replace(queryParameters: {'prettyPrint': 'false', 'hl': language});
    const reads = {
      'browse',
      'next',
      'search',
      'player',
      'get_transcript',
      'account/account_menu',
      'account/accounts_list',
      'music/get_search_suggestions',
    };
    http.Response response;
    var attempts = 0;
    while (true) {
      try {
        response = await _request(
          'POST',
          uri,
          headers: _headers(authenticated: authenticated),
          body: body,
        );
        break;
      } on ServiceException catch (error) {
        // Never retry a mutation: an ambiguous network failure may have committed it.
        if (!reads.contains(endpoint) || !error.retryable || attempts++ >= 1) {
          rethrow;
        }
        await Future<void>.delayed(Duration(milliseconds: 400 * attempts));
      }
    }
    if (generation != _sessionGeneration) {
      throw const ServiceException(
        'The account changed while this request was loading.',
      );
    }
    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const ServiceException(
        'YouTube Music returned an unexpected response.',
      );
    }
    final error = InnertubeParser.object(decoded['error']);
    if (error.isNotEmpty) {
      throw ServiceException(
        error['message'] as String? ?? 'YouTube Music refused this request.',
        statusCode: (error['code'] as num?)?.toInt(),
      );
    }
    _visitorData ??= InnertubeParser.string(
      InnertubeParser.at(decoded, ['responseContext', 'visitorData']),
    );
    return decoded;
  }

  Future<JsonMap> player(String videoId) => post('player', {
    'videoId': videoId,
    'contentCheckOk': true,
    'racyCheckOk': true,
  });
  Future<JsonMap> browse(String id, {String? params}) =>
      post('browse', {'browseId': id, 'params': ?params});
  Future<JsonMap> browseContinuation(String token) =>
      post('browse', {'continuation': token});

  void requireSession() {
    if (!isSignedIn) {
      throw const ServiceException(
        'Sign in to YouTube Music to use this feature.',
      );
    }
  }

  Future<http.Response> _request(
    String method,
    Uri uri, {
    required Map<String, String> headers,
    String? body,
    int maxBytes = 16 * 1024 * 1024,
  }) async {
    try {
      return await (() async {
        final request = http.Request(method, uri)..followRedirects = false;
        request.headers.addAll(headers);
        if (body != null) request.body = body;
        final streamed = await _http.send(request);
        if ((streamed.contentLength ?? 0) > maxBytes) {
          throw const ServiceException('The response exceeds the size limit.');
        }
        final bytes = BytesBuilder(copy: false);
        await for (final chunk in streamed.stream) {
          if (bytes.length + chunk.length > maxBytes) {
            throw const ServiceException(
              'The response exceeds the size limit.',
            );
          }
          bytes.add(chunk);
        }
        final response = http.Response.bytes(
          bytes.takeBytes(),
          streamed.statusCode,
          headers: streamed.headers,
          request: request,
        );
        if (response.statusCode < 200 || response.statusCode >= 300) {
          final status = response.statusCode;
          throw ServiceException(
            status == 401 || status == 403
                ? 'YouTube Music rejected the session. Please sign in again.'
                : 'YouTube Music request failed (HTTP $status).',
            statusCode: status,
            retryable: status == 429 || status >= 500,
          );
        }
        return response;
      })().timeout(const Duration(seconds: 25));
    } on TimeoutException {
      throw const ServiceException(
        'YouTube Music timed out. Check the connection and retry.',
        retryable: true,
      );
    } on http.ClientException {
      throw const ServiceException(
        'Could not connect to YouTube Music.',
        retryable: true,
      );
    }
  }

  static String? sapisidFrom(String cookieHeader) {
    final jar = <String, String>{};
    for (final part in cookieHeader.split(';')) {
      final index = part.indexOf('=');
      if (index > 0) {
        jar[part.substring(0, index).trim()] = part.substring(index + 1).trim();
      }
    }
    for (final name in ['SAPISID', '__Secure-3PAPISID', '__Secure-1PAPISID']) {
      if (jar[name]?.isNotEmpty == true) return jar[name];
    }
    return null;
  }

  static String? normalizeDataSyncId(String? raw) {
    final value = raw?.trim();
    if (value == null || value.isEmpty) return null;
    final separator = value.indexOf('||');
    if (separator < 0) return value;
    final delegated = value.substring(separator + 2).trim();
    if (delegated.isNotEmpty) return delegated;
    final personal = value.substring(0, separator).trim();
    return personal.isEmpty ? null : personal;
  }

  static String sapisidHash(
    String value,
    DateTime now, {
    String origin = musicOrigin,
  }) {
    final timestamp = now.millisecondsSinceEpoch ~/ 1000;
    final digest = sha1.convert(utf8.encode('$timestamp $value $origin'));
    return 'SAPISIDHASH ${timestamp}_$digest';
  }

  void close() {
    _closed = true;
    _sessionGeneration++;
    if (_ownsClient) _http.close();
  }
}
