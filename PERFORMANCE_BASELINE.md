# Kotlin and Flutter performance baseline protocol

## Status and reference

Kotlin reference: `1.9.2` at `348b613a0c3db1a54de56483ea6f8748d6465211`. Flutter target: future release build from `2.0.0-flutter` under `flutterDesktop/`. Audit date: 2026-11-11.

**No application RAM, CPU, startup or frame measurements were collected in Phase 0.** Kotlin tests/build are blocked by JitPack 403 for InnertubeX/NewPipeExtractor; Flutter does not yet exist. Historical `docs/performance/1.8.3-startup-memory.md` diagnostic examples and its prior test claims are not measurements of this commit. Native library compilation and a Java graphics smoke check are not music-application baselines. No memory saving is claimed.

| Platform / build | Idle RAM | Playback RAM / CPU | Minimized / PiP | Startup / frame latency | Status |
|---|---|---|---|---|---|
| Kotlin 1.9.2 Linux release | Not measured | Not measured | Not measured | Not measured | Dependency-access blocked |
| Kotlin 1.9.2 Windows release | Not measured | Not measured | Not measured | Not measured | Windows runtime unavailable |
| Flutter Linux release | Not measured | Not measured | Not measured | Not measured | Not implemented |
| Flutter Windows release | Not measured | Not measured | Not measured | Not measured | Not implemented |

## Comparable builds and environment

Use same physical machine per platform, fixed OS/kernel, CPU governor/power mode, RAM, GPU/driver, monitor resolution, refresh rate and DPI. Record CPU count, thermal/throttling state, power source and background workload. Use real desktop/audio devices for product measurements; Xvfb/software rendering is a separate diagnostic class and cannot stand in for physical GPU results. Record Linux X11/XWayland/Wayland and compositor, Windows build and audio endpoint/shared-mode settings.

Build Kotlin standalone using the reference Gradle wrapper and full Java 21, e.g. `./gradlew :desktopApp:createDistributable -Pbitchord.version=1.9.2` (PowerShell: `.\gradlew.bat ...`). Launch the packaged application executable directly. Preserve reference `-Xmx512m` and G1 settings from desktopApp/build.gradle.kts. Do not measure Gradle or IDE/daemon memory as product RAM. Flutter: run `flutter build linux --release` or `flutter build windows --release` from `flutterDesktop/`, then launch its bundle executable. Use Flutter profile mode/DevTools only in separate attribution runs; do not mix debug/profile memory with release totals.

Use separate test-owned profiles containing equivalent data and settings; never benchmark against the developer's live browser profile or mutate their library. Record dataset manifest and hashes, playlist order, track formats/rates/channels/durations, model hashes, account state and cache configuration. Pin all app/reference/SDK/native versions. Preserve the reference's actual default feature settings for the primary comparison; include settings export and enabled provider list. Setting off Canvas, blur, lyrics, analysis or integrations on Flutter alone invalidates the comparison.

## Workload matrix

For local workloads use a reproducible redistributable corpus: at least stereo FLAC 16/44.1, FLAC 24/96, AAC/Opus, mono, multichannel where supported, silence-rich and speech/live tracks; include 20 distinct tracks with tagged artwork and embedded lyrics. Keep waveform and metadata hashes. For UI stress use a deterministic 10,000-row local-library fixture, 1,000-row playlist and 200 distinct thumbnail URLs/images; network fixtures must serve identical payloads/timing to both apps. These are proposed fixture sizes, not already collected data.

