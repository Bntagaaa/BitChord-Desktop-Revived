# BitChord Desktop startup optimization

## Reference baseline

Baseline commit `acf101ac9390f42c4a219e2d0e96e2b54400f67a`. Ten new processes launched the same release-mode jpackage image with a new guest profile per run and a warm filesystem cache. The system was CachyOS with KDE Plasma on Wayland, Intel Core i3-1005G1, 8 GB RAM, Eclipse Temurin 21.0.13.1, and a shared desktop workload. Measurements continued for 23 seconds per launch. The late RSS sample is a fixed-time startup observation, not a long-running idle measurement.

| Metric | Baseline median | Baseline P95 | Candidate on reference hardware |
| --- | ---: | ---: | --- |
| Kotlin timer to original window-created callback | 4.58 s | 5.48 s | Replaced by separate peer and visibility events |
| External launch to shared updater readiness | 10.00 s | 11.75 s | Unverified |
| JVM CPU time at readiness | 25.26 core-s | 28.73 core-s | Unverified |
| Peak sampled JVM RSS through readiness | 594.7 MiB | 632.8 MiB | Unverified |
| JVM RSS at the end of the observation | 710.7 MiB | 727.6 MiB | Unverified |

The first readiness run was interrupted during a wider host slowdown and was outside this completed ten-run set. A candidate smoke run overlapped with local Gradle compilation and was also interrupted. Its draw, visibility, and restoration milestones fired, but it is excluded from the paired comparison. All subsequent builds and measurements run in GitHub Actions at the user's request.

The existing updater marker is a common measurement. It acknowledges a visible, non-minimized Compose window after two frame callbacks. The candidate also records the real shell draw and a later AWT queue turn. The latter is a readiness proxy. Direct input-to-response measurement requires an additional input driver.

[Reference metrics, milestone logs, and class-load excerpt](docs/performance/verification/startup/reference-linux-baseline/)

## Hosted Linux comparison

Ten launches per build used the same Ubuntu 22.04 runner with four AMD EPYC 7763 vCPUs, Temurin 21.0.13+9, an Xvfb display with software rendering, and an isolated fresh guest profile for each launch. The candidate was the PR merge at `bb69a8d91b6d412ea153ce56b63595a3db4a3254`. The launcher and runtime format matched across builds.

| Metric | Baseline median / P95 | Candidate median / P95 | Median change |
| --- | ---: | ---: | ---: |
| External launch to shared updater readiness | 4.75 / 6.46 s | 3.35 / 3.48 s | 29.4% faster |
| CPU consumed by readiness | 13.43 / 14.99 core-s | 7.78 / 8.18 core-s | 42.1% lower |
| Peak sampled RSS through readiness | 473.4 / 554.0 MiB | 368.4 / 377.3 MiB | 22.2% lower |
| RSS at 20 seconds | 749.5 / 768.9 MiB | 756.9 / 789.4 MiB | 1.0% higher |

The candidate recorded first shell draw at 2.61 s median, window showing at 2.69 s, and the post-draw AWT queue turn at 2.69 s. It restored the playback queue and history at 2.82 s and drew fresh Home content at 4.41 s. The baseline lacked equivalent draw milestones, so those events have no valid before-and-after percentage. The candidate's readiness P95 improved 46.1%. The fixed 20-second RSS reading increased about 7.5 MiB and does not establish stabilized idle behavior.

[Hosted raw logs and samples](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/45342349798) · [Machine-readable online results](docs/performance/verification/startup/hosted-linux-online/)

## Observed startup path

The jpackage launcher loads the bundled HotSpot runtime in process. For AppImage, the type-2 runtime first mounts or extracts the filesystem and invokes `AppRun`, which execs that launcher. Windows uses its jpackage native executable. The release build used Temurin 21, which is also the toolchain configured by the production packaging workflows.

