# Unified video engine benchmarks

The visible-window 1080p playback and post-close measurements for the Phase 1 software Music Video path are in the [Music Video memory investigation](music-video-memory-investigation.md). Those runs include the Compose renderer, audio-clock pacing, seeking, window transitions and the post-close allocator/driver observations.

## Measured native pipeline probe

November 20, 2026, CachyOS Linux x86-64, KDE Wayland with XWayland, Intel UHD Graphics (ICL GT1), Intel iHD 26.3.5, Mesa 26.2.6, GStreamer 1.28.9. Both modes used the same 1080p H.264 fixture, 30 frames at 30 FPS and no audio. Each of 30 cycles opens, plays to EOS, sets the pipeline to NULL, and releases its references in the same process. All 30 cycles completed for both modes. There is no JVM in this comparison.

| Measurement | VA-API DMA-BUF → native Wayland | libav CPU decode → BGRx → native Wayland |
| --- | ---: | ---: |
| Peak RSS (KiB), process high-water mark | 35,248 | 110,756 |
| Median sampled RSS from 5 s onward (KiB) | 32,720 | 102,308 |
| Median sampled PSS from 5 s onward (KiB) | 26,695 | 95,024 |
| CPU usage, percentage of one logical CPU | 3.93% | 23.96% |
| Median / max time to first buffer at sink input (ms) | 31.71 / 53.83 | 78.24 / 135.06 |
| Sink rendered / reported dropped (900 encoded frames) | 900 / 0 | 899 / 0 |
| Median / max pipeline teardown (ms) | 7.42 / 17.57 | 7.16 / 17.99 |
| Elapsed wall time (s) | 31.50 | 33.76 |

The software route originally failed negotiation with the decoder's direct I420 output. An explicit BGRx conversion negotiated and played. The software sink reported 899 rendered frames and zero drops; these counters do not explain the missing frame or count compositor drops. Likewise sink-input timing precedes actual presentation. The VA samples showed 5 threads and 17 file descriptors at both ends of the measured interval. RSS rose from 31,548 to 33,416 KiB between the first sample after 5 s and the last sample. These samples include live pipelines or teardown, depending on timing, and the short run does not establish that retained memory plateaus.

The probe did not measure allocator-level native bytes, GPU allocations, buffer allocation rate or compositor frame timings. Native process CPU includes conversion and GStreamer work. Process memory omits compositor and separately accounted driver memory. This is a native decode/presentation comparison with generated media. Application memory and performance improvements remain unmeasured.

Evidence includes [VA cycle timings and caps](unified-video-engine-evidence/wayland-va.txt), [software cycle timings and caps](unified-video-engine-evidence/wayland-cpu.txt), [VA process samples](unified-video-engine-evidence/wayland-va-memory.csv), [software process samples](unified-video-engine-evidence/wayland-cpu-memory.csv), [landscape capture](unified-video-engine-evidence/wayland-landscape.png), and [portrait capture](unified-video-engine-evidence/wayland-portrait.png). The direct libmpv control observed VA-API and 148 successful swaps during the five-second landscape clip. It used an SDL Wayland framebuffer outside Compose.

The XWayland Compose child showed the 1080p landscape fixture letterboxed, with 150 frames at the sink and 0 reported drops. The screenshot shows rectangular boundaries and unshaded video below the midpoint, although Compose requests a rounded clip and a dark bottom gradient. Enabling `compose.interop.blending` produced the same result. [Default Compose capture](unified-video-engine-evidence/compose-x11-desktop.png), [interop blending capture](unified-video-engine-evidence/compose-x11-blending.png), [sink log](unified-video-engine-evidence/compose-x11-desktop.txt).

## Reproduce the probes

Install a C11 compiler, pkg-config, GStreamer 1.x core/base plus development headers, the Good MP4 demuxer, Bad H.264 parser/VA/Wayland/D3D elements as applicable, the OpenGL plugin for X11, the libav plugin and FFmpeg with libx264. On Windows use an MSYS2 UCRT64 shell and the [packages listed for the probe workflow](../../.github/workflows/video-backend-probe.yml). The workflow's fakesink run validates init, finite decode and failure exit codes. Real GPU evidence requires a native desktop session.

```bash
out="$(mktemp -d)"
bash tools/video-backend-probe/prepare.sh "$out"
"$out/gstreamer-probe" wayland-va "$out/landscape.mp4" 1
"$out/gstreamer-probe" wayland-va "$out/portrait.mp4" 1
"$out/gstreamer-probe" wayland-cpu "$out/landscape.mp4" 1
GST_GL_WINDOW=x11 GST_GL_PLATFORM=egl "$out/gstreamer-probe" x11-va "$out/landscape.mp4" 1
```

For each benchmark mode, run the one-second fixture 30 times from the same native process. Start the sampler against that PID while it runs. For CPU and peak RSS, obtain child resource usage via `wait4(2)` or run the equivalent command under GNU `time -v`. Capture caps negotiation and the sink counters from the probe log.

