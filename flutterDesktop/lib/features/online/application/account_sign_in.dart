import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../domain/catalog.dart';

/// The composition root injects the in-app WebView and the external-browser
/// fallback independently. Widgets never launch processes or read cookies.
typedef BrowserSignInCapture = Future<String> Function(RequestScope scope);

final embeddedSignInCaptureProvider = Provider<BrowserSignInCapture?>(
  (ref) => null,
);

final browserSignInCaptureProvider = Provider<BrowserSignInCapture?>(
  (ref) => null,
);
