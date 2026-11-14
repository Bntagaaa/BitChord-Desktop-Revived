import 'package:desktop_webview_window/desktop_webview_window.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../lib/features/online/application/account_sign_in.dart';
import '../lib/features/online/application/catalog_controller.dart';
import '../lib/features/online/data/embedded_sign_in.dart';
import '../lib/features/online/domain/catalog.dart';
import '../lib/features/online/domain/online_port.dart';
import '../lib/features/online/presentation/account_session.dart';
import 'online_controller_test.dart' show ControlledCatalog;

WebviewCookie cookie(String domain, String name, String value) =>
    WebviewCookie(
      name: name,
      value: value,
      domain: domain,
      path: '/',
      expires: null,
      secure: true,
      httpOnly: true,
      sessionOnly: true,
    );

class AccountCatalog extends ControlledCatalog implements OnlineAccounts {
  @override
  bool signedIn = false;
  String? lastCookie;
  @override
  Future<void> signIn(String cookie, RequestScope scope) async {
    scope.check();
    lastCookie = cookie;
    signedIn = true;
  }

  @override
  Future<void> signOut() async {
    signedIn = false;
  }
}

void main() {
  test('embedded cookie jar filters unrelated Google and third-party hosts',
      () {
    final header = EmbeddedSignIn.youtubeHeader([
      cookie('.youtube.com', 'SAPISID', 'session-key'),
      cookie('music.youtube.com', 'LOGIN_INFO', 'selected-channel'),
      cookie('accounts.google.com', 'SID', 'do-not-export'),
      cookie('youtube.com.attacker.test', 'LEAK', 'do-not-export'),
      cookie('spotify.com', 'sp_dc', 'do-not-export'),
    ]);
    expect(header, contains('SAPISID=session-key'));
    expect(header, contains('LOGIN_INFO=selected-channel'));
    expect(header, isNot(contains('do-not-export')));
    expect(header, isNot(contains('LEAK')));
    expect(header, isNot(contains('sp_dc')));
  });

  test('embedded cookie jar does not fabricate an authenticated session', () {
    expect(
      EmbeddedSignIn.youtubeHeader([
        cookie('youtube.com', 'PREF', 'just-preferences'),
      ]),
      isNull,
    );
    expect(EmbeddedSignIn.youtubeHeader([]), isNull);
    expect(
      () => EmbeddedSignIn.youtubeHeader([
        cookie('youtube.com', 'SAPISID', 'semicolon;injection'),
      ]),
      throwsA(isA<ProviderFailure>()),
    );
  });

  testWidgets('account panel chooses the in-app sign-in port by default',
      (tester) async {
    final port = AccountCatalog();
    var embeddedCalls = 0;
    var externalCalls = 0;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          onlinePortProvider.overrideWithValue(port),
          embeddedSignInCaptureProvider.overrideWithValue((scope) async {
            embeddedCalls++;
            scope.check();
            return 'SAPISID=signed-in-test';
          }),
          browserSignInCaptureProvider.overrideWithValue((scope) async {
            externalCalls++;
            return 'SAPISID=external-test';
          }),
        ],
        child: const MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(child: AccountSession()),
          ),
        ),
      ),
    );
    expect(find.byKey(const Key('in-app-sign-in')), findsOneWidget);
    expect(find.byKey(const Key('browser-sign-in')), findsOneWidget);
    await tester.tap(find.byKey(const Key('in-app-sign-in')));
    await tester.pumpAndSettle();
    expect(embeddedCalls, 1);
    expect(externalCalls, 0);
    expect(port.lastCookie, 'SAPISID=signed-in-test');
    expect(port.signedIn, true);
    expect(
      find.text('YouTube Music session verified'),
      findsOneWidget,
    );
  });

  testWidgets('explicit browser fallback never silently runs in-app sign-in',
      (tester) async {
    final port = AccountCatalog();
    var externalCalls = 0;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          onlinePortProvider.overrideWithValue(port),
          embeddedSignInCaptureProvider.overrideWithValue((scope) async {
            throw StateError('not selected');
          }),
          browserSignInCaptureProvider.overrideWithValue((scope) async {
            externalCalls++;
            return 'SAPISID=fallback-session';
          }),
        ],
        child: const MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(child: AccountSession()),
          ),
        ),
      ),
    );
    await tester.tap(find.byKey(const Key('browser-sign-in')));
    await tester.pumpAndSettle();
    expect(externalCalls, 1);
    expect(port.lastCookie, 'SAPISID=fallback-session');
  });
}
