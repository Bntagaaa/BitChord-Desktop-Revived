import 'dart:async';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:bitchord_desktop/data/auth_store.dart';
import 'package:bitchord_desktop/data/innertube_client.dart';
import 'package:bitchord_desktop/domain/models.dart';

class FakeVault extends FlutterSecureStorage {
  String? cookie;
  Completer<String?>? readGate;
  Completer<void>? writeGate;
  Completer<void>? deleteGate;
  final writeStarted = Completer<void>();
  final deleteStarted = Completer<void>();
  final events = <String>[];
  bool failNextWrite = false;

  @override
  Future<String?> read({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async => readGate == null ? cookie : await readGate!.future;

  @override
  Future<void> write({
    required String key,
    required String? value,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    events.add('write:start:$value');
    if (!writeStarted.isCompleted) writeStarted.complete();
    await writeGate?.future;
    if (failNextWrite) {
      failNextWrite = false;
      throw StateError('Vault unavailable');
    }
    cookie = value;
    events.add('write:complete:$value');
  }

  @override
  Future<void> delete({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    events.add('delete:start');
    if (!deleteStarted.isCompleted) deleteStarted.complete();
    await deleteGate?.future;
    cookie = null;
    events.add('delete:complete');
  }
}

class AccountClient extends InnertubeClient {
  AccountClient(this.loadAccount)
    : super(
        httpClient: MockClient(
          (_) async => throw StateError(
            'The authentication unit test must not make HTTP requests',
          ),
        ),
      );
  final Future<Map<String, dynamic>> Function(String? cookie) loadAccount;
  @override
  Future<Map<String, dynamic>> post(
    String endpoint,
    Map<String, dynamic> payload, {
    bool authenticated = true,
  }) => loadAccount(cookie);
}

Map<String, dynamic> account(String name) => {
  'activeAccountHeaderRenderer': {
    'accountName': {'simpleText': name},
  },
};

void main() {
  test(
    'a late restoration cannot overwrite a completed manual sign-in',
    () async {
      final vault = FakeVault()..readGate = Completer<String?>();
      final auth = AuthStore(storage: vault);
      final client = AccountClient((_) async => account('New account'));
      final restoration = auth.restore(client);
      await auth.signIn('SAPISID=new', client);
      vault.readGate!.complete('SAPISID=old');
      await restoration;
      expect(client.cookie, 'SAPISID=new');
      expect(vault.cookie, 'SAPISID=new');
      client.close();
    },
  );

  test(
    'sign-out immediately clears memory and fences a pending restoration',
    () async {
      final vault = FakeVault()
        ..readGate = Completer<String?>()
        ..deleteGate = Completer<void>();
      final auth = AuthStore(storage: vault);
      final client = AccountClient((_) async => account('Old account'))
        ..setCookie('SAPISID=old');
      final restoration = auth.restore(client);
      final logout = auth.signOut(client);
      expect(client.cookie, isNull);
      vault.readGate!.complete('SAPISID=old');
      await restoration;
      expect(client.cookie, isNull);
      vault.deleteGate!.complete();
      await logout;
      expect(vault.cookie, isNull);
      client.close();
    },
  );

  test(
    'failed superseded validation cannot roll back a later logout',
    () async {
      final validation = Completer<Map<String, dynamic>>();
      final vault = FakeVault()..cookie = 'SAPISID=old';
      final auth = AuthStore(storage: vault);
      final client = AccountClient((_) => validation.future)
        ..setCookie('SAPISID=old');
      final failure = expectLater(
        auth.signIn('SAPISID=new', client),
        throwsA(isA<ServiceException>()),
      );
      await auth.signOut(client);
      validation.completeError(const ServiceException('Rejected'));
      await failure;
      expect(client.cookie, isNull);
      expect(vault.cookie, isNull);
      client.close();
    },
  );

  test(
    'an already-started sign-in vault write completes before logout deletion',
    () async {
      final vault = FakeVault()..writeGate = Completer<void>();
      final auth = AuthStore(storage: vault);
      final client = AccountClient((_) async => account('New account'));
      final failure = expectLater(
        auth.signIn('SAPISID=new', client),
        throwsA(isA<ServiceException>()),
      );
      await vault.writeStarted.future;
      final logout = auth.signOut(client);
      expect(client.cookie, isNull);
      expect(vault.deleteStarted.isCompleted, isFalse);
      vault.writeGate!.complete();
      await Future.wait([failure, logout]);
      expect(vault.cookie, isNull);
      expect(vault.events, [
        'write:start:SAPISID=new',
        'write:complete:SAPISID=new',
        'delete:start',
        'delete:complete',
      ]);
      client.close();
    },
  );

  test(
    'an older failed sign-in cannot replace a newer successful account',
    () async {
      final oldValidation = Completer<Map<String, dynamic>>();
      final vault = FakeVault();
      final auth = AuthStore(storage: vault);
      final client = AccountClient(
        (cookie) => cookie == 'SAPISID=first'
            ? oldValidation.future
            : Future.value(account('Second')),
      );
      final first = expectLater(
        auth.signIn('SAPISID=first', client),
        throwsA(isA<ServiceException>()),
      );
      expect((await auth.signIn('SAPISID=second', client)).name, 'Second');
      oldValidation.completeError(const ServiceException('Old attempt failed'));
      await first;
      expect(client.cookie, 'SAPISID=second');
      expect(vault.cookie, 'SAPISID=second');
      client.close();
    },
  );

  test('current failed sign-in restores the committed account, not an unverified attempt', () async {
    final oldValidation = Completer<Map<String, dynamic>>();
    final vault = FakeVault()..cookie = 'SAPISID=committed';
    final auth = AuthStore(storage: vault);
    final client = AccountClient(
      (cookie) => cookie == 'SAPISID=first'
          ? oldValidation.future
          : Future.error(const ServiceException('Second rejected')),
    )..setCookie('SAPISID=committed');
    final first = expectLater(
      auth.signIn('SAPISID=first', client),
      throwsA(isA<ServiceException>()),
    );
    await expectLater(
      auth.signIn('SAPISID=second', client),
      throwsA(isA<ServiceException>()),
    );
    oldValidation.complete(account('First'));
    await first;
    expect(client.cookie, 'SAPISID=committed');
    expect(vault.cookie, 'SAPISID=committed');
    client.close();
  });

  test('a failed vault write does not poison a subsequent logout', () async {
    final vault = FakeVault()
      ..cookie = 'SAPISID=committed'
      ..failNextWrite = true;
    final auth = AuthStore(storage: vault);
    final client = AccountClient((_) async => account('New'))
      ..setCookie('SAPISID=committed');
    await expectLater(auth.signIn('SAPISID=new', client), throwsStateError);
    expect(client.cookie, 'SAPISID=committed');
    expect(vault.cookie, 'SAPISID=committed');
    await auth.signOut(client);
    expect(vault.cookie, isNull);
    client.close();
  });
}
