import '../../domain/catalog.dart';
import '../provider_http.dart';
import '../youtube_session.dart';
import '../youtube_session_store.dart';

// One account generation owns all catalog and resolver operations. Switching
// account/signing out cancels old work instead of merely hiding late UI results.
final class YouTubeAccounts {
  YouTubeAccounts([this._store]);
  final YouTubeSessionStore? _store;
  YouTubeSession? _session;
  int _generation = 0;
  final _active = <RequestScope>{};
  YouTubeSession? get session => _session;
  int get generation => _generation;
  bool get signedIn => _session != null;

  void signOut() {
    _generation++;
    _session = null;
    for (final operation in _active.toList()) {
      operation.cancel();
    }
    _active.clear();
    _store?.clear();
  }

  void cancelActive() {
    for (final operation in _active.toList()) {
      operation.cancel();
    }
    _active.clear();
  }

  Future<void> signIn(
    String cookie,
    ProviderHttp http,
    RequestScope parent,
  ) async {
    signOut();
    final generation = _generation;
    final session = await run(
      parent,
      (scope, _) => YouTubeSession.verify(cookie, http, scope),
    );
    parent.check();
    if (generation != _generation)
      throw const ProviderFailure(ProviderProblem.cancelled);
    signOut();
    _session = session;
    await _store?.save(session);
  }

  Future<YouTubeSession?> restore(
    ProviderHttp http,
    RequestScope parent,
  ) async {
    final store = _store;
    if (store == null || !store.exists()) return null;
    final data = await store.loadData();
    if (data == null) return null;
    final cookie = data['cookie'] as String?;
    if (cookie == null || cookie.isEmpty) return null;

    final fallback = YouTubeSession.restore(
      cookie: cookie,
      authUser: (data['authUser'] as String?) ?? '0',
      version: (data['version'] as String?) ?? '0.1',
      pageId: data['pageId'] as String?,
      dataSyncId: data['dataSyncId'] as String?,
      visitorData: data['visitorData'] as String?,
    );

    try {
      final verified = await run(
        parent,
        (scope, _) => YouTubeSession.verify(cookie, http, scope),
      );
      _session = verified;
      await store.save(verified);
      return verified;
    } on ProviderFailure catch (failure) {
      if (failure.problem == ProviderProblem.unauthorized ||
          failure.problem == ProviderProblem.loginRequired) {
        // Expired or revoked session: clear to prevent infinite retry loops.
        signOut();
        return null;
      }
      // Temporary network failure: tentatively restore fallback so offline/cached library works
      if (failure.problem == ProviderProblem.network ||
          failure.problem == ProviderProblem.timeout) {
        _session = fallback;
        return fallback;
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  Future<T> run<T>(
    RequestScope parent,
    Future<T> Function(RequestScope, YouTubeSession?) work,
  ) async {
    parent.check();
    final generation = _generation;
    final operation = RequestScope();
    final detach = parent.onCancel(operation.cancel);
    _active.add(operation);
    try {
      final value = await work(operation, _session);
      operation.check();
      if (generation != _generation)
        throw const ProviderFailure(ProviderProblem.cancelled);
      return value;
    } finally {
      detach();
      _active.remove(operation);
      operation.cancel();
    }
  }
}
