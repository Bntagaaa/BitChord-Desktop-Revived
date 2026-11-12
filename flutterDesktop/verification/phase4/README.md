# Phase 4 evidence and outstanding integration gates

Reference: Kotlin `1.9.2`, `348b613a0c3db1a54de56483ea6f8748d6465211`.
Status: **INCOMPLETE**. Phase 1/2 user acceptance and the user-reported Linux
seven-codec/audibility handoff are preserved. Phase 5 is not authorized.

## Audited reference contracts

All desktop filenames below are under
`desktopApp/src/main/kotlin/com/music/bitchord/desktop/` at the pinned commit.

| IDs | Reference | Implemented portion / remaining contract |
|---|---|---|
| P03/P04/P05 | `DesktopSearchClient.kt`, `DesktopSearchSession.kt`, `DesktopSharedPages.kt`; shared `data/YtMusicRepository.kt`, `data/innertube/Innertube.kt`, `InnertubeParser.kt` | WEB_REMIX guest Home/New/Explore, song search, browse and continuation adapter; catalog lists and collection routes. Full shelf grouping, search filters/suggestions/quick-search, artwork, artist layout, owned collection mutations and visual parity remain open |
| P06/P20/P21 | `DesktopAccounts.kt`, `DesktopYouTubeAuth.kt`, `DesktopYouTubeSession.kt`, `DesktopBrowserSignIn.kt`, `DesktopWebSignIn.kt`, `DesktopBrowserCookies.kt`, `DesktopWindowsCrypto.kt`, `DesktopSecretStore.kt`, `DesktopSpotifyLibraryAuth.kt`, `DesktopSpotifyChromiumToken.kt`, `DesktopSpotifyLibrary.kt` | Accounts, browser import, protected persistence, token refresh and Spotify library NOT implemented. No successful sign-in is simulated. Real test-account scenarios remain unverified |
| P17/P18 | `DesktopSourceRegistry.kt`, `DesktopSourceConfig.kt`, `DesktopTrackMatcher.kt`, `DesktopAddon.kt`, `DesktopModules.kt`, `DesktopJioSaavn.kt` | Typed add-on manifest/search/stream and JioSaavn search/URL resolution. Same-recording matcher, ordered/racing substitute sources, live rendition upgrades, direct-source policy and module JS runtime remain open. Add-on server configuration is session-only; no secret-bearing address persisted |
| P19/P11/P12 | shared `data/innertube/{StreamResolver,InnerTubeXResolver,PlayerClient}.kt`, `potoken/*`; desktop `DesktopPoTokenWebView.kt`, `DesktopRangeStream.kt`, `DesktopAudioCache.kt` | Bounded HTTP ranges, source expiry renewal and isolated asynchronous Dart IO broker; native FFmpeg accepts only opaque loopback grants in addition to the existing local path. Cipher/client/PoToken and authenticated YouTube audio NOT implemented or claimed |
| P08/P35/P36 | `DesktopDownloadManager.kt`, `DesktopManifestDownload.kt`, `DesktopDownloadQueue.kt`, `DesktopDownloadManagerUi.kt`, `DesktopAudioCache.kt` | Hash-journaled progressive HTTP download/resume, offline reopen/integrity, partial exclusion, owned removal. Manifest demux/remux, queue/quality/settings parity, custom download paths and disk-full/platform fault matrix remain open |

## Implementation and boundaries

- `lib/features/online/domain/`: typed entries, source descriptions, failures,
  cancellation and UI-facing port. Signed locations/headers have no persistence
  representation and a redacted string conversion.
- `data/provider_http.dart`: cancellable owned sockets, total deadlines, capped
  bodies and credential-origin redirect restriction. No broad retry loop.
- `data/youtube_music.dart`: current client version extracted from actual shell;
  catalog calls do not pretend to supply audio.
- `data/addon_provider.dart`, `data/jiosaavn.dart`: real protocol adapters;
  unsupported DRM/manifests fail explicitly. JioSaavn's public DES URL obfuscation
  runs outside the render path through a small FFmpeg-libavutil utility;
  `bindings.g.dart` is regenerated with pinned ffigen, ABI structs unchanged.
- `data/range_source.dart`: 512 KiB requests, eight-span/4 MiB memory cache per
  source, exact Content-Range/body/length validation, stable validator and one
  expiry re-resolution. Large ignored-Range responses fail explicitly.
