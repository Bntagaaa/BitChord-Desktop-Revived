import 'catalog.dart';

enum AccountService { youtube, spotify }

enum AccountIssue {
  browserUnavailable,
  browserClosed,
  browserTimeout,
  loginRequired,
  storageUnavailable,
  corruptStorage,
  unsupportedProfile,
  operationFailed,
}

final class AccountFailure implements Exception {
  const AccountFailure(this.issue);
  final AccountIssue issue;
  @override
  String toString() => switch (issue) {
    AccountIssue.browserUnavailable =>
      'No supported browser was found. Install Chrome, Chromium, Edge or Brave, or use the session-only import.',
    AccountIssue.browserClosed =>
      'Sign-in was cancelled or the browser closed without a signed-in session.',
    AccountIssue.browserTimeout =>
      'The sign-in browser did not finish in time. Close its window and try again.',
    AccountIssue.loginRequired =>
      'This account needs to sign in again. Your saved library has not been replaced.',
    AccountIssue.storageUnavailable =>
      'Secure account storage is unavailable or locked. Unlock your desktop keyring and retry. No plaintext account was saved.',
    AccountIssue.corruptStorage =>
      'Saved account data could not be read. It was not overwritten.',
    AccountIssue.unsupportedProfile =>
      'This account profile is not supported for this operation.',
    AccountIssue.operationFailed =>
      'The account operation failed. No session details were logged.',
  };
}

final class AccountSummary {
  const AccountSummary({
    required this.id,
    required this.service,
    required this.label,
    this.active = false,
    this.needsSignIn = false,
  });
  final String id, label;
  final AccountService service;
  final bool active, needsSignIn;
}

abstract interface class AccountVault {
  Future<String?> read(RequestScope scope);
  Future<void> write(String secret, RequestScope scope);
  Future<void> delete(RequestScope scope);
}

// Separate from the existing OnlineAccounts interface so session-only adapters
// and test fixtures are not silently promoted to persistent account managers.
abstract interface class OnlineAccountManager {
  Future<List<AccountSummary>> accounts(RequestScope scope);
  Future<void> connectAccount(
    AccountService service,
    String label,
    RequestScope scope,
    void Function(String) progress,
  );
  Future<void> activateAccount(String id, RequestScope scope);
  Future<void> removeAccount(String id, RequestScope scope);
  Future<void> refreshAccount(AccountService service, RequestScope scope);
}
