# Flutter desktop native builds

The Flutter target lives in `flutterApp`. Its generated runners were created
with Flutter **3.47.9** and use application ID `com.music.bitchord.flutter`.
The Kotlin applications and their workflows remain separate targets.

## Build and verification commands

Run from `flutterApp` using the pinned Flutter version:

```sh
flutter pub get --enforce-lockfile
dart format --output=none --set-exit-if-changed lib test
flutter analyze --no-pub
flutter test --no-pub --coverage
```

Build on the target operating system:

| System | Command | Complete output to distribute for review |
| --- | --- | --- |
| Linux x64 | `flutter build linux --release --no-pub` | `build/linux/x64/release/bundle/` |
| Windows x64 | `flutter build windows --release --no-pub` | `build/windows/x64/runner/Release/` |
| macOS arm64 + x64 | `flutter config --no-enable-macos-arm64-only` then `flutter build macos --release --no-pub` | `build/macos/Build/Products/Release/BitChord Desktop Revived.app` |

Release builds on macOS produce a universal binary with the pinned toolchain.
The workflow builds it on **both** `macos-15` (Apple Silicon) and
`macos-15-intel` and verifies both slices using `lipo "$binary" -verify_arch arm64 x86_64`.
These are distinct host builds of the same universal target, not single-slice
packages mislabeled as universal. Linux ARM64 and Windows ARM64 are not claimed
as validated release targets in this workflow.

`.github/workflows/flutter-desktop.yml` runs format, analyzer and normal Flutter
tests before the four release-build jobs. It uploads review archives only; it
does not publish a release, sign a distributable installer, deploy a service,
merge a branch or alter the Kotlin workflows.

## Linux

The native build needs Clang, CMake, Ninja, pkg-config, GTK 3, liblzma, libsecret,
Ayatana AppIndicator and libmpv development packages. On Ubuntu 24.04:

```sh
sudo apt-get update
sudo apt-get install clang cmake ninja-build pkg-config libgtk-3-dev \
  liblzma-dev libsecret-1-dev libayatana-appindicator3-dev libmpv-dev
```

The review tarball includes Flutter's native library, the executable, assets and
plugin libraries. It depends on the corresponding system runtime libraries,
including **libmpv**; it is not a self-contained AppImage or a Debian installer.
Run it from an extracted directory, retaining `data/` and `lib/` beside the
executable. The window icon resolves from the bundled source branding regardless
of the launcher's working directory. Credential storage needs an available
Secret Service keyring such as GNOME Keyring or KWallet.

## Windows

Use Visual Studio 2022 with **Desktop development with C++**, the Windows SDK
and the matching Flutter desktop toolchain. The stable native executable name
is `bitchord_desktop.exe`; its display name, resource metadata, process app ID
and icon identify BitChord Desktop Revived. Distribute the whole Release
directory rather than the EXE alone: Flutter, libmpv and plugin DLLs and the
`data/` directory are required. CI produces a ZIP, not a signed MSI/EXE installer.

## macOS

Use Xcode and its command-line tools and CocoaPods for dependencies that do not
yet provide Swift packages. The runner's deployment target is macOS 12.0.

This is a standalone desktop distribution. App Sandbox is disabled deliberately
so the source application's default Music-directory scan and user-chosen local
directories remain accessible. Both entitlement files declare outbound network,
incoming network, user-selected file access, Downloads access and read-only
Music access. Debug/profile retains Flutter's JIT entitlement. The incoming
network entitlement is retained in release because the bounded media transport
listens on a token-protected loopback URL.

Secure storage uses the macOS private legacy Keychain through
`MacOsOptions(usesDataProtectionKeychain: false)`. The app does not share a
Keychain group with another application, so the runners do not add
`keychain-access-groups` or require a team provisioning profile. Adding a shared
Keychain/App Group later requires provisioning and changes the distribution
requirements. The native assets use the existing BitChord source icon at the
sizes required by the macOS asset catalog.

The generated project uses ad-hoc signing for local/CI builds. Public macOS
distribution still requires the owner's Developer ID signing and notarization
pipeline; review archives from this workflow do not claim those credentials.

## Secondary windows and native ownership

Every desktop runner registers the generated plugin list for each secondary
Flutter engine created by `desktop_multi_window`. Registration follows the
installed plugin's Windows callback, Linux registry callback and macOS
`FlutterMultiWindowPlugin.setOnWindowCreatedCallback` APIs. This is necessary
for window-manager, media surface and platform channels in PiP windows.

Audio playback remains owned by the main `PlaybackController`. Borrowing a
video surface or opening a secondary window does not transfer or duplicate
the audio decoder's lifetime. Native plugin registration itself is not an
audible playback or multi-window runtime test.

## Evidence and remaining validation

The implementation session verified the pure playback assertions through the
isolated Dart AOT procedure documented in `playback-parity.md`. Normal Flutter
test startup in that managed environment was blocked by the `objective_c`
native-asset hook exiting 255 under its JIT runtime before any test ran. The
workflow deliberately keeps the standard Flutter test command so CI must
establish the full Flutter result. No cross-platform release-build success is
claimed without its corresponding workflow result.

Primary references used for runner and workflow configuration:

- [Flutter macOS entitlements and standalone builds](https://docs.flutter.dev/platform-integration/macos/building)
- [desktop_multi_window secondary-engine registration](https://github.com/MixinNetwork/flutter-plugins/tree/main/packages/desktop_multi_window)
- [flutter_secure_storage macOS Keychain provisioning](https://github.com/juliansteenbakker/flutter_secure_storage)
- [flutter-action version and architecture inputs](https://github.com/subosito/flutter-action)
- [GitHub-hosted runner labels and architectures](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
