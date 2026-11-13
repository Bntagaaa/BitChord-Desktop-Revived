import '../../domain/catalog.dart';
import '../provider_http.dart';
import '../youtube_session.dart';

// One account generation owns all catalog and resolver operations. Switching
// account/signing out cancels old work instead of merely hiding late UI results.
final class YouTubeAccounts {
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