| Scenario | Actions and timing after readiness | Metrics / required correctness |
|---|---|---|
| S01 cold launch | Clean app caches and test profile; first-run welcome separate; wait 60 s after first usable window | Entry→window→first frame→usable; peak/steady RAM; no deferred task accidentally counted as complete |
| S02 warm idle Home | Existing profile/cache, 60 s settle then 5 min idle | Whole-product steady/peak RAM, CPU, threads/handles; same feed and no audio |
| S03 local playback | Play fixed 20-track order for 10 min, no video | RAM/CPU, decoder/buffer counts, underruns, click-to-first-sample, Next/seek latency |
| S04 online/auth | Same account library and recorded/live playlist, 10 min | Resolver/auth/browser subprocess RAM, first-audio and upgrade behavior; distinguish external latency |
| S05 lyrics | Same word/duet/gap fixture, foreground/unfocused/seek/buffer at scripted times, 10 min | Lyric/audio drift, frame p95/p99, CPU/RAM; no reduced timing quality |
| S06 browse churn | Repeat Home→Search→Artist→Library→Settings→back, scroll and open edge menus for 20 min | Plateau/growth slope, image bytes/counts, retained page payloads, frame latency and scroll restoration |
| S07 rich media | Same Canvas/artist motion/music-video sequence, 10 min | GPU/texture/native/browser memory, dropped frames and A/V drift; static fallback separately labeled |
| S08 transitions | Crossfade 0/6/12 s and each Automix duration/performance mode; six consecutive transitions each | Peak overlap/ONNX/native RAM, CPU, PCM continuity, feature/plan agreement; setup latency separate |
| S09 hidden/tray | Play 5 min, minimize/close to tray 10 min, restore 5 min | Reclamation after 30/60/300 s, CPU, media key/party correctness, restore latency; do not stop audio |
| S10 PiP | Same track/lyrics, main visible→hidden→PiP closed→main restored, 10 cycles | Full process-tree RAM including extra Flutter engine, window count and texture/subscription cleanup |
| S11 resources/failures | Scan corpus, download/cancel/delete, source timeout, device unplug/replug | Transient/peak RAM, handle/FD leaks, cancel latency, no stale UI/audio completion |
| S12 soak/shutdown | Repeat playback+browse+party reconnect for 2 h; quit and relaunch | Growth slope, leak counts, persisted state, zero orphan product processes/handles; shutdown drain latency |

Run each primary scenario at least five times per app/OS in alternating randomized order. Use one excluded rehearsal to populate tooling caches. Reset app caches for each cold trial; retain and document equivalent caches for warm trials. Do not drop OS caches globally on a shared machine; a “cold OS boot” trial requires controlled dedicated-machine reboot and separate labeling. Keep trial failures, outliers and their causes. Report median, p95, min/max and trial count; for comparisons bootstrap paired-run confidence intervals or report raw paired deltas if too few samples. Streaming live-service trials supplement deterministic fixtures and cannot replace them.

## Sampling and accounting

Sample at 1 Hz from process start through shutdown; use a monotonic clock and store UTC timestamp as metadata. Enumerate the entire product process tree, including browser/codec/updater workers and additional Flutter engines. Exclude build tools and sampler overhead, but include all persistent product subprocesses. Track process identity by PID plus start time to avoid PID reuse. Use a dedicated Linux cgroup or Windows Job Object where possible to capture reparented children; otherwise keep a discovered descendant set and explicitly flag incomplete accounting. Do not omit a helper merely because its parent exited.

Linux: use `/proc/<pid>/smaps_rollup` for RSS, PSS, private clean/dirty, and `/proc/<pid>/stat` for cumulative user/system CPU ticks. Record `getconf CLK_TCK`, `/proc/<pid>/status` thread count and `/proc/<pid>/fd` counts. `pidstat -r -u -p <pid> 1` is useful for inspection but a single PID does not give a complete browser/process-tree total. Sum PSS across the product tree as the primary physical-memory comparison; also report RSS sum, noting shared-page double counting, and private bytes separately. Container `memory.current` alone includes caches/tooling and is not app memory. Missing permissions/read errors invalidate that metric, not a zero sample.

Windows: use a sampler based on process handles/Job Object membership or PerfMon/PowerShell `Get-Process` with PID/start-time tracking. Capture PrivateMemorySize64 (private commit), WorkingSet64 and CPU cumulative seconds, handle/thread counts and process-tree membership each second. Working set can include shared pages; report private commit and working-set sum separately. Compare within the same OS with the same accounting method; Windows private commit is not Linux PSS. Record GPU dedicated/shared allocations separately using available platform tooling; do not add ambiguous shared totals twice.

CPU per interval: `100 * sum(delta process CPU seconds) / delta wall seconds` (100% = one logical core). Also report machine-normalized utilization by dividing by logical-core count. Do not compare tools with different normalization conventions. Report median/p95 and integral CPU-seconds by workload. Product-tree memory needs median, p95 and peak bytes converted to MiB (2^20). Sampled 1 Hz peaks may miss shorter spikes; label them and use higher-frequency attribution traces when investigating.

For Kotlin attribution optionally enable `BITCHORD_PERF_DIAGNOSTICS=1` and use JVM heap/native tooling in a separate run. `DesktopStartupDiagnostics.kt` provides monotonic milestones; validate milestone meaning at the pinned reference. Heap figures exclude Skia/FFmpeg/ONNX/browser/OS allocations. Flutter attribution uses DevTools heap snapshots, native allocation tracking, image-cache/texture/session counters and frame timings in a separate profile build. Neither Dart heap nor JVM heap establishes total product memory.

