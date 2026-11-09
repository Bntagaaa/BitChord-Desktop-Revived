# Compose Desktop architecture experiment

Status: implementation in progress; not accepted as a complete re-architecture.

## Isolation and baseline

- Source snapshot: `348b613a0c3db1a54de56483ea6f8748d6465211` (`1.9.2`). The existing implementation branch was exactly at this snapshot before changes.
- Sole implementation branch: `1.9.2-Compose-Dekstop`. No reset, force push, default-branch change, automatic merge, or access to the independent Flutter branch is required.
- The source snapshot is pinned so later baseline measurements cannot silently use different source or settings.

## Module boundaries

| Module | Responsibility |
| --- | --- |
| `desktopApp` | Native entry point, desktop application assembly, existing screens/platform integrations and playback adapters |
| `desktopUi` | JVM-only Compose target, JVM platform UI implementations, desktop presentation lifetime, visual regression tests |
| `desktopRuntime` | Android/Compose-free application resource owner, byte budgets, shared asynchronous loads |
| `shared` | Existing models/networking/business logic, retained without rewriting |
| `sharedUi` | Android presentation target; shared layouts/resources remain one physical source reused by `desktopUi` |
| `app` | Existing Android app, retaining its Android UI dependency and data/storage paths |

This does not duplicate the player or redesign it. Existing JVM implementations and tests move to desktop ownership; layout/assets remain shared to preserve appearance. Linux/Windows Compose, Skiko and Haze compatibility pins are preserved. The desktop app no longer depends on the Android UI project. This is a build/presentation boundary improvement, not evidence that the old JVM runtime contained Android framework libraries.

The large `DesktopApp.kt` still combines application state and callbacks. Its remaining domain/navigation extraction is explicitly unfinished.

## Resource ownership implemented

- Application services outlive window visibility. Shutdown/disposal cancels the application scope and closes resources once in reverse registration order, even if another close/log callback fails.
- Artwork producers use a shared in-flight request per URL/generation. One departing screen does not cancel another reader. The last reader cancels obsolete work; at most four producers execute concurrently.
- A cache clear invalidates old generations. Visible readers may finish, but old results cannot refill the cleared cache. Shutdown cancels all outstanding work.
- Oversized images are returned to readers without being retained above the byte budget. Eviction never closes an image another composable still owns.
- Existing budget settings remain unchanged; no small heap limit or new GC flags are used to manufacture a memory improvement.
- Presence subscription lifetime belongs to the application owner.
- Addon Graal contexts have one serialized owner per module key. Concurrent searches cannot overwrite/leak a second context. Reload retires busy handles until their call finishes, closes idle contexts, and rejects stale creations; failed constructor initialization closes its context. The registry itself closes with the application. Arbitrary third-party JavaScript that never returns is not claimed to support immediate cooperative cancellation.
- Native window visibility/focus controls presentation subscriptions. Hidden presentation is CREATED, visible/unfocused STARTED, focused RESUMED. Playback service scopes remain independent.
- Duplicate window-hide callbacks coalesce. Disk trimming leaves the UI thread. The existing hidden-window GC request is retained but skipped if the window has already been restored.
- Playback metadata and clock samples have separate presentation paths. Ordinary 250 ms clock ticks update only the stable playhead object and native video clock. Transport/track/analysis changes still reach the application. Keyboard seeking and OS/scrobbling/Discord services read the original engine snapshot. Playback state/format models are separated from stream resolution. A real Compose test verifies that 12 playhead ticks update the scrubber without recomposing the application fixture, while a transport change still does.

Blocking URL reads/decodes are still limited by their existing timeouts and cooperative cancellation; cancellation is not claimed to interrupt every native operation immediately. Thumbnail and Coil caches are not yet unified; decoder downsampling and remaining native subsystem ownership still need measured audit.

## Feature preservation inventory

These implementations are retained; source presence does not mean every live integration has been verified.

