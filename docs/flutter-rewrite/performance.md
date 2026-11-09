# Performance evidence and measurement protocol

## Result at this checkpoint

There is **no measured Kotlin-versus-Flutter startup, RAM, CPU, GPU, frame-time or
transition-latency comparison yet**. No performance improvement is claimed.
Dart formatting, static analysis, deterministic tests and successful compilation
are not performance measurements. The managed Linux environment lacks the
complete native desktop build/session prerequisites and its Dart JIT child
processes exit 255 before the standard Flutter test suite starts.

## Resource controls implemented in production code

- Flutter's decoded image cache is bounded at 200 entries / 64 MiB; artwork
  widgets request images at the rendered physical size.
- Catalogue/search/lyrics and resolved-stream/manifest caches have explicit
  entry bounds and expiry or invalidation rules. Account changes invalidate
  affected resolver state; credentials/signed URLs are not stored in profiles.
- Back navigation retains at most 30 identifiers instead of full catalogue
  responses. Long lists are built lazily.
- Playback position listeners are separated from catalogue state, so every
  clock tick does not rebuild the entire application. System media state is
  throttled; lyric views listen independently.
- Local file/tag/artwork scanning runs off the UI isolate. Copied embedded art
  is individually bounded; the on-disk art cache is trimmed to 128 MiB.
- Audio transports use bounded 2 MiB range windows rather than caching an
  entire remote recording in RAM. Direct downloads use streaming file writes
  and at most two concurrent tasks with cooperative cancellation.
- Playback owns native deck/video/proxy lifecycles, cancels obsolete selections,
  and restores a paused queue without eagerly starting a decoder.
- PiP owns a separate rendering engine and lightweight snapshots, not a second
  player or another account/profile. Opening PiP therefore still has additional
  Flutter engine/renderer memory cost that must be measured.
- Hidden/minimized windows pause Flutter tickers; the source lyric-focus bypass
  can keep visible unfocused lyric animation active. Playback continues.
- Blur is retained and clipped to the surface that needs it. The explicit
  source reduce-blur preference remains available.

These are implementation choices, not proof that total working-set growth is
bounded. Native decoder caches, GPU allocations, third-party plugins, profile
history and service-response sizes must be included in profiling.

## Reproducible comparison

Use the source commit recorded in the inventory and the exact rewrite commit
under review on **the same machine**, OS version, display, scaling, audio output,
network, local music set and account. Record release builds, runtime/native
library versions and flags. Include the JVM and child processes in the Kotlin
process tree; include all native/Flutter child engines in the rewrite totals.
Keep cold and warm cache runs separate. Use at least five repeats for startup and
track changes, reporting the samples and median/p95 rather than one best result.

| Scenario | Workload | Measurements |
| --- | --- | --- |
| Cold start | Cleared app caches, saved 200-track queue, same sign-in state | Launch-to-first-frame, launch-to-interactive, launch-to-audible first track |
| Warm start | Same profile after graceful exit | Same startup timing, idle after 60 seconds |
| Idle | Home visible for five minutes, no playback | Process-tree RSS/working set, private bytes if available, CPU and GPU |
| Audio | Same 30-minute local and remote playlists | Peak/median memory, decoder buffers, CPU, underruns and output interruptions |
| Browse while playing | Identical Home/search/album navigation and scrolling | UI/raster frame p50/p95/p99, dropped frames, memory after returning Home |
| Lyrics/blur | Same timed lyrics, full player, active/unfocused/minimized states | Frame and CPU/GPU costs with source preferences held equal |
| Video/PiP | Same clip and timestamps, repeated open/close/toggle operations | AV drift, incremental engine/decoder memory and post-close recovery |
| Transition | Gapless next, manual seek/skip and configured crossfade | Audible boundary latency, underruns and overlap gain; AutoMix only after implemented |
| Sustained | Two-hour browse/play/queue cycle and 100 PiP opens/closes | Retained-object/native allocation trend and memory after idle/GC |
| Local scan/download | Same folders and two concurrent transfers | UI responsiveness, CPU, disk growth, cancellation and cleanup |

On Linux collect `/proc` process-tree RSS, `pidstat` CPU/I/O, native allocator
profiles and compositor/GPU counters where available. On Windows use Performance
Monitor/WPR with process-tree private bytes, working set and GPU counters. On
macOS use Instruments/Activity Monitor with physical footprint and allocation
traces. Flutter DevTools profile-mode frame traces supplement release resource
measurements; do not compare debug Flutter frame timings to release Kotlin.

Record raw CSV/JSON/traces, hardware, versions and test scripts with the report.
Never fill unavailable measurements with estimates or extrapolate a Dart heap
size to total desktop RAM. Optimize measured bottlenecks after functional and
visual parity are established; do not remove source visuals to produce a smaller
benchmark number.