Synchronize first-input, request, first decoded sample, first submitted/audible sample, handoff and frame markers. Use loopback audio capture for true audible latency and drift; sink submission time is only an approximation and must be labeled. Reference/Flutter fixture clocks use the same event script. Startup “ready” means a usable rendered window, not process creation or an open port. Instrumentation missing in release is a future benchmark prerequisite, not a fabricated measurement.

## Acceptance and regression policy

Goal: lower total product memory with complete behavior. Proposed performance target, to be confirmed after Kotlin baseline: at least 20% median reduction in comparable idle and ordinary playback memory on **each** platform using Linux tree PSS and Windows tree private commit, with no regression in the other reported residency metrics unexplained. Report actual percentage as `100 * (Kotlin - Flutter) / Kotlin` for matched scenarios only. This target is a design objective, not an observed result or permission to exclude features.

CPU/startup/frame/audio gates: no more than 5% median regression or measurement noise band (whichever is larger) in matched steady CPU and latency, and no new underruns, clipping, A/V/lyric timing failures, lost state or leaked handles. Predeclare PCM/timing tolerances from reference fixtures and audio device resolution before running acceptance; do not choose tolerances after seeing failures. Frame p95/p99 and process-tree peak memory must be reported even when median RAM improves. Soak growth must plateau within bounded caches; investigate a persistent positive slope rather than hiding it behind forced GC or restart. All P-ID correctness gates still apply.

## Artifact record for each trial

Store outside ordinary source changes: run ID; app/ref/native/model/asset hashes; build mode and command; OS/hardware/display/audio settings; profile/dataset manifest with secrets removed; scenario/event script; timestamps; raw CSV/JSON samples with process membership; workload correctness results; instrumentation configuration; screenshots/frame/audio traces as permitted; median/p95/peak and paired differences; failures/missing metrics and reason. Name artifacts by commit/platform/scenario/trial. Include a reproducibility README and sampler revision. Do not commit tokens, cookies, copyrighted listening fixtures or personal library data.

Populate the result table only from these artifacts. A future summary must distinguish measured release results, attribution-only runs, failed/skipped trials and unmeasured scenarios. No “Flutter is lighter” conclusion is valid before a functionally equivalent workload is measured.

## Phase 0.5 hybrid-backend accounting and audio gates

The finalized backend is C++17 via C ABI/Dart FFI. This is a design choice, not a RAM benchmark. The entire in-process native allocation footprint and any additional Flutter/browser engines count toward product memory. Do not compare only Dart heap to the Kotlin process or exclude C++ PCM/ONNX/FFmpeg/curl memory. All existing benchmark scenarios and “Not measured” results remain unchanged.

Add these counters to future release diagnostic sampling, disabled or sampled cheaply in the actual render path: live engine/deck/AV*/Swr/curl/ONNX/session/tensor counts; PCM ring capacity/occupancy/high-water in frames and bytes; prepared loop regions and scratch buffers; command/event journal count/bytes/credits and oldest age; analysis decode/feature/tensor high-water; retained source descriptor bytes; active IO/analysis threads; clock/device epochs, discontinuities, underruns, worker failures/recovery attempts and accepted/presented frames. Counter collection must not allocate or log per audio callback. Keep memory categories separate to avoid double-counting pools as both allocated capacity and current contents.

Reference cost examples derived from source, **not measurements**: one eight-second stereo float32 PCM budget at 48 kHz is `8 × 48000 × 2 × 4 = 3,072,000 bytes` (~2.93 MiB), excluding codec/graph/queue overhead; two ordinary deck budgets double this before extra scratch/overlap/analysis. A speculative two-second window is 768,000 bytes (~0.73 MiB) at that format. Analysis MAX_SAMPLES is `20 × 60 × 11025 = 13,230,000` float values (~50.47 MiB for one contiguous mono buffer); reference chunk accumulation plus joining can briefly keep more than one such copy. ONNX tensors/windows and decoded stereo loop regions are separate. The reference requests a 64 MiB analysis-thread stack: reservation and resident/committed usage are distinct and OS-dependent. No claim is made that all these maxima occur simultaneously. Budget native analysis with these costs, preserve feature coverage and measure before reducing windows/precision.

Required bridge/real-time trials in addition to S03/S08/S11/S12:

| Trial | Correctness evidence | Resource/performance evidence |
|---|---|---|
| Stall Flutter UI and separately the service consumer for 1/5/30 seconds while two tracks are prepared | Native output/seam stays independent of Dart; no stale select, duplicate adoption or loss of acknowledged outcomes; journal-full safe-boundary policy is observable | Bound journal/PCM/CPU; no growth with consumer stall; report resume/event-drain latency |
| Burst rapid select/seek/settings plus expired source replies at negotiated queue limits | Accepted vs applied distinguished, every accepted ID terminal exactly once, rejected mutations explicit, old generations never audible | Admission/tail latency, peak queue bytes, native allocation balance; no unbounded retries |
| Same-format adjacent fixture, codec priming/end padding and different-rate fixture | Continuous same-format PCM sample sequence, correct codec trimming; documented drain/conversion behavior for rate changes, no universal gapless assertion | Loopback seam/gap/duplicate duration, device reopen count and first-audio latency |
| Speed, silence, tempo ease, midpoint seek, device default switch/unplug and suspend/resume | Source time agrees with presented-frame segments and timing-quality flags; Dart clock offset/epoch never inferred from raw Stopwatch subtraction | Audio-to-lyric drift distribution, calibration uncertainty and clock age; native played/queued counters |
| EQ sweep/crossfade/loop prepare plus simultaneous model work | Reference PCM envelope/response and plan decisions preserved; no audio callback dependency on model progress | Render duration p50/p95/p99/max vs device deadline; allocation/lock instrumentation and underruns under load |
| Quit during blocked HTTP/open/model pass and repeated create/destroy | No free-before-worker-exit, no callbacks after engine destruction, clean cancellation and explicit shutdown-pending timeout | All live handle/session counts return to baseline after quiescence; sanitizer/FD/COM/thread accounting, not forced-GC guesses |

Predeclare sample/plan/timing tolerances from reference oracle precision and test-device resolution before acceptance. Use exact integer frame/identity equality where the contract is exact; floating algorithms may require explicit platform-specific numeric tolerances, with audible/response review. Compiler fast-math/SIMD/FMA, FFmpeg build options and ONNX execution providers are recorded trial inputs; do not enable optimizations that change sound/decisions without differential evidence. The native loop renderer's sample-exact beat knots and the overall OS scheduling/output latency are different measurements. Only actual loopback/physical evidence can establish audible timing; a successful C ABI call or waveform screenshot cannot.

No new benchmark trial ran in Phase 0.5. The offline canonical-file verifier passed; it establishes source/model identity, not throughput, RAM savings, gapless quality, model accuracy or platform output readiness.

Phase 1 continuation note: Dart shell source and validation workflow now exist, but no SDK-capable local or hosted run was completed. All performance result cells remain unmeasured. Source cache limits and absence of a native engine in this shell do not demonstrate memory savings relative to a functioning Kotlin player.

## Measurement availability update — 2026-11-11

No Kotlin or Flutter RAM, CPU, startup-time, audio-latency, or long-duration
resource-retention measurements have been collected.

| Target | Validation status | Performance measurements |
| --- | --- | --- |
| Kotlin 1.9.2 | Runtime validation blocked by JitPack dependency resolution | Not measured |
| Flutter Windows | Hosted analysis, six tests, and release build succeeded | Not measured |
| Flutter Linux | Final hosted result not confirmed | Not measured |

A successful build or widget test is not a performance measurement.

Do not claim reduced memory usage. Run the existing benchmark protocol using
matched hardware, release builds, window dimensions, datasets, and workloads
when both applications can run.

Record the application commit, toolchain, operating system, hardware, sampling
method, warm-up period, workload duration, and repeated-run results with every
measurement. Benchmark advanced playback only after the real native backend
is available.

### Later CI status — 2026-11-12

Linux hosted validation in run `43041242897` finished with **failure** during
desktop dependency installation. No Linux runtime or performance measurement
was obtained; all performance measurements remain unavailable.



### YouTube startup validation method - 2026-11-13

The fast-start change removes unconditional subprocess extraction on direct guest success and reduces large-file preflight to at most 32 KiB instead of two 512 KiB probes plus a repeated initial 512 KiB broker fetch. These are code-path and byte-budget claims verified by controlled tests, NOT a measured speedup factor. Keep resolve/direct/extractor/probe/broker/native-command timings separate. Native command completion is not the first audible frame. Repeat the same videos on the same device, network, account and quality for old/new first-audible timing and seek/soak acceptance. Physical latency, RAM saving and universal reliability remain unmeasured.
