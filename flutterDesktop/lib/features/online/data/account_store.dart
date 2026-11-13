import 'dart:convert';
import 'dart:math';

import '../domain/account_port.dart';
import '../domain/catalog.dart';

// These records never reach presentation state. Only summary() is public UI
// data; serialized records go exclusively to AccountVault, never preferences.
final class SavedSession {
  SavedSession({
    required this.id,
    required this.service,
    required this.label,
    required Map<String, dynamic> credentials,
  }) : credentials = Map.unmodifiable(credentials);
  final String id, label;
  final AccountService service;
  final Map<String, dynamic> credentials;
  AccountSummary summary(bool active, {bool needsSignIn = false}) =>
      AccountSummary(
        id: id,
        service: service,
        label: label,
        active: active,
        needsSignIn: needsSignIn,
      );
  Map<String, Object?> toJson() => {
    'id': id,
    'service': service.name,
    'label': label,
    'credentials': credentials,
  };
  static SavedSession parse(Object? value) {
    if (value is! Map<String, dynamic>)
      throw const AccountFailure(AccountIssue.corruptStorage);
    final id = value['id'],
        name = value['label'],
        serviceName = value['service'],
        secret = value['credentials'];
    final service = AccountService.values
        .where((item) => item.name == serviceName)
        .firstOrNull;
    if (id is! String ||
        !RegExp(r'^[a-f0-9]{32}$').hasMatch(id) ||
        name is! String ||
        name.isEmpty ||
        name.length > 120 ||
        service == null ||
        secret is! Map<String, dynamic>) {
      throw const AccountFailure(AccountIssue.corruptStorage);
    }
    return SavedSession(
      id: id,
      service: service,
      label: name,
      credentials: secret,
    );
  }

  @override
  String toString() => 'SavedSession(redacted)';
}

final class AccountStore {
  AccountStore(this.vault);
  final AccountVault vault;
  List<SavedSession> _sessions = const [];
  Map<AccountService, String> _active = const {};
  Future<void> _tail = Future.value();
  bool _loaded = false;
  List<SavedSession> get sessions => List.unmodifiable(_sessions);
  SavedSession? active(AccountService service) => _sessions
      .where(
        (session) =>
            session.id == _active[service] && session.service == service,
      )
      .firstOrNull;

  Future<T> _ordered<T>(Future<T> Function() work) {
    final result = _tail.then((_) => work());
    _tail = result.then<void>((_) {}, onError: (Object _, StackTrace __) {});
    return result;
  }

  Future<void> load(RequestScope scope) => _ordered(() => _load(scope));
  Future<void> _load(RequestScope scope) async {
    scope.check();
    if (_loaded) return;
    final text = await vault.read(scope);
    scope.check();
    if (text == null) {
      _loaded = true;
      return;
    }
    try {
      final document = jsonDecode(text);
      if (document is! Map ||
          document['version'] != 1 ||
          document['sessions'] is! List ||
          document['active'] is! Map)
        throw const FormatException();
      final rows = (document['sessions'] as List)
          .map(SavedSession.parse)
          .toList();
      if (rows.length > 12 ||
          rows.map((row) => row.id).toSet().length != rows.length)
        throw const FormatException();
      final active = <AccountService, String>{};
      for (final service in AccountService.values) {
        final id = (document['active'] as Map)[service.name];
        if (id == null) continue;
        if (id is! String ||
            !rows.any((row) => row.id == id && row.service == service))
          throw const FormatException();
        active[service] = id;
      }
      _sessions = List.unmodifiable(rows);
      _active = Map.unmodifiable(active);
      _loaded = true;
    } catch (_) {
      throw const AccountFailure(AccountIssue.corruptStorage);
    }
  }

  Future<void> _commit(
    List<SavedSession> rows,
    Map<AccountService, String> active,
    RequestScope scope,
  ) async {
    scope.check();
    final encoded = jsonEncode({
      'version': 1,
      'sessions': rows.map((row) => row.toJson()).toList(),
      'active': {
        for (final entry in active.entries) entry.key.name: entry.value,
      },
    });
    if (utf8.encode(encoded).length > 256 * 1024)
      throw const AccountFailure(AccountIssue.corruptStorage);
    // Commit the secure transaction before changing the visible saved list.
    // A locked keyring never destroys a previously saved account document.
    try {
      if (rows.isEmpty) {
        await vault.delete(scope);
      } else {
        await vault.write(encoded, scope);
      }
    } catch (_) {
      // An interrupted OS operation may have committed: reload before writing.
      _loaded = false;
      rethrow;
    }
    _sessions = List.unmodifiable(rows);
    _active = Map.unmodifiable(active);
    scope.check();
  }

  Future<SavedSession> add(
    AccountService service,
    String label,
    Map<String, dynamic> credentials,
    RequestScope scope,
  ) => _ordered(() async {
    await _load(scope);
    if (_sessions.length >= 12)
      throw const AccountFailure(AccountIssue.operationFailed);
    final clean = label.trim();
    if (clean.isEmpty ||
        clean.length > 120 ||
        RegExp(r'[\x00-\x1f]').hasMatch(clean))
      throw const AccountFailure(AccountIssue.operationFailed);
    final random = Random.secure();
    final id = List.generate(
      16,
      (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
    ).join();
    final session = SavedSession(
      id: id,
      service: service,
      label: clean,
      credentials: credentials,
    );
    await _commit([..._sessions, session], {..._active, service: id}, scope);
    return session;
  });

  Future<SavedSession> activate(String id, RequestScope scope) =>
      _ordered(() async {
        await _load(scope);
        final session = _sessions.where((row) => row.id == id).firstOrNull;
        if (session == null)
          throw const AccountFailure(AccountIssue.operationFailed);
        await _commit(_sessions, {..._active, session.service: id}, scope);
        return session;
      });

  Future<void> replace(SavedSession replacement, RequestScope scope) =>
      _ordered(() async {
        await _load(scope);
        if (!_sessions.any(
          (row) =>
              row.id == replacement.id && row.service == replacement.service,
        ))
          throw const AccountFailure(AccountIssue.operationFailed);
        await _commit(
          [
            for (final row in _sessions)
              if (row.id == replacement.id) replacement else row,
          ],
          _active,
          scope,
        );
      });

  Future<void> remove(String id, RequestScope scope) => _ordered(() async {
    await _load(scope);
    final active = Map<AccountService, String>.of(_active)
      ..removeWhere((_, value) => value == id);
    await _commit(
      _sessions.where((row) => row.id != id).toList(),
      active,
      scope,
    );
  });
}
