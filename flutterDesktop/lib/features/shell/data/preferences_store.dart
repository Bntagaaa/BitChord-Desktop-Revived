import 'package:shared_preferences/shared_preferences.dart';

import '../domain/shell_settings.dart';

// Separate Flutter preference namespace; never writes Kotlin's Java Preferences.
final class PreferencesStore implements ShellSettingsStore {
  PreferencesStore(this.preferences);
  final SharedPreferencesAsync preferences;
  static const _key = 'bitchord.flutter.shell.v1';

  @override
  Future<ShellSettings> read() async {
    final values = await preferences.getStringList(_key);
    if (values == null || (values.length != 5 && values.length != 6))
      return const ShellSettings();
    var appearance = Appearance.system;
    for (final option in Appearance.values) {
      if (option.name == values[0]) appearance = option;
    }
    return ShellSettings(
      appearance: appearance,
      pitchBlack: values[1] == 'true',
      sidebarCollapsed: values[2] == 'true',
      reduceMotion: values[3] == 'true',
      reduceBlur: values[4] == 'true',
      uiScalePercent: normalizeUiScale(
        values.length == 6 ? int.tryParse(values[5]) ?? 100 : 100,
      ),
    );
  }

  @override
  Future<void> write(ShellSettings value) => preferences.setStringList(_key, [
    value.appearance.name,
    '${value.pitchBlack}',
    '${value.sidebarCollapsed}',
    '${value.reduceMotion}',
    '${value.reduceBlur}',
    '${normalizeUiScale(value.uiScalePercent)}',
  ]);
}
