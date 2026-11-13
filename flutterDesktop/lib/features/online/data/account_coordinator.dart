import '../../../core/platform/accounts/browser_session.dart';
import '../domain/account_port.dart';
import '../domain/catalog.dart';
import 'account_store.dart';
import 'provider_http.dart';
import 'youtube_session.dart';

final class AccountCoordinator implements OnlineAccountManager {
  AccountCoordinator({required this.store, required this.browser, required this.http, required this.adoptYoutube, required this.clearYoutube});
  final AccountStore store;
  final AccountBrowser browser;
  final ProviderHttp http;
  final void Function(YouTubeSession) adoptYoutube;
  final void Function() clearYoutube;
  final _needsSignIn = <String>{};
  Future<void> _tail = Future.value();
  bool _restored = false, _closed = false;
  int _generation = 0;
  int get generation => _generation;
  final _requests = <RequestScope>{};

  Future<T> _ordered<T>(RequestScope parent, Future<T> Function(RequestScope) work) {
    final result = _tail.then((_) async {
      parent.check();
      if (_closed) throw const ProviderFailure(ProviderProblem.cancelled);
      final scope = RequestScope(), detach = parent.onCancel(() {});
      detach();
      final unsubscribe = parent.onCancel(scope.cancel);
      _requests.add(scope);
      try { final value = await work(scope); scope.check(); return value; }
      finally { unsubscribe(); _requests.remove(scope); scope.cancel(); }
    });
    _tail = result.then<void>((_) {}, onError: (Object _, StackTrace __) {});
    return result;
  }

  Future<YouTubeSession> _youtube(SavedSession record, RequestScope scope) async {
    final cookie = record.credentials['cookie'];
    if (cookie is! String || cookie.isEmpty) throw const AccountFailure(AccountIssue.corruptStorage);
    return YouTubeSession.verify(cookie, http, scope);
  }

  Future<void> _restore(RequestScope scope) async {
    await store.load(scope);
    if (_restored) return;
    final youtube = store.active(AccountService.youtube);
    if (youtube != null) {
      try {
        final verified = await _youtube(youtube, scope);
        scope.check();
        adoptYoutube(verified);
      } on ProviderFailure catch (error) {
        scope.check();
        if (error.problem == ProviderProblem.cancelled) rethrow;
        _needsSignIn.add(youtube.id);
      } on AccountFailure { _needsSignIn.add(youtube.id); }
    }
    scope.check();
    _restored = true;
  }

  Future<void> restore(RequestScope scope) => _ordered(scope, _restore);

  @override
  Future<List<AccountSummary>> accounts(RequestScope scope) => _ordered(scope, (child) async {
    await _restore(child);
    return [for (final session in store.sessions) session.summary(store.active(session.service)?.id == session.id, needsSignIn: _needsSignIn.contains(session.id))];
  });

  @override
  Future<void> connectAccount(AccountService service, String label, RequestScope scope, void Function(String) progress) => _ordered(scope, (child) async {
    await _restore(child);
    // Probe protected storage before opening the browser. Do not complete a
    // login only to silently fall back to unprotected cookies on disk.
    final captured = await browser.connect(service, child, progress);
    child.check();
    if (captured.service != service) throw const AccountFailure(AccountIssue.operationFailed);
    YouTubeSession? youtube;
    if (service == AccountService.youtube) {
      final cookie = captured.values['cookie'];
      if (cookie is! String) throw const AccountFailure(AccountIssue.loginRequired);
      youtube = await YouTubeSession.verify(cookie, http, child);
    } else { _validateSpotify(captured.values); }
    child.check();
    progress('Saving the verified account in protected system storage...');
    final saved = await store.add(service, label, captured.values, child);
    if (youtube != null) adoptYoutube(youtube);
    _needsSignIn.remove(saved.id);
    ++_generation;
  });

  @override
  Future<void> activateAccount(String id, RequestScope scope) => _ordered(scope, (child) async {
    await _restore(child);
    final saved = store.sessions.where((row) => row.id == id).firstOrNull;
    if (saved == null) throw const AccountFailure(AccountIssue.operationFailed);
    YouTubeSession? youtube;
    if (saved.service == AccountService.youtube) {
      youtube = await _youtube(saved, child);
    } else {
      await _spotify(saved, child, force: false);
    }
    child.check();
    await store.activate(id, child);
    if (youtube != null) adoptYoutube(youtube);
    _needsSignIn.remove(id);
    ++_generation;
  });

  @override
  Future<void> removeAccount(String id, RequestScope scope) => _ordered(scope, (child) async {
    await _restore(child);
    final saved = store.sessions.where((row) => row.id == id).firstOrNull;
    if (saved == null) return;
    final active = store.active(saved.service)?.id == id;
    await store.remove(id, child);
    if (active && saved.service == AccountService.youtube) clearYoutube();
    _needsSignIn.remove(id);
    if (active) ++_generation;
  });

  @override
  Future<void> refreshAccount(AccountService service, RequestScope scope) => _ordered(scope, (child) async {
    await _restore(child);
    final saved = store.active(service);
    if (saved == null) throw const AccountFailure(AccountIssue.loginRequired);
    try {
      if (service == AccountService.youtube) {
        final verified = await _youtube(saved, child);
        child.check();
        adoptYoutube(verified);
      } else { await _spotify(saved, child, force: true); }
      _needsSignIn.remove(saved.id);
    } catch (_) { child.check(); _needsSignIn.add(saved.id); rethrow; }
  });

  static void _validateSpotify(Map<String, dynamic> values) {
    for (final key in ['accessToken', 'clientToken', 'sp_dc']) {
      final value = values[key];
      if (value is! String || value.isEmpty || value.length > 16384 || RegExp(r'[\x00-\x20;]').hasMatch(value)) throw const AccountFailure(AccountIssue.loginRequired);
    }
    if (values['expiresAt'] is! num) throw const AccountFailure(AccountIssue.loginRequired);
  }

  Future<Map<String, dynamic>> _spotify(SavedSession saved, RequestScope scope, {required bool force}) async {
    _validateSpotify(saved.credentials);
    final expires = (saved.credentials['expiresAt'] as num).toInt();
    if (!force && expires > DateTime.now().millisecondsSinceEpoch + 60000) return saved.credentials;
    final next = await browser.refreshSpotify(saved.credentials, scope);
    scope.check();
    if (next.service != AccountService.spotify) throw const AccountFailure(AccountIssue.loginRequired);
    _validateSpotify(next.values);
    await store.replace(SavedSession(id: saved.id, service: saved.service, label: saved.label, credentials: next.values), scope);
    _needsSignIn.remove(saved.id);
    // Token rotation preserves account identity; do not invalidate the caller
    // performing this refresh. Actual switches/removal advance the generation.
    return next.values;
  }

  Future<Map<String, dynamic>> spotifyCredentials(RequestScope scope, bool force) => _ordered(scope, (child) async {
    await _restore(child);
    final saved = store.active(AccountService.spotify);
    if (saved == null) throw const AccountFailure(AccountIssue.loginRequired);
    try { return await _spotify(saved, child, force: force); }
    catch (_) { child.check(); _needsSignIn.add(saved.id); rethrow; }
  });

  Future<void> close() async {
    _closed = true;
    for (final scope in _requests.toList()) { scope.cancel(); }
    await _tail;
    clearYoutube();
  }
}