- `data/source_broker.dart`: separate Dart IO isolate, loopback-only listener,
  unguessable 256-bit capability, max two clients, streamed backpressure and
  revocation. Provider URLs/headers do not cross the native audio ABI. Native
  demuxer accepts a strict loopback address pattern and restricted formats/
  protocols; no manifests or arbitrary remote URLs enter the decoder.
- `data/download_store.dart`: Flutter-owned download root, chunk SHA-256 journal,
  durable flush then promotion, restart verification and corruption rejection.
  Validators/identity/hashes only; URLs/cookies/headers never journaled. Downloads
  are separate from the disposable bounded streaming cache.
- application/presentation: Riverpod cancellation/generation fences, separate
  transport subscriber, real list/detail/search/download actions and native
  pause/seek. Empty reference shell is preserved when bootstrap provides no
  online port (e.g. existing shell tests). Production composes real adapters.

## Reproducible verification

With the pinned SDK/native prerequisites, from `flutterDesktop`:

```sh
python tool/check_boundaries.py
dart format --output=none --set-exit-if-changed lib test
flutter analyze --fatal-infos
flutter test
cmake -S native/audio -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --config Release --parallel 2
ctest --test-dir build/native -C Release --output-on-failure
python tool/phase3_platform.py
python tool/phase4_platform.py
dart run tool/phase4_live.dart # opt-in anonymous network check; counts/status only
```

`phase4_check.dart` starts a controlled HTTP server and calls production adapters.
It checks encoded provider queries/IDs, catalog continuation/duplicate rows,
range/body bounds, validator/range corruption, expiry renewal, cancellation,
timeout, interrupted download/resume, offline restart/corruption/removal,
capability isolation and real FFmpeg decode of 384000 exact stereo PCM frames.
This is fixture evidence, not live service or account parity. CI writes
`verification/phase4-http.json`; both OS outcomes must be inspected.

Local Linux checks: standalone Dart 3.13.7 (language 3.11 formatter), C++17/GCC,
FFmpeg 9 and the real production broker passed controlled HTTP/native PCM checks.
Full local Flutter SDK is unavailable; full analyzer/widget/build checks run in
CI. Anonymous live WEB_REMIX returned 20 search entries and 30 Home entries with
continuations. This proves those live catalog calls on this Linux workstation,
not authentication, audio resolution or Windows live access.

## Deferred Phase 3 probe — implemented

`bitchord_audio_probe <file> [device]` now waits until native EOF by default.
`bitchord_audio_probe --timeout 120 <file> [device]` applies a positive configurable
wall-clock limit. `--help` documents exit codes: **0 EOF, 2 PLAYBACK_FAILURE,
3 PROCESS_FAILURE/API/shutdown failure, 4 TIMED_OUT, 64 invalid arguments**.
JSON preserves the terminal playback snapshot and separately reports safe
shutdown; transient `BC_RETRY` snapshots are retried. Source paths are not logged.

Local real Pulse software-sink trial: a 31.25-second WAV reached EOF with exactly
**1500000 submitted/presented frames**, position **31250000 us**, error 0;
configured timeout returned 4, missing file returned 2, seven malformed command
cases returned 64, all with clean shutdown. This is not physical audibility.
`tool/phase4_probe.py` runs the same >30-second regression inside hosted platform
verification when an actual endpoint exists. Windows no-device outcomes remain
explicitly blocked rather than called a long-track pass.

## Browser/resolver capability evaluation and open gates

Official `webview_flutter` supports Android/iOS/macOS, not Windows/Linux.
`webview_windows` is Windows/WebView2 only and requires a runtime. Native WebView2
and WebKitGTK expose cookies and asynchronous JS APIs; API availability is not
proof of session import, PoToken execution or lifecycle/resource parity. No
unverified desktop webview plugin is selected or shipped as working auth.
Local WebKitGTK 4.1 development metadata exists, but no authenticated/PoToken
runtime proof was obtained. A protected secret store and missing/locked-keyring
failure path must precede persistent accounts; there is no plaintext fallback.

