# Desktop YouTube Music resolver (1.9.3)

## Scope

`DesktopStreamClient.resolve` now exclusively calls `DesktopYtDlpResolver`. It never falls back to InnerTubeX/NewPipe. yt-dlp extracts a direct audio URL plus metadata; the existing FFmpeg/range-reading audio engine streams the bytes. There is no whole-song download and no persistent Python/yt-dlp daemon.

Search, browsing, account persistence/history, other providers, lossless playback, DSP, audio output routing, and Compose UI lifecycle are not rewritten. Shared Android code and the existing shared resolver initialization remain untouched; this change replaces the desktop audio entry point, not every auxiliary YouTube feature in the repository.

## Runtime

- One extraction at a time, on `Dispatchers.IO`, with a 45-second process timeout and bounded stdout/stderr (4 MiB / 64 KiB).
- Cancellation destroys the process tree and propagates rather than becoming a playback error. Normal completion, timeout, and cancellation release streams/reader threads; temporary cookie and Deno/PyInstaller files are removed. A JVM shutdown hook handles active extractor processes.
- Commands use argument lists, not a shell. User configs, browser-cookie discovery, plugins, and remote JS-component downloads are disabled.
- Official standalone yt-dlp includes EJS; bundled Deno supplies its JavaScript runtime. No Python installation is required.
- Prefer audio-only Opus, then another direct HTTPS audio format. The existing Low bitrate ceiling applies to both choices. No muxed-video, DRM, SABR, HLS/DASH fragments, or guessed media URL is passed to the direct byte-range reader. Tracks without a supported direct rendition return an actionable error, not an automatic switch back to the old resolver.
- Only expected HTTPS Googlevideo URLs and safe media headers are accepted. Cookie/Authorization values and raw extractor stderr/signed URLs are not copied into diagnostic messages.
- The current imported YouTube session is written into an owner-only temporary Netscape cookie file. The stored app session is not deleted or modified. Guest playback does not inspect a browser. Multi-account/brand-channel behavior needs a live acceptance check; only the cookie jar, not the app's channel-selection metadata, is passed to yt-dlp.
- No additional signed-URL cache is introduced. Each call resolves afresh. Existing queue/read-ahead and media retry policy stay in place; this PR does not promise new seamless mid-track expiry recovery.

## Packaging and updates

`gradle/desktop-ytdlp.gradle` is applied only to `:desktopApp`. `processResources` depends on `prepareYouTubeRuntime`, so AppImage, native Windows/Linux packages, and Windows portable builds all include `ytdlp/` resources in the application jar.

Pinned binaries: yt-dlp `2026.10.26`, Deno `v2.9.8`, Windows/Linux x86-64. Downloads happen at build time from official release assets and are checked against the committed SHA-256 values. The verified Deno archive is unpacked by exact entry name. A manifest records each executable's digest; the app verifies resources again when extracting into its private per-user tool directory. Build preparation fails on a download/checksum error instead of shipping a silently incomplete player.

Upstream license texts, yt-dlp third-party notices, and source locations are included under `ytdlp/licenses/`. Standalone yt-dlp includes GPLv3+ components; redistributors must preserve notices and meet corresponding-source obligations for the binaries they distribute.

There is **no automatic runtime self-update or update-settings UI** in this PR. To update the bundled toolchain, update the pinned versions and all platform checksums, then rebuild and run acceptance checks. Advanced/local runs can explicitly override absolute executable paths with `BITCHORD_YTDLP` and `BITCHORD_DENO` (or JVM properties `bitchord.ytdlp.path` and `bitchord.deno.path`). Such overrides are user-managed and must include a compatible EJS installation. No implicit PATH or current-directory executable is trusted.

## Validation

```sh
./gradlew :desktopApp:test --tests '*DesktopYtDlp*'
./gradlew :desktopApp:prepareYouTubeRuntime :desktopApp:createDistributable
```

The deterministic tests cover command construction, bitrate ceilings, invalid IDs, cookie scope/permissions, process success/failure, pipe draining, output caps, timeout/cancellation cleanup, JSON metadata, and unsupported media rejection. They do not contact YouTube.

Authoring-environment evidence: 11 command/cookie/process tests passed on Linux with JDK 21 and the available Kotlin compiler, using the actual production classes and Java child-process fixture. The JUnit annotation was substituted only for a local reflection-based runner because the full Gradle dependency graph was unavailable. The four parser tests, full application compilation, binary-download/package smoke tests, and Windows execution were not run locally: repository/dependency downloads were blocked by DNS/network access. CI must establish full integration status; the focused test result is not a successful application build.

Before merging, check guest and signed-in playback, Low/Medium quality, skip during extraction, seeking, queue transitions, restart with persistent login, ordinary non-YouTube lossless playback, and close/tray/PiP on both supported platforms. Watch the process list: no extractor should remain after resolution or cancellation. Validate a packaged app on a machine with neither Python, yt-dlp, nor Deno preinstalled. Live YouTube extraction/playback remains an acceptance check, not a claimed test pass.
