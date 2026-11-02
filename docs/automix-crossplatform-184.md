# AutoMix for Windows and Linux — integration/v1.8.4

Base inspected: `700ef284b86e5383b3c2a0c932611b4da6c75142`.
Working branch: `fix/automix-beat-aware-crossplatform-184`.
Validation date: 2026-11-02. This is an implementation report, not a release certification.

## Architecture and changes

Both desktop platforms use `DesktopPlaybackEngine`, FFmpeg decoding, the shared
musical planner, and the same pitch-preserving tempo FIFO and float PCM mixer.
Windows routes output through WASAPI (with Java Sound fallback); Linux uses Java
Sound and its selected system audio device. There are no separate musical algorithms.

The base already had musical analysis, cue ranking, tempo compatibility, filtering,
vocal protection, duration planning, and instrumental loop mixing. This change
repairs execution and planning gaps rather than replacing that architecture:

- Execute the seam at the correct frame within a decoder block, using submitted
  source time instead of the heard clock and its throttled 250 ms UI updates.
- Seek the incoming decoder up to three seconds before the seam and freeze the
  pair's prepared plan. A manual skip cannot reuse a decoder at an AutoMix cue.
- Align measured outgoing and incoming downbeats by a bounded incoming cue shift,
  respecting the incoming skip limit, remaining content and measured vocal clashes.
  Static bar alignment does not enable live, audible phase-correction speed pulses.
- Preserve the adaptive beat-based duration: the matched-pair path previously
  overrode it with a 12-second lower bound. Compatible faster incoming songs may
  now blend with a fixed slower incoming rate; the outgoing deck stays at its rate.
- Use explicit ordinary-fade fallback for missing downbeats, late entry, or an
  incoming underrun. FIFO samples survive a temporary underrun, and incomplete
  incoming blocks do not consume the fade as if they were complete.
- Reserve overlap headroom for correlated masters in AutoMix, while retaining the
  independent ordinary Crossfade gain curve. Drain held tempo samples at EOF.
- Count only frames actually accepted by the output device and retry partial
  writes. A rejected write stops with an output error rather than advancing time.
- Cancel an outgoing overlap on seek, clear prepared cues after relevant settings
  changes, and settle the transition before output reconfiguration. Pause retains
  the frame-based schedule. A rejected asynchronous seek becomes a decoder failure.

## Windows

**Previous behavior and confirmed issues.** The musical planner and PCM mixer were
already shared with Linux, so delayed seam scheduling, the duration override,
missing static bar alignment and incomplete-write accounting affected Windows too.
WASAPI's native writer explicitly permits partial returns on endpoint errors; the
Kotlin caller previously counted the entire requested block. This was confirmed by
source inspection, not reproduced on Windows hardware here.

PR #97 is absent from the inspected integration base. Its changes were read from
`refs/pull/97/head` (`d282b9fc936e0a6bc8dcf43cea7e591478ad80ca`), and the
MinGW static-runtime link options and packaging dependency audit were carried
forward. This preserves the corrected DLL behavior the user reported. If #97
merges independently, overlapping native/CI changes need reconciliation.

**Fixes.** Windows receives every musical, scheduling, FIFO, gain, seek and output
accounting change above. EXE/MSI/portable workflows now audit DLL imports, load the
analyser, execute a real JNI BPM/downbeat/resampling probe, check packaged models,
and repeat JNI execution against the packaged DLL bytes. The standalone regression
matrix runs on Windows, and the packaging matrix builds EXE, MSI and portable ZIP.

**Build/test results.** No Windows runtime, MinGW cross-compiler, Wine or PowerShell
was available locally. However, the actual [Windows engine/JNI CI job](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/41436731583/job/125248242192)
for commit `337653d8` succeeded. It compiled the selected production planner/PCM
sources, ran their focused regressions, built the native analyser with MinGW,
audited DLL imports, loaded the DLL on Windows, and ran the real JNI BPM/downbeat/
resampling probe.

The [Windows EXE/MSI/portable CI job](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/41436731583/job/125248242726)
for the same commit succeeded in 10m44s. It ran the full shared/UI/desktop tests,
compiled the application, built the portable tree/ZIP and EXE/MSI installers,
validated packaged DLL/models with real JNI calls, and passed the existing real
artifact metadata/updater-helper checks. A 326 MB portable ZIP artifact was uploaded.
The installer files were built and audited; that workflow uploads only the portable
ZIP, not the EXE/MSI files. It explicitly does not exercise an actual installer upgrade.

**Remaining limitations.** WASAPI endpoint changes, hardware clock behavior,
audible continuity, physical playback, listening quality, and actual installer/portable
GUI operation still require device testing. Passing collecting-sink tests does not
establish physical audio output quality.
The Windows evidence comes from its own CI runner, not the Linux JVM results.

## Linux

**Previous behavior and confirmed issues.** Java Sound feeds the same decoder,
tempo FIFO and mixer. Its approximately 200 ms output queue made a transition
scheduled from the heard clock late at the render boundary. The shared planner's
duration override and speed-direction restriction were independently covered by
regressions. FFmpeg seeks run asynchronously in the decode worker, so cueing only
when the fade began gave it no preparation runway; a false seek result was logged
but otherwise allowed playback from an incorrect position.

