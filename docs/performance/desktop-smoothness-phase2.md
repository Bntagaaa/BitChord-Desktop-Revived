# Desktop smoothness, Phase 2

## Executive summary

Base: `feature/reduce-desktop-memory` at `4cf9755104c65ea5615f28ed6ec7d5fe53f5f4a4`.
Implementation branch: `perf/desktop-smoothness-phase2`. The base reference is unchanged.

This change removes measured synchronous Preferences XML export from UI interactions,
isolates cancellable Search typeahead effects, caches scroll-mask brushes, and prevents
a cancelled minimize cleanup from subsequently requesting GC after restore. It also
adds opt-in diagnostics and repeatable comparison workloads. Phase 1's playback clock,
artwork limits, native-frame ownership and hidden-window media lifecycle are retained.

These are bounded improvements, not evidence that the whole application matches
Spotify. The strongest measured result is settings persistence. Software-rendered
desktop measurements do not establish smoothness on the user's GPU, under Meet/Brave,
or while authenticated music/video playback is running.

## Bottleneck analysis

1. **Synchronous settings disk flush — measured.** Each UI write could export the
   entire Preferences node to XML. JFR samples include `FileSystemPreferences`,
   `XmlSupport.exportMap` and `writeDoc` on an `AWT-EventQueue-*` thread. With a 980,000-character
   fixture node, 100 slider-like changes spend about one second of CPU on the EDT.
   This affects ordinary settings/volume interactions independently of video.
2. **Frame state read during video composition — verified by counters; timing must
   be evaluated separately.** A new Canvas/artist/music-video image can recompose its
   container every frame. Moving observation into drawing removes those compositions,
   but fewer compositions alone does not guarantee better frame pacing or allocation.
3. **Search effects observing query/generation at the desktop root — code evidence
   and scope/cancellation tests.** The 220/400 ms debounces now live in small child
   scopes. Hidden Search cancels provider work. Late results cannot replace a newer
   request. Network clients already used IO; this is not a claim that HTTP previously
   ran synchronously on the EDT. Quick Search already has its own scope and is retained.
4. **Scroll fade resources — code evidence and pixel comparison.** Brushes were
   constructed per draw and every viewport requested an offscreen surface, including
   content that could not scroll. Brushes now persist until viewport changes; only
   scrollable content requests the masking surface. No measured GPU speedup is claimed.
5. **Restore during blocking memory cleanup — regression reproduced in a test.**
   Cancellation during a blocking cache trim previously fell through to `System.gc()`.
   A cancellation/visibility check now precedes collection. Collection already in
   progress cannot be cancelled, and visibility can change immediately after the check.

Other inspected paths already separate blocking work: artwork decode/palette work,
catalogue HTTP loading, decoder initialization and minimized cache eviction. This
patch does not introduce another executor for each item or change prefetch limits.
Playback uses `distinctUntilChanged(::samePlaybackUiState)`; its position clock still
feeds progress/video independently. Lyrics text/layout and their timing behavior are
retained because this environment cannot demonstrate a lyrics bottleneck.

## Implementation and trade-offs

- `DesktopPreferenceFlush`: values enter Preferences immediately. UI-originated disk
  flushes coalesce on one daemon worker (150 ms quiet interval, 1.5 s maximum scheduling
  delay during continuous input). Background writes remain synchronous. Normal shutdown
  drains pending writes with a 3 s bound. Abrupt termination/power failure can lose the
  latest UI settings. A slow filesystem can exceed these scheduling intervals, and
  Preferences node locks can still briefly contend with UI writes while a flush runs.
  Failure leaves dirty values to the JDK's usual retry and logs only the exception class.
- `DesktopLiveFramePainter`, Canvas, artist motion and music video surfaces: frame state
  is observed during drawing; derived readiness changes composition only when a frame
  appears/disappears. Intrinsic-size changes still reach layout. The stable painter
  delegates drawing to the same frame implementation and preserves fit/crop/alpha.
- `DesktopTypeaheadEffect`: preserves debounce delays, limits and failure fallback;
  puts provider processing on Default and publishes in the caller's composition context.
  Cancellation and query/generation checks prevent stale publication, including a
  provider that completes after cancellation. Returning to Search restarts its debounce.
- Shared scroll fade: the same 34/54 dp masks, blend mode and small-panel clamping.
  Pixel comparison covers the top, middle, bottom and non-scrollable cases.
- `DesktopWindowMemoryTrimmer`: retains hidden-window GC and off-thread eviction;
  tests cover restore while trim is blocked and the next legitimate hidden collection.