```bash
mode=wayland-va # Repeat with wayland-cpu, after this run closes.
"$out/gstreamer-probe" "$mode" "$out/cycles.mp4" 30 > "$out/$mode.log" 2>&1 &
pid=$!
BITCHORD_MEMORY_PROFILE_JCMD=0 bash scripts/profile-desktop-memory.sh "$pid" 40 1 "$out/$mode"
wait "$pid"
```

Set `WAYLAND_DISPLAY` to a nonexistent socket for a native startup failure. Expect a nonzero status and zero frames. Run the software path separately on the real display to check negotiation and visible output. This demonstrates bounded failure and a separate fallback path. In-app recovery and automatic selection still need implementation.

For the real Compose/AWT child probe, use JDK 21 and absolute paths. Set `BITCHORD_GSTREAMER_MODE` to `x11-va`, `x11-cpu`, `d3d11` or `d3d11-cpu` as applicable. The native probe binary takes the realized AWT component handle. While it runs, capture the test window with the desktop compositor's screenshot tool. Compose draws a dark vertical gradient over the video and requests a rounded clip. Inspect both effects in the capture.

```bash
BITCHORD_DESKTOP_ONLY=true GST_GL_WINDOW=x11 GST_GL_PLATFORM=egl \
  BITCHORD_GSTREAMER_PROBE="$out/gstreamer-probe" \
  BITCHORD_GSTREAMER_CLIP="$out/landscape.mp4" \
  ./gradlew :desktopApp:test --rerun --tests '*DesktopGStreamerOverlayProbeTest'
```

For the alternate direct libmpv control, install libmpv and SDL3 development packages. Run under native Wayland rather than selecting an X11 or offscreen SDL driver.

```bash
cc -std=c11 -Wall -Wextra -Werror tools/video-backend-probe/mpv-wayland-probe.c \
  -o "$out/mpv-wayland-probe" $(pkg-config --cflags --libs mpv sdl3)
SDL_VIDEODRIVER=wayland "$out/mpv-wayland-probe" "$out/landscape.mp4"
```

## Comparable BitChord runs

**A — Measured Linux baseline.** Built the current production sources from `87ada9ad` with JDK 21, including the Linux AutoMix library, and launched the packaged launcher with fresh configuration and NMT enabled. The welcome overlay was visible on Home, with no track playing. After 20 seconds of warm-up, 59 samples over 60 seconds yielded median RSS of 726,940 KiB, median PSS of 697,838 KiB, and a process peak RSS of 752,056 KiB. The initial JVM snapshot reported 111,772 KiB heap used and 128,000 KiB heap committed. NMT reported 325,299 KiB total committed, which includes heap and excludes some third-party allocations. Idle CPU and GPU memory were outside this measurement. [Idle process samples](unified-video-engine-evidence/bitchord-idle-memory.csv), [packaged idle capture](unified-video-engine-evidence/bitchord-idle.png).

Use immutable baseline and candidate packages from the same configuration on Linux and Windows. Hold output settings, screen geometry, GPU driver, media sources and warm-up time fixed. Start a new app process for each scenario. Capture a screen recording or repeatable screenshot at the same playhead positions and record user-visible startup, frame timings and resource counters. Measure each process through setup, steady state, and a fixed post-close interval. On Linux use [the desktop sampler](../../scripts/profile-desktop-memory.sh) for RSS/PSS/fds and enable JVM NMT with `-XX:NativeMemoryTracking=summary` before launch. Use JFR for JVM allocation rate. On Windows gather private bytes, working set and CPU via process counters and a trace of presentation events. Include native allocator or GPU byte counts only if the platform instrumentation provides them.

| Run | Repeatable scenario and evidence to retain |
| --- | --- |
| A — Idle | Fresh launch with video unmounted. Sample 60 s after 20 s of warm-up. Save Home and mini-player captures. |
| B — Animated Now Playing | Use one fixed animated track. Record startup and 60 s steady state. Close the player and sample another 60 s. Save expanded, animated cover, gradient and blur captures. |
| C — Fullscreen | Open and close fullscreen 30 times from the same scrolled Home feed. Capture portrait and landscape at the same times and check that Home feed and scroll return without refetch. |
| D — Artist motion | Open 20 fixed artist pages with motion; scroll each hero fully offscreen, scroll back, then leave. Record active decoder/surface counts and screenshots of the banner and overlays. |
| E — Music video | For 1080p and supported 4K, run ten minutes each, pause, seek forward/back, change speed and switch aspect modes. Save quality and audio-clock evidence and a fullscreen/windowed capture. |
| F — Source changes | Switch between 30 predetermined static and animated tracks. Wait for still/video readiness on each before advancing. Record orphaned sessions and post-close retained resources. |
| G — PiP/tray | Cycle main, fullscreen, PiP, minimized and tray, then restore. Capture PiP UI and validate continuing audio, with visible surface counts. |
| H — Software fallback | Force software mode and repeat B and E. Check first-frame presentation, startup timeouts, allocations and bounded pools. |
| I — Renderer failure | Inject a renderer initialization failure and loss after playback. Record readiness, teardown, recovery, fallback count and fallback rendering. |

The hardware migration is gated. Full-application runs B–I and base/candidate visual comparisons are pending. The software decoder and Compose texture experiments below use isolated fixtures.

