# Phase 5 advanced listening evidence

**Phase 5 INCOMPLETE. This continues the existing implementation; it does not
complete the authorized phase.** The roadmap and acceptance gates are unchanged. No Phase 6 work or
pinned Kotlin/reference modification is included. Delivery is on
`codex/phase5-advanced-listening`, PR base `2.0.0-flutter`, without auto-merge.

## Continuation results (2026-11-14)

Preserved the starting uncommitted DSP implementation and its 28 cases. Added
`src/listening.h`, `src/sleep.h` and native engine integration rather than
replacing the engine. All references remain pinned to `348b613a`.

| IDs / reference | Delivered continuation | Evidence / limits |
|---|---|---|
| P11/P12/P14; D DesktopAudioEngine.renderProcessed/play, DesktopAudioSpeed, DesktopPlayhead, DesktopAudioProcessors | Native bounded silence -> WSOLA stage, retained surplus/partial writes, source-time jumps/slopes on submitted-frame segments, seek/device/select resets, EOF drain; separate `BC_LISTEN` leaves legacy `BC_RATE` unchanged | FIXTURE_TESTED: ten additional composed PCM/clock cases, exact frame counts, declared PCM 2e-6 and source-clock 2-us tolerances; PLATFORM_VERIFIED locally for Linux Pulse only |
| Advanced P10–P13; D DesktopSleepTimer, DesktopAudioEngine, DesktopApp, DesktopSongMenuUi | Native elapsed deadline/cancel/after-track, nomination suppression, flush at deadline, resume from presented source time, no catalog EOF auto-advance after sleep; command-ID-fenced expiry events | Native collecting-sink test includes timer-without-source, paused expiry, cancel, full-track stop, suppressed prepared handoff and restart; real Linux Pulse after-track checks at four speeds. Windows/physical tests pending |
| P14 / related P31 | Durable speed/silence settings with old-document defaults; reference speed cycle; native-capability-gated controls and 15/30/45/60, custom 5..720-minute, after-track/cancel sleep controls | Typed ports/controllers; poll disposal/stale poll, settings, validation and accessibility regression tests. Fixed the earlier fader/balance semantics assertion. New surfaces are not claimed visually accepted |

Actual continuation checks:

- Release native CMake/CTest: **5/5 passed**. Existing decoder/ABI/store/DSP
  tests retained; added real-decoder listener/sleep test with zero/73-frame
  partial writes at .25/.75/1/1.25/3 speed. Existing DSP test retains 17-frame
  partial writes. Each speed's PCM is identical between full and partial writes.
- Kotlin oracle was executed again with six unchanged source files, including
  DesktopPlayhead. **38 PCM cases**, **50 content hashes** (38 PCM, ten clock,
  two skipped-count files); exact PCM counts and observed maximum absolute PCM
  difference **0** locally. **0** observed post-preparation processing
  allocations in that corpus. Clock tolerance was declared before comparison.
- All five tests passed with address/undefined/leak instrumentation after
  approving process inspection outside the sandbox. The expanded clock corpus
  also passed; no sanitizer was disabled.
- Scoped fatal analyzer, layer/asset checks, reference tokens, Phase 2 inventory,
  Phase 5 content hashes and Python compilation passed.
- Full Flutter suite: **94 tests passed**, including the source-matched speed
  cycle and sleep UI/controller regressions. An initially ambiguous test finder
  was corrected to select the labelled toggle rather than its adjacent text.
- ffigen **20.1.1** dependency download and generation succeeded with approval.
  The generated diff adds constants only; ABI 1 functions/layouts are unchanged.
- Phase 3 FFI/store/import/450 queue transitions passed. Phase 4 HTTP/provider/
  download/broker checks passed, including **384000 exact PCM frames** and zero
  remaining native decoders; both 49-assertion YouTube contract sets passed.
- Linux debug bundle built. Local isolated GUI smoke could not start because
  `xvfb-run`/Openbox are absent; this is a test-environment prerequisite, not a
  build failure. Hosted Windows/Linux release smokes remain required.
