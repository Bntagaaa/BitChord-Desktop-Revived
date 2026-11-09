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
- Native window visibility/focus controls presentation subscriptions. Hidden presentation is CREATED, visible/unfocused STARTED, focused RESUMED. Playback service scopes remain independent.
- Duplicate window-hide callbacks coalesce. Disk trimming leaves the UI thread. The existing hidden-window GC request is retained but skipped if the window has already been restored.

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

## Build and verification

Use JDK 21 and the existing native build prerequisites.

```sh
./gradlew :desktopRuntime:test :shared:jvmTest :desktopUi:jvmTest :desktopApp:test
./gradlew :desktopApp:createDistributable
```

Android SDK builds keep `:app` and `:sharedUi`; desktop-only settings do not configure the Android UI target. The shared data module still uses the existing Android/KMP build tooling.

Local evidence: runtime ownership/race/cancellation tests and isolated actual desktop presentation-lifetime tests pass; desktop resource generation passes. Full local compilation is currently blocked by the environment's HTTP 403 denial for JitPack. Native Windows/Linux builds, Android regression compilation and packaged bytecode/native checks run in the dedicated branch CI. Claims must follow the actual CI results.

## Performance acceptance still outstanding

No before/after packaged-process PSS result or 30% reduction is claimed yet. Required measurements remain idle/playback/navigation/fullscreen/long-session RSS/PSS, heap, native allocations, CPU, startup/transition latency and frame timing. Compare equivalent packaged builds with identical settings and fixtures; never substitute Gradle daemon memory or a cache microbenchmark for application memory. JVM NMT does not account for all Skia/FFmpeg/driver allocations.

Optional subsystem initialization, JavaFX/GraalVM/ONNX/native decoder lifetimes, navigation data retention and shared Coil/artwork budgets require further profiling. All remaining feature, visual and platform checks above must be completed before calling the experiment a complete re-architecture.

### Reproducible packaged idle comparison

The Compose packaged baseline workflow builds the immutable baseline above and the candidate on the same Linux runner, with the same release launcher version, dependency audit script and JVM options. It alternates three fresh-profile launches of each in an identical 1280×900 Xvfb display. Each run opens the real welcome screen, warms up for 20 seconds, then samples the actual launcher PID for 60 seconds. The sampler records RSS, PSS, private/swap bytes, threads and CPU time from /proc, a native-window appearance latency, screenshots and jcmd heap/NMT summaries.

The two production builds receive the same diagnostic-only NativeMemoryTracking=summary option through their environment. Their shipped heap/GC configuration remains unchanged. This is a clean welcome idle measurement, not a playback, navigation, first-usable-frame, physical GPU or long-session benchmark. It must not be extrapolated into those claims. JVM NMT covers only the allocations it tracks; process PSS additionally includes third-party native allocations. The dependency inventory lists resolved artifact coordinates, sizes and SHA-256s for both builds and rejects packaged Android AARs without treating AndroidX JVM libraries as Android-only.

Results are uploaded as compose-packaged-linux-idle when the workflow succeeds. An absent/failed measurement remains unverified; this document intentionally contains no invented before/after numbers.
