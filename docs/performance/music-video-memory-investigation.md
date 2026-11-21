# Music Video memory investigation

The pre-change revision is `f0a0c27e091f9fabe3eea07fb693e58601610c66` from PR #157. A visible Compose window reproduces the reported shape of memory retention. In the first baseline cycle, process memory remained elevated after the last view closed and the session had released both its decoder and published frame. The second cycle fell below the initial RSS while the operating system moved memory into swap.

## Ownership and cleanup

| Owner | Resources and observed lifetime |
| --- | --- |
| `DesktopVideoSession` | The application owner holds a reusable session controller. The controller's current worker and the per-source counts in `users`, `liveSurfaces` and `pendingRelease` are the active state. The 350 ms handoff holds the latest image across quick reattachments and pauses decoding while there are zero consumers. At expiry the slot closes, the active source is removed, and the worker is cancelled. Registry and worker counters return to zero once native close completes. A real end of the audio clock clears the worker and frame even if its view remains mounted. |
| FFmpeg and JavaCPP | `DesktopCanvasDecoder` owns the format and codec contexts, packet and frame, scaler, output pointers and FFmpeg interrupt callback. Codec reorder buffers and the demuxer also own native memory. The worker closes these resources when initialization fails, a track or quality is replaced, or the last surface times out. Native calls have owner cancellation checks and deadlines. An image that arrives after cancellation is closed before publication. |
| `VideoFrame` and Compose | The session holds an image under the same lock used for drawing and disposal. The Music Video surface observes only source, size and revision metadata. The painter reads the actual image inside the session's draw scope. A draw records the native image before the wrapper can be replaced or closed. The observable slot holds one current image. The worker may hold one additional image while pacing against audio. An unrecorded current image stalls further conversion until a draw, seek, or handoff. |
| Native Skia and Skiko | `Image.makeRaster` with native Data uses immutable ref-counted pixels. Releasing the JVM wrapper drops one native reference. A recorded native picture or upload can still own the image. The native regression replays a recorded red frame after the decoder and Kotlin frame wrapper are closed. There is no per-image GPU eviction or completion API on the canvas available to this painter. Releasing or purging the window's shared context would affect unrelated rendering. [SkImages](https://api.skia.org/namespaceSkImages.html), [SkSurface](https://api.skia.org/classSkSurface.html), [GrDirectContext](https://api.skia.org/classGrDirectContext.html). |
| Backdrop, PiP and retained layouts | The Music Video painter does not extract palette or backdrop frames. Canvas and artist motion use their existing small samples and separate ownership. The shared play/pause, seek, fullscreen, minimize, PiP and tray paths continue to use the audio clock and presentation visibility published by the existing player and window owners. PiP attaches to the shared Music Video session while visible and keeps its still artwork fallback. Closing the final video surface releases the decoder while audio remains active. |

