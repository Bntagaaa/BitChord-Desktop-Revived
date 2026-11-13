import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../../../features/online/domain/account_port.dart';
import '../../../features/online/domain/catalog.dart';

final class BrowserCredentials {
  BrowserCredentials(this.service, Map<String, dynamic> values)
    : values = Map.unmodifiable(values);
  final AccountService service;
  final Map<String, dynamic> values;
  @override
  String toString() => 'BrowserCredentials(redacted)';
}

abstract interface class AccountBrowser {
  Future<BrowserCredentials> connect(
    AccountService service,
    RequestScope scope,
    void Function(String) progress,
  );
  Future<BrowserCredentials> refreshSpotify(
    Map<String, dynamic> previous,
    RequestScope scope,
  );
}

// Owns a fresh profile, never attaches to the user's ordinary browser/profile.
// Like DesktopBrowserSignIn.kt, interactive login runs WITHOUT remote debugging.
// Closing that window authorizes a short, loopback-only capture of this profile.
final class DesktopAccountBrowser implements AccountBrowser {
  DesktopAccountBrowser({this.executable});
  final String? executable;
  bool _busy = false;
  static const _youtube = 'https://music.youtube.com/';
  static const _spotify = 'https://open.spotify.com/';

  Future<String> _browser() async {
    if (executable != null && await File(executable!).exists())
      return executable!;
    final env = Platform.environment;
    final paths = <String>[];
    if (Platform.isWindows) {
      for (final root in [
        env['LOCALAPPDATA'],
        env['ProgramFiles'],
        env['ProgramFiles(x86)'],
      ].whereType<String>()) {
        paths.addAll([
          '$root/Google/Chrome/Application/chrome.exe',
          '$root/Microsoft/Edge/Application/msedge.exe',
          '$root/BraveSoftware/Brave-Browser/Application/brave.exe',
          '$root/Vivaldi/Application/vivaldi.exe',
        ]);
      }
    } else if (Platform.isLinux) {
      paths.addAll([
        '/usr/bin/google-chrome-stable',
        '/usr/bin/google-chrome',
        '/usr/bin/chromium',
        '/usr/bin/chromium-browser',
        '/usr/bin/microsoft-edge',
        '/usr/bin/brave-browser',
        '/usr/bin/vivaldi',
        '/snap/bin/chromium',
      ]);
    }
    for (final path in paths) {
      if (await File(path).exists()) return path;
    }
    throw const AccountFailure(AccountIssue.browserUnavailable);
  }

  @override
  Future<BrowserCredentials> connect(
    AccountService service,
    RequestScope scope,
    void Function(String) progress,
  ) => _capture(service, scope, progress: progress);
  @override
  Future<BrowserCredentials> refreshSpotify(
    Map<String, dynamic> previous,
    RequestScope scope,
  ) => _capture(AccountService.spotify, scope, previous: previous);

