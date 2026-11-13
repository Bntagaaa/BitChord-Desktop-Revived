import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../domain/catalog.dart';
import 'youtube_session.dart';

/// Kotlin DesktopBrowserSignIn analogue: login in a dedicated browser profile
/// without debugging, then read that isolated profile with a headless browser.
/// Never inspect or modify the user's ordinary browser profile.
final class BrowserSignIn {
  BrowserSignIn._();

  static const _loginTimeout = Duration(minutes: 15);
  static const _probeInterval = Duration(milliseconds: 125);

  static Future<String?> availableBrowser() async {
    if (Platform.isWindows) {
      final env = Platform.environment;
      final paths = <String>[
        if (env['LOCALAPPDATA'] != null)
          env['LOCALAPPDATA']! + r'\Google\Chrome\Application\chrome.exe',
        if (env['LOCALAPPDATA'] != null)
          env['LOCALAPPDATA']! + r'\Microsoft\Edge\Application\msedge.exe',
        if (env['PROGRAMFILES'] != null)
          env['PROGRAMFILES']! + r'\Google\Chrome\Application\chrome.exe',
        if (env['PROGRAMFILES'] != null)
          env['PROGRAMFILES']! + r'\Microsoft\Edge\Application\msedge.exe',
        if (env['PROGRAMFILES(X86)'] != null)
          env['PROGRAMFILES(X86)']! + r'\Microsoft\Edge\Application\msedge.exe',
      ];
      for (final path in paths) {
        if (await File(path).exists()) return path;
      }
      return null;
    }
    if (Platform.isLinux) {
      final path = Platform.environment['PATH'] ?? '';
      for (final binary in [
        'google-chrome',
        'google-chrome-stable',
        'chromium',
        'chromium-browser',
        'microsoft-edge',
        'brave-browser',
      ]) {
        for (final directory in path.split(':')) {
          if (directory.isEmpty) continue;
          final file = File('$directory/$binary');
          if (await file.exists()) return file.path;
        }
      }
    }
    return null;
  }

  /// Accept only the ephemeral localhost browser endpoint, never arbitrary
  /// paths/hosts from files, web responses, preferences or user input.
  static String? endpointFromPortFile(String raw) {
    final lines = raw.trim().split(RegExp(r'\r?\n'));
    if (lines.length < 2) return null;
    final port = int.tryParse(lines[0].trim());
    final path = lines[1].trim();
    if (port == null ||
        port < 1 ||
        port > 65535 ||
        !RegExp(r'^/devtools/browser/[a-zA-Z0-9-]{8,128}$').hasMatch(path)) {
      return null;
    }
    return 'ws://127.0.0.1:$port$path';
  }

  /// Extract only youtube.com cookies, reject malformed names, delimiters and
  /// a missing signing secret. No cookies or signed URLs are logged or stored.
  static String youtubeCookieHeader(Object? response) {
    final result = response is Map ? response['result'] : null;
    final cookies = result is Map ? result['cookies'] : null;
    if (cookies is! List) {
      throw const ProviderFailure(ProviderProblem.malformed);
    }
    final jar = <String, String>{};
    for (final item in cookies) {
      if (item is! Map) continue;
      final domain = (item['domain'] is String ? item['domain'] as String : '')
          .toLowerCase()
          .replaceFirst(RegExp(r'^\.'), '');
      if (domain != 'youtube.com' && !domain.endsWith('.youtube.com')) {
        continue;
      }
      final name = item['name'], value = item['value'];
      if (name is! String ||
          value is! String ||
          !RegExp(r'^[A-Za-z0-9_-]{1,80}$').hasMatch(name) ||
          value.isEmpty ||
          RegExp(r'[\x00-\x20;\x7f]').hasMatch(value)) {
        continue;
      }
      jar[name] = value;
    }
    final header = jar.entries.map((e) => e.key + '=' + e.value).join('; ');
    YouTubeSession.signingSecret(header);
    return header;
  }

