import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import '../lib/core/platform/accounts/browser_session.dart';
import '../lib/features/online/domain/catalog.dart';

void main() {
  test('Browser endpoint accepts only the owned loopback endpoint shape', () {
    final endpoint = DesktopAccountBrowser.parseEndpoint(
      '12345\n/devtools/browser/1234-abcd\n',
    );
    expect(endpoint?.host, '127.0.0.1');
    expect(endpoint?.port, 12345);
    for (final invalid in [
      '0\n/devtools/browser/test',
      '65536\n/devtools/browser/test',
      '90\n//evil.invalid',
      '90\n/devtools/browser/test?token=x',
      '90\n/devtools/browser/test\nextra',
    ]) {
      expect(DesktopAccountBrowser.parseEndpoint(invalid), isNull);
    }
  });
  test(
    'Browser cookie capture rejects foreign domains and header injection',
    () {
      final header = DesktopAccountBrowser.youtubeHeader([
        {'domain': '.youtube.com', 'name': 'SAPISID', 'value': 'fixture'},
        {
          'domain': '.youtube.com.attacker.invalid',
          'name': 'SAPISID',
          'value': 'bad',
        },
        {'domain': '.google.com', 'name': 'OTHER', 'value': 'private'},
        {
          'domain': '.youtube.com',
          'name': 'INJECT',
          'value': 'value; next=bad',
        },
        {
          'domain': '.youtube.com',
          'name': 'EXPIRED',
          'value': 'old',
          'expires': 1,
        },
      ]);
      expect(header, 'SAPISID=fixture');
      expect(
        DesktopAccountBrowser.spotifySessionCookie([
          {
            'domain': '.spotify.com.attacker.invalid',
            'name': 'sp_dc',
            'value': 'bad',
          },
        ]),
        isNull,
      );
    },
  );
  test(
    'Only a current explicitly authenticated Spotify response is accepted',
    () {
      final token = <String, dynamic>{
        'accessToken': 'fixture',
        'accessTokenExpirationTimestampMs':
            DateTime.now().millisecondsSinceEpoch + 600000,
        'isAnonymous': false,
      };
      expect(
        DesktopAccountBrowser.authenticatedSpotifyToken(token)?['accessToken'],
        'fixture',
      );
      expect(
        DesktopAccountBrowser.authenticatedSpotifyToken({
          ...token,
          'isAnonymous': true,
        }),
        isNull,
      );
      expect(
        DesktopAccountBrowser.authenticatedSpotifyToken(
          {...token}..remove('isAnonymous'),
        ),
        isNull,
      );
      expect(
        DesktopAccountBrowser.authenticatedSpotifyToken({
          ...token,
          'accessToken': 'header\r\ninjection',
        }),
        isNull,
      );
      expect(
        DesktopAccountBrowser.authenticatedSpotifyToken({
          ...token,
          'accessTokenExpirationTimestampMs': 1,
        }),
        isNull,
      );
    },
  );
  test(
    'CDP matches response identity and cancellation releases pending commands',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final sockets = <WebSocket>[];
      server.listen((request) async {
        final socket = await WebSocketTransformer.upgrade(request);
        sockets.add(socket);
        socket.listen((dynamic raw) {
          final data = jsonDecode(raw as String) as Map;
          if (data['method'] == 'Fixture.pending' ||
              data['method'] == 'Browser.close')
            return;
          socket.add(
            jsonEncode({
              'id': data['id'],
              'result': {'method': data['method']},
            }),
          );
        });
      });
      final scope = RequestScope();
      final cdp = await CdpConnection.open(
        Uri.parse('ws://127.0.0.1:${server.port}/devtools/browser/fixture'),
        scope,
      );
      try {
        final responses = await Future.wait([
          cdp.command('Fixture.first', const {}),
          cdp.command('Fixture.second', const {}),
        ]);
        expect(responses.map((row) => row['method']), [
          'Fixture.first',
          'Fixture.second',
        ]);
        final pending = cdp.command('Fixture.pending', const {});
        final assertion = expectLater(pending, throwsA(isA<ProviderFailure>()));
        scope.cancel();
        await assertion;
      } finally {
        await cdp.closeBrowser();
        for (final socket in sockets) {
          await socket.close();
        }
        await server.close(force: true);
      }
    },
  );
}