  Future<BrowserCredentials> _capture(
    AccountService service,
    RequestScope scope, {
    void Function(String)? progress,
    Map<String, dynamic>? previous,
  }) async {
    scope.check();
    if (_busy) throw const AccountFailure(AccountIssue.operationFailed);
    _busy = true;
    Directory? profile;
    Process? process;
    CdpConnection? cdp;
    void Function()? detach;
    try {
      final browser = await _browser();
      profile = await Directory.systemTemp.createTemp('bitchord-account-');
      final ownedProfile = profile;
      if (previous == null) {
        progress?.call(
          'Sign in in the browser, open ${service == AccountService.youtube ? 'YouTube Music' : 'Spotify'}, then close that browser window.',
        );
        process = await _launch(browser, ownedProfile, [
          '--new-window',
          service == AccountService.youtube ? _youtube : _spotify,
        ]);
        final owned = process;
        detach = scope.onCancel(() {
          owned.kill();
        });
        await owned.exitCode.timeout(const Duration(minutes: 10));
        detach();
        detach = null;
        scope.check();
        process = null;
      }
      progress?.call('Verifying the browser session...');
      final portFile = File('${ownedProfile.path}/DevToolsActivePort');
      if (await portFile.exists()) await portFile.delete();
      process = await _launch(browser, ownedProfile, [
        '--headless=new',
        '--remote-debugging-port=0',
        '--remote-debugging-address=127.0.0.1',
        'about:blank',
      ]);
      final reader = process;
      detach = scope.onCancel(() {
        reader.kill();
      });
      bool exited = false;
      unawaited(
        reader.exitCode.then((_) {
          exited = true;
        }),
      );
      Uri? endpoint;
      for (var attempt = 0; attempt < 200; attempt++) {
        scope.check();
        if (exited) throw const AccountFailure(AccountIssue.browserClosed);
        if (await portFile.exists()) {
          final text = await portFile.readAsString();
          endpoint = parseEndpoint(text);
          if (endpoint != null) break;
        }
        await Future<void>.delayed(const Duration(milliseconds: 100));
      }
      if (endpoint == null)
        throw const AccountFailure(AccountIssue.browserTimeout);
      cdp = await CdpConnection.open(endpoint, scope);
      if (previous != null) {
        final cookie = previous['sp_dc'];
        if (cookie is! String ||
            cookie.isEmpty ||
            cookie.length > 16384 ||
            RegExp(r'[\x00-\x20;]').hasMatch(cookie)) {
          throw const AccountFailure(AccountIssue.loginRequired);
        }
        await cdp.command('Storage.setCookies', {
          'cookies': [
            {
              'name': 'sp_dc',
              'value': cookie,
              'domain': '.spotify.com',
              'path': '/',
              'secure': true,
              'httpOnly': true,
            },
          ],
        });
      }
      final response = await cdp.command('Storage.getCookies', const {});
      final cookies = response['cookies'];
      if (cookies is! List || cookies.length > 10000)
        throw const AccountFailure(AccountIssue.loginRequired);
      if (service == AccountService.youtube) {
        final header = youtubeHeader(cookies);
        if (!RegExp(
          r'(?:^|; )(?:SAPISID|__Secure-3PAPISID|__Secure-1PAPISID)=',
        ).hasMatch(header))
          throw const AccountFailure(AccountIssue.loginRequired);
        return BrowserCredentials(service, {'cookie': header});
      }
      final spotifyCookie = spotifySessionCookie(cookies);
      if (spotifyCookie == null)
        throw const AccountFailure(AccountIssue.loginRequired);
      final tokens = await _spotifyTokens(cdp, scope);
      return BrowserCredentials(service, {...tokens, 'sp_dc': spotifyCookie});
    } on ProviderFailure {
      rethrow;
    } on AccountFailure {
      rethrow;
    } on TimeoutException {
      scope.check();
      throw const AccountFailure(AccountIssue.browserTimeout);
    } catch (_) {
      scope.check();
      throw const AccountFailure(AccountIssue.operationFailed);
    } finally {
      detach?.call();
      await cdp?.closeBrowser();
      if (process != null) {
        process.kill();
        try {
          await process.exitCode.timeout(const Duration(seconds: 2));
        } catch (_) {
          process.kill(ProcessSignal.sigkill);
        }
      }
      if (profile != null) {
        for (var attempt = 0; attempt < 5; attempt++) {
          try {
            if (await profile.exists()) await profile.delete(recursive: true);
            break;
          } catch (_) {
            await Future<void>.delayed(const Duration(milliseconds: 200));
          }
        }
      }
      _busy = false;
    }
  }

  Future<Process> _launch(
    String executable,
    Directory profile,
    List<String> arguments,
  ) async {
    final process = await Process.start(executable, [
      '--user-data-dir=${profile.absolute.path}',
      '--disable-background-mode',
      '--no-first-run',
      '--no-default-browser-check',
      ...arguments,
    ], runInShell: false);
    // Browser diagnostics can contain account/provider data. Drain, never log.
    unawaited(process.stdout.drain<void>());
    unawaited(process.stderr.drain<void>());
    return process;
  }

  static Uri? parseEndpoint(String text) {
    if (text.length > 2048) return null;
    final lines = text.trim().split('\n');
    if (lines.length != 2) return null;
    final port = int.tryParse(lines[0].trim()), path = lines[1].trim();
    if (port == null ||
        port < 1 ||
        port > 65535 ||
        !RegExp(r'^/devtools/browser/[a-zA-Z0-9-]+$').hasMatch(path))
      return null;
    return Uri(scheme: 'ws', host: '127.0.0.1', port: port, path: path);
  }

  static String youtubeHeader(List<dynamic> cookies) {
    final jar = <String, String>{};
    for (final item in cookies) {
      if (item is! Map) continue;
      final domain = '${item['domain'] ?? ''}'
          .replaceFirst(RegExp(r'^\.'), '')
          .toLowerCase();
      if (domain != 'youtube.com' && !domain.endsWith('.youtube.com')) continue;
      final name = item['name'], value = item['value'];
      if (name is! String ||
          value is! String ||
          name.isEmpty ||
          value.isEmpty ||
          name.length > 256 ||
          value.length > 16384 ||
          !RegExp(r'^[A-Za-z0-9_\-]+$').hasMatch(name) ||
          RegExp(r'[\x00-\x20;]').hasMatch(value))
        continue;
      final expiry = item['expires'];
      if (expiry is num &&
          expiry > 0 &&
          expiry * 1000 <= DateTime.now().millisecondsSinceEpoch)
        continue;
      jar[name] = value;
    }
    final header = jar.entries
        .map((entry) => '${entry.key}=${entry.value}')
        .join('; ');
    if (header.length > 32768)
      throw const AccountFailure(AccountIssue.loginRequired);
    return header;
  }

