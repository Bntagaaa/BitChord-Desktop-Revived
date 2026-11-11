enum Appearance { system, light, dark }

final class ShellSettings {
  const ShellSettings({this.appearance = Appearance.system, this.pitchBlack = false,
    this.sidebarCollapsed = false, this.reduceMotion = false, this.reduceBlur = false});
  final Appearance appearance;
  final bool pitchBlack;
  final bool sidebarCollapsed;
  final bool reduceMotion;
  final bool reduceBlur;

  ShellSettings copyWith({Appearance? appearance, bool? pitchBlack,
    bool? sidebarCollapsed, bool? reduceMotion, bool? reduceBlur}) => ShellSettings(
      appearance: appearance ?? this.appearance, pitchBlack: pitchBlack ?? this.pitchBlack,
      sidebarCollapsed: sidebarCollapsed ?? this.sidebarCollapsed,
      reduceMotion: reduceMotion ?? this.reduceMotion, reduceBlur: reduceBlur ?? this.reduceBlur);
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