- The restricted FFI trial returned NO_DEVICE 105. **The approved unrestricted
  trial succeeded through actual libpulse (backend 1): 24000 submitted/played
  baseline frames**. Additional real sink trials: .25x **85697**, .75x **30507**,
  1.25x **20103**, 3x **10472** presented frames, all ending at source position
  **500000 us** and stopping via native after-track sleep. Sleep arm/cancel and
  SQLite reopen also passed. These are observed short-fixture sink outcomes,
  not ideal-duration formulas, physical speaker/loopback or listening claims.

Reproduction additions from `flutterDesktop/` (toolchain on PATH):

```sh
cmake -S native/audio -B build/native-asan -DCMAKE_BUILD_TYPE=Debug -DCMAKE_CXX_COMPILER=clang++ -DBC_SANITIZE=ON
cmake --build build/native-asan --parallel 2
ctest --test-dir build/native-asan --output-on-failure
cd tool/ffi
dart pub get --enforce-lockfile
dart run ffigen --config ffigen.yaml
```

The earlier widget/socket, LeakSanitizer and ffigen restrictions below describe
the previous attempt. Approved reruns supersede those blockers. Native output
also required approved access to the host audio server. These fixes do **not**
complete the still-missing full planner/Automix, ONNX queue/model/cache pipeline,
Loop & Mix renderer/session/lyric exclusions or waveform/diagnostic surfaces.
Six real transitions, interruption/tempo-ease/device/repeat/queue-change cases,
callback deadline/underrun telemetry, bounded model/decoder memory and physical
Windows/Linux listening remain required. These are implementation/acceptance
gaps, not permission errors. No whole P-ID is PARITY_ACCEPTED.

## Delivery and exact-source CI

Git staging succeeded with explicit approval for the linked worktree's parent
Git index. Final local source passed 94 Flutter tests, 5/5 release CTests and
5/5 address/undefined/leak-instrumented CTests (21.06 seconds), and the Linux
debug rebuild passed. Publication and new-source CI inspection follow; the old
baseline is never substituted for a Phase 5 result. No release or merge is
authorized. The PR must remain a draft while implementation/acceptance is open.

## Prerequisite audit (2026-11-14)

Starting branch was clean at merged source `0475403f31cc6c217f95bdc52707d7c370b1ea24`.
This includes PR #140 auth persistence, direct fast playback and history tracking,
and PR #139 presentation changes. Connected GitHub confirmed baseline matrix
[43599294138](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43599294138)
completed successfully on that exact SHA. The older Phase 4 report's
`43595419822` is historical rather than the latest merged-source result.

The Phase 3 native engine and Phase 4 playable-source paths exist and permit
independent DSP work. Full Phase 4 acceptance, authenticated/physical Windows
and Linux playback, native visual equivalence and the intermittent Linux close
SIGSEGV remain open. A subsequent successful baseline run does not establish a
fix for intermittent shutdown. The report's latency numbers are not independent
first-audible or performance measurements collected in this task.

## Source-to-implementation ledger

All reference paths below are at
`348b613a0c3db1a54de56483ea6f8748d6465211`.
`D` = `desktopApp/src/main/kotlin/com/music/bitchord/desktop/`;
`S` = `shared/src/commonMain/kotlin/com/music/bitchord/playback/`.

