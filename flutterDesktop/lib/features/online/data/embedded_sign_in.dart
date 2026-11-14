import 'dart:async';
import 'dart:io';

import 'package:desktop_webview_window/desktop_webview_window.dart';

import '../domain/catalog.dart';
import 'browser_sign_in.dart';

/// Owns a BitChord-created native WebView2 (Windows) / WebKitGTK (Linux)
/// window. Unlike the external-browser fallback, this never starts an
/// installed Chrome/Edge/Chromium executable or reads its profile.
///
/// IMPORTANT: Google can reject embedded sign-in under its security policy.
/// That is a real provider refusal, not an excuse to spoof a browser identity.
final class EmbeddedSignIn {
  EmbeddedSignIn._();

  static const signInUrl = 'https://music.youtube.com/';
  static const _poll = Duration(milliseconds: 450);
  static const _timeout = Duration(minutes: 15);

  /// Accept only YouTube cookies from the WebView's own jar, including HttpOnly
  /// signing cookies. No document JS access, page scripts, Google/Spotify
  /// cookies, browser-profile imports or secret logs are involved.
  static String? youtubeHeader(List<WebviewCookie> cookies) {
    try {
      return BrowserSignIn.youtubeCookieHeader({
        'result': {
          'cookies': [
            for (final cookie in cookies)
              {
                'domain': cookie.domain,
                'name': cookie.name,
                'value': cookie.value,
              },
          ],
        },
      });
    } on ProviderFailure catch (failure) {
      if (failure.problem == ProviderProblem.unauthorized) return null;
      rethrow;
    }
  }

  /// The sign-in window is part of BitChord, not the user's installed browser.
  /// Closing the window is an explicit "Use this profile" action: we keep the
  /// most recent valid YouTube cookies while it is open, then verify that
  /// captured session via OnlineAccounts.signIn on the application boundary.
  static Future<String> captureYouTube(RequestScope scope) async {
    scope.check();
    if (!Platform.isWindows && !Platform.isLinux) {
      throw StateError('Built-in sign-in is available on Windows and Linux.');
    }
    if (Platform.isWindows && !await WebviewWindow.isWebviewAvailable()) {
      throw StateError(
        'Windows WebView2 Runtime is unavailable. Install the Microsoft '
        'WebView2 Runtime or use the browser fallback.',
      );
    }
    final profile = await Directory.systemTemp.createTemp(
      'bitchord-embedded-signin-',
    );
    Webview? view;
    final detach = scope.onCancel(() {
      final window = view;
      if (window != null) {
        unawaited(window.close().catchError((Object _) {}));
      }
    });
    try {
      scope.check();
      try {
        view = await WebviewWindow.create(
          configuration: CreateConfiguration(
            title: 'BitChord — Sign in to YouTube Music',
            windowWidth: 570,
            windowHeight: 770,
            windows: WindowsWebviewOptions(
              userDataFolder: profile.path,
              denyCameraAndMicrophone: true,
            ),
          ),
        );
      } catch (_) {
        throw StateError(
          'BitChord could not open its built-in sign-in window. '
          'On Linux, WebKitGTK 4.1 is required; on Windows, WebView2 '
          'Runtime is required. The browser fallback remains available.',
        );
      }
      scope.check();
      final window = view!;
      await window.launch(signInUrl);
      var closed = false;
      unawaited(window.onClose.then((_) {
        closed = true;
      }));
      final end = DateTime.now().add(_timeout);
      String? candidate;
      while (!closed && DateTime.now().isBefore(end)) {
        scope.check();
        try {
          final cookies = await window
              .getAllCookies()
              .timeout(const Duration(seconds: 4));
          scope.check();
          candidate = youtubeHeader(cookies) ?? candidate;
        } on ProviderFailure {
          rethrow; // Malformed or oversized session must not be accepted.
        } catch (_) {
          // The native window may close during a cookie read. A transient
          // WebView error is not an authenticated session or a fatal crash.
        }
        if (closed) break;
        await Future.any<void>([
          window.onClose,
          Future<void>.delayed(_poll),
        ]);
      }
      scope.check();
      if (!closed) {
        throw StateError('The built-in sign-in window timed out.');
      }
      if (candidate == null) {
        throw StateError(
          'No signed-in YouTube Music profile was found in the BitChord '
          'window. Finish login and profile selection before closing it. '
          'Google may block embedded sign-in; use the browser fallback '
          'if Google shows disallowed_useragent.',
        );
      }
      return candidate;
    } finally {
      detach();
      final window = view;
      if (window != null) {
        try {
          await window.close();
          await window.onClose.timeout(const Duration(seconds: 5));
        } catch (_) {
          // Native shutdown may already have completed when user closed it.
        }
      }
      // Temporary cookies and HTML storage must not linger on disk.
      var removed = false;
      for (var attempt = 0; attempt < 12; attempt++) {
        try {
          if (await profile.exists()) await profile.delete(recursive: true);
          removed = true;
          break;
        } catch (_) {
          await Future<void>.delayed(const Duration(milliseconds: 350));
        }
      }
      if (!removed) {
        throw StateError(
          'BitChord could not delete its temporary sign-in profile. '
          'Close the sign-in window and remove the temporary '
          'bitchord-embedded-signin- folder before trying again.',
        );
      }
    }
  }
}