- No playback resolution/FPS limit, blur removal, rewritten UI toolkit or dependency
  additions. Reduce Blur's existing bypass remains; the scroll mask is not a blur.
  JVM memory flags are unchanged. Packaged runtimes additionally include `jdk.management`
  and `jdk.jfr` to support opt-in diagnostics; those modules increase runtime disk size.

## Measurement method

Linux 6.18.55 x86_64 cloud VM, AMD EPYC 9V74, five exposed CPUs with a four-core cgroup
quota, 32 GiB memory limit, JDK 21.0.9. Xvfb `:99`, 1920×1080, Skiko software fallback
because no Linux GL context is available. Reported display refresh is unknown (0).
The 16.67/8.33 ms columns are 60/120 Hz references, not proof of physical refresh.

Each final comparison uses three separate JVMs, alternating modes, with no concurrent
test/build workloads. Gradle dependency/compile work completes before fixture measurement.
Tables use the **median of three per-run percentiles/rates**, not a pooled percentile.
Raw counts and per-run values are retained. Development trials, wrong-coordinate
trials and measurements concurrent with tests are excluded from the final dataset.

Workloads:

- **Settings:** real Java Preferences backend, chunked 980,000-character node, 20 warmup
  writes then 100 changes with 5 ms gaps. Before replays Phase 1's synchronous flush
  via a trace-only switch; the original pre-change measurements corroborate it. Both
  modes use identical fixture/probe code. Total elapsed includes intentional gaps;
  measured write CPU/allocation covers the UI operation, not its worker flush.
- **Motion:** actual Compose Window/Skia image drawing, immutable 720×405 BGRA frames,
  180 callback warmup updates then 600 measured updates with an equally applied draw-counter wrapper. This excludes decoder, network
  and audio. Callback intervals use `withFrameNanos`; actual presentation is unknown.
- **Desktop shell:** instrumentation-only detached Phase 1 checkout versus Phase 2.
  Real AWT Robot typing in Search and Ctrl+K, a local 400-song playlist, down/up wheel
  scrolling, twelve window sizes, close-to-tray/restore. Ten seconds warmup after the
  window appears. Screenshots verify the controls/playlist. An extra Reduce Blur run
  is a smoke check, not a three-run performance comparison. Home and search network
  content are loading/error states because YouTube endpoints are unavailable here.
- **GC:** allocate/discard 96 MiB of arrays, explicit collection versus natural GC,
  sample after 600 ms. This establishes a benefit/cost for that fixture only.

### What the probe measures

`awt_dispatch` measures event-handler elapsed time, including non-input events.
`edt_queue_lag` measures a coalesced 20 ms posted task's wait. These expose EDT stalls.
`input_dispatch_to_frame_callback` starts when an AWT input is dispatched, not when the
physical input occurs; it coalesces events and stops at the next Compose frame callback,
before layout/paint/presentation. Navigation marks have the same limitation and do not
measure first content. Hidden/restore and scenario boundaries clear pending timestamps.

`draw_cpu_scope` measures the instrumented root's drawing call only. Cached child layers
can draw without visiting that root, so neither it nor `app_draw_interval` is a complete
frame stream. Idle redraw gaps are not scored as animation jank. Layout time, GPU waits,
display presentation, input-to-photon and network-to-first-content remain unmeasured.

GC notification durations are collection elapsed time, **not exact stop-the-world pause**.
Use JFR `jdk.GCPhasePause` for pauses. Runtime samples once per second cover process CPU,
heap, Linux RSS and EDT cumulative allocation. They miss subsecond peaks and include JIT,
startup and background work. CPU percentage is relative to one logical core (100% = one
core). Heap usage/short-term deltas are not retained-size/leak measurements.

Instrumentation requires `-Dbitchord.smoothness.output=/path/timings.csv`. Normal launch
adds no sampler/event queue wrapper/frame-request loop or drawing wrapper. Verified by
thread dumps of the packaged application, with and without this property. Diagnostic
sampling itself adds overhead; compare both revisions with identical instrumentation.
Metrics retain the last 20,000 observations per metric and the first 10,000 one-second
resource samples. No query/title/token is logged. JFR can contain local paths and
application metadata: inspect captures before sharing.

## Benchmark results

### Settings: UI operation and EDT stalls