Sources checked: [Flutter webview support](https://pub.dev/packages/webview_flutter),
[Windows requirements](https://pub.dev/packages/webview_windows),
[WebView2 cookies](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2cookiemanager),
[WebKitGTK JS](https://webkitgtk.org/reference/webkit2gtk/stable/method.WebView.evaluate_javascript.html),
[WebKitGTK cookies](https://webkitgtk.org/reference/webkit2gtk/stable/class.CookieManager.html),
[Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow),
[Spotify redirect requirements](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri),
[yt-dlp external JS requirements](https://github.com/yt-dlp/yt-dlp/wiki/ejs).

Phase 4 cannot be accepted until real account/provider/resolver/audio and failure
recovery have evidence on both platforms. No whole P-ID is PARITY_ACCEPTED, and
no RAM saving, physical Windows sound, authenticated provider integration or
custom-module execution is claimed. Next work remains in Phase 4.

## Verified first Windows/Linux matrix and additional live evidence

[a343213e / run 43209360258](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43209360258)
finished **SUCCESS** on both OSs and retention. Both downloaded artifacts were
inspected: **32 Flutter tests each**, 2/2 native CTests, 3/3 Kotlin export tests,
real FFI/SQLite/import/450-transition corpus, fatal analyzer/format/lockfile,
release bundle and native GUI/fullscreen/appearance/close (exit 0). Both OSs
passed real HTTP/native broker decoding with **384000 exact PCM frames** and
zero live decoders afterward. This is not full provider or visual parity.
Hosted native Home captures contain actual anonymous catalog rows on both OSs;
content varies by region/session and is not a matched Kotlin visual fixture.

Linux's hosted 31.25-second WAV probe also reached EOF at exactly 1500000 frames.
Windows still has no WASAPI endpoint (error 105, zero frames), so its long-song
speaker/EOF test is **BLOCKED**, while argument and missing-file outcomes passed.
Local additional **32.5-second FLAC**, `--timeout 45`, reached EOF with exactly
1433250 submitted/presented frames and position 32500000 us. Redacted local
records are `local-probe.json` and `local-flac-probe.json`.

Actual Linux JioSaavn search returned 25 entries, resolved an audio location and
validated a 524288-byte range with a validator. A fresh live resolution then
played through the native engine/Pulse **software sink**: phase PLAYING, error 0,
39093 presented frames before the intentional one-second timeout. See
`local-jiosaavn-live.json` and opt-in `tool/phase4_jiosaavn_live.dart`.
No Windows live JioSaavn or physical-output pass is claimed.

The separate YouTube resolver spike used official yt-dlp **2026.10.26**
(`yt-dlp_linux` SHA-256
`767ba9dce7052aa196f8b12652f433d76de128ba168966fa84b6394d5e6c6795`)
and official Node **22.23.5** (Linux archive SHA-256
`cdeb8670a0c81f51b4dc4ac61919f9b1ad917b304bf8221eb939570663d240f8`).
Both were downloaded into temporary storage, publisher hashes verified, and
config/plugin/remote-component loading disabled. The original public test video
was unavailable; a current real catalog track then resolved an **Opus/audio-only
URL**, but its actual media range returned **HTTP 403 / zero bytes**. This is a
failed streaming proof despite resolver exit 0. `youtube-resolver-spike.json`
contains only status/format metadata. No binary, signed URL or catalog payload is
committed. These tools are **not shipped as a working YouTube resolver**.
Client/PoToken/header compatibility and authenticated testing remain unresolved.

A follow-up strengthens credential-origin redirect checks (including custom
headers), rejects weak ETags as byte-range resume validators, exercises eight-span
LRU eviction and explicitly labels the current 800-item UI paging limit. The
follow-up's final matrix must be checked independently from the first run above.

A further opt-in live JioSaavn trial downloaded **12487540 bytes** into a
test-owned temporary directory, reopened the journal offline, verified every
chunk hash and played the verified local file through the same native engine
(**41759 presented frames** before deliberate timeout). Streaming in that trial
presented 40508 frames. The test removed all temporary media afterward; only
redacted numeric/status evidence is retained. Reproduce with
`dart run tool/phase4_jiosaavn_live.dart <native-library> <probe> <test-endpoint> --download`.
This is a Linux software-sink integration result, not Windows/physical parity.

Additional application safeguards fence a delayed offline selection before
native adoption, cancel connect/play scopes at shutdown, and report missing
bootstrap services as failures. No missing provider port can report a successful
operation. Controller regressions cover late results, disposal, stale offline
selection, explicit limits and unsupported operations.