## Unified software decode and raster draw

On November 21, the baseline jar from `87ada9ad` and the unified candidate jar decoded the same generated H.264 files under JDK 21 with a 512 MiB heap cap. Each run decoded and drew 180 frames to the same 320×180 Skia raster target. The harness aimed for 30 FPS, with no skipped inputs. The older path copied the decoded frame to JVM scratch storage, cloned it and recreated the raster image on draw. The candidate writes packed pixels directly into native Skia storage.

| Fixture and mode | Peak RSS from wait4 (KiB) | Median sampled RSS (KiB) | Decode-and-draw thread allocations per frame | CPU (one logical CPU) | First draw (ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1080p baseline | 645,868 | 642,692 | 8,300,232 B | 77.92% | 1,049.60 |
| 1080p unified | 129,744 | 130,210 | 8,471 B | 60.79% | 862.22 |
| 4K baseline | 690,300 | 688,082 | 33,183,433 B | 88.31% | 2,200.56 |
| 4K unified | 185,928 | 184,044 | 8,471 B | 77.82% | 2,410.94 |

Thread allocations include the reflection and harness overhead and exclude other threads and native allocations. The 1080p peak from Linux `wait4` and sampled `VmRSS` use different kernel accounting. The first draw includes JVM/Skia startup. One run per mode cannot establish a startup improvement, a playback frame rate or long-session retention. The 4K candidate completed in 8.41 seconds for six seconds of source video, including 2.41 seconds to its first draw. The baseline took 10.33 seconds. These are decoder-and-raster comparisons. Full UI playback, GPU memory and ten-minute stream measurements are pending. [1080p baseline](unified-video-engine-evidence/software-1080p-baseline.txt), [1080p unified](unified-video-engine-evidence/software-1080p-candidate.txt), [4K baseline](unified-video-engine-evidence/software-4k-baseline.txt), [4K unified](unified-video-engine-evidence/software-4k-candidate.txt).

Use the [software harness](../../tools/video-backend-probe/SoftwareDecoderBenchmark.java) with the app jar from each build and the same dependency directory. Generate each clip with FFmpeg and `testsrc2=size=1920x1080:rate=30` or `testsrc2=size=3840x2160:rate=30`, `-t 6 -an -c:v libx264 -preset ultrafast -pix_fmt yuv420p`. Use JDK 21 rather than the stripped packaged launcher to access the thread allocation counter. For Linux, run each invocation under GNU `time -v` or use `wait4(2)` to obtain CPU and high-water RSS.

```bash
# package_deps points to the Gradle distribution's lib/app directory.
# skiko_jar is the resolved skiko-awt-runtime-linux-x64 jar with its native library.
javac -cp "$package_deps/*" -d "$out" tools/video-backend-probe/SoftwareDecoderBenchmark.java
java -Xms32m -Xmx512m -cp "$out:$baseline_jar:$skiko_jar:$package_deps/*" \
  SoftwareDecoderBenchmark baseline "$out/1080p.mp4"
java -Xms32m -Xmx512m -cp "$out:$candidate_jar:$skiko_jar:$package_deps/*" \
  SoftwareDecoderBenchmark candidate "$out/1080p.mp4"
```

## Compose Wayland texture proof

Use GStreamer 1.24 or newer with VA and the current SDL3, EGL and GL development packages. The tested Intel driver exposes the required tiled `AB24` modifier. The probe owns a native Wayland window and presents through the desktop Compose scene after composition. It asserts real video pixels, rounded clipping and the bottom gradient from the composed framebuffer and resizes from 640×480 to 720×520. Its one-shot readbacks are diagnostic.

```bash
cc -std=c11 -fPIC -shared -Wall -Wextra -Werror tools/video-backend-probe/gstreamer-compose-gl.c \
  -o "$out/libbitchord-texture-probe.so" \
  $(pkg-config --cflags --libs sdl3 egl gl gstreamer-app-1.0 gstreamer-allocators-1.0 gstreamer-video-1.0)
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=1280x720:rate=30' \
  -t 8 -an -c:v libx264 -preset ultrafast -pix_fmt yuv420p "$out/texture.mp4"
BITCHORD_DESKTOP_ONLY=true SDL_VIDEODRIVER=wayland \
  BITCHORD_GSTREAMER_TEXTURE_PROBE="$out/libbitchord-texture-probe.so" \
  BITCHORD_GSTREAMER_CLIP="$out/texture.mp4" \
  BITCHORD_GSTREAMER_TEXTURE_CAPTURE="$out/compose-texture.png" \
  ./gradlew :desktopApp:test --rerun --tests '*DesktopGStreamerTextureProbeTest'
```

An opt-in 720p run swapped 200 of 240 input frames and resized successfully. The appsink drops superseded frames when the host falls behind. This is proof of the texture path and the effects, with further work needed for frame pacing and production host integration. A live Windows GPU run is deferred.

The [resized framebuffer capture](unified-video-engine-evidence/compose-wayland-texture.png) shows the video cropped behind the rounded clip and bottom fade. [Texture caps and swap counters](unified-video-engine-evidence/compose-wayland-texture.txt).
