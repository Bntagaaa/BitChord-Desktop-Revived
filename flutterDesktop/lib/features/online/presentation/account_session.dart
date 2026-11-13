import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../application/catalog_controller.dart';
import '../domain/catalog.dart';
import '../domain/online_port.dart';

class AccountSession extends ConsumerStatefulWidget {
  const AccountSession({super.key});
  @override
  ConsumerState<AccountSession> createState() => _AccountSessionState();
}

class _AccountSessionState extends ConsumerState<AccountSession> {
  final _cookie = TextEditingController();
  RequestScope? _scope;
  String? _error;
  bool _busy = false;
  @override
  void dispose() {
    _scope?.cancel();
    _cookie.clear();
    _cookie.dispose();
    super.dispose();
  }

  Future<void> _connect(OnlineAccounts accounts) async {
    final scope = _scope = RequestScope();
    final cookie = _cookie.text.trim();
    _cookie.clear();
    setState(() {
      _busy = true;
      _error = null;
    });
    // Cancel personalized catalog/download operations across account generations.
    ref.read(catalogProvider.notifier).cancel();
    try {
      await accounts.signIn(cookie, scope);
      scope.check();
      final state = ref.read(catalogProvider);
      if (state.browseId != null || state.query.isNotEmpty) {
        await ref
            .read(catalogProvider.notifier)
            .load(
              provider: state.provider,
              query: state.query,
              browseId: state.browseId,
              params: state.params,
              filter: state.filter,
            );
      }
    } catch (e) {
      if (mounted)
        setState(
          () => _error = e is ProviderFailure
              ? e.toString()
              : 'Session verification failed.',
        );
    } finally {
      scope.cancel();
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final port = ref.watch(onlinePortProvider);
    if (port is! OnlineAccounts) return const SizedBox.shrink();
    final accounts = port as OnlineAccounts;
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            accounts.signedIn
                ? 'YouTube Music session verified'
                : 'YouTube Music · Guest',
          ),
          const SizedBox(height: 8),
          Text(
            'Use an existing YouTube Music cookie for this app session. It is used for YouTube catalog and playback requests, passed to the bundled resolver in memory, and cleared on exit. No cookie file is saved. Browser sign-in and protected account storage are not available.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          if (!accounts.signedIn) ...[
            TextField(
              key: const Key('session-cookie'),
              controller: _cookie,
              obscureText: true,
              enableSuggestions: false,
              autocorrect: false,
              enableIMEPersonalizedLearning: false,
              decoration: const InputDecoration(
                hintText: 'YouTube Music cookie',
              ),
            ),
            TextButton(
              onPressed: _busy ? null : () => _connect(accounts),
              child: Text(_busy ? 'Verifying session…' : 'Verify session'),
            ),
          ] else
            TextButton(
              onPressed: () async {
                ref.read(catalogProvider.notifier).cancel();
                await accounts.signOut();
                final state = ref.read(catalogProvider);
                if (state.browseId != null || state.query.isNotEmpty) {
                  await ref
                      .read(catalogProvider.notifier)
                      .load(
                        provider: state.provider,
                        query: state.query,
                        browseId: state.browseId,
                        params: state.params,
                        filter: state.filter,
                      );
                }
                if (mounted) setState(() {});
              },
              child: const Text('Sign out'),
            ),
          if (_error != null)
            Text(_error!, style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }
}
