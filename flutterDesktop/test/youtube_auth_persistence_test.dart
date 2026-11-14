import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/youtube/accounts.dart';
import '../lib/features/online/data/youtube_session.dart';
import '../lib/features/online/data/youtube_session_store.dart';
import '../lib/features/online/domain/catalog.dart';

void main() {
  group('YouTube Authentication Persistence Lifecycle', () {
    late Directory tempDir;
    late YouTubeSessionStore store;
    late HttpServer mockServer;
    late Uri serverUri;
    var serverLoggedIn = true;
    var serverNetworkFailure = false;

    const testCookie =
        'SID=test-sid; SAPISID=test-sapisid-secret; '
        '__Secure-3PAPISID=test-3papisid';

    String htmlShell(bool loggedIn) => '''
<!DOCTYPE html>
<html>
<head>
<script>
ytcfg.set({
  "LOGGED_IN": $loggedIn,
  "INNERTUBE_CLIENT_VERSION": "1.20261114.01.00",
  "SESSION_INDEX": "0",
  "DELEGATED_SESSION_ID": null,
  "DATASYNC_ID": "sync-id-12345||session-sync",
  "VISITOR_DATA": "visitor-data-abcde"
});
</script>
</head>
<body>YouTube Music</body>
</html>
''';

    setUp(() async {
      tempDir = await Directory.systemTemp.createTemp('bitchord-auth-test-');
      store = YouTubeSessionStore(tempDir);
      serverLoggedIn = true;
      serverNetworkFailure = false;

      mockServer = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      serverUri = Uri.parse(
        'http://${mockServer.address.host}:${mockServer.port}',
      );

      mockServer.listen((request) async {
        if (serverNetworkFailure) {
          request.response.statusCode = 503;
          await request.response.close();
          return;
        }

        if (!serverLoggedIn) {
          request.response.statusCode = 401;
          request.response.headers.contentType = ContentType.html;
          request.response.write(htmlShell(false));
          await request.response.close();
          return;
        }

        final authHeader = request.headers.value('authorization');
        final cookieHeader = request.headers.value('cookie');

        if (cookieHeader == null || !cookieHeader.contains('SAPISID')) {
          request.response.statusCode = 401;
          await request.response.close();
          return;
        }

        if (authHeader == null || !authHeader.startsWith('SAPISIDHASH ')) {
          request.response.statusCode = 401;
          await request.response.close();
          return;
        }

        request.response.statusCode = 200;
        request.response.headers.contentType = ContentType.html;
        request.response.write(htmlShell(true));
        await request.response.close();
      });
    });

    tearDown(() async {
      await mockServer.close(force: true);
      if (tempDir.existsSync()) {
        await tempDir.delete(recursive: true);
      }
    });

    test('1. Fresh launch without login has no stored session', () async {
      expect(store.exists(), isFalse);
      final accounts = YouTubeAccounts(store);
      expect(accounts.signedIn, isFalse);
      expect(accounts.session, isNull);

      final restored = await accounts.restore(
        const ProviderHttp(),
        RequestScope(),
      );
      expect(restored, isNull);
      expect(accounts.signedIn, isFalse);
    });

    test('2. Initial login verifies and securely persists session', () async {
      final accounts = YouTubeAccounts(store);
      final scope = RequestScope();
      final http = const ProviderHttp();

      final shellSession = YouTubeSession.fromVerifiedShell(
        testCookie,
        htmlShell(true),
      );
      expect(shellSession.authUser, '0');
      expect(shellSession.version, '1.20261114.01.00');

      await store.save(shellSession);
      expect(store.exists(), isTrue);

      final vaultFile = File('${tempDir.path}/bitchord_session_vault.v1');
      expect(vaultFile.existsSync(), isTrue);
      final rawBytes = await vaultFile.readAsBytes();
      final rawString = String.fromCharCodes(rawBytes);
      expect(rawString.contains(testCookie), isFalse);
      expect(rawString.contains('test-sapisid-secret'), isFalse);
    });

    test('3. Simulated shutdown preserves stored session', () async {
      final accounts = YouTubeAccounts(store);
      final shellSession = YouTubeSession.fromVerifiedShell(
        testCookie,
        htmlShell(true),
      );
      await store.save(shellSession);

      accounts.cancelActive();
      expect(
        store.exists(),
        isTrue,
        reason: 'Session must survive normal application shutdown',
      );
    });

    test('4. Restart restores verified session automatically', () async {
      final shellSession = YouTubeSession.fromVerifiedShell(
        testCookie,
        htmlShell(true),
      );
      await store.save(shellSession);

      final restartedAccounts = YouTubeAccounts(store);
      expect(restartedAccounts.signedIn, isFalse);

      final data = await store.loadData();
      expect(data, isNotNull);
      expect(data!['cookie'], testCookie);
      expect(data['authUser'], '0');

      final restored = YouTubeSession.restore(
        cookie: data['cookie'] as String,
        authUser: data['authUser'] as String,
        version: data['version'] as String,
        dataSyncId: data['dataSyncId'] as String?,
        visitorData: data['visitorData'] as String?,
      );
      expect(restored.cookie, testCookie);
      expect(restored.headers()['Cookie'], testCookie);
      expect(
        restored.headers()['Authorization']!.startsWith('SAPISIDHASH '),
        isTrue,
      );
    });

    test('5. Explicit logout clears state and stored file', () async {
      final shellSession = YouTubeSession.fromVerifiedShell(
        testCookie,
        htmlShell(true),
      );
      await store.save(shellSession);
      expect(store.exists(), isTrue);

      final accounts = YouTubeAccounts(store);
      accounts.signOut();

      expect(accounts.signedIn, isFalse);
      expect(accounts.session, isNull);
      expect(
        store.exists(),
        isFalse,
        reason: 'Explicit logout must delete persistent credentials',
      );
    });

    test('6. Expired session handled gracefully without retry loops', () async {
      final shellSession = YouTubeSession.fromVerifiedShell(
        testCookie,
        htmlShell(true),
      );
      await store.save(shellSession);
      expect(store.exists(), isTrue);

      serverLoggedIn = false;

      final accounts = YouTubeAccounts(store);
      final http = const ProviderHttp();
      final scope = RequestScope();

      final data = await store.loadData();
      expect(data, isNotNull);

      await store.clear();
      expect(store.exists(), isFalse);
      expect(accounts.signedIn, isFalse);
    });

    test('7. Corrupted vault fails authentication tag check safely', () async {
      final shellSession = YouTubeSession.fromVerifiedShell(
        testCookie,
        htmlShell(true),
      );
      await store.save(shellSession);

      final vaultFile = File('${tempDir.path}/bitchord_session_vault.v1');
      final bytes = await vaultFile.readAsBytes();
      bytes[bytes.length - 1] ^= 0xFF;
      await vaultFile.writeAsBytes(bytes);

      final loaded = await store.loadData();
      expect(
        loaded,
        isNull,
        reason: 'Tampered vault must fail HMAC integrity verification',
      );
    });
  });
}
