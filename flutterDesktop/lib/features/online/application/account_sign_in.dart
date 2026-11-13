import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../domain/catalog.dart';

/// The composition root injects a desktop browser capability. No application
/// state depends on a concrete browser process or reads browser data directly.
typedef BrowserSignInCapture = Future<String> Function(RequestScope scope);

final browserSignInCaptureProvider = Provider<BrowserSignInCapture?>(
  (ref) => null,
);
