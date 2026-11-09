import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../domain/models.dart';
import 'innertube_client.dart';
import 'cookie_input.dart';
import 'innertube_parser.dart';

/// Authentication is never written to the JSON profile, logs, or export files.
class AuthStore {
  AuthStore({FlutterSecureStorage? storage})
    : _storage =
          storage ??
          const FlutterSecureStorage(
            mOptions: MacOsOptions(usesDataProtectionKeychain: false),
          );
  final FlutterSecureStorage _storage;
  static const _cookieKey = 'bitchord_flutter_youtube_cookie_v1';
  int _generation = 0;
  bool _hasExplicitIntent = false;
  bool _hasCommittedCookie = false;
  String? _committedCookie;
  Future<void> _vaultWrites = Future<void>.value();

  Future<void> restore(InnertubeClient client) async {
    // Bootstrap restoration never outranks an explicit login or logout.
    if (_hasExplicitIntent) return;
    final generation = ++_generation;
    try {
      final cookie = await _storage.read(key: _cookieKey);
      if (generation != _generation || _hasExplicitIntent) return;
      _committedCookie = cookie;
      _hasCommittedCookie = true;
      client.setCookie(cookie);
    } catch (_) {
      if (generation == _generation && !_hasExplicitIntent) rethrow;
    }
  }

  Future<Account> signIn(String value, InnertubeClient client) async {
    _hasExplicitIntent = true;
    final generation = ++_generation;
    if (!_hasCommittedCookie) {
      _committedCookie = client.cookie;
      _hasCommittedCookie = true;
    }
    try {
      final cookie = parseCookieInput(value);
      if (InnertubeClient.sapisidFrom(cookie) == null) {
        throw const ServiceException(
          'This cookie export does not contain a YouTube sign-in session.',
        );
      }
      client.setCookie(cookie);
      final account = InnertubeParser.account(
        await client.post('account/account_menu', {}),
      );
      _requireCurrent(generation);
      if (account == null) {
        throw const ServiceException(
          'YouTube Music did not recognize this account.',
        );
      }
      await _saveCookie(generation, cookie);
      _requireCurrent(generation);
      _committedCookie = cookie;
      return account;
    } catch (_) {
      if (generation == _generation) {
        client.setCookie(_committedCookie);
        // A superseded write may already have entered the OS vault. Queue a
        // rollback behind it so a failed newer login leaves the committed
        // session on disk as well as in memory.
        await _saveCookie(generation, _committedCookie);
      }
      rethrow;
    }
  }

  Future<void> signOut(InnertubeClient client) async {
    _hasExplicitIntent = true;
    final generation = ++_generation;
    _committedCookie = null;
    _hasCommittedCookie = true;
    client.setCookie(null);
    // Complete an explicit logout even if a newer login is still validating.
    // Any successful newer login queues its write after this deletion.
    await _saveCookie(generation, null, alwaysApply: true);
  }

  void _requireCurrent(int generation) {
    if (generation != _generation) {
      throw const ServiceException(
        'A newer account action replaced this sign-in.',
      );
    }
  }

  Future<void> _saveCookie(
    int generation,
    String? cookie, {
    bool alwaysApply = false,
  }) {
    final operation = _vaultWrites.then((_) async {
      if (!alwaysApply && generation != _generation) return;
      if (cookie == null) {
        await _storage.delete(key: _cookieKey);
      } else {
        await _storage.write(key: _cookieKey, value: cookie);
      }
    });
    // A failed vault action must not block a later logout/delete attempt.
    _vaultWrites = operation.catchError((Object _) {});
    return operation;
  }

  static String parseCookieInput(String input) => CookieInput.parse(input);
}