Music Video writes swscale output directly to padded native BGRA Data and publishes an immutable Skia image. The application retains one published frame and can own one additional frame while waiting for its audio timestamp. New full-size JVM pixel arrays are unnecessary. A raster pool was tested as a separate candidate. It used padded native scratch, two reusable raster surfaces, one native copy and copy-on-write backing when older pictures were retained. It increased peak and post-close RSS in the controlled capture, so the production path keeps the direct immutable storage. [Skia raster surface implementation](https://skia.googlesource.com/skia/+/refs/heads/main/src/image/SkSurface_Raster.cpp).

The resolver still selects the user's quality, with a default request of 1080p. Once resolved, the FFmpeg conversion edge is capped by the reported rendition's larger dimension and the existing 8192 safety ceiling. When dimensions are unavailable the ceiling still applies. Output aspect ratio is kept. Diagnostics log resolved and output dimensions without stream URLs or headers. This limits conversion output, while decode itself must still process the encoded rendition.

No video-specific GC, allocator trimming, GPU context purge, or changes to audio output, DSP, clock authority or routing were added.

## Reproduce

Use the same host, JDK 21, renderer, desktop mode, clip and 1220 × 780 dp window configuration for both runs. Run inside a graphical Linux session. The harness opens a full-screen Compose window for 60 seconds per cycle, switches to windowed mode and removes the final surface. It uses a generated H.264 file and drives the real Music Video worker, painter and render context with a synthetic audio clock. It does not require provider authentication or an active audio output device.

```bash
ffmpeg -hide_banner -f lavfi -i 'testsrc2=size=1920x1080:rate=30' -t 80 -an \
  -c:v libx264 -preset ultrafast -crf 32 -g 60 -pix_fmt yuv420p /tmp/music-video-1080p.mp4
bash scripts/profile-music-video.sh /tmp/music-video-1080p.mp4 /tmp/music-video-candidate
```

For comparison, compile the old branch's `:desktopApp:compileKotlin` task in a separate checkout using the same desktop settings and JDK. Archive its `desktopApp/build/classes/kotlin/main` with `jar cf /tmp/music-video-baseline.jar -C desktopApp/build/classes/kotlin/main .`. Set `BITCHORD_MV_PROFILE_BASELINE_CLASSES=/tmp/music-video-baseline.jar` when invoking the same harness from the new checkout with a different output directory. This keeps test code, dependencies and capture overhead matched. `BITCHORD_MV_PROFILE_CYCLES` defaults to two per JVM. On Linux hosts with a systemd user manager, `BITCHORD_MV_PROFILE_NO_SWAP=1` starts the test inside a benchmark-only scope with `MemorySwapMax=0` and verifies the test worker inherited the limit.

The harness records idle after a 15-second warm-up, first video draw, a pause, resume followed by three seeks, fullscreen-to-windowed handoff, final detach and samples at 1, 5, 15, 30 and 60 seconds. Its phase CSV and the existing `profile-desktop-memory.sh` process CSV can be joined by the nearest epoch timestamp. It records HotSpot NMT summaries, JFR thread allocation statistics and object samples, glibc allocator summaries and per-client DRM resident counters when those interfaces are available. NMT describes HotSpot-tracked memory. glibc arena size, free blocks and mmapped sizes describe that allocator's view. DRM reports graphics client buffers, which can overlap mappings counted by the OS. These are independent views of the process and should not be added together.

## Interpreting a close

The decoder and published frame counters reached zero within one second of final detachment on both pre-change baseline cycles. In the first matched baseline cycle, approximately 49 MiB of glibc arena blocks were already free at one second, versus approximately 11 MiB while idle at startup. The allocator still reported approximately 99 MiB of arena space. Its mmapped allocations had already returned to about 24 MiB. At the same moment the DRM client still reported about 36 MiB resident, falling to effectively zero by 60 seconds. HotSpot's committed Java heap remained 60 MiB throughout those baseline cycles. This separates allocator and graphics retention from the session's immediate ownership and shows why the peak-to-close RSS curve alone cannot locate the buffers.

Some runs on this host also swapped a substantial portion of the process under system memory pressure. A drop in RSS/PSS while VmSwap increases is evidence of paging. The phase evidence therefore includes swap, alongside NMT and allocator counters. These counters cannot assign every byte to FFmpeg, Skia or the driver. Proving that every third-party allocation is freed would require temporal native allocation tracing and driver-level ownership instrumentation.

The regression suite exercises 30 complete closes, short handoffs, two simultaneous surfaces, repeated paused seeks and resume, cancellation during a blocking decode, drawing concurrently with session disposal, audio end, and native image lifetime. The visible-window harness asserts that the first frame has actual color detail and that every completed handoff/close returns the instrumented resource counters to their expected values.

## Music Video 1080p controlled comparison

Three completed 60-second captures used the same direct JUnit launcher, 64 MiB committed Java heap, identical H.264 source/window geometry, and a systemd scope with `MemorySwapMax=0`. The first generation used fresh 1920 × 1080 BGRA frames as in PR #157. The tested pool used two owned surfaces and reusable scratch. The final build retains the lifecycle and quality changes with direct immutable native frames. Each column is one complete controlled cycle. An extra baseline run closed its window before the last sample and was excluded from the table. Values are in MiB unless marked otherwise.

| Metric | Baseline | Raster trial | Final immutable path |
| --- | ---: | ---: | ---: |
| Peak RSS | 312.3 | 342.1 | 336.7 |
| Initial idle RSS | 238.4 | 245.3 | 226.8 |
| RSS 1 s after final detach | 250.3 | 308.9 | 336.6 |
| RSS 60 s after final detach | 244.2 | 289.1 | 293.1 |
| PSS 60 s after final detach | 199.4 | 244.4 | 249.6 |
| Private Dirty 60 s after final detach | 190.4 | 215.4 | 236.9 |
| JVM used heap 60 s after final detach | 26.5 | 25.3 | 25.1 |
| NMT total committed 60 s after final detach | 204.4 | 204.7 | 204.4 |
| glibc arena size / free blocks at 60 s | 90.9 / 36.2 | 110.9 / 56.3 | 126.6 / 72.8 |
| glibc arena minus free blocks, plus mmap allocations, at 60 s | 64.8 | 64.7 | 64.7 |
| Approx. JFR-sampled allocation during play (MiB/s) | 0.505 | 0.525 | 0.596 |
| Active decoders, owned frames at 1 s and later | 0, 0 | 0, 0 | 0, 0 |
| File descriptors at 60 s | 178 | 174 | 177 |
| Draw calls in 60 s of playback | 1,800 | 1,805 | 1,804 |

The final run logged 1920 × 1080 input and output and reached zero active workers, published frames, owned frame bytes, surfaces and pending handoffs by the one-second check. DRM reported approximately 23.5 MiB resident one second after final detach and zero by 15 seconds. The lower resident peak and absence of extra full-size scratch justified discarding the raster pool. The final peak and post-close RSS were still above the main controlled baseline run. The excluded partial baseline also illustrates the variation, with a 336.4 MiB peak and 296.4 MiB RSS at 30 seconds. The final run's extra 36.6 MiB of free glibc arena blocks at 60 seconds accounts for much of its residual gap, although `malloc_info` reports allocator bookkeeping and does not measure the residency of each block. NMT and the approximate in-use bytes were comparable at the end of all three controlled runs. There is no demonstrated reduction in post-close RSS. [Controlled phase evidence](unified-video-engine-evidence/music-video-1080p-no-swap.csv).

## Music Video 1080p raster pool trial

CachyOS x86-64, KDE Wayland/XWayland, Compose OpenGL, Intel UHD Graphics, Mesa 26.2.6, glibc 2.44, Temurin 21.0.13. The first comparison ran two cycles per JVM, with NMT and the same clip, window geometry, warm-up and capture procedure. The Gradle workers committed 60 MiB (baseline) and 64 MiB (candidate) of Java heap. The host was allowed to swap. Values are shown as cycle 1 / cycle 2 in MiB unless stated otherwise.

| Metric | PR #157 before | Reusable raster trial |
| --- | ---: | ---: |
| Peak RSS across both cycles | 347.4 | 365.0 |
| Idle RSS | 258.1 / 230.8 | 246.7 / 262.2 |
| RSS 1 s after final detach | 254.6 / 234.1 | 288.4 / 251.8 |
| RSS 60 s after final detach | 230.8 / 87.6 | 262.2 / 63.4 |
| PSS 60 s after final detach | 168.0 / 47.0 | 208.9 / 20.8 |
| Private Dirty 60 s after final detach | 125.0 / 38.1 | 131.4 / 8.2 |
| Swap 60 s after final detach | 75.7 / 179.1 | 77.0 / 248.0 |
| JVM used heap 60 s after final detach | 40.6 / 39.4 | 37.5 / 38.3 |
| NMT total committed 60 s after final detach | 208.4 / 209.2 | 212.1 / 212.9 |
| glibc arena size / free blocks 60 s after final detach | 98.8 / 54.1, 101.8 / 57.2 | 98.6 / 54.2, 141.2 / 96.3 |
| glibc arena minus free blocks, plus mmap allocations, at 60 s | 69.0 / 68.9 | 68.7 / 69.2 |
| Approx. JFR-sampled JVM allocation rate during playback, MiB/s | 0.809 / 0.775 | 0.839 / 0.790 |
| Decoder and owned full-size frame at 1 s and each later stage | 0 / 0 | 0 / 0 |
| Active session workers at 1 s and each later stage | Counter unavailable | 0 / 0 |
| Open file descriptors 60 s after final detach | 217 / 217 | 218 / 216 |
| Draw calls in the 60 s playback segment | 1,803 / 1,795 | 1,806 / 1,803 |

The full [phase evidence](unified-video-engine-evidence/music-video-1080p-lifecycle.csv) includes the 5, 15 and 30 second values, session counters, output frame payload, driver resident memory and sampled thread counts. Process values are matched to the closest one-second sample. Peak RSS uses the maximum high-water mark across all process samples. JFR rates sum observed deltas in per-thread allocation counters over the playback segment. Short-lived threads can be missed. Each baseline frame requested a new full-size native Data allocation. The candidate reuses the scratch and raster slots, with copy-on-write backing allocations as required by recorded draws. The profiles do not count every internal Skia or driver allocation. Draws count canvas submissions, including possible redraws, and are not compositor frame-drop or vblank timings.

The raster trial held about 23.7 MiB of reusable native frame stores at 1080p while open. After close, glibc still reported arena address space as free blocks. The approximate in-use glibc calculation was about 69 MiB at both closes on both revisions. The trial's peak RSS was higher, and the increase in swap makes the second cycle's low resident values unsuitable as evidence of freeing more memory. The data supports prompt release of BitChord's session resources and rules out a decoder or published-frame accumulation in these runs. It does not establish a peak-RSS reduction or complete attribution of graphics memory.

## Spotify Canvas in Now Playing

Spotify Canvas uses the engine's looping motion lease with a maximum decoded edge of 540 pixels. A portrait fixture resolves to approximately 302 × 540, or 0.62 MiB of BGRA payload per decoded frame. Now Playing also receives a separate backdrop sample up to 128 pixels on its longest edge. The 540 × 960 fixture produces a backdrop sample of 36,864 bytes. The derived palette or mesh is small and holds colors. The downloaded clip remains in the bounded disk cache.

Before the change, a still-mounted Now Playing screen could retain its last backdrop sample by URL after the Canvas stopped displaying. Also, the Canvas draw node and its conditional offscreen composition layer remained mounted while its frame was empty. The renderer keeps only frame size and revision metadata now, and removes the Canvas draw node when the image goes away. The shared player drops the backdrop sample when the renderer reports it has stopped. Pause and active fades keep their existing frame and effects. The final motion lease closes the frame and decoder and removes the session record after the cancelled worker finishes.

The separate Canvas harness exercises the production composable in a visible window. It plays the same portrait H.264 clip twice, first losing presentation visibility with the composable still mounted and then disabling Canvas while the window stays open.

```bash
ffmpeg -hide_banner -f lavfi -i 'testsrc2=size=540x960:rate=30' -t 8 -an \
  -c:v libx264 -preset ultrafast -crf 30 -g 60 -pix_fmt yuv420p /tmp/spotify-canvas.mp4
BITCHORD_VIDEO_PROFILE_SCENARIO=canvas bash scripts/profile-music-video.sh \
  /tmp/spotify-canvas.mp4 /tmp/canvas-candidate
```

Both revisions completed the same two cases with process swap at zero. The fixture used the stock Compose OpenGL renderer and a 1220 × 780 dp window, which remained open after each detach. Values are in MiB.

| Stage | Baseline RSS / PSS | Candidate RSS / PSS |
| --- | ---: | ---: |
| Idle before the first Canvas | 234.3 / 192.0 | 275.4 / 231.2 |
| Playing before window hide | 324.1 / 281.4 | 322.8 / 277.5 |
| 1 s after window hide | 324.3 / 280.7 | 322.9 / 277.6 |
| 30 s after window hide | 322.6 / 277.7 | 326.3 / 280.9 |
| Playing before Canvas disable | 328.2 / 283.3 | 331.0 / 285.5 |
| 1 s after Canvas disable | 328.2 / 283.3 | 331.0 / 285.5 |
| 30 s after Canvas disable | 322.6 / 277.9 | 331.2 / 285.6 |
| Peak RSS overall | 333.6 | 335.9 |

The candidate's session record and backdrop sample were both gone by the first post-close sample in each case and stayed gone. Its JVM committed heap remained 64 MiB. Private Dirty after 30 s was 221.0 MiB after hide and 225.9 MiB after disable, compared with 217.6 MiB and 218.9 MiB before the change. The desktop Compose pixel test additionally verifies that the draw node is removed and the still artwork is visible after hiding. The profiles support the resource cleanup and reproduce the resident plateau even when swap is disabled. They do not show an RSS reduction for Spotify Canvas. The different initial idle values and continued lifetime of the Compose window limit attribution of the remaining memory. The test uses Spotify Canvas presentation settings with a local clip, without logging in to Spotify. [Canvas phase evidence](unified-video-engine-evidence/spotify-canvas-lifecycle.csv).

## Build and runtime coverage

The Linux desktop app, shared, runtime and desktop UI suites passed locally with the JVM regression suites passing. The opt-in visible Music Video and Spotify Canvas runs were executed separately. The Linux desktop distributable was built and checked against 42 freshly compiled player classes. Packaged AutoMix JNI, resampling and model checks passed. Actual Windows GPU presentation was previously deferred. GitHub actions cover Linux and Windows JVM tests, Linux AppImage/DEB/RPM packaging, Windows ZIP/EXE/MSI packaging and packaged JNI/model tests. Provider-backed playback against a real account and simultaneous audio-output timing were outside this local fixture.

## Sustained fullscreen animated artwork

The earlier Canvas profile kept a small Canvas composable visible briefly before testing close. To investigate growth while the player is left open, this profile renders the real `DesktopPlayerSheet` and landscape `NowPlayingScreen` in a fullscreen Compose OpenGL window. It uses a local portrait H.264 loop, a still cover extracted from the same fixture, the Default backdrop, and a synthetic playhead updated every 500 ms. The sequence records a static baseline, 90 seconds of looping, 45 seconds paused on the last frame, and 15 seconds with artwork animation disabled. Linux swap is disabled only for the profile scope.

JFR identified repeated Flow collector and short-circuit exception allocations under the motion decoder's active loop. The decoder now reads the current running state while playing and subscribes only when it needs to wait. The landscape artwork also used to capture one native bitmap for its default no-op callback. It now opts out. Layouts that use video colors still capture fresh frames, while paused or stalled frames are no longer sampled repeatedly.

```bash
ffmpeg -hide_banner -i /tmp/spotify-canvas.mp4 -frames:v 1 /tmp/canvas-cover.png
BITCHORD_VIDEO_PROFILE_SCENARIO=canvas BITCHORD_CANVAS_FULLSCREEN_SECONDS=90 \
  BITCHORD_CANVAS_PROFILE_COVER=/tmp/canvas-cover.png BITCHORD_MV_PROFILE_NO_SWAP=1 \
  bash scripts/profile-music-video.sh /tmp/spotify-canvas.mp4 /tmp/canvas-fullscreen
```

Rebuild the baseline with the same profile test and script to compare both the shared UI and desktop decoder revisions. Allocation rates from JFR estimate Java allocation over the timed playback period. Sampled object stacks identify allocation sites. Neither describes retained native allocations. The local fixture does not reproduce provider-specific playback, library pressure, or the exact session reported for “7 rings”.

| Stage | Baseline RSS / PSS (MiB) | Candidate RSS / PSS (MiB) |
| --- | ---: | ---: |
| Static, before enabling motion | 377.6 / 324.7 | 346.0 / 296.8 |
| Playing at 15 s | 414.2 / 361.2 | 378.5 / 329.9 |
| Playing at 90 s | 397.5 / 349.7 | 339.9 / 297.6 |
| Paused for 45 s | 395.2 / 347.5 | 339.7 / 299.6 |
| Animation disabled for 15 s | 394.8 / 347.0 | 341.7 / 301.6 |

The baseline had a cold-start RSS peak of 416.4 MiB versus 385.6 MiB for the candidate. The starting values differed by about 32 MiB, which means the peak difference cannot be credited to this change. Neither run reproduced continuously increasing RSS. DRM client resident memory moved with playback, from approximately 143–149 MiB at the end of the active phase to 64–65 MiB after 45 seconds paused. Both runs removed the motion session after disabling animation. [Fullscreen phase samples](unified-video-engine-evidence/fullscreen-canvas-steady-state.csv).

The estimated JVM allocation rate changed from 0.875 to 0.851 MiB/s over the same active interval. Nine sampled Flow objects had stacks in the baseline motion decoder and zero did in the candidate. This confirms removal of the repeated collector path in this capture. It does not establish a general reduction in RSS or rule out a slower, session-specific native leak. The Canvas UI regression verifies that the still frame is kept during pause, repeated sampling stops, the zero-capture request draws without taking a sample, and hiding still restores artwork.
