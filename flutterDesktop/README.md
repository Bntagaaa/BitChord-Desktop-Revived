# BitChord Flutter desktop foundation — Phase 1

The independently implemented Flutter shell lives here. Kotlin 1.9.2 at
`348b613a0c3db1a54de56483ea6f8748d6465211` remains the behavioral reference;
no previous Flutter rewrite is used. The shell has real navigation, appearance
preferences and native lifecycle, with disconnected music pages and disabled
transport controls. There is no audio backend or simulated playback.

Phase 1 remains incomplete for visual acceptance. The latest authoritative
gate results and remaining prerequisites are in the targeted acceptance audit
at the end of [`MIGRATION_PROGRESS.md`](../MIGRATION_PROGRESS.md).

## Toolchain and commands

Pin: Flutter **3.47.9**, framework `c197ca5199e9ee5661e865596c9c07294dfab51c`,
engine `89c39edfdfae2949f6806e4a315c0c08522238b7`, hosted Dart **3.13.7**.
Both hosted toolchains have compiled the shell. Run from this directory:

```sh
python3 tool/initialize_platforms.py
flutter pub get --enforce-lockfile # use flutter pub get only before initial lockfile retention
python3 tool/check_boundaries.py
python3 tool/verify_reference_tokens.py
dart format --output=none --set-exit-if-changed lib test
flutter analyze --fatal-infos
flutter test
flutter build linux --release
python3 tool/launch_smoke.py build/linux/x64/release/bundle/bitchord_desktop
# Windows: flutter build windows --release
# pwsh -NoProfile -File tool/launch_smoke_windows.ps1 -Executable build/windows/x64/runner/Release/bitchord_desktop.exe
```

`initialize_platforms.py` requires the exact SDK revision and invokes official
`flutter create --empty --no-pub --platforms=windows,linux` in a temporary
project. It copies only generated hosts/metadata and never overwrites Dart
application source. Existing hosts require matching generation provenance.
Official hosts, resolved lockfile and formatter output were retained in
`6f67ac2f` after the complete matrix and native smoke checks passed. Subsequent
CI checks formatting without rewriting source and enforces the committed lockfile.

Linux needs Clang, CMake, Ninja, pkg-config, GTK3/liblzma development packages
and a C++ runtime. The hosted smoke also needs D-Bus, Xvfb, Xauth, Openbox,
xdotool, x11-utils and ImageMagick; `tool/install_linux_ci.py` is scoped to Ubuntu 22.04
runners and uses signed official HTTPS repositories. Windows needs Visual
Studio's Desktop C++ workload and Windows SDK; CI uses Server 2022/VS 2022.
The smoke requires a visible display large enough for both client sizes at
96 DPI. CI requests 1600×1200; do not change a user's display to run it.

## Verification and artifact interpretation

The workflow `.github/workflows/flutter-desktop.yml` generates official hosts,
records `flutter doctor -v`/SDK provenance, resolves packages, checks formatting,
analyzes, runs twelve tests and builds release bundles on Linux and Windows. Native
smokes launch the release executable, capture painted 1220×780 and 900×600
**client** surfaces, check responsive sidebar/dock landmarks and close through
the native window path with exit code zero. Actual F11/Escape input verifies
fullscreen and restoration to the prior client size. Account/drawer/settings
captures and real Light/Dark/Pitch-black selections extend the smoke; their
results must be checked in the specific run's native JSON and logs. These are hosted lifecycle checks,
not physical GPU/compositor or reference-parity acceptance. Kotlin's initial
1220×780 is an outer-window size; a matched comparison must record native insets.

Artifacts `flutter-foundation-linux` and `flutter-foundation-windows` contain
logs, test summaries, SDK provenance, generated/formatted source ZIPs and release
bundles. Native PNGs/JSON record actual desktop capture/geometry; widget PNGs
named `shell-dark-*` use the test Ahem font and are **layout diagnostics only**.
Neither is a Kotlin golden. The retention job is restricted to trusted migration
branch pushes, requires both platform jobs, checks the validated SHA against the
branch head and never force-pushes.

