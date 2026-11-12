// Matches DesktopUiScaleSettings: bounded nearest option, ties choose lower.
const uiScaleOptions = [80, 90, 100, 110, 125];
int normalizeUiScale(int value) => uiScaleOptions.reduce(
  (best, next) => (next - value).abs() < (best - value).abs() ? next : best,
);

enum Appearance { system, light, dark }

final class ShellSettings {
  const ShellSettings({
    this.appearance = Appearance.system,
    this.pitchBlack = false,
    this.sidebarCollapsed = false,
    this.reduceMotion = false,
    this.reduceBlur = false,
    this.uiScalePercent = 100,
  });
  final Appearance appearance;
  final bool pitchBlack;
  final bool sidebarCollapsed;
  final bool reduceMotion;
  final bool reduceBlur;
  final int uiScalePercent;

  ShellSettings copyWith({
    Appearance? appearance,
    bool? pitchBlack,
    bool? sidebarCollapsed,
    bool? reduceMotion,
    bool? reduceBlur,
    int? uiScalePercent,
  }) => ShellSettings(
    appearance: appearance ?? this.appearance,
    pitchBlack: pitchBlack ?? this.pitchBlack,
    sidebarCollapsed: sidebarCollapsed ?? this.sidebarCollapsed,
    reduceMotion: reduceMotion ?? this.reduceMotion,
    reduceBlur: reduceBlur ?? this.reduceBlur,
    uiScalePercent: normalizeUiScale(uiScalePercent ?? this.uiScalePercent),
  );
}

abstract interface class ShellSettingsStore {
  Future<ShellSettings> read();
  Future<void> write(ShellSettings settings);
}

abstract interface class DesktopWindow {
  Future<void> toggleFullscreen();
  Future<void> exitFullscreen();
  Future<void> close();
}