  static Future<Process> _launch(String executable, List<String> args) async {
    final process = await Process.start(executable, args);
    unawaited(process.stdout.drain<void>());
    unawaited(process.stderr.drain<void>());
    return process;
  }

  static Future<void> _stop(Process? process) async {
    if (process == null) return;
    process.kill();
    try {
      await process.exitCode.timeout(const Duration(seconds: 4));
    } catch (_) {
      process.kill(ProcessSignal.sigkill);
    }
  }

  /// Returns session cookies only in memory. The caller MUST validate the
  /// signed-in YouTube shell before adopting this as an authenticated account.
  static Future<String> captureYouTube(RequestScope scope) async {
    scope.check();
    final executable = await availableBrowser();
    if (executable == null) {
      throw StateError(
        'Chrome, Chromium, Edge or Brave is required for browser sign-in.',
      );
    }
    final profile = await Directory.systemTemp.createTemp(
      'bitchord-ytm-signin-',
    );
    Process? interactive, reader;
    final detach = scope.onCancel(() {
      interactive?.kill();
      reader?.kill();
    });
    try {
      scope.check();
      interactive = await _launch(executable, [
        '--user-data-dir=' + profile.path,
        '--disable-background-mode',
        '--no-first-run',
        '--no-default-browser-check',
        '--new-window',
        'https://music.youtube.com/',
      ]);
      // The listener signs in normally and closes this isolated window.
      await interactive.exitCode.timeout(_loginTimeout);
      scope.check();

      final portFile = File(
        profile.path + Platform.pathSeparator + 'DevToolsActivePort',
      );
      if (await portFile.exists()) await portFile.delete();
      reader = await _launch(executable, [
        '--user-data-dir=' + profile.path,
        '--headless=new',
        '--disable-background-mode',
        '--no-first-run',
        '--no-default-browser-check',
        '--remote-debugging-address=127.0.0.1',
        '--remote-debugging-port=0',
        'about:blank',
      ]);
      String? endpoint;
      for (var i = 0; i < 160; i++) {
        scope.check();
        if (await portFile.exists()) {
          endpoint = endpointFromPortFile(await portFile.readAsString());
          if (endpoint != null) break;
        }
        await Future<void>.delayed(_probeInterval);
      }
      if (endpoint == null) {
        throw StateError('Could not start the isolated sign-in reader.');
      }
      for (var attempt = 0; attempt < 12; attempt++) {
        scope.check();
        final socket = await WebSocket.connect(endpoint)
            .timeout(const Duration(seconds: 8));
        try {
          socket.add(jsonEncode({'id': 1, 'method': 'Storage.getCookies'}));
          final response = await socket
              .firstWhere((value) {
                if (value is! String) return false;
                try {
                  final decoded = jsonDecode(value);
                  return decoded is Map && decoded['id'] == 1;
                } catch (_) {
                  return false;
                }
              })
              .timeout(const Duration(seconds: 8));
          scope.check();
          final decoded = jsonDecode(response as String);
          try {
            return youtubeCookieHeader(decoded);
          } on ProviderFailure catch (error) {
            if (error.problem != ProviderProblem.unauthorized ||
                attempt == 11) {
              rethrow;
            }
          }
        } finally {
          await socket.close();
        }
        await Future<void>.delayed(const Duration(milliseconds: 500));
      }
      throw const ProviderFailure(ProviderProblem.unauthorized);
    } finally {
      detach();
      await _stop(interactive);
      await _stop(reader);
      // No persistent login profile or plaintext cookie fallback. Fail closed
      // when the temporary browser profile cannot be deleted.
      var removed = false;
      for (var attempt = 0; attempt < 8; attempt++) {
        try {
          if (await profile.exists()) await profile.delete(recursive: true);
          removed = true;
          break;
        } catch (_) {
          await Future<void>.delayed(const Duration(milliseconds: 250));
        }
      }
      if (!removed) {
        throw StateError(
          'Could not clean up the temporary sign-in profile. '
          'Close the sign-in browser before trying again.',
        );
      }
    }
  }
}