Originally Kotlin main created the cookie jar, configured the full shared resolver and its timed warm-up, installed Presence, and constructed playback with synchronous queue and history restoration. Compose then initialized AWT, Skiko, the window, and the full app shell. In parallel, the transport host registered D-Bus or Windows media integration. The initial composition decoded account and library records, started Home requests and download verification, and installed the native analyzer loader.

One additional release launch with HotSpot class/JIT logging showed the following phase boundaries. Class loading identifies activity and ordering. The timestamps alone do not establish exclusive wall time or CPU cost for a class.

| Event | JVM uptime |
| --- | ---: |
| Main class loaded | 105 ms |
| JavaFX WebKit cookie manager class loaded | 493 ms |
| InnerTubeX resolver class loaded | 578 ms |
| Playback engine class loaded | 1,322 ms |
| X11 AWT toolkit class loaded | 1,431 ms |
| ComposeWindow class loaded | 2,318 ms |
| Platform media D-Bus class loaded | 2,377 ms |
| Skiko layer class loaded | 2,628 ms |
| Main app composition class loaded | 4,052 ms |
| JavaFX platform implementation class loaded | 4,435 ms |
| BotGuard WebView class loaded | 6,449 ms |
| Skia Surface class loaded | 7,870 ms |

The same instrumented run logged about 9,951 class loads and 4,535 JIT compilation records by 5.2 seconds. It reached shared updater readiness at 10.00 seconds. The JavaFX warm-up, platform integrations, and full UI therefore used the same CPU and I/O during the window's critical path. Preference deserialization is an additional variable for populated profiles. The guest baseline could not isolate the cost of a large library or expired credential.

## Startup stages

- **Bootstrap.** `Main.kt` installs platform adapters, the bounded Coil factory, the process cookie handler, and the shutdown hook. It reads the installation's onboarding marker, theme and essential settings. The initial system-theme probe runs before Compose rendering. The JavaFX cookie jar is allocated on first use.
- **Interactive shell.** `DesktopStartup.kt` observes the existing window and the Compose draw modifier on the regular shell. It releases deferred work after a native showing event and a shell draw with a subsequent AWT event-queue turn. This preserves the existing sidebar, navigation, player, title bar and onboarding screen.
- **Restoration.** `DesktopPlaybackService.kt` loads the existing queue and history on I/O, publishes them on Swing, and drains pending transport and queue actions in order. The player can render the restored track while paused and Play then uses the existing decoder path. `DesktopApp.kt` restores local playlists, downloads and ratings on I/O. Actions that change these records wait for restored state. Download verification removes only the original records it actually checked. Account restoration is shared by Home, library and search callers.
- **Network and integrations.** Home reads one bounded, versioned first-page cache by account and profile before asynchronous refresh. A failed refresh can leave cached content visible. The cache is non-authoritative, writes by atomic replacement, and holds neither credentials nor continuation tokens. Page eviction and Coil limits are unchanged. Tray, media registration and Presence start after the shell and retain process ownership. The YouTube stream resolver still warms up to protect first playback, with an on-demand path for earlier requests.
- **Native audio and analysis.** The audio pump starts on the first stream load or advance preparation. Analyzer installation happens when Automix analysis needs it. The output sink, FFmpeg decoder and video pipeline keep their existing open and ownership rules.

The baseline persisted the queue and its selected index. It did not persist the playhead across a full process restart. Existing seek, pause, minimization and tray playback keep their process-owned state.

## JVM and launcher findings

The slim packaged image has no default CDS archive. A local Temurin 21 compatibility probe showed that combining `jlink --strip-native-commands` with `--generate-cds-archive` fails because the stripped image has no `bin/java`. Temporarily restoring the same JDK's launch binary to a copied Linux runtime let `-Xshare:dump` create a matching base archive, which `-Xshare:on` subsequently loaded. This verifies archive compatibility with that copied runtime. It provides no launch-benefit measurement or Windows deployment evidence. Production GC, JIT, heap limits, and packaging runtime options remain unchanged. AppCDS needs matching runtime and application inputs; this PR does not ship an unmeasured archive. [OpenJDK JEP 350](https://openjdk.org/jeps/350)

