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
analyzes, runs thirteen tests and builds release bundles on Linux and Windows. Native
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
match the source's sizes, physical weights and line-height policy. Thirteen tests
are defined, including separate Linux and Windows Home-heading offsets. The
refreshed matrix passed all thirteen tests on both platforms, release builds,
appearance captures and native lifecycle checks in
[43156992243](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43156992243).
The retained final Windows Flutter captures can be compared with the pinned
Kotlin Windows captures under
[`verification/phase1-shell-correction/`](verification/phase1-shell-correction/);
the final Linux pair is in
[`verification/phase1-linux-final/`](verification/phase1-linux-final/), recorded
sequentially in one isolated 96-DPI Xvfb display with temporary profiles and
separate D-Bus sessions. The Kotlin capture uses explicit Skiko SOFTWARE
rendering and Flutter uses Mesa llvmpipe; these are client-geometry comparisons.
Both Home headings begin at y=16, but fallback Flutter glyphs remain 4 pixels
wider and 1 pixel taller. Native compositor behavior remains unaccepted.
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
Phase 1 remains incomplete for visual acceptance; Phase 2 has not started.

## Latest Phase 1 handoff — 2026-11-12

The Phase 1 Flutter desktop **foundation** has passed hosted Windows/Linux release, tests, and native GUI/lifecycle smoke checks in [run 43163359448](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43163359448). This does **not** mean the user has accepted full visual equivalence.

Follow-up changes:
- Tab destinations use `NoTransitionPage` to avoid mobile-style Material route animations.
- A compact empty dock keeps core transport visible at very small widths.
- The user clarified that the apparent sidebar spacing mismatch was an invalid comparison of Kotlin **110% UI Scale** against Flutter **100%**; matched 100% spacing is reported correct. Preserve the source geometry, including 7 dp vertical navigation-item padding.
- User-supplied five-cut static `bitchord-sans` (400/500/600/700/800) is the preferred **installed** system font family. The TTF files are **not bundled or committed** without verified redistributable licensing, and appearance still depends on installed fonts/platform fallback. Font/glyph fidelity remains open.
- The latest `bitchord-sans` code change is commit `c9cfdb1e`. Its [GitHub Actions workflow 43169153250](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43169153250) had not yet completed when this note was written. Recheck actual terminal CI status before stating a final build result.

Manual visual review is still required for typography, tab transitions, scaling, glass/Haze, OS chrome, DPI/focus/accessibility and matching reference screenshots. Phase 2 has not been implemented by this documentation change. Consult `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md` and `.codex/phases/phase-2.md` before beginning authorized Phase 2 work.

## Phase 1 acceptance update — 2026-11-12

The user has **manually accepted the Phase 1 foundation and shell** and confirmed the current UI is satisfactory for moving forward. This supersedes earlier README statements that Phase 1 is awaiting manual review. The successful hosted Windows/Linux validation baseline is [run 43163359448](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43163359448); the later font-specific [run 43169153250](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43169153250) was still in progress when acceptance was recorded.

Phase 1 complete **within its shell/foundation scope**. Full Kotlin feature parity, native audio, exact visual effects, DPI/accessibility matrix and font redistribution verification are still open work in later phases. The sidebar spacing comparison must use matched UI scale: Kotlin 110% versus Flutter 100% was not a valid defect. `bitchord-sans` cuts are used by name when installed but are not packaged without license review. **Phase 2 is ready to start on explicit instruction; it has not started as part of this documentation update.**

## Phase 2 presentation work

Phase 2 is authorized; current evidence and unresolved gates are recorded in
[`verification/phase2/README.md`](verification/phase2/README.md). Reproduce the
source inventory and shell translation pipeline with
`python3 tool/phase2_reference.py --check` (the pinned reference Git object must
exist). UI zoom matches the source 80/90/100/110/125% choices and remains separate
from display DPI. No fonts are redistributed, and no live music service or native
audio implementation is added. Full phase acceptance remains incomplete pending
the evidence ledger; Phase 3 does not start automatically.

## Phase 2 manually tested and accepted — 2026-11-12

The user explicitly reported **testing all items in the Phase 2 manual audit checklist and finding everything OK**. Mark the currently delivered Phase 2 shell/design-system/navigation/scaling/Settings scope **USER_ACCEPTED (manual checklist passed as reported by user)**. This is a stronger statement than a general visual approval; it should not be conflated with independent instrumented pixel-level or physical-platform verification.

Windows/Linux automated tests and native smoke were successful in [run 43179670749](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43179670749), with 26 passing tests per OS. Full media functionality and exact cross-platform Haze/Mica/DPI/accessibility/font-licensing verification remain future work.

**Phase 3 explicitly authorized**: start `../.codex/phases/phase-3.md` for local domain, persistence and C++17 native PCM foundation. No Phase 3 code was implemented by this documentation update. Phase 4 must not start automatically.

## Phase 3 local PCM foundation

Real native C++17/FFmpeg decode and WASAPI/libpulse/ALSA output now live in
`native/audio/`. Bootstrap loads the packaged native library in its own Dart
service isolate and closes it after native quiescence. SQLite local state is
separate from Kotlin Preferences. The normal shell remains disconnected from
catalog/player surfaces; local output can be exercised with the real packaged
`bitchord_audio_probe <file>` or `bitchord_desktop --local-file=/absolute/file.wav`.
No playback is simulated. See [the Phase 3 ledger](verification/phase3/README.md)
for reproducible commands, native dependency licenses, imported-state subset
and physical/device/precision blockers. **Phase 3 is INCOMPLETE** pending its
platform/physical acceptance gates. Do not start Phase 4.

## Phase 3 physical Linux audio handoff; Phase 4 go-ahead — 2026-11-12

The user manually tested all seven supplied codec test files on Linux and reported **no errors**. A real full-length FLAC sounded normal until the existing 30-second probe limit; `phase=2, error=0, exit=2` is the probe's pre-EOF timeout result, not a proven playback crash. The user approves moving on and requests the longer-track probe/timeout polish be retained as an explicit Phase 4 task.

Phase 3 native foundation remains CI-verified by [43197210264](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43197210264) and **user-accepted for the scoped Linux audible codec path**. Windows physical speakers, calibrated latency, hotplug and soak tests remain OPEN; do not claim full audio parity or independent confirmation of the user's physical observations. **Phase 4 is explicitly authorized** and covers online providers, accounts, streaming and downloads. Phase 5 is not authorized.