**Fixes.** Linux receives all common changes above. No PulseAudio/PipeWire routing
policy is changed: scheduling is independent of device queue latency, and existing
default-device following is retained. The packaged `.so` is extracted and loaded
through JNI with its models checked, both before AppImage creation and from the
extracted AppImage tree in the existing AppImage workflow.

**Build/test results observed locally.**

- `./gradlew -p tools/automix-validation --no-daemon --max-workers=3 test`:
  **72 executed, 72 passed, 0 failed, 0 skipped**. It compiles actual selected
  production sources and runs musical planner, loop planner, render-clock,
  mixed-block FIFO, gain/filter/echo and partial-write regressions. It does not
  compile the full application or execute the full playback engine.
- The [Linux engine/JNI CI job](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/41436731583/job/125248243051)
  for commit `337653d8` also succeeded, including its real native probe.
- Linux native analyser: CMake/GNU C++ build passed. The real JNI library loaded;
  a synthetic 120 BPM accented track measured **120.003 BPM**, produced ordered
  downbeats, and passed resampling length/finite-PCM checks.
- Full shared/UI/desktop tests and `:desktopApp:createDistributable`: attempted,
  **blocked before compilation/test execution** by HTTP 403 from `jitpack.io`
  for InnerTubeX and NewPipeExtractor. The new real-engine seek/pause/handoff
  regressions and rejected-seek worker regression therefore remain unrun locally.
- Changed workflow YAML parses; Linux checker shell syntax and `git diff --check`
  pass. These do not establish installer or application readiness.

**Remote full-build results.** The [Linux AppImage CI job](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/41436731583/job/125248242756)
for commit `337653d8` succeeded in 8m14s. This ran the full shared/UI/desktop tests
(including the real-engine lifecycle regressions), compiled the application,
built the distributable and AppImage, checked native/models resources, executed
JNI against the packaged `.so` and extracted AppImage tree, and completed the
workflow's AppImage relaunch/recovery checks. A 329 MB AppImage artifact was produced.
This remote success does not remove the local JitPack access blocker.

**Remaining limitations.** Physical Java Sound playback, PipeWire/PulseAudio device
changes and perceptual listening quality remain unverified. There is no desktop audio/display
session available for a physical listening test.

## Behavior comparison and acceptance matrix

| Capability | Windows implementation | Linux implementation | Observed evidence |
| --- | --- | --- | --- |
| Musical strategies, BPM and cue policy | Shared planner | Same planner | Focused JVM regressions pass locally and in both OS CI jobs |
| Dynamic duration and incoming tempo | Adaptive beats, fixed incoming stretch | Identical | Slow/fast and tempo-direction regressions pass |
| Downbeat scheduling | Static cue alignment, submitted-frame seam | Identical | Offset-grid/rate/vocal-safe/frame-boundary tests pass |
| PCM overlap and headroom | Same float mixer and FIFO | Same float mixer and FIFO | FIFO/envelope/partial-write regressions pass |
| Seek/skip/pause/queue lifecycle | Shared engine cancellation/preparation | Identical | Full engine tests pass in both Windows and Linux package CI |
| Fallback and normal Crossfade | Explicit fade fallback; separate gain curve | Identical | Pure fallback and ordinary-plan regressions pass |
| Native analyser | Static MinGW runtime, DLL import/load audit | `.so` dependency/load audit | Real JNI passed locally on Linux and in both OS CI jobs |
| Output integration | WASAPI, Java Sound fallback | Java Sound/system routing | Hardware behavior untested on both |
| Packages | EXE, MSI, portable ZIP | AppImage | Both package CI jobs passed; actual Windows installer upgrade remains untested |

The intended musical behavior is identical; the OS-specific boundary is output
and native packaging. The focused musical/PCM regressions and JNI probe have passed on each OS.
Full-engine tests and both packaging pipelines also passed in
[CI run 41436731583](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/41436731583),
which finished successfully in 10m48s. Physical audio/listening checks remain open
on both platforms, and actual Windows installer upgrades remain untested.

## Reproduction and remaining actions

Use Java 21 and the repository wrapper. The focused suite has no JitPack dependency:

```bash
./gradlew -p tools/automix-validation --no-daemon test
```

Then run the full tests and distributable build:

```bash
./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopApp:test :desktopApp:createDistributable
```

Run `windows-automix-check.ps1` or `bash desktopApp/packaging/tests/linux-automix-check.sh`
against the built package. The Windows checker supports `-NativeOnly` for a standalone
native build. The shared Java JNI probe is under `desktopApp/packaging/tests/native/`.

The cloud configuration draft includes `jitpack.io` for dependency resolution and
`api.github.com` for PR creation/status. Those settings must be applied before the
blocked local operations can be retried. Saving that draft does not apply it or
prove the full build works. Review CI artifacts and complete both platforms'
device/listening and package checks before merging or releasing.


## Delivery status

Implementation commit `337653d87aa4577d9498a730aff3de1b9002fb18` was pushed to
`fix/automix-beat-aware-crossplatform-184`. Its cross-platform workflow passed all
four jobs. PR creation targeting `integration/v1.8.4` was attempted with `gh pr create`
but the environment rejected `https://api.github.com/graphql` with HTTP 403. No PR
was created by that attempt. Apply the saved API-domain requirement, then retry
creation; do not interpret the pushed branch or successful CI run as a submitted PR.