  static String? spotifySessionCookie(List<dynamic> cookies) {
    for (final item in cookies) {
      if (item is! Map || item['name'] != 'sp_dc') continue;
      final domain = '${item['domain'] ?? ''}'
          .replaceFirst(RegExp(r'^\.'), '')
          .toLowerCase();
      if (domain != 'spotify.com' && !domain.endsWith('.spotify.com')) continue;
      final value = item['value'], expiry = item['expires'];
      if (expiry is num &&
          expiry > 0 &&
          expiry * 1000 <= DateTime.now().millisecondsSinceEpoch)
        continue;
      if (value is String &&
          value.isNotEmpty &&
          value.length <= 16384 &&
          !RegExp(r'[\x00-\x20;]').hasMatch(value))
        return value;
    }
    return null;
  }

  // Observe the actual Spotify page's response, never synthesize a bearer/TOTP.
  Future<Map<String, dynamic>> _spotifyTokens(
    CdpConnection cdp,
    RequestScope scope,
  ) async {
    final created = await cdp.command('Target.createTarget', {
      'url': 'about:blank',
    });
    final target = created['targetId'];
    if (target is! String)
      throw const AccountFailure(AccountIssue.operationFailed);
    final attached = await cdp.command('Target.attachToTarget', {
      'targetId': target,
      'flatten': true,
    });
    final session = attached['sessionId'];
    if (session is! String)
      throw const AccountFailure(AccountIssue.operationFailed);
    final result = Completer<Map<String, dynamic>>();
    unawaited(
      result.future.then<void>((_) {}, onError: (Object _, StackTrace __) {}),
    );
    final requests = <String, String>{};
    Map<String, dynamic>? bearer;
    String? clientToken;
    void finish() {
      if (bearer != null && clientToken != null && !result.isCompleted)
        result.complete({...bearer!, 'clientToken': clientToken});
    }

    final listener = cdp.events.listen((event) async {
      if (event['sessionId'] != session ||
          scope.cancelled ||
          result.isCompleted)
        return;
      final params = event['params'];
      if (params is! Map) return;
      try {
        if (event['method'] == 'Network.responseReceived') {
          final response = params['response'];
          final id = params['requestId'];
          if (response is! Map ||
              id is! String ||
              requests.length >= 16 ||
              response['status'] != 200)
            return;
          final uri = Uri.tryParse('${response['url']}');
          if (uri == null ||
              uri.scheme != 'https' ||
              uri.userInfo.isNotEmpty ||
              uri.port != 443)
            return;
          if (uri.host == 'open.spotify.com' && uri.path == '/api/token')
            requests[id] = 'bearer';
          if (uri.host == 'clienttoken.spotify.com' &&
              uri.path == '/v1/clienttoken')
            requests[id] = 'client';
        } else if (event['method'] == 'Network.requestWillBeSent') {
          final request = params['request'];
          if (request is! Map || request['headers'] is! Map) return;
          final uri = Uri.tryParse('${request['url']}');
          if (uri?.scheme != 'https' ||
              uri?.host != 'api-partner.spotify.com' ||
              uri?.port != 443)
            return;
          for (final entry in (request['headers'] as Map).entries) {
            if ('${entry.key}'.toLowerCase() == 'client-token' &&
                entry.value is String &&
                _safeToken(entry.value as String))
              clientToken = entry.value as String;
          }
          finish();
        } else if (event['method'] == 'Network.loadingFinished') {
          final id = params['requestId'];
          final kind = requests.remove(id);
          if (kind == null || id is! String) return;
          final body = await cdp.command('Network.getResponseBody', {
            'requestId': id,
          }, session: session);
          final raw = body['body'];
          if (raw is! String || raw.length > 128 * 1024) return;
          final text = body['base64Encoded'] == true
              ? utf8.decode(base64Decode(raw))
              : raw;
          final decoded = jsonDecode(text);
          if (decoded is! Map<String, dynamic>) return;
          if (kind == 'bearer') {
            bearer = authenticatedSpotifyToken(decoded);
          } else {
            final granted = decoded['granted_token'];
            if (granted is Map &&
                granted['token'] is String &&
                _safeToken(granted['token'] as String))
              clientToken = granted['token'] as String;
          }
          finish();
        }
      } catch (_) {
        /* One unsupported/anonymous response is not successful login. */
      }
    });
    final detach = scope.onCancel(() {
      if (!result.isCompleted)
        result.completeError(const ProviderFailure(ProviderProblem.cancelled));
    });
    try {
      await cdp.command('Network.enable', {
        'maxTotalBufferSize': 2 * 1024 * 1024,
        'maxResourceBufferSize': 256 * 1024,
      }, session: session);
      await cdp.command('Page.enable', const {}, session: session);
      await cdp.command('Page.navigate', {'url': _spotify}, session: session);
      return await result.future.timeout(const Duration(seconds: 35));
    } finally {
      detach();
      await listener.cancel();
    }
  }