| IDs | Reference | New implementation/evidence | Current status and limit |
|---|---|---|---|
| P14 | D `DesktopEqualizer.kt`, S `EqualizerCurve.kt` | `native/audio/src/dsp.{h,cpp}`: ten topology-preserving sections, native manual/tone curve/preamp, logarithmic-Q and gain/balance glides, denormal cleanup | FIXTURE_TESTED locally; engine/UI connected, platform result pending |
| P14 | D `DesktopAudioProcessors.kt` spatial processor | Same native DSP file; stereo-only width 2.5, output .82, crossfeed .2, lowpass .3, 15-ms delay and reset | FIXTURE_TESTED; no change to mono/multichannel bypass |
| P14/P12 | D `DesktopAudioProcessors.kt` silence skipper | Bounded preparation processor, exact threshold/retained edges/skipped counter and reset | FIXTURE_TESTED algorithm only; **not integrated or advertised** until source-time segments/EOF behavior are implemented |
| P14/P12 | D `DesktopAudioSpeed.kt`, `DesktopTempoBuffer.kt` | Bounded WSOLA processor with correlation continuity, return-to-1x and drain | FIXTURE_TESTED speed algorithm only; tempo FIFO, played-frame mapping, per-deck adoption/ease and quality rate converter still missing; BC_RATE keeps its existing resampling meaning |
| P15 | D `DesktopTransitionEcho.kt` | Bounded native echo ring with original feedback/wet constants | FIXTURE_TESTED primitive only; no planned/real transitions claimed |
| P11/P12/P14 | D `DesktopPcmWrite.kt`, `DesktopAudioEngine.kt` | `src/engine.cpp` retains statefully processed suffix across zero/partial sink writes; clears pending PCM/filter history on timeline reset; atomic generation-fenced BC_DSP | FIXTURE_TESTED through real decoder/engine and collecting PCM sink; collecting sink is not an OS/hardware output test |
| P14/related P31 | D `DesktopEqualizerSettings.kt`, `DesktopEqualizerUi.kt`, S `EqualizerPreset.kt` | Typed settings; SQLite CAS persistence; bounded Riverpod updates; 560-dp dialog, 200-dp 11x11 tone pad, 30x150-dp drawn signed faders, 16 presets, balance and stereo widening | IMPLEMENTED; analyzer checked, widget tests added; native visual/motion/DPI parity not verified |

EQ/spatial controls require the actual ABI capability bit. No Automix, ONNX,
silence skipping or pitch-preserving speed toggle is offered on the strength of
standalone processors. Coefficients/planning/PCM never run in Dart widgets.
Playback selects paused, applies saved tuning/volume, then plays, preventing a
fresh source from briefly starting with old settings. Older native libraries
without the additive capability retain the previous start sequence.

## Differential corpus and actual local checks

`tool/phase5_reference.py` extracts five **unchanged** Kotlin source files using
`git show` and compiles/runs `tool/phase5/Oracle.kt` with an installed Kotlin
compiler. No Kotlin application/dependency build or source rewrite is involved.
The checked corpus has 28 PCM cases plus two skipped-frame counters.
`test/fixtures/phase5/manifest.json` pins reference, source, driver and output
SHA-256 values. It predeclares absolute EQ tolerance **1e-4**, other PCM **2e-6**,
and exact sample/skipped-frame counts. These are pure DSP tolerances, not
physical device timing tolerances or acceptance of the whole feature family.

Local environment: Linux x64, Clang 23.1.4, CMake 4.5.0 from the existing
temporary tool directory, FFmpeg libavformat 63.1.102/libavutil 61.1.102,
libpulse 17.0, ALSA 1.2.16.1, SQLite 3.54.0. Kotlin compiler 2.3.20 ran the
oracle with OpenJDK 27.0.1.0.3. Cached Flutter SDK reports Dart 3.13.7.

- Release CMake build and CTest: **4/4 passed** (`native_dsp_reference`,
  `native_dsp_engine`, existing decoder and ABI/store tests).
- 28 Kotlin cases: exact sample counts; maximum observed absolute difference
  **0** on this compiler. Rates 8/44.1/48/96/192 kHz; EQ mono/stereo/6-channel;
  tone retune, balance, spatial bypass, silence edges and speed .25/.75/1/1.25/3
  with return-to-1x/drain. Processing allocation instrumentation: **0** observed
  allocations after preparation in these scenarios, not an engine-wide audit.
- Real FFmpeg -> production engine/DSP -> collecting sink: **24000 frames**,
  zero/17-frame partial writes versus full writes; observed max difference 0.
  Decoder release and invalid/stale settings admission checks passed.
- `dart run tool/phase5_check.dart <library> <WAV>`: real service isolate, FFI
  capability, durable settings/reopen and decoder lifetime passed; actual sink
  returned **NO_DEVICE 105 / zero submitted and played frames**. This is a
  blocked output gate, not a successful sound/listening test.
