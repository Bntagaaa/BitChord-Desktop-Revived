import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../application/catalog_controller.dart';
import '../application/search_suggestions.dart';
import '../application/download_queue.dart';
import '../data/browser_sign_in.dart';
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
  bool _busy = false, _browserBusy = false;

  @override
  void dispose() {
    _scope?.cancel();
    _cookie.clear();
    _cookie.dispose();
    super.dispose();
  }

  void _invalidateAccountState() {
    ref.read(catalogProvider.notifier).cancel();
    ref.read(searchSuggestionsProvider.notifier).dismiss();
    ref.read(downloadQueueProvider.notifier).cancelAll();
  }

  Future<void> _reloadCurrentCatalog() async {
    final current = ref.read(catalogProvider);
    if (current.browseId != null || current.query.isNotEmpty) {
      await ref.read(catalogProvider.notifier).load(
        provider: current.provider,
        query: current.query,
        browseId: current.browseId,
        params: current.params,
        filter: current.filter,
      );
    }
  }

  Future<void> _connect(OnlineAccounts accounts, {bool browser = false}) async {
    if (_busy) return;
    _scope?.cancel();
    final scope = _scope = RequestScope();
    final manualCookie = _cookie.text.trim();
    _cookie.clear();
    setState(() {
      _busy = true;
      _browserBusy = browser;
      _error = null;
    });
    _invalidateAccountState();
    try {
      // Browser owns a fresh isolated profile; no user's ordinary Chrome data
      // is inspected. The returned cookies are held in memory and verified by
      // the existing authenticated YouTube shell before session adoption.
      final cookie = browser
          ? await BrowserSignIn.captureYouTube(scope)
          : manualCookie;
      scope.check();
      await accounts.signIn(cookie, scope);
      scope.check();
      await _reloadCurrentCatalog();
    } catch (error) {
      if (mounted && !scope.cancelled) {
        setState(() {
          _error = error is ProviderFailure
              ? error.toString()
              : error is StateError
              ? error.message.toString()
              : 'Session verification failed.';
        });
      }
    } finally {
      scope.cancel();
      if (mounted) {
        setState(() {
          _busy = false;
          _browserBusy = false;
        });
      }
    }
  }

  Future<void> _signOut(OnlineAccounts accounts) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    _scope?.cancel();
    _invalidateAccountState();
    try {
      await accounts.signOut();
      await _reloadCurrentCatalog();
    } catch (_) {
      if (mounted) setState(() => _error = 'Sign-out failed.');
    } finally {
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
          if (!accounts.signedIn) ...[
            Text(
              'Sign in using an isolated Chrome, Edge, Brave or Chromium window. '
              'Complete Google sign-in, open YouTube Music, then close that '
              'window. This app verifies your session without reading your '
              'normal browser profile or saving a cookie file.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            FilledButton(
              key: const Key('browser-sign-in'),
              onPressed: _busy ? null : () => _connect(accounts, browser: true),
              child: Text(
                _browserBusy ? 'Complete sign-in in browser…' : 'Sign in with browser',
              ),
            ),
            if (_browserBusy)
              TextButton(
                key: const Key('cancel-browser-sign-in'),
                onPressed: () => _scope?.cancel(),
                child: const Text('Cancel browser sign-in'),
              ),
            const SizedBox(height: 8),
            Text(
              'Advanced: verify an existing YouTube Music session for this '
              'app run only. It is cleared on exit.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
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
              child: Text(_busy && !_browserBusy ? 'Verifying session…' : 'Verify session'),
            ),
          ] else ...[
            Text(
              'Your session is held in memory only and is removed on sign-out '
              'or application exit.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            TextButton(
              onPressed: _busy ? null : () => _signOut(accounts),
              child: const Text('Sign out'),
            ),
          ],
          if (_error != null)
            Text(
              _error!,
              style: Theme.of(context).textTheme.bodySmall,
            ),
        ],
      ),
    );
  }
}