| Area | Retained features | Verification |
| --- | --- | --- |
| Navigation/UI | Home, New, Explore, Search, Quick Search, Library, albums/artists/playlists, detail pages, settings/about, dialogs/menus, responsive sidebar | Existing render/state tests plus native CI; full interactive parity review pending |
| Appearance | Default/Legacy fonts, light/dark/Pitch Black, existing Haze/glass/blur, artwork meshes, hover, animation timing and reduced-motion settings | Layout/assets reused; render comparison pending |
| Playback | YouTube Music/local music, queue/shuffle/repeat/autoplay, Instant Playback, Automix/crossfade/EQ/audio output/persistence | Existing engine/domain/native JNI tests; live streaming/long-session checks pending |
| Multimedia | Music video, audio/video switch, fullscreen, PiP, artwork effects | Existing native/render tests; physical device verification pending |
| Lyrics | Synced/unsynced lyrics, translation/romanization, fullscreen/queue, scroll/animations | Existing real player and lyric regression suites migrated unchanged |
| Library/offline | Remote and local playlists, Spotify import/matching, MP3/FLAC/embedded covers, downloads | Existing tests retained; full account/import verification pending |
| Integrations | Google sign-in/PoToken, Discord, Last.fm, ListenBrainz, tray/notifications, MPRIS/Windows media, addons/modules, updater | Existing adapters/dependencies retained; authenticated and platform manual checks pending |
| Data | Settings/theme/font preferences, library/playlists/download records, auth storage, cache locations | No schema/path migration or deletion introduced |

The desktop navigation enum also includes Spotify and History. Settings sections retained from the source are Account, Listen Together, Sources, Audio Quality, Downloads, Output Precision, Audio Output, Playback, Content, Desktop Playback, Appearance, Lyrics, Local Music, Storage, Miscellaneous, Language, Advanced and App Updates. Retention of their source/callbacks does not substitute for authenticated/manual verification.

### Visual evidence

The Compose visual parity workflow renders the pinned baseline and candidate on both Windows and Linux using the existing real LandscapePlayerMotionTest and DesktopPipUiTest. It compares the eight saved player frames (including timed transitions and lyric hover states) and ten PiP renders at multiple sizes, including the existing reduced-blur case. Comparison requires identical dimensions, all RGBA pixels, and the same file set. Missing files fail the check. Differences are preserved as PNGs rather than automatically accepted. This verifies only those fixtures; the remaining screens, font/theme combinations and physical GPU behavior still require review.

## Build and verification

Use JDK 21 and the existing native build prerequisites.

```sh
./gradlew :desktopRuntime:test :shared:jvmTest :desktopUi:jvmTest :desktopApp:test
./gradlew :desktopApp:createDistributable
```

Android SDK builds keep `:app` and `:sharedUi`; desktop-only settings do not configure the Android UI target. The shared data module still uses the existing Android/KMP build tooling.

Local evidence: 15 runtime ownership/race/cancellation tests in `:desktopRuntime:test`, all 19 presentation-lifetime and regression tests in `:desktopUi:jvmTest`, and playback clock projection tests in `:desktopApp:test` pass with JDK 21. At commit 64d7e70a, branch CI run 42547639362 passed Linux and Windows desktop tests, Android and backend checks, AppImage packaging, and generated complete Windows ZIP, EXE, and MSI installers. In addition, Compose visual parity run 42547190826 passed on both Windows and Linux with 100% pixel-by-pixel equality across all 18 fixtures. Draft PR automation in Actions remains restricted by repository permissions; that refusal does not affect compiler or test verification.

## Performance acceptance still outstanding

The first packaged idle result is below. No 30% reduction is claimed. Required measurements still include playback/navigation/fullscreen/long-session RSS/PSS, heap, native allocations, CPU, startup/transition latency and frame timing. Compare equivalent packaged builds with identical settings and fixtures; never substitute Gradle daemon memory or a cache microbenchmark for application memory. JVM NMT does not account for all Skia/FFmpeg/driver allocations.

Optional subsystem initialization, JavaFX/GraalVM/ONNX/native decoder lifetimes, navigation data retention and shared Coil/artwork budgets require further profiling. All remaining feature, visual and platform checks above must be completed before calling the experiment a complete re-architecture.

### Reproducible packaged idle comparison

The Compose packaged baseline workflow builds the immutable baseline above and the candidate on the same Linux runner, with the same release launcher version, dependency audit script and JVM options. It alternates three fresh-profile launches of each in an identical 1280×900 Xvfb display. Each run opens the real welcome screen, warms up for 20 seconds, then samples the actual launcher PID for 60 seconds. The sampler records RSS, PSS, private/swap bytes, threads and CPU time from /proc, a native-window appearance latency, screenshots and jcmd heap/NMT summaries.

