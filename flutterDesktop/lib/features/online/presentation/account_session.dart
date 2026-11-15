import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../application/catalog_controller.dart';
import '../application/search_suggestions.dart';
import '../application/download_queue.dart';
import '../application/account_sign_in.dart';
import '../domain/catalog.dart';
import '../domain/online_port.dart';

/// DesktopAccountUi.kt's bounded account selector. The current adapter exposes
/// one saved session, so additional accounts are visibly unavailable rather
/// than replacing that session when the user asks to add another account.
class AccountPicker extends ConsumerStatefulWidget {
  const AccountPicker({super.key});
  @override
  ConsumerState<AccountPicker> createState() => _AccountPickerState();
}

class _AccountPickerState extends ConsumerState<AccountPicker> {
  bool _managing = false;
  Future<void> _add() async {
    await showDialog<void>(
      context: context,
      builder: (context) => Dialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520, maxHeight: 650),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Padding(
                  padding: EdgeInsets.fromLTRB(22, 20, 22, 0),
                  child: Text('Sign in to YouTube Music'),
                ),
                const AccountSession(),
                TextButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('Done'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final port = ref.watch(onlinePortProvider);
    final accounts = port is OnlineAccounts ? port as OnlineAccounts : null;
    final signedIn = accounts?.signedIn ?? false;
    final secondary = Theme.of(
      context,
    ).colorScheme.onSurface.withValues(alpha: .55);
    Widget action(String title, IconData icon, VoidCallback? callback) =>
        SizedBox(
          height: 44,
          child: TextButton(
            onPressed: callback,
            style: TextButton.styleFrom(
              alignment: Alignment.centerLeft,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              foregroundColor: Theme.of(context).colorScheme.onSurface,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(9),
              ),
            ),
            child: Row(
              children: [
                Icon(icon, size: 19),
                const SizedBox(width: 12),
                Text(title),
              ],
            ),
          ),
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 13, 16, 5),
          child: Text(
            signedIn ? 'YouTube Music' : 'Accounts',
            style: Theme.of(
              context,
            ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
          ),
        ),
        if (signedIn)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              constraints: const BoxConstraints(minHeight: 50),
              decoration: BoxDecoration(
                color: Theme.of(
                  context,
                ).colorScheme.onSurface.withValues(alpha: .075),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.account_circle_rounded,
                    size: 32,
                    color: secondary,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('YouTube Music'),
                        Text(
                          'Connected account',
                          style: Theme.of(
                            context,
                          ).textTheme.bodySmall?.copyWith(color: secondary),
                        ),
                      ],
                    ),
                  ),
                  const Icon(Icons.check_rounded, size: 18),
                ],
              ),
            ),
          )
        else
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Text(
              'Nobody is signed in. Your library, playlists and history need a YouTube Music account.',
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: secondary),
            ),
          ),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          child: Divider(height: .5, thickness: .5),
        ),
        Tooltip(
          message: signedIn
              ? 'Multiple saved accounts are not available yet.'
              : 'Sign in to YouTube Music',
          child: action(
            'Add account',
            Icons.add,
            accounts == null || signedIn ? null : _add,
          ),
        ),
        if (signedIn)
          action(
            'Manage accounts',
            Icons.manage_accounts_rounded,
            () => setState(() => _managing = !_managing),
          ),
        if (_managing && signedIn)
          AccountSession(onChanged: () => setState(() {})),
        const SizedBox(height: 6),
      ],
    );
  }
}

class AccountSession extends ConsumerStatefulWidget {
  const AccountSession({super.key, this.onChanged});
  final VoidCallback? onChanged;
  @override
  ConsumerState<AccountSession> createState() => _AccountSessionState();
}

class _AccountSessionState extends ConsumerState<AccountSession> {
  final _cookie = TextEditingController();
  RequestScope? _scope;
  String? _error;
  bool _busy = false, _browserBusy = false, _inAppBusy = false;

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
      await ref
          .read(catalogProvider.notifier)
          .load(
            provider: current.provider,
            query: current.query,
            browseId: current.browseId,
            params: current.params,
            filter: current.filter,
          );
    }
  }

  Future<void> _connect(
    OnlineAccounts accounts, {
    bool browser = false,
    bool inApp = false,
  }) async {
    if (_busy) return;
    _scope?.cancel();
    final scope = _scope = RequestScope();
    final manualCookie = _cookie.text.trim();
    _cookie.clear();
    setState(() {
      _busy = true;
      _browserBusy = browser;
      _inAppBusy = inApp;
      _error = null;
    });
    _invalidateAccountState();
    try {
      // Prefer BitChord's native WebView window. A standard-browser capture
      // remains an explicit fallback for Google embedded-login restrictions.
      final cookie = inApp
          ? await (ref.read(embeddedSignInCaptureProvider)?.call(scope) ??
                Future<String>.error(
                  StateError('Built-in sign-in is not available.'),
                ))
          : browser
          ? await (ref.read(browserSignInCaptureProvider)?.call(scope) ??
                Future<String>.error(
                  StateError('Browser sign-in is not available.'),
                ))
          : manualCookie;
      scope.check();
      await accounts.signIn(cookie, scope);
      scope.check();
      await _reloadCurrentCatalog();
      widget.onChanged?.call();
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
          _inAppBusy = false;
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
      widget.onChanged?.call();
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
              'Finish Google sign-in, select your YouTube Music '
              'profile, and close that window to connect your account.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            FilledButton(
              key: const Key('in-app-sign-in'),
              onPressed: _busy ? null : () => _connect(accounts, inApp: true),
              child: Text(
                _inAppBusy
                    ? 'Finish sign-in in the BitChord window…'
                    : 'Sign in inside BitChord',
              ),
            ),
            if (_inAppBusy)
              TextButton(
                key: const Key('cancel-in-app-sign-in'),
                onPressed: () => _scope?.cancel(),
                child: const Text('Cancel sign-in'),
              ),
            const SizedBox(height: 8),
            Text(
              'Google may block sign-in in embedded windows '
              '(disallowed_useragent). If so, use an installed browser '
              'instead; BitChord does not bypass Google security checks.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            TextButton(
              key: const Key('browser-sign-in'),
              onPressed: _busy ? null : () => _connect(accounts, browser: true),
              child: Text(
                _browserBusy
                    ? 'Complete sign-in in browser…'
                    : 'Use installed browser instead',
              ),
            ),
            if (_browserBusy)
              TextButton(
                key: const Key('cancel-browser-sign-in'),
                onPressed: () => _scope?.cancel(),
                child: const Text('Cancel browser sign-in'),
              ),
            const SizedBox(height: 8),
            ExpansionTile(
              title: const Text('Use an existing session'),
              children: [
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
                  child: Text(
                    _busy && !_browserBusy
                        ? 'Verifying session…'
                        : 'Verify session',
                  ),
                ),
              ],
            ),
          ] else ...[
            TextButton(
              onPressed: _busy ? null : () => _signOut(accounts),
              child: const Text('Sign out'),
            ),
          ],
          if (_error != null)
            Text(_error!, style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }
}
