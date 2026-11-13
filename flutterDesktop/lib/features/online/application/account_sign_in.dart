import '../data/browser_sign_in.dart';
import '../domain/catalog.dart';

/// Application boundary for user-initiated desktop sign-in. Presentation
/// never starts a process or reads browser cookies directly.
final class AccountSignIn {
  AccountSignIn._();

  static Future<String> captureYouTube(RequestScope scope) =>
      BrowserSignIn.captureYouTube(scope);
}