[43126969435](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43126969435)
at `ea6b5c90` passed on both OSs: official generation, dependency resolution,
format/analyze, **eight tests each** (zero failed/skipped), release build,
painted client captures at both sizes and native close/exit 0. Generated hosts,
lockfile, formatted source and Windows/Linux capture/JSON evidence are checked in
under `verification/`. Earlier black/stale capture, small-display and Windows
force-quit failures are recorded with their fixes in `../MIGRATION_PROGRESS.md`.
This verifies the foundation and lifecycle, not full visual parity.

Local checks on 2026-11-12 passed Python compilation, layer/asset verification
and all 779 recorded reference hashes. The full Flutter SDK is absent locally; the older
cloud SDK HTTP 403 is historical. This workstation has under 1 GiB workspace
free disk and only full JDK 27. Local offline reference tests failed before
execution on that JDK. JitPack currently responds HTTP 200 locally. Hosted pinned
Kotlin Linux validation passed **857 tests** and built a distributable in
[43125482870](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43125482870).
Those early Windows reference runs failed inside the native Gradle task. Focused
diagnosis proved GNU 14.2 builds analysis but fails SMTC's incomplete MinGW WinRT
types. The reference's supported MSVC path plus an external SDK header prelude
now builds **all three original DLLs** in
[43134106555](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43134106555).
`tool/reference_msvc.init.gradle` changes CI toolchain actions only; protected
reference files and tests remain intact. Full tests/package/capture results are
recorded separately in the current acceptance audit; focused compilation alone
does not prove them.
Official standalone Dart 3.13.7 was downloaded with its publisher SHA-256
checked, and local formatting at language version 3.11 passed. Flutter analyze,
tests and builds use CI; local native capture cleanup is separate from CI's
verified native-close path.

## Ownership and source provenance

- `app/`: composition, go_router routes and bounded navigation history.
- `features/shell/domain/`: storage/window ports and immutable settings.
- `features/shell/application/`: Riverpod state and bounded, ordered preference writes.
- `features/shell/data/`: real shared_preferences adapter in a Flutter-only namespace.
- `core/platform/`: window_manager adapter, close/flush lifecycle and bounded diagnostics.
- `core/domain/`: future audio contracts only; no FFI engine or fake service.
- `design_system/` and shell presentation: semantic palettes, type mapping, clipped
  glass, sidebar/rail/drawer and empty player dock.

`assets/provenance.json`, `assets/NOTICE.md` and `assets/LICENSE_BitChord.txt`
preserve original asset hashes/notices. `tool/dependency_provenance.json` records
five direct pub.dev archive versions, SHA-256 values and source repositories;
actual transitive resolution is recorded in the generated `pubspec.lock`.
SDK/package checks establish compatibility for this shell, not future audio,
authentication, media or PiP integrations. C++17/CMake/C ABI remains the selected
future audio architecture; no native audio library is loaded here.

## Open visual and platform gates

The initial local Linux light-theme pairs at both client sizes are in
[`verification/phase1-local-comparison/`](verification/phase1-local-comparison/),
with provenance/limits in `capture.json`. They compare common shell regions:
Kotlin's anonymous live catalog differs from Flutter's disconnected body. Local
numeric DPI is unmeasured. The targeted continuation removes the extra 48-dp
header, matches sidebar/header/row/footer dimensions, restores the account
avatar/empty menu and rail action, uses the original gray dock asset and adds
the original action slots and unavailable volume track. Typography roles now
match the source's sizes, physical weights and line-height policy. Twelve tests
and the full release/native/retention matrix passed in run
[43134106705](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43134106705).
Repeated local Linux pairs are in
[`verification/phase1-shell-correction/`](verification/phase1-shell-correction/),
alongside focused Windows native provenance. This does not establish full
reference parity. SF Pro redistribution rights,
fallback ink metrics/wrapping and Haze noise/blur equivalence remain unresolved. Native
Mica/caption material, template app/taskbar icons, OS theme changes, focus and
100/125/150/200% DPI, glass/motion and accessibility still
need matched platform checks. Widget font rectangles must not be called text
parity. Material appearance controls and the disconnected
page body are Phase 1 infrastructure; legacy top player, full player/side panels,
account/data screens and PiP remain later work. No RAM improvement is measured.
Phase 1 is incomplete until its remaining gates have evidence; Phase 2 has not
started.