Startup diagnostics can read an external launch timestamp on Linux and Windows. Linux AppImage additionally supplies a timestamp from `AppRun` when diagnostics are enabled. This separates outer AppImage startup from the JVM path in a benchmark. JVM start comes from its runtime management clock, and Kotlin and post-entry milestones use a monotonic clock. Backend availability, peer creation, showing, and app draw have separate diagnostic events. The Skiko fallback sample is taken at the first shell draw when the backend exposes no earlier change callback.

## Validation

- The reference Linux release image and AppImage were constructed locally before the switch to hosted-only builds. The candidate reference GUI smoke reached the shell, account and playback milestones while compilation was active. It does not establish performance on the reference system.
- [Hosted comparison and raw artifacts](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/workflows/desktop-startup-benchmark.yml) build both versions on the same Linux runner and exercise ten real Compose windows per build under Xvfb. These results characterize the hosted runner and its renderer.
- The first candidate passed the standard Linux, Windows, Android, and backend suites and both updater jobs. GitHub Actions produced the [Windows setup EXE, MSI and portable ZIP](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/45342341790) and a [tested Linux AppImage](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/45346225142).
- The separate [AutoMix cross-platform workflow](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/45342514160) failed its pinned upstream hash check for `AnalysisStore.kt` on both operating systems. The checked file and verification script are byte-for-byte unchanged from `1.10`. Its Windows and Linux packaging jobs passed.
- [PR test and packaging results](https://github.com/Bntagaaa/BitChord-Desktop-Revived/pull/180/checks) cover the repository suites and updater reliability. The added startup tests cover shell dependency order, shared lazy initialization, cancellation, failure isolation, deferred playback actions, legacy queue restoration, and account/profile cache isolation and corruption.
- Cold filesystem cache, authenticated and expired-account workloads, acoustic time to first audio, Windows GUI latency, and extended playback, video and tray RSS remain unverified unless listed in the results below.

## Reproduce

In a disposable GUI test environment, build each commit with the same supported JDK and version label.

```sh
BITCHORD_DESKTOP_ONLY=true ./gradlew :desktopApp:createReleaseDistributable -Pbitchord.version=1.10-startup-ci
python3 scripts/benchmark-desktop-startup.py \
  --launcher desktopApp/build/compose/binaries/main-release/app/BitChord/bin/BitChord \
  --version 1.10-startup-ci --output /tmp/bitchord-candidate \
  --scenario fresh-profile/warm-filesystem --runs 10 --duration 23
```

The benchmark requires Python with `psutil`. Use a new output directory for each build. It saves machine-readable results, time series, and launcher output. It creates separate profiles for every launch. On Windows, use a disposable Windows user because Java Preferences are stored in the registry, select the packaged `BitChord.exe`, and pass `--disposable-windows-user`. A sanitized profile can be supplied with `--fixture`. On Linux, `--synthetic-library` creates 80 playlists, a 200-entry saved queue, local history and ratings, an enabled Automix setting, and a guest Home cache. The hosted workflow launches this fixture inside a disconnected network namespace. A controlled cache-conditioning command can be supplied with `--before-run`. A new process or user profile alone does not imply cold filesystem cache.

Set `BITCHORD_PERF_DIAGNOSTICS=1` to enable startup milestones in a release launcher. `-Dbitchord.perf.diagnostics=true` enables JVM-side diagnostics. Timestamp capture occurs at each event. RSS or Windows working set and process CPU time are sampled by the asynchronous reporter. For an isolated class/JIT profile, add `--java-option=-Xlog:class+load=info,jit+compilation=debug,gc=info:file=startup-jvm.log:uptimemillis,tags,tid` and keep that launch outside the benchmark sample set.

For audio, the existing `-Dbitchord.playback.diagnostics=true` reports first PCM submission. Measure an audio loopback or acoustic signal separately when comparing audible latency.
