import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import '../lib/features/online/data/account_store.dart';
import '../lib/features/online/domain/account_port.dart';
import '../lib/features/online/domain/catalog.dart';

final class MemoryVault implements AccountVault {
  String? value;
  bool locked = false;
  int writes = 0, activeWrites = 0, maxWrites = 0;
  @override
  Future<String?> read(RequestScope scope) async { scope.check(); if (locked) throw const AccountFailure(AccountIssue.storageUnavailable); return value; }
  @override
  Future<void> write(String secret, RequestScope scope) async {
    scope.check(); if (locked) throw const AccountFailure(AccountIssue.storageUnavailable);
    activeWrites++; if (activeWrites > maxWrites) maxWrites = activeWrites;
    await Future<void>.delayed(Duration.zero);
    scope.check(); value = secret; writes++; activeWrites--;
  }
  @override
  Future<void> delete(RequestScope scope) async { scope.check(); if (locked) throw const AccountFailure(AccountIssue.storageUnavailable); value = null; }
}

void main() {
  test('Secure store restores independent saved providers and active accounts', () async {
    final vault = MemoryVault();
    final current = AccountStore(vault);
    final youtube = await current.add(AccountService.youtube, 'Personal', {'cookie': 'test-only'}, RequestScope());
    final spotify = await current.add(AccountService.spotify, 'Music', {'sp_dc': 'test-only'}, RequestScope());
    final reopened = AccountStore(vault);
    await reopened.load(RequestScope());
    expect(reopened.sessions.length, 2);
    expect(reopened.active(AccountService.youtube)?.id, youtube.id);
    expect(reopened.active(AccountService.spotify)?.id, spotify.id);
    expect(youtube.toString(), 'SavedSession(redacted)');
  });
  test('Failed secure write does not destroy a previously saved account', () async {
    final vault = MemoryVault(), scope = RequestScope();
    final store = AccountStore(vault);
    final first = await store.add(AccountService.youtube, 'First', {'cookie': 'fixture'}, scope);
    final before = vault.value;
    vault.locked = true;
    await expectLater(store.add(AccountService.youtube, 'Second', {'cookie': 'other'}, scope), throwsA(isA<AccountFailure>()));
    expect(vault.value, before);
    expect(store.active(AccountService.youtube)?.id, first.id);
    vault.locked = false;
    await store.load(scope);
    expect(store.sessions.length, 1);
  });
  test('Concurrent saved-account changes are serialized', () async {
    final vault = MemoryVault();
    final current = AccountStore(vault);
    await Future.wait([for (var i = 0; i < 4; i++) current.add(AccountService.youtube, 'Profile $i', {'cookie': '$i'}, RequestScope())]);
    expect(current.sessions.length, 4);
    expect(vault.maxWrites, 1);
  });
  test('Removing an inactive account does not switch another provider', () async {
    final vault = MemoryVault(), scope = RequestScope();
    final store = AccountStore(vault);
    final first = await store.add(AccountService.youtube, 'First', {'cookie': 'fixture'}, scope);
    final second = await store.add(AccountService.youtube, 'Second', {'cookie': 'other'}, scope);
    await store.remove(first.id, scope);
    expect(store.active(AccountService.youtube)?.id, second.id);
    await store.remove(second.id, scope);
    expect(vault.value, isNull);
    expect(store.sessions, isEmpty);
  });
  test('Corrupt saved identities fail without overwriting the vault', () async {
    final vault = MemoryVault()..value = jsonEncode({'version': 1, 'sessions': [{'id': '../bad', 'service': 'youtube', 'label': 'bad', 'credentials': {}}], 'active': {}});
    final before = vault.value;
    await expectLater(AccountStore(vault).load(RequestScope()), throwsA(isA<AccountFailure>()));
    expect(vault.value, before);
    expect(vault.writes, 0);
  });
  test('Cancelled requests do not save an account', () async {
    final vault = MemoryVault(), scope = RequestScope()..cancel();
    await expectLater(AccountStore(vault).add(AccountService.youtube, 'Cancelled', {'cookie': 'fixture'}, scope), throwsA(isA<ProviderFailure>()));
    expect(vault.writes, 0);
  });
}
