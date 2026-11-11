# BitChord Flutter desktop foundation — Phase 1 in progress

This directory contains independently authored Dart application source and tests. **It has not been compiled, analyzed, formatted with Dart, launched, or visually accepted in this environment.** Flutter's Dart/engine artifacts are blocked by proxy policy. No previous Flutter rewrite was used. Kotlin 1.9.2 remains the reference, and no native audio backend is implemented here.

## Official initialization and running

Flutter source/SDK pin: **3.47.9**, framework `c197ca5199e9ee5661e865596c9c07294dfab51c`, engine `89c39edfdfae2949f6806e4a315c0c08522238b7`. Version and framework revision must match before generation; the downloaded SDK artifacts still require validation on an accessible runner. No complete Dart SDK was found locally.

With that official SDK available, from `flutterDesktop/`:

```sh
python tool/initialize_platforms.py
flutter pub get
# Review and commit the generated pubspec.lock after successful resolution.
dart format lib test
flutter analyze --fatal-infos
flutter test
flutter build linux --release
flutter run -d linux
# On Windows, use flutter build windows --release / flutter run -d windows.
```

`initialize_platforms.py` invokes **official `flutter create --empty --no-pub --platforms=windows,linux`** in a temporary project and copies only its generated Windows/Linux hosts and metadata. It never creates platform CMake/C++/runner files by hand and never overwrites the application source. It fails when Flutter is absent or its source revision differs. Generated directories are ignored until they have actually been created and reviewed; download the complete generated project from the workflow artifact for review. Do not treat this source-only checkout as an already initialized/runnable Flutter desktop project.

Linux runners need Clang, CMake, Ninja, pkg-config, GTK3 development libraries, liblzma development libraries and a supported C++ runtime. Windows requires Visual Studio's desktop C++ workload/CMake/Ninja. SDK and dependency caches must be user-owned. Keep TLS/checksum verification and inherited proxy/CA configuration enabled.

## GitHub-hosted fallback

`.github/workflows/flutter-desktop.yml` uses pinned `subosito/flutter-action` on Ubuntu 22.04 and Windows 2022. It installs the pinned SDK, generates official hosts, resolves dependencies, formats/analyzes/tests, builds release bundles and uploads evidence. Linux additionally launches the real release executable under Xvfb/Openbox, checks a visible painted window, captures 1220×780 and 900×600 client surfaces, and closes through the window manager. This is a planned smoke check, not an executed result. A matched Kotlin capture remains necessary.

The bootstrap workflow intentionally formats source in its checkout because local Dart formatting is blocked. Its source artifact includes the formatter output, official generated hosts, SDK provenance and resolved `pubspec.lock`. Review and commit those results after the first successful run; do not represent the unformatted repository source as format-verified. Once a lockfile exists, CI uses `--enforce-lockfile`. No transitive lockfile was fabricated. Application source, not build caches, is included in the source archive. No workflow was dispatched or inspected here: GitHub API access returned Forbidden. This commit does not publish the workflow or claim a GitHub run succeeded.

## Structure and behavior implemented in source

- `app/`: composition, go_router routes and bounded navigation history.
- `features/shell/domain/`: storage/window ports and settings values; no Flutter imports.
- `features/shell/application/`: Riverpod state, one active settings write plus one coalesced pending snapshot.
- `features/shell/data/`: real shared_preferences storage in a Flutter-only namespace; Kotlin preferences remain untouched.
- `core/platform/`: window_manager adapter, listener/close/flush lifecycle, sanitized bounded diagnostics.
- `core/domain/`: future audio and diagnostic contracts only; no fake playback/FFI service.
- `design_system/`: original semantic palettes, type-scale mapping, dimensions and clipped glass surface.
- `features/shell/presentation/`: sidebar/rail/drawer, route navigation/back, content status, appearance controls and disabled empty player dock.

The shell retains the reference 1220×780 initial window, 900×600 minimum, 220/72 sidebar and 980 rail breakpoint; theme/collapse settings persist, automatic rail collapse does not overwrite the preference. Geometry is not persisted because no reference geometry restore was established. Native decorations remain OS-owned. Settings appearance controls are functional source; other routes clearly report disconnected services and contain no invented songs, artists or playlists. Unavailable transport controls have no callbacks. No timer, dummy audio state or native engine runs behind them.

## Checks and evidence

Locally executed: Python layer/provenance checks, Python syntax checks and third-party Dart grammar parsing. Six Dart tests were authored (theme policy; ordered storage/bounded history; expanded shell/dock; narrow drawer; navigation/appearance/window shortcuts; resize/capture). **None of those six has run yet.** Grammar parsing cannot detect Dart type/API errors, plugin linking errors or rendering failures.

`tool/dependency_provenance.json` records selected direct versions and source-archive SHA-256 verified against pub.dev metadata. This establishes source integrity only, not dependency resolution, compatibility, a complete SBOM or runtime validation. Riverpod 3.3.2 and go_router 17.5.0 avoid requiring Dart 3.12 when the pinned Flutter source declares a Dart 3.11 floor. window_manager 0.5.1 uses its stable legacy API rather than deprecated compatibility exports in 0.6.0. Transitive packages and platform behavior must pass the actual runner checks.

## Remaining visual/functionality gaps

- No paired Kotlin/Flutter screenshots exist. Font metrics, spacing, pixel colors, animation curves, blur and DPI need actual comparison; no pixel-perfect claim.
- SF Pro/font binaries were not copied because redistribution permission has not been established. Flutter uses its platform fallback with the reference sizes/weight remapping; this is an explicit typography gap.
- Standard go-back/status header, appearance controls and disconnected-service body are shell infrastructure, not completed Home/Search/Library/Settings feature parity. The legacy top player, full player/side panels, data integrations, account UI and PiP are not implemented.
- Native Windows Mica/caption material, exact app/taskbar icons, fullscreen behavior on real compositors, OS-theme propagation and DPI/accessibility need platform tests. Generated platform runner metadata/icons retain official template defaults until reviewed customization.
- Glass uses bounded Flutter blur, but its renderer does not establish equivalence to Compose Haze. Reduced blur/animation state exists; remaining original per-surface motion/interaction policies are not all implemented.
- No native audio, playback, tray/media integration, provider access or performance measurement is claimed. No Phase 2 work began.

## Current verification status — 2026-11-11

Last confirmed pushed implementation: `529c8d60`.

Windows hosted validation succeeded: official Flutter generation, dependency
resolution, formatting, analysis, six tests, and release build.

Verification run:
https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43041242897

The final Linux result and native launch checks have not been confirmed.
Retention of generated platform files and verification artifacts has not
been confirmed.

Local Flutter installation remains blocked by inaccessible SDK artifacts.
The desktop application has not been confirmed running interactively in the
local environment.

The application provides the Phase 1 foundation and visual shell. Audio
controls remain disabled because the native audio backend is not implemented.
No invented library content or simulated playback is provided.

Pixel-perfect parity, Kotlin runtime comparison, and memory improvements
remain unverified. Phase 2 has not started.

### Later CI verification — 2026-11-12

Run `43041242897` has completed: Windows succeeded, Linux failed during
`Install Linux desktop dependencies`, and `retain-verified-generation`
was skipped. Earlier pending language above reflects the 2026-11-11 snapshot.
No Linux GUI launch or visual parity has been verified.

