# Music Video memory investigation

The pre-change revision is `f0a0c27e091f9fabe3eea07fb693e58601610c66` from PR #157. A visible Compose window reproduces the reported shape of memory retention. In the first baseline cycle, process memory remained elevated after the last view closed and the session had released both its decoder and published frame. The second cycle returned far closer to the initial idle state.

## Ownership and cleanup

| Owner | Resources and observed lifetime |
| --- | --- |
| `DesktopVideoSession` | The application owner holds a reusable session controller. The controller's current worker and the per-source counts in `users`, `liveSurfaces` and `pendingRelease` are the active state. The 350 ms handoff holds the latest image across quick reattachments and pauses decoding while there are zero consumers. At expiry the slot closes, the active source is removed, and the worker is cancelled. Registry and worker counters return to zero once native close completes. A real end of the audio clock clears the worker and frame even if its view remains mounted. |
| FFmpeg and JavaCPP | `DesktopCanvasDecoder` owns the format and codec contexts, packet and frame, scaler, output pointers and FFmpeg interrupt callback. Codec reorder buffers and the demuxer also own native memory. The worker closes these resources when initialization fails, a track or quality is replaced, or the last surface times out. Native calls have owner cancellation checks and deadlines. An image that arrives after cancellation is closed before publication. |
| `VideoFrame` and Compose | The session holds an image under the same lock used for drawing and disposal. The Music Video surface observes only source, size and revision metadata. The painter reads the actual image inside the session's draw scope. A draw records the native image before the wrapper can be replaced or closed. The observable slot holds one current image. The worker may hold one additional image while pacing against audio. An unrecorded current image stalls further conversion until a draw, seek, or handoff. |
| Native Skia and Skiko | `Image.makeRaster` with native Data uses immutable ref-counted pixels. Releasing the JVM wrapper drops one native reference. A recorded native picture or upload can still own the image. The raster storage regression test replays a recorded red frame after the decoder reuses the source and disposes its references. There is no per-image GPU eviction or completion API on the canvas available to this painter. Releasing or purging the window's shared context would affect unrelated rendering. [SkImages](https://api.skia.org/namespaceSkImages.html), [SkSurface](https://api.skia.org/classSkSurface.html), [GrDirectContext](https://api.skia.org/classGrDirectContext.html). |
| Backdrop, PiP and retained layouts | The Music Video painter does not extract palette or backdrop frames. Canvas and artist motion use their existing small samples and separate ownership. The shared play/pause, seek, fullscreen, minimize, PiP and tray paths continue to use the audio clock and presentation visibility published by the existing player and window owners. PiP attaches to the shared Music Video session while visible and keeps its still artwork fallback. Closing the final video surface releases the decoder while audio remains active. |

Music Video now reuses padded native BGRA scratch and two raster surfaces. FFmpeg writes into the scratch and Skia copies it into the next surface for an immutable snapshot. If Skia still references the previous snapshot of that surface, its raster copy-on-write machinery preserves those pixels. The decoder owns at most three reusable full-size pixel stores, about 23.73 MiB at 1080p, plus codec buffers. Native draws and the graphics driver can temporarily own extra backing stores. The reusable bound is on the producer's slots. This approach adds a native pixel copy, avoids JVM pixel arrays, and preserves immutable lifetime across threads. It leaves animated cover and artist motion on their existing paths. [Skia raster surface implementation](https://skia.googlesource.com/skia/+/refs/heads/main/src/image/SkSurface_Raster.cpp).

The resolver still selects the user's quality, with a default request of 1080p. Once resolved, the FFmpeg conversion edge is capped by the reported rendition's larger dimension and the existing 8192 safety ceiling. When dimensions are unavailable the ceiling still applies. Output aspect ratio is kept. Diagnostics log resolved and output dimensions without stream URLs or headers. This limits conversion output, while decode itself must still process the encoded rendition.

No video-specific GC, allocator trimming, GPU context purge, or changes to audio output, DSP, clock authority or routing were added.

## Reproduce

Use the same host, JDK 21, renderer, desktop mode, clip and 1220 × 780 dp window configuration for both runs. Run inside a graphical Linux session. The harness opens a full-screen Compose window for 60 seconds per cycle, switches to windowed mode and removes the final surface. It uses a generated H.264 file and drives the real Music Video worker, painter and render context with a synthetic audio clock. It does not require provider authentication or an active audio output device.

```bash
ffmpeg -hide_banner -f lavfi -i 'testsrc2=size=1920x1080:rate=30' -t 80 -an \
  -c:v libx264 -preset ultrafast -crf 32 -g 60 -pix_fmt yuv420p /tmp/music-video-1080p.mp4
bash scripts/profile-music-video.sh /tmp/music-video-1080p.mp4 /tmp/music-video-candidate
```

For comparison, compile the old branch's `:desktopApp:compileKotlin` task in a separate checkout using the same desktop settings and JDK. Archive its `desktopApp/build/classes/kotlin/main` with `jar cf /tmp/music-video-baseline.jar -C desktopApp/build/classes/kotlin/main .`. Set `BITCHORD_MV_PROFILE_BASELINE_CLASSES=/tmp/music-video-baseline.jar` when invoking the same harness from the new checkout with a different output directory. This keeps test code, dependencies and capture overhead matched. `BITCHORD_MV_PROFILE_CYCLES` defaults to two per JVM.

The harness records idle after a 15-second warm-up, first video draw, a pause, resume followed by three seeks, fullscreen-to-windowed handoff, final detach and samples at 1, 5, 15, 30 and 60 seconds. Its phase CSV and the existing `profile-desktop-memory.sh` process CSV can be joined by the nearest epoch timestamp. It records HotSpot NMT summaries, JFR thread allocation statistics and object samples, glibc allocator summaries and per-client DRM resident counters when those interfaces are available. NMT describes HotSpot-tracked memory. glibc arena size, free blocks and mmapped sizes describe that allocator's view. DRM reports graphics client buffers, which can overlap mappings counted by the OS. These are independent views of the process and should not be added together.

## Interpreting a close

The decoder and published frame counters reached zero within one second of final detachment on both pre-change baseline cycles. In the first matched baseline cycle, approximately 49 MiB of glibc arena blocks were already free at one second, versus approximately 11 MiB while idle at startup. The allocator still reported approximately 99 MiB of arena space. Its mmapped allocations had already returned to about 24 MiB. At the same moment the DRM client still reported about 36 MiB resident, falling to effectively zero by 60 seconds. HotSpot's committed Java heap remained 60 MiB throughout those baseline cycles. This separates allocator and graphics retention from the session's immediate ownership and shows why the peak-to-close RSS curve alone cannot locate the buffers.

Some runs on this host also swapped a substantial portion of the process under system memory pressure. A drop in RSS/PSS while VmSwap increases is evidence of paging. The phase evidence therefore includes swap, alongside NMT and allocator counters. These counters cannot assign every byte to FFmpeg, Skia or the driver. Proving that every third-party allocation is freed would require temporal native allocation tracing and driver-level ownership instrumentation.

The regression suite exercises 30 complete closes, short handoffs, two simultaneous surfaces, repeated paused seeks and resume, cancellation during a blocking decode, drawing concurrently with session disposal, audio end, and native image lifetime. The visible-window harness asserts that the first frame has actual color detail and that every completed handoff/close returns the instrumented resource counters to their expected values.

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