| Metric | Synchronous Phase 1 behavior | Coalesced worker flush |
|---|---:|---:|
| Write P50/P95/P99 (ms) | 10.034/15.192/22.733 | 0.053/0.113/0.125 |
| EDT queue lag P50/P95/P99 (ms) | 3.053/11.715/14.563 | 0.090/0.124/0.138 |
| UI write observations >16.67 ms, median rate | 4.0% | 0.0% |
| UI write observations >8.33 ms, median rate | 66.0% | 0.0% |
| Total UI write CPU for 100 changes (ms) | 993.156 | 7.408 |
| UI write allocation for 100 changes (bytes) | 77,142,440 | 207,480 |
| Elapsed for 100 changes, including 5 ms gaps (ms) | 1,574.052 | 531.826 |

UI write CPU falls 99.25%; this excludes worker flushing and is not a whole-app CPU
reduction. A real disk flush still occurs after coalescing. Backend size, OS Preferences
implementation and filesystem affect the absolute result. These are operation timings,
not frame or display-latency measurements.

### Actual desktop shell, software rendering

| Scenario | Baseline EDT queue lag P50/P95/P99 (ms) | Phase 2 EDT queue lag P50/P95/P99 (ms) |
|---|---:|---:|
| shell-search-typing | 8.916/38.190/48.967 | 7.013/33.143/41.227 |
| shell-quick-search | 18.063/30.074/39.857 | 16.876/29.782/39.863 |
| shell-playlist-scroll | 35.481/50.093/59.171 | 38.954/54.195/60.990 |
| shell-resize | 19.202/30.797/36.794 | 18.494/29.363/41.257 |
| shell-hide-restore | 0.081/6.125/28.836 | 0.106/6.390/17.241 |

Search queue-lag P95 improves about 13%, while scrolling P95 is about 8% worse.
Quick Search is nearly unchanged; resize P99 is worse. This dataset does **not**
demonstrate an overall scrolling/resize win. The mask change is retained for resource
reuse and equivalent pixels, not as a proven scrolling speedup.

| Scenario | Baseline input-dispatch → callback P50/P95/P99 (ms) | Phase 2 input-dispatch → callback P50/P95/P99 (ms) |
|---|---:|---:|
| shell-search-typing | 1.807/3.402/5.329 | 2.099/4.078/7.326 |
| shell-quick-search | 1.296/2.890/6.109 | 1.383/3.181/4.277 |
| shell-playlist-scroll | 9.891/17.488/20.314 | 11.256/21.033/21.267 |
| shell-hide-restore | 6.589/6.589/6.589 | 6.978/6.978/6.978 |

These coalesced callback proxies include mouse/key events; they exclude queued input
before dispatch and exclude rendering after the callback. Search/playlist callback
tails worsen even while Search EDT queue lag improves: different observation points
must not be treated as interchangeable. The one navigation observation per run is
1.275 → 2.214 ms (median); it ends at a callback, **not first content**. There is no
credible network navigation-to-first-content or physical input-latency result here.

| Whole shell capture, median of three | Baseline | Phase 2 |
|---|---:|---:|
| Sampled duration (s) | 29.99 | 28.98 |
| Process CPU (% of one core) | 93.24 | 90.43 |
| Peak sampled RSS (MiB) | 534.34 | 528.52 |
| Peak sampled heap used (MiB) | 67.93 | 67.18 |
| EDT allocated delta (MiB) | 223.24 | 203.29 |

These samples include startup, warmup and all shell actions, with minor duration
differences. They show no large sampled RSS regression in this local fixture, not
a universal memory reduction or long-session leak result. Peaks are not continuously
sampled. Live media and artwork downloads could have very different costs.

### Explicit GC decision

The artificial 96 MiB allocation fixture gives median RSS 168.93 → 81.91 MiB at
600 ms, heap used about 204.17 → 11.14 MiB, and explicit-call elapsed 5.945 ms.
This supports retaining Phase 1 collection while hidden. Full application GC is
more expensive: hidden-window collection notifications in shell captures are median
38 ms baseline and 47 ms Phase 2. Those are collection elapsed, not exact pause times.
They occur while hidden; a GC already started cannot be cancelled on restore.
The new guard prevents collection only when cleanup is cancelled before it starts.

### Motion fixture

| Metric, three JVM runs | Legacy composition path | Drawing observation path |
|---|---:|---:|
| Callback interval P50/P95/P99 (ms) | 15.468 / 17.445 / 22.756 | 15.420 / 16.367 / 18.986 |
| Callback intervals >16.67 ms, median rate | 6.68% | 4.17% |
| Callback intervals >8.33 ms, median rate | 100% | 100% |
| Container compositions per 600 frame updates | 600 | 0 |
| Counted image draws per 600 updates | 600 | 600 |
| Measured process CPU (ms) | 8,190 | 7,780 |
| EDT queue lag P50/P95/P99 (ms) | 3.248 / 11.035 / 16.972 | 2.921 / 10.456 / 13.520 |