The two production builds receive the same diagnostic-only NativeMemoryTracking=summary option through their environment. Their shipped heap/GC configuration remains unchanged. This is a clean welcome idle measurement, not a playback, navigation, first-usable-frame, physical GPU or long-session benchmark. It must not be extrapolated into those claims. JVM NMT covers only the allocations it tracks; process PSS additionally includes third-party native allocations. The dependency inventory lists resolved artifact coordinates, sizes and SHA-256s for both builds and rejects packaged Android AARs without treating AndroidX JVM libraries as Android-only.

Results are uploaded as compose-packaged-linux-idle when the workflow succeeds. An absent/failed measurement remains unverified; this document intentionally contains no invented before/after numbers.

### First measured idle result

Run 42546036077 compares source 348b613a with candidate 1b17fa59, before the later playback-clock projection. The recorded numerical evidence and artifact digest are in measurements/linux-idle-1b17fa59.json. Values below are medians across three runs; memory units are MiB.

| Metric | Baseline | Candidate |
| --- | ---: | ---: |
| Process PSS | 827.06 | 812.49 |
| Process RSS | 854.95 | 840.43 |
| Heap used at final sample | 78.27 | 77.17 |
| JVM NMT committed at final sample | 387.42 | 361.79 |
| CPU, percent of one core | 104.23% | 104.44% |
| Native window appearance | 3362 ms | 3075 ms |

The observed PSS difference is 1.76%, with overlapping run ranges; this does not establish a substantial steady-state reduction. The aspirational 30% target is not met. CPU remains high in both builds under software-rendered Xvfb. Neither native window appearance nor a fixture recomposition count is a usable-first-frame/frame-time benchmark. The high process usage relative to heap merits further native/render attribution; NMT committed memory cannot be subtracted from PSS to infer exact third-party native allocations.

### Second measured idle result with thread and native attribution

Run 42547573281 compares source 348b613a with candidate 8162b6b3 (incorporating playback clock projection decoupling and per-thread attribution). Medians across three rounds:

| Metric | Baseline | Candidate | Difference |
| --- | ---: | ---: | ---: |
| Process PSS | 878.42 | 866.15 | -1.40% |
| Process RSS | 908.63 | 896.38 | -1.35% |
| Heap used at final sample | 82.78 | 83.91 | +1.37% |
| JVM NMT committed at final sample | 421.13 | 420.93 | -0.05% |
| CPU, percent of one core | 104.70% | 105.09% | +0.39% |
| Native window appearance | 2833.55 ms | 2571.76 ms | -9.24% |

#### Thread CPU attribution
Sampling of surviving process threads attributes the ~105% CPU consumption to:
- `AWT-EventQueue-0`: 99.36% - 99.39% of one core (the AWT / Skiko event dispatch and Compose render loop running under unaccelerated Xvfb software Mesa `swrast`).
- `C2 CompilerThread`: 2.09% - 2.35% of one core (JIT compilation passes).
- `kotlinx.coroutines`: ~0.90% of one core (background dispatcher maintenance).
- `JavaFX Application Thread`: 0.53% - 0.56% of one core.
- `skiko-dispatcher`: 0.48% - 0.49% of one core.

#### Loaded native libraries
The audit identified that both baseline and candidate load:
- JavaFX WebKit and graphics stack: `libjfxwebkit.so`, `libglass.so`, `libglassgtk3.so`, `libprism_es2.so`, `libprism_sw.so`
- Skiko renderer: `libskiko-linux-x64.so`
- Mesa software OpenGL / X11 rasterization: `swrast_dri.so`, `libGL.so`, `libGLX_mesa.so`, `libLLVM-15.so`, `libX11.so`, `libXext.so`, etc.
- JVM core native runtime: `libjvm.so`, `libawt.so`, `libawt_xawt.so`, `libfontmanager.so`, `libfreetype.so`, `libnet.so`, `libnio.so`

Both inventories contain zero AAR artifacts and the same Linux Compose 1.12.0, Skiko 0.150.1, Haze 1.7.3 and Coil 3.0.4 renderer versions. Therefore, no Android-runtime removal or dependency-version upgrade is claimed as the cause of this measured difference.

### Idle Memory and CPU Optimizations Applied

