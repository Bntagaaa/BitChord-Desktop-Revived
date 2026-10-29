# Desktop memory and responsiveness

Base: `upstream-1109-port` at `3fd06fd5`.
Branch: `feature/reduce-desktop-memory`.

A low Java heap limit alone cannot bound a desktop JVM process. RSS also includes
Skia/native pixel buffers, GPU/driver allocations, FFmpeg, JavaFX/WebKit, ONNX,
thread stacks and shared libraries. Similar RSS in Spotify and BitChord does not
establish similar CPU, GPU, swap or frame-time behavior. The reported 1.2–1.7 GB
workload has not been reproduced here with the user's Brave/Meet session.

## Changes

- The ordinary audio player's 250 ms clock no longer invalidates the entire
  desktop composition. The root observes semantic changes and the Previous-button
  threshold. The live clock continues directly to lyrics, video and playheads;
  mini-player progress is read only during drawing. A 1000-tick regression fixture
  produces two root-facing states, preserving pause, seek, error and device changes.
- The audio command pump waits for a signal while stopped or paused, instead of
  waking every 20 ms (50 times per second) from startup onward. Enqueued commands
  wake it immediately and shutdown interrupts the wait.
- Packaged and JVM-only launchers share an explicit 32 MiB initial heap, 512 MiB
  maximum and 20/40 heap-free ratios, reducing unused committed headroom. The
  existing periodic-GC interval remains 20 seconds. This is not a hard RSS cap.
- Window visibility pauses picture decoding even when Compose views remain mounted
  after minimizing. Retained decoders and the last frame resume without resolving
  video URLs again. Audio continues independently.
- All three decorative motion paths (Canvas, shared-player Canvas, artist motion)
  retain immutable JVM pixel snapshots. Temporary native Skia draw images close
  immediately after recording the draw. Full native bitmaps are created only for
  occasional backdrop/palette capture. Dimensions, aspect, sampling, animation
  pacing, fade, portrait alignment and bottom dissolve are preserved.
- Decoder cleanup encloses opening as well as decoding, covering cancellation
  while an IO dispatcher is opening a stream. Reopening a decoder releases its
  old context. Continued's artist decoder now frees option dictionaries and plane
  tables, and owns the codec before operations which can fail.
- Artwork shares one in-flight fetch/decode per URL. At most four complete loads
  and two decodes execute concurrently, bounding live payloads and CPU bursts.
  A 128 MiB on-disk HTTP cache respects freshness/validators and supports reloads
  after decoded-image eviction. Last-consumer cancellation cancels abandoned HTTP
  work; another mounted consumer keeps shared work alive.
- The existing desktop decoded cache budget is retained to avoid arbitrary churn.
  The shared player's separate Coil cache gets an explicit 32 MiB idle-entry
  budget and two concurrent decodes. No image-resolution or format downgrade is
  requested. Referenced visible images remain valid after cache eviction.
- Palette extraction preserves exactly the prior every-16th-pixel average using
  32-row scratch stripes. Mesh extraction reads only its existing sampled rows.
  Neither allocates a complete source-sized IntArray; temporary mesh bitmaps close
  after transferring their pixel reference to the immutable image.
- Minimize/restore eviction is debounced on a worker. Disk walking and explicit GC
  no longer run directly inside the window visibility event. Restoring before the
  delay cancels pending work. Memory eviction preserves the on-disk HTTP cache and
  active image consumers, avoiding missing artwork after restore.

Blur settings, blur radii, shaders, artwork resolution, video quality, FPS limits,
playback controls, audio processing, Spotify integration and the 512 MiB production
heap limit are unchanged. Model or browser features are not disabled.

## Reproducible frame benchmark

`DesktopMemoryProbe` is test-only. `:desktopApp:memoryProbe` runs it in an isolated
JVM; it is not included in ordinary tests or application startup. Example:

```
./gradlew :desktopApp:memoryProbe -Pmemory.mode=canvas-legacy -Pmemory.frames=1800
./gradlew :desktopApp:memoryProbe -Pmemory.mode=canvas-scoped -Pmemory.frames=1800
```

`-Pmemory.home=/writable/isolated/home` can isolate native extraction in a sandbox.
The legacy mode reproduces the old native-image → Compose bitmap conversion and
its drawing; scoped mode exercises the production `DesktopCanvasFrame` drawing.
Both warm up 200 frames, then render 1800 frames of 540 × 540 BGRA pixels into the
same raster Skia surface, retaining two frames. They run faster than real time.
Both JVMs use the same 512 MiB G1 heap and existing periodic-GC settings.

Three independent JVM runs per mode, Linux x64/JDK 21; medians:

| Metric | Legacy | Scoped |
| --- | ---: | ---: |
| Peak process RSS (`VmHWM`) | 480.3 MiB | 222.2 MiB |
| Render thread CPU time | 609 ms | 384 ms |
| Wall time for 1800 frames | 637 ms | 410 ms |
| GC collections during measured batch | 17 | 17 |
| GC collection time during batch | 28 ms | 25 ms |

This is **53.7% lower peak RSS and 36.9% lower render-thread CPU** in this isolated
fixture. It is not a measurement of the complete player, GPU blur, YouTube network
playback, Spotify, Meet/browser contention, swap or all native model allocations.
It is not a promise that total BitChord RAM becomes 222 MiB or cannot exceed 1 GB.
The test does not establish lower GC frequency: the collection count is unchanged.
Raw run results are in `desktop-memory-frame-results.json`.

## Startup smoke measurement

Three cold launches each of the frozen base package and final ordinary configuration
used isolated preferences/caches and the forced welcome dialog, sampled at 20/25 s.
The 25-second median RSS was 467.7 MiB for the base and 450.0 MiB for the tuned build.
The new build's diagnostic window-created milestones were 1679, 1720 and 1729 ms.
No base diagnostic milestone was captured, so these do **not** establish a faster
window-opening time. The idle difference is small and subject to native/JIT variability.
These launches do not reproduce the user's authenticated Home/player or concurrent
browser/Meet workload. Xvfb fell back to software Skiko rendering, so no GPU cache
limit was adopted from this test. Raw samples: `desktop-memory-startup-results.json`.

## Validation

Regression tests cover request coalescing/concurrency, independent consumer
cancellation, stale-load cache protection, failed-load retry, hide/restore consumer
survival, immutable capture ownership, exact pixel/palette preservation, offscreen
render equivalence and decoder reopen/failure cleanup. Existing player/fullscreen,
queue, beta, artwork and blur tests remain part of the desktop suite.

Full shared/shared UI/desktop tests and Linux `createDistributable` are run for this
change. Additional tests pin actual Compose root recomposition isolation, video
visibility with mounted surfaces and the signal-driven audio pump. Windows native rendering and the user's KDE/Brave/Meet workload require
on-device validation; CI/live GitHub API availability is reported separately.