The longer fixture improves median callback P95 about 6%, P99 about 17%, and process
CPU about 5%. This supports the local change but does not measure decoder throughput,
GPU upload cost, audio continuity or display pacing. The fixture produces at the
available callback cadence, rather than pretending this is 120 Hz video playback.

An earlier short fixture (30 warmup/180 measured updates, no draw-counter wrapper)
gave **worse** isolated callback P95/P99 (15.895/16.287 → 17.327/19.119 ms), more GC,
and CPU 2,600 → 2,900 ms. It is retained in `short-motion/`, not hidden. Follow-up JFR
runs showed allocation dominated by the same immutable frame-array copies in both
modes, with G1 humongous allocations and results sensitive to heap/JIT warmup. The
longer fixture adds longer warmup and verifies actual drawing in both modes. Its
absolute timings must not be compared directly with the differently instrumented
short fixture. Three VM runs are not enough to eliminate all variance; on-device
video validation remains required. No JVM flag or Phase 1 frame-ownership shortcut
was introduced to make this test look faster.

### JFR pause evidence and raw artifacts

One matched settings run per mode also records JFR. Across the entire JVM lifetime,
including warmup, `jdk.GCPhasePause` has 18 observations before versus 2 after; maximum
pause is 5.955 → 1.787 ms. With only one recording and two after observations, this is
not a reliable cross-workload P95 improvement claim. The JSON includes every pause,
its P50/P95/P99, and sampled EDT leaf methods. Sampling after the change found work
on `BitChord-Settings-Flush` rather than EDT XML work; absence of an EDT sample is not
proof that no EDT IO or lock contention can ever occur.

Raw numerical data: [settings/shell/GC aggregates](smoothness-phase2-results/aggregate.csv),
[per-run fixture counters](smoothness-phase2-results/fixtures.json),
[sampled resources](smoothness-phase2-results/resources.csv),
[steady motion aggregates](smoothness-phase2-results/steady-motion/aggregate.csv),
[short motion aggregates](smoothness-phase2-results/short-motion/aggregate.csv),
[JFR pause summary](smoothness-phase2-results/jfr-summary.json), and
[source provenance](smoothness-phase2-results/provenance.json).
Per-run CSVs preserve counts/tails and runtime observations; aggregate rates are medians
of the per-run rates. Full binary JFR and screenshots remain local, not in Git.

## Validation

`shared:jvmTest`, `sharedUi:jvmTest`, `desktopApp:test` and
`desktopApp:createDistributable -Pbitchord.version=phase2` pass: **641 tests**
(70 shared, 34 sharedUi, 537 desktop; no failures/errors/skips).
The eight added tests cover flush coalescing/immediate reads/background durability,
chunk replacement/removal, frame drawing isolation, typeahead scope/stale results/
failure handling, exact scroll-mask pixels and the restore/GC race.
Existing playback clock, video/session/layout, queue, Quick Search/focus, lyrics-source,
artwork concurrency and memory ownership tests remain in the suite.

Linux packaged launches succeed both normally and with CSV/JFR enabled. Ordinary
launch has no `BitChord-Perf-Probe` thread; the opt-in launch has the thread and exports
both artifacts on exit. This tests the slim packaged runtime, not only a full JDK.
Common JVM sources compile; Windows packaging/driver/audio behavior has not been run.

## Remaining issues and on-device validation

Real music/video audio output, authenticated media/catalogue, hardware blur/GPU
composition, Windows, physical 60/120 Hz pacing, Meet/Brave contention and long sessions
are not benchmarked here. Headless software measurements cannot settle those targets.
Startup still initializes the existing service graph; there is no measured cold-start
speed claim. Large uploads/decoder copies, text measurement and GPU composition remain
possible bottlenecks requiring an on-device profile. Do not conclude Spotify parity.

For each revision, use the same account, cached content, audio device, window size,
display refresh, power mode and competing apps. Warm up for at least 30 seconds, run
three times in alternating order, and record default effects and Reduce Blur separately.
Keep music actively playing during every row except the dedicated stopped-startup case.
Quit from the tray to export captures; closing to the tray does not end the recording.

