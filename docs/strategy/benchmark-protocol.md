# Reproducible desktop benchmark protocol

This protocol accompanies the [9 November 2026 audit](competitive-audit-2026-11.md). **No application performance results have been collected by this document.** Release asset sizes and the isolated media-cache retention exercise are separate evidence classes.

## Comparable test matrix

Use Windows x64 for the first head-to-head run because both inspected releases publish Windows binaries. Revived Linux testing is a separate platform gate. A Linux comparison needs a reproducibly built Continued Rust binary and must not compare Revived on Linux with Continued on Windows. Do not use the old Kotlin Continued v1.9 against Rust claims about v2.0.

Record hardware model, CPU/RAM, GPU/driver, OS/build, display resolution/refresh/scaling, output device/sample rate, power plan, app/release commit/hash, package kind, installed footprint, network route/RTT/throughput, cache/profile state and settings. Preserve existing user profiles; create disposable isolated test profiles. Alternate app order between runs to reduce cache/thermal bias. Match process-tree scope; report login/webview children separately where they terminate after auth.

Run three scenarios rather than hiding feature differences:

1. **Common-core:** same YouTube item ID and audio codec/quality, queue, normalization, crossfade, display size and visible static player. Sign-in state must match. Disable addons/optional motion in both. If exact rendition cannot be matched, mark comparison non-equivalent and record both renditions.
2. **Default experience:** each app's released defaults; record screenshots/settings and explain differences.
3. **Feature stress:** local music, video, translated lyrics, addon FLAC, PiP and Automix for each app that supports them. Label these standalone acceptance tests when the other app lacks the feature.

Use 10 fixed short tracks, several 10-minute tracks, and one long >40 MiB item. Include licensed local AAC/Opus/MP3/FLAC fixtures, 44.1/48/96/192 kHz where supported, embedded/folder art and corrupted media. Keep a manifest with content IDs and file checksums; do not redistribute recordings without permission. Use fixed decoded settings for audio tests; do not select a smaller buffer only to improve RAM.

## Metric definitions and sampling

| Metric | Start / finish / sample | Minimum repetitions |
| --- | --- | --- |
| Cold startup | External launcher timestamp to first presented usable frame; cold OS/file cache state defined and recorded | 10 cold runs per app; reboot/cold cache procedure documented |
| Warm startup | Same external boundary after one priming launch, same profile/cache | 20 runs per app |
| Feed-ready | Launch to first actionable populated Home feed; separate from frame-ready | Same startup runs; separate network component |
| Idle RAM | Process tree after 2-minute settling, no playback/request in progress | 60 one-second samples per condition |
| Playback RAM/CPU/GPU | Fixed 10-minute queue; discard initial 60 seconds for steady-state stats, retain startup peaks separately | 3 complete runs per condition |
| Memory growth | Continuous navigation/scroll/search/playback sequence for 2 hours | At least 2 runs; report slope after first 10 minutes and cache plateau |
| Click-to-audio | Instrumented input event to loopback onset above defined noise threshold; output-submission timestamp reported separately | 30 clicks with cache state recorded |
| Track transitions | Last audible output of A to first audible output of B, including intended crossfade overlap | 30 transitions each for gapless/default, crossfade and Automix |
| UI responsiveness | Input-event timestamp to presented update, plus present/frame durations | 30 scripted navigation/search/queue operations; report p50/p95 |
| Background work | Visible focused; visible unfocused; minimized; tray; PiP open with main hidden | 5 minutes per condition, same playing track |
| Footprint | Download compressed bytes, extracted tree bytes, executable bytes and installed directory bytes separately | One checksum-identified package per OS/kind |

On Windows use PerfMon counters for process private bytes/working set, CPU time and GPU engine; WPR/WPA or PresentMon for input/present/frame evidence where available. On Linux use `/proc/<pid>/status` RSS and `/proc/<pid>/smaps_rollup` PSS/private data, process CPU time deltas, and an appropriate driver-specific GPU/present tool. Capture PID plus start time so recycled PIDs do not corrupt a run. Include child processes. Working set/RSS and private bytes/PSS are different quantities: report each accurately and compare like with like.

