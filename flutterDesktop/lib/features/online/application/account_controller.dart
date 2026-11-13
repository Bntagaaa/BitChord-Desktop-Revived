import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../domain/account_port.dart';
import '../domain/catalog.dart';
import 'catalog_controller.dart';
import 'download_queue.dart';
import 'search_suggestions.dart';

final accountControllerProvider = NotifierProvider<AccountController, AccountView>(AccountController.new);

final class AccountView {
  const AccountView({this.accounts = const [], this.busy = false, this.loaded = false, this.progress, this.error, this.revision = 0});
  final List<AccountSummary> accounts;
  final bool busy, loaded;
  final String? progress, error;
  final int revision;
}

final class AccountController extends Notifier<AccountView> {
  RequestScope? _scope;
  bool _disposed = false;
  int _epoch = -1;
  @override
  AccountView build() {
    ref.onDispose(() { _disposed = true; _scope?.cancel(); });
    return const AccountView();
  }
  OnlineAccountManager? get manager {
    final port = ref.read(onlinePortProvider);
    return port is OnlineAccountManager ? port as OnlineAccountManager : null;
  }
  static String message(Object error) => error is AccountFailure || error is ProviderFailure ? error.toString() : 'The account operation failed. No credentials were changed intentionally.';

  void _invalidate(int epoch) {
    if (_epoch == epoch) return;
    final previous = _epoch;
    _epoch = epoch;
    // Do not reset the guest's catalog merely for opening an empty account menu.
    if (previous >= 0 || epoch != 0) {
      ref.read(catalogProvider.notifier).cancel();
      ref.read(searchSuggestionsProvider.notifier).dismiss();
      ref.read(downloadQueueProvider.notifier).cancelAll();
    }
  }

  Future<bool> _run(Future<void> Function(OnlineAccountManager, RequestScope) action) async {
    final api = manager;
    if (api == null || state.busy) return false;
    final scope = _scope = RequestScope();
    final before = state;
    state = AccountView(accounts: before.accounts, loaded: before.loaded, busy: true, revision: before.revision);
    try {
      await action(api, scope);
      scope.check();
      final rows = await api.accounts(scope);
      scope.check();
      if (_disposed || !identical(_scope, scope)) return false;
      final changed = _epoch != api.accountGeneration;
      _invalidate(api.accountGeneration);
      state = AccountView(accounts: List.unmodifiable(rows), loaded: true, revision: before.revision + (changed ? 1 : 0));
      return true;
    } catch (error) {
      if (!_disposed && identical(_scope, scope) && !scope.cancelled) {
        state = AccountView(accounts: before.accounts, loaded: before.loaded, error: message(error), revision: before.revision);
      }
      return false;
    } finally {
      scope.cancel();
      if (identical(_scope, scope)) _scope = null;
    }
  }

  Future<bool> refresh() => _run((_, __) async {});
  Future<bool> connect(AccountService service, String label) => _run((api, scope) => api.connectAccount(service, label, scope, (progress) {
    if (_disposed || scope.cancelled || !identical(_scope, scope)) return;
    state = AccountView(accounts: state.accounts, busy: true, loaded: state.loaded, progress: progress, revision: state.revision);
  }));
  Future<bool> activate(String id) => _run((api, scope) => api.activateAccount(id, scope));
  Future<bool> remove(String id) => _run((api, scope) => api.removeAccount(id, scope));
  Future<bool> renew(AccountService service) => _run((api, scope) => api.refreshAccount(service, scope));
  Future<bool> disconnect(AccountService service) => _run((api, scope) => api.disconnectAccount(service, scope));
  void cancel() {
    _scope?.cancel(); _scope = null;
    if (!_disposed) state = AccountView(accounts: state.accounts, loaded: state.loaded, revision: state.revision);
  }
}