| Scenario | Repeatable action and behavior to verify |
|---|---|
| Cold start, stopped then playing | Capture first window/first content separately; queue play must acknowledge immediately |
| Idle Home | 30 s untouched; no continuous unrelated page composition |
| Home scroll | Same cached shelves, 20 down/up wheel steps; artwork and hover remain |
| Explore scroll | Same category, 20 down/up steps; navigation stays responsive |
| Search | Three identical 20-character bursts; stale suggestions never overwrite newer text |
| Quick Search | Ctrl+K, type, select/ESC, repeat; text focus and shortcuts recover |
| Playlist navigation | Open the same 400+ track playlist ten times; skeleton/retained content appears promptly |
| Artist navigation | Ten opens, including an animated banner; fallback/aspect/fade remain |
| Album navigation | Ten opens, scroll and play; pending network does not block navigation |
| Library | Same saved content, 20 scroll steps; stable item identity/hover |
| Mini-player | 60 s progress, drag seek and volume; clock does not recompose unrelated pages |
| Fullscreen Player | Enter/leave ten times while video plays; session/audio must continue |
| Fullscreen Lyrics | Same synced track for 60 s; highlight/scroll accuracy, seek and ESC |
| Queue | Add/remove/reorder 20 items during playback; keyboard navigation works |
| Artist motion | Two loops, navigate away/back; clipping, looping and fallback preserved |
| Canvas/music video | Two loops each, track changes and A/V sync; no new FPS/quality limit |
| Resize | Twelve alternate sizes during playback and lyrics; capture JFR plus presentation trace |
| Minimize/restore | Restore at 0.2/1.0/2.0 s during repeated cycles; no decoder restart or post-restore cleanup GC |
| Long session | 60+ minutes cycling these actions; compare RSS/heap trends, audio continuity and tails |

Use JFR/JMC to inspect AWT stalls, allocation samples, `jdk.GCPhasePause`, monitor waits
and worker competition. Use a platform presentation trace (e.g. PresentMon on Windows)
and high-speed capture for actual end-to-end feedback latency; CSV callback timing is
insufficient. A platform trace is also needed to distinguish GPU wait from CPU drawing.

## Reproduction

Requires JDK 21, the repository's native build dependencies and a running desktop display.
Set `JAVA_HOME`, `DISPLAY`/platform equivalents and Gradle configuration for your machine.

```sh
./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopApp:test :desktopApp:createDistributable
python3 docs/performance/tools/prepare-smoothness-baseline.py /tmp/bitchord-phase1-probed
python3 docs/performance/tools/run-smoothness-benchmarks.py \
  --baseline /tmp/bitchord-phase1-probed --output /tmp/bitchord-smoothness-results
python3 docs/performance/tools/summarize-smoothness.py /tmp/bitchord-smoothness-results
docs/performance/tools/capture-smoothness.sh /path/to/BitChord /tmp/bitchord-device-capture
```

The baseline helper creates a new detached checkout of the exact base and copies only
the probe, shell driver, opt-in Main wrapper, three navigation marks and shell Gradle
task. It applies no optimization. The runner refuses an existing output directory and
seeds only isolated homes/preferences for its shell fixture. The capture script runs
the user's ordinary app without seeding or replacing data. Do not point shell fixtures
at a real user profile. JFR/logs/screenshots/fixture homes are not committed.

## Pull request description

**Title:** Reduce desktop UI stalls and isolate high-frequency frame/search updates

**Base:** `feature/reduce-desktop-memory`
**Head:** `perf/desktop-smoothness-phase2`

Slider/settings writes synchronously exported the whole Preferences node on the UI
thread, and video frame changes unnecessarily recomposed their image containers.
Coalesce UI-originated flushes on one worker, observe live frames during drawing,
isolate/cancel typeahead effects, cache unchanged scroll masks, and skip deferred
hidden-window GC after cancelled cleanup. Preserve Phase 1 memory limits and media
ownership, Astra effects, Reduce Blur, playback/lyrics/queue/focus behavior and JVM flags.

Evidence: matched three-run settings fixture reduces median EDT write CPU
993 → 7.4 ms for 100 changes. The warmed 600-update motion fixture retains 600 draws,
reduces container compositions 600 → 0, and improves callback P95 17.45 → 16.37 ms.
Actual software shell measurements are mixed: Search queue-lag P95 improves, while
playlist scrolling and resize tails need further work. No Spotify parity, physical
presentation or live authenticated media result is claimed. The report includes the
negative short-motion results, all raw counters and remaining device-validation steps.

Validation: 641 tests pass; Linux distributable builds and launches both normally
and with CSV/JFR enabled. The pixel mask comparison, stale typeahead/cancellation,
frame observation and restore/GC regression checks pass. See the reproduction scripts
and active-playback device matrix above. Windows/GPU/audio/long-session coverage is
pending. UI settings durability is delayed up to the coalescing interval under normal
conditions; orderly exit drains pending writes. Do not merge automatically.