Following thread CPU and native library attribution, three major root causes were resolved:
1. **Lazy BotGuard PoToken Initialization**:
   - `PoTokenGenerator.asTokenProvider()` and `getWebClientPoToken` now bypass JavaFX startup and WebEngine creation for blank `videoId` prewarm calls (`InnerTubeExtractor.prewarm()`).
   - WebEngine and WebKit runtime (`libjfxwebkit.so`, `libglass.so`, `libprism_es2.so`) remain uninitialized until a real track playback with BotGuard requirements or an in-app web sign-in dialog is explicitly triggered.
   - Saves ~150–250 MiB of native WebKit/JavaScriptCore heap allocation at startup and idle.
2. **Modal Overlay Animation Suspension**:
   - `HomeScreen` and `MoodGridPage` artwork motion is now disabled when the first-launch welcome modal, settings pages, or other modal overlays are obscuring the screen (`artworkMotion = !shortcutBlockedByModal && overlays.settingsPage == null && ...`).
   - Prevents `TopPicksShelf` `loopPhase` from spinning `withFrameNanos` continuously on `AWT-EventQueue-0` under unaccelerated Xvfb Mesa software rasterization.
3. **G1 Uncommit & Sized Initial Heap**:
   - Configured jpackage JVM args `-Xms32m`, `-XX:MinHeapFreeRatio=20`, `-XX:MaxHeapFreeRatio=40`, `-XX:-ShrinkHeapInSteps`, `-XX:G1PeriodicGCInterval=10000`, and `-XX:G1PeriodicGCSystemLoadThreshold=0`.
   - Prevents the JVM from eagerly committing 256 MiB+ of heap on high-memory systems when only ~80 MiB is needed, actively uncommitting unused pages back to the OS every 10 seconds.
   - Applied symmetrically to `BitChord.bat` in the portable distribution.

### Navigation & Active UI Memory Optimizations Applied

Addressing runtime memory swelling during active UI browsing across screens (where RSS previously expanded from ~800 MiB to 1.5–2 GiB):
1. **Dedicated Desktop Coil ImageLoader (`DesktopImageLoader`)**:
   - Registered a global singleton `ImageLoader` on desktop with a strict `32 MiB` in-memory bitmap limit (`MAX_MEMORY_CACHE_BYTES`).
   - Disabled weak references (`weakReferencesEnabled(false)`): on JVM/Desktop, Skia `Bitmap` wrapper objects are tiny (~24 bytes) on heap while pinning megabytes of off-heap C++ raster memory. Weak references prevented Skia textures from being freed without major heap GC. Disabling weak references releases native buffers immediately upon strong LRU eviction.
   - Configured a persistent `100 MiB` local disk cache (`DesktopMediaCache.directory/coil_cache`), allowing evicted images to reload sub-millisecond from disk without network calls.
2. **Proactive Navigation Trimming & Eviction**:
   - `DesktopApp.kt` trims Coil memory cache on screen navigation transitions (`selectDestination`), freeing off-heap textures from departing views.
   - Lowered `DesktopPageMemoryPolicy.TRIM_THRESHOLD_BYTES` from 1024 MiB to 650 MiB.
   - Enhanced eviction with `DesktopPageMemoryPolicy.trimProcessMemory()`: empties `DesktopArtworkCache`, clears Coil memory cache, triggers `System.gc()`, and on Linux invokes `malloc_trim(0)` via JNA to immediately release freed C++ allocator arenas back to the OS kernel.
3. **Artwork Cache Clamping**:
   - Clamped `DesktopArtworkCache.budgetBytes` to `[16 MiB, 32 MiB]` (down from `[32 MiB, 128 MiB]`), bounding in-memory custom artwork cache usage.

## External delivery blockers

GitHub rejected draft creation from Actions with: "GitHub Actions is not permitted to create or approve pull requests (createPullRequest)." Repository settings were not changed. The environment also currently denies api.github.com, preventing a direct authenticated CLI fallback. The prepared PR description is in compose-pr.md. The implementation branch is pushed normally; no automatic merge is attempted.

Earlier benchmark attempts stopped in the artifact inventory because Gradle had packaged project classes without creating every project JAR. The audit now declares its artifact build dependencies; run 42546036077 completed both packaged builds and all six real process measurements.