Normalize CPU time as `100 × process_cpu_seconds_delta / elapsed_seconds`: one fully busy core is 100%; also record machine core count. Do not silently mix tools whose CPU normalization is total-machine percentage. If per-process GPU attribution is unavailable, report that limitation and background GPU activity rather than treating whole-device use as app-only. Use the same OS/tool for relative claims.

Application logs help attribute stages but do not replace external timings. Revived startup diagnostics can be enabled by `BITCHORD_PERF_DIAGNOSTICS=1`; playback instrumentation uses JVM property `-Dbitchord.playback.diagnostics=true` passed through the chosen launcher's documented JVM-argument mechanism. Existing output explicitly calls submission a proxy, not acoustic time-to-first-audio. The internal startup clock begins at diagnostics-object initialization, excluding earlier process/VM startup. Its current RSS helper assumes 4096-byte Linux pages and has no Windows implementation; do not use it as the cross-platform memory authority.

Continued has YTM_* development hooks for smoke/search/background/FPS and logging. Only use hooks after verifying their exact behavior in the pinned source; forced FPS/offscreen/mute modes are not representative default-user benchmarks. For timing, record whether diagnostics or screen recording were enabled and repeat without high-overhead capture if needed.

## Deterministic interaction script

After a 2-minute idle settle: open Home → Search, enter the same fixed query at 100 ms/key, open the same album, play item 1, queue items 2–10, scroll the same number of viewports, open/close lyrics and queue, change volume once, seek once, then return Home. Repeat every 5 minutes during the 2-hour soak. Preserve a screen recording and step log where possible. The script is a specification; no scheduled automation was created.

Audio robustness: rapid 100 skips, 20 seeks including near EOF, pause/resume, output-device unplug/reconnect, expired stream URL, unsupported Range response, HTTP 429/5xx and bounded bandwidth/latency/loss. Apply impairment on a controlled test fixture/proxy consistently; avoid hammering public providers. Validate no stale-generation audio, unbounded retry, permanently blocked worker, false EOF, or queue corruption.

Video/PiP: open fullscreen and separate PiP, minimize/restore main, switch video quality, change track during resolve, test visible unfocused vs hidden. Check AV sync and lyric position after transitions. Windows fullscreen must cover 100/125/150/200% scaling and mixed-monitor movement; Linux should cover KDE and GNOME with actual session protocol recorded.

## Results schema and interpretation

Save raw samples with:

```text
run_id,app,release_sha,os,scenario,phase,elapsed_ms,pid,rss_bytes,private_bytes,cpu_time_ns,gpu_percent,frame_ms,underruns
```

Unavailable numeric values must be blank/null, never zero. Keep startup/latency event tables separately, keyed by run_id and operation_id; join them only when clocks are aligned. Calculate p50/p95 from raw per-run values, state the percentile method, retain min/max, count and spread, and investigate outliers instead of deleting them. For small n=10 cold starts, emphasize raw values and uncertainty rather than a precise p95. Asset bytes come from release metadata; report retrieval date and distinguish compressed/extracted sizes.

Declare a regression budget before edits (initial proposal: >5% p95 or >10% peak memory deterioration triggers review; any new audible glitch or incorrect playback blocks acceptance). These are proposed decision thresholds. A small change with noisy samples is inconclusive, not a victory. A requested optimization is accepted only when supported behavior remains intact and reproducible evidence shows the intended effect.

## Current execution limits

The audit host is Linux, initially JDK17, without Rust/cargo, Xvfb or a configured comparable desktop/audio setup. APT installation was blocked by process privilege restrictions. Gradle 8.11.1 was obtained, but project test configuration could not resolve Android Gradle plugin 8.10.1. Consequently no valid head-to-head runtime benchmark, full application build or new Windows/Linux runtime acceptance test was completed here.

Completed narrow verification: actual DesktopMediaCache source before/after compiled in an isolated Kotlin 2.0.20/JDK17 harness with a configuration stub; identical paths/cache reuse/clear/disk trim and a 10,000-URL retention exercise passed. This does not establish a whole-app memory, startup or latency result.
