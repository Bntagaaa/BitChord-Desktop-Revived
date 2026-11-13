import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/features/online/data/browser_sign_in.dart';
import 'package:bitchord_desktop/features/online/domain/catalog.dart';

void main() {
  group('Kotlin-compatible isolated browser sign-in boundaries', () {
    test('accepts a valid temporary localhost DevTools port only', () {
      expect(
        BrowserSignIn.endpointFromPortFile(
          '40017\n/devtools/browser/12345678-1234-1234-1234-123456789abc\n',
        ),
        'ws://127.0.0.1:40017/devtools/browser/12345678-1234-1234-1234-123456789abc',
      );
      expect(
        BrowserSignIn.endpointFromPortFile(
          '99999\n/devtools/browser/12345678-1234\n',
        ),
        isNull,
      );
      expect(
        BrowserSignIn.endpointFromPortFile(
          '80\n//evil.example/devtools/browser/12345678\n',
        ),
        isNull,
      );
      expect(
        BrowserSignIn.endpointFromPortFile(
          '81\n/devtools/page/123456789\n',
        ),
        isNull,
      );
      expect(
        BrowserSignIn.endpointFromPortFile('not-a-port\n/devtools/browser/x'),
        isNull,
      );
    });

    test('includes signed YouTube cookies and excludes unrelated hosts', () {
      final response = {
        'result': {
          'cookies': [
            {
              'domain': '.youtube.com',
              'name': 'SAPISID',
              'value': 'own-signing-cookie',
            },
            {
              'domain': 'music.youtube.com',
              'name': 'LOGIN_INFO',
              'value': 'signed-in',
            },
            {
              'domain': 'notyoutube.com',
              'name': 'PASSWORD',
              'value': 'must-not-leak',
            },
            {
              'domain': 'google.com',
              'name': 'SID',
              'value': 'unrelated',
            },
            {
              'domain': 'evil.youtube.com.attacker.test',
              'name': 'LEAK',
              'value': 'bad',
            },
          ],
        },
      };
      final header = BrowserSignIn.youtubeCookieHeader(response);
      expect(header, contains('SAPISID=own-signing-cookie'));
      expect(header, contains('LOGIN_INFO=signed-in'));
      expect(header, isNot(contains('must-not-leak')));
      expect(header, isNot(contains('unrelated')));
      expect(header, isNot(contains('LEAK')));
    });

    test('never accepts cookies without a YouTube signing secret', () {
      expect(
        () => BrowserSignIn.youtubeCookieHeader({
          'result': {
            'cookies': [
              {
                'domain': '.youtube.com',
                'name': 'PREF',
                'value': 'test',
              },
            ],
          },
        }),
        throwsA(
          isA<ProviderFailure>().having(
            (value) => value.problem,
            'problem',
            ProviderProblem.unauthorized,
          ),
        ),
      );
    });

    test('rejects delimiter injection and overlong cookie values', () {
      expect(
        () => BrowserSignIn.youtubeCookieHeader({
          'result': {
            'cookies': [
              {
                'domain': 'youtube.com',
                'name': 'SAPISID',
                'value': 'a; Cookie: injected',
              },
            ],
          },
        }),
        throwsA(isA<ProviderFailure>()),
      );
      expect(
        () => BrowserSignIn.youtubeCookieHeader({
          'result': {
            'cookies': [
              {
                'domain': '.youtube.com',
                'name': 'SAPISID',
                'value': 'x' * 33000,
              },
            ],
          },
        }),
        throwsA(isA<ProviderFailure>()),
      );
      expect(
        () => BrowserSignIn.youtubeCookieHeader({
          'result': null,
        }),
        throwsA(isA<ProviderFailure>()),
      );
    });
  });
}
