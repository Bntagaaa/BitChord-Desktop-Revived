import 'package:flutter/widgets.dart';

import 'shell_catalogues.g.dart';

/// Source language -> English -> call-site fallback, as DesktopStrings.kt.
/// Only migrated shell strings are exposed; future feature copy stays out of UI.
String shellString(BuildContext context, String key, String fallback) =>
    lookupShellString(
      WidgetsBinding.instance.platformDispatcher.locale.languageCode,
      key,
      fallback,
    );

String lookupShellString(String language, String key, String fallback) =>
    shellCatalogues[language]?[key] ?? shellCatalogues['en']?[key] ?? fallback;