- `flutter analyze --no-pub --fatal-infos lib test tool`: clean after corrections.
  This explicit scope avoids analyzing the cached SDK itself under `build/`.
- Layer/asset checks, 30 corpus hashes and Python compilation passed.
- `flutter build linux --debug --no-pub` passed after adding the existing
  temporary CMake/Ninja directory and its library directory to the command's
  environment. The initial attempt without that PATH failed to find CMake.
  This is compilation/package evidence, not a GUI/listening or release pass.
- Local Flutter widget test launch is **environment-blocked** by localhost socket
  bind `EPERM` before any assertion executes. The six new tests cover settings
  validation, legacy persistence, update coalescing/failure, capability hiding,
  disabled input, tone pointer/keyboard and presets; they are UI/controller tests,
  not audio proof. Existing Phase 4 tests are retained.
- Address/undefined/leak-instrumented build succeeded. All four test processes
  failed at LeakSanitizer's prohibited process/ptrace inspection. No sanitizer
  pass is claimed, no sanitizer/test was disabled; hosted Linux reruns it.
- The ffigen generator's offline dependency resolution is unavailable locally.
  The ABI adds only a command and capability; function signatures and struct
  sizes remain unchanged. Existing generated layout bindings are unchanged.

Reproduction from `flutterDesktop/`:

```sh
cmake -S native/audio -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel 2
ctest --test-dir build/native --output-on-failure
python tool/phase5_reference.py --check
dart run tool/phase5_check.dart build/native/libbitchord_audio.so native/audio/test/fixtures/tone.wav
flutter analyze --fatal-infos
flutter test
```

Hosted `phase3_platform.py` also invokes the Phase 5 FFI trial while its isolated
real Pulse software sink is alive and requires all 24000 frames presented. This
still cannot establish physical speakers/listening. Windows no-endpoint status
remains explicit. Matrix/native/sanitizer/widget/release results for this source
must be inspected after publication; baseline success is not this increment's CI.

## Unfinished authorized Phase 5 work (unchanged roadmap)

| Requirement | Evidence still required |
|---|---|
| P10–P13 advanced transport, P15 planner/overlap | Port full `TransitionPlanner.kt`, `TransitionPolicy.kt`, `BeatAwareTransition.kt`, ride/filter/tempo/loop session logic; exact seam split, midpoint incoming identity/time, suppression, queue edits, repeat, seek/cancel/device/rate/stale-generation cases |
| P16 ONNX/analysis | Native C API runtime distribution, pinned model/source/license provenance, exact beat/vocal resampling/tensors/window stitching/postprocessing, confidence/fallback, bounded cancellable work/session/cache ownership and allocation telemetry |
| P15/P16 loopmix and integration | Legally attributed reference native renderer/Signalsmith reuse, prepared loop regions/lyric exclusions, real one-clock two-deck output; native waveform/plan/diagnostic projections and reference UI |
| Sleep behavior | Native monotonic deadline/after-track semantics and queue/prefetch/Automix suppression with Flutter controls; not a Dart audible-transition scheduler |
| Corpus and acceptance | Feature -> full plan -> PCM differential corpus, six real transitions and interruption scenarios on both OSs, listener/loopback evidence, callback deadline/underrun traces, bounded model/decoder memory and regression-free normal playback |

These are implementation gaps, **not invented environment failures**. The actual
local audio/widget/sanitizer restrictions are listed separately above. No whole
P-ID is PARITY_ACCEPTED, no RAM reduction is claimed, and no Phase 5 COMPLETE or
Phase 6 authorization is inferred from this increment.

## Historical delivery restriction in the previous attempt

`git add` failed creating the linked worktree's index lock because Git metadata
is mounted read-only. Both connected GitHub `create_branch` and `create_blob`
were rejected with: "MCP tool call requires approval, but approval policy is
never". No remote branch/commit, push or PR was created. No Phase 5 CI run can
be attributed to this session. Baseline reads succeeded; write capability did
not. A binary-safe patch is prepared outside the tracked tree for handoff.
The source remains uncommitted in this worktree; no release or merge occurred.