  static bool _safeToken(String value) =>
      value.isNotEmpty &&
      value.length <= 16384 &&
      !RegExp(r'[\x00-\x20]').hasMatch(value);
  static Map<String, dynamic>? authenticatedSpotifyToken(
    Map<String, dynamic> data,
  ) {
    final token = data['accessToken'],
        expires = data['accessTokenExpirationTimestampMs'];
    if (data['isAnonymous'] != false ||
        token is! String ||
        !_safeToken(token) ||
        expires is! num ||
        expires <= DateTime.now().millisecondsSinceEpoch + 30000)
      return null;
    return {
      'accessToken': token,
      'expiresAt': expires.toInt(),
      if (data['clientId'] is String) 'clientId': data['clientId'],
    };
  }
}

// A small bounded CDP request multiplexer. It accepts only a loopback browser
// endpoint created by this broker, with no wildcard remote-debugging origin.
final class CdpConnection {
  CdpConnection._(this._socket, this._scope) {
    _subscription = _socket.listen(
      _message,
      onError: (Object _) => _abort(),
      onDone: _abort,
    );
    _detach = _scope.onCancel(_abort);
  }
  final WebSocket _socket;
  final RequestScope _scope;
  final _pending = <int, Completer<Map<String, dynamic>>>{};
  final _events = StreamController<Map<String, dynamic>>.broadcast();
  late final StreamSubscription<dynamic> _subscription;
  late final void Function() _detach;
  int _id = 0;
  bool _closed = false;
  Stream<Map<String, dynamic>> get events => _events.stream;

  static Future<CdpConnection> open(Uri endpoint, RequestScope scope) async {
    scope.check();
    if (endpoint.scheme != 'ws' ||
        endpoint.host != '127.0.0.1' ||
        endpoint.port < 1 ||
        endpoint.port > 65535 ||
        endpoint.userInfo.isNotEmpty ||
        endpoint.hasQuery ||
        endpoint.hasFragment ||
        !RegExp(r'^/devtools/browser/[a-zA-Z0-9-]+$').hasMatch(endpoint.path))
      throw const AccountFailure(AccountIssue.operationFailed);
    final socket = await WebSocket.connect(
      endpoint.toString(),
      compression: CompressionOptions.compressionOff,
    ).timeout(const Duration(seconds: 10));
    if (scope.cancelled) {
      await socket.close();
      scope.check();
    }
    return CdpConnection._(socket, scope);
  }

  void _message(dynamic text) {
    if (_closed || text is! String || text.length > 1024 * 1024) {
      _abort();
      return;
    }
    try {
      final data = jsonDecode(text);
      if (data is! Map<String, dynamic>) return;
      final completion = _pending.remove(data['id']);
      if (completion != null) {
        if (data['error'] != null || data['result'] is! Map<String, dynamic>) {
          completion.completeError(
            const AccountFailure(AccountIssue.operationFailed),
          );
        } else {
          completion.complete(data['result'] as Map<String, dynamic>);
        }
      } else if (data['method'] is String) {
        _events.add(data);
      }
    } catch (_) {
      _abort();
    }
  }

  Future<Map<String, dynamic>> command(
    String method,
    Map<String, Object?> params, {
    String? session,
  }) async {
    _scope.check();
    if (_closed || _pending.length >= 32)
      throw const AccountFailure(AccountIssue.operationFailed);
    final id = ++_id, completion = Completer<Map<String, dynamic>>();
    _pending[id] = completion;
    try {
      _socket.add(
        jsonEncode({
          'id': id,
          'method': method,
          'params': params,
          if (session != null) 'sessionId': session,
        }),
      );
      return await completion.future.timeout(const Duration(seconds: 10));
    } finally {
      _pending.remove(id);
    }
  }

  void _abort() {
    if (_closed) return;
    _closed = true;
    for (final completion in _pending.values) {
      completion.completeError(
        _scope.cancelled
            ? const ProviderFailure(ProviderProblem.cancelled)
            : const AccountFailure(AccountIssue.browserClosed),
      );
    }
    _pending.clear();
    unawaited(_socket.close());
  }

  Future<void> closeBrowser() async {
    if (!_closed) {
      try {
        _socket.add(jsonEncode({'id': ++_id, 'method': 'Browser.close'}));
      } catch (_) {}
    }
    _detach();
    _abort();
    await _subscription.cancel();
    await _events.close();
  }
}
