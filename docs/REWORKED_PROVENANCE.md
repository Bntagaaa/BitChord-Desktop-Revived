# Reworked source provenance

## Baseline and exclusions

The application baseline is the original [`kushagrasinghx/BitChord` stable `v1.9.1`](https://github.com/kushagrasinghx/BitChord/tree/7f45c768a461fd7e68bf4613f71e8a767e643d4c), commit `7f45c768a461fd7e68bf4613f71e8a767e643d4c`. Its Android application, `shared`, and `sharedUi` supply the models, repositories, algorithms, assets, and screen contracts. That stable tree has no `desktopApp` module.

The desktop target was created for this branch. It does **not** incorporate the previous Revived desktop module, historical upstream desktop modules, or the web application's implementation. An earlier historical-desktop experiment was discarded before this implementation and is outside this repository's deliverable.

Revived [`1.9.2`](https://github.com/Bntagaaa/BitChord-Desktop-Revived/tree/1e65021e7c05b8692afb7e35cd5cfff52035f4b0), commit `1e65021e7c05b8692afb7e35cd5cfff52035f4b0`, was read to inventory **behavior**. Its components were not the source for the new port. Revived performance profiles, Instant Playback/prefetch, memory/LRU/GC tuning, and focus-throttling machinery are deliberately excluded. Resource cleanup and bounds in the new adapters are ordinary correctness requirements, not imports of that optimization architecture.

The repository's existing license and original copyright notices apply to the retained and adapted original code. This file records provenance; it does not replace those terms.

## Direct mobile ports

Paths in the middle column are relative to `app/src/main/java/com/music/bitchord/` at the baseline commit. Destination paths are relative to `desktopApp/src/main/kotlin/com/music/bitchord/`.

| Destination | Original mobile source | Desktop adaptation |
| --- | --- | --- |
| `data/sources/addon/AddonModels.kt` | Same relative path | Retains manifest resources, quality/defaults, download/lossless/auth policy, track and stream metadata, encryption/Atmos flags; accepts optional media headers and explicit-content metadata. |
| `data/sources/addon/AddonClient.kt` | Same relative path | Same manifest/search/stream endpoints, settings selection and rate-limit behavior; JVM version string, bounded JSON response, no Android codec or hardware-attestation API. Required Android verification produces an explicit unavailable state. |
| `data/sources/addon/SourceFormats.kt` | Same relative path | Same manifest/module-index shape detection and search-only fallback, JVM user agent and bounded document reads. |
| `data/sources/module/SharedCalls.kt` | Same relative path | Original mobile in-flight call lifecycle. No Revived desktop cache implementation is involved. |
| `data/sources/module/QuickJsExecutor.kt` | Same relative path | Same module exports, polyfills, async HTTP binding and engine lifecycle, using the library's JVM artifact; removes unused Android logging import, bounds transport/evaluation time, avoids logging resolved media URLs. |
| `data/sources/module/ModuleManager.kt` | Same relative path | Same index/load/search/stream protocol; JVM logging, bounded response sizes, properly JSON-quoted query/ID/settings arguments. |
| `data/sources/module/ModuleIndex.kt`, `ModuleResults.kt`, `SpineModule.kt` | Same relative paths | Original index/result models and category handling; optional stream headers are retained by the desktop adapter. |
| `data/canvas/` | Original mobile Canvas/Apple artist/Spotify/Tidal/community artwork files | Original lookup/model behavior; JVM settings/HTTP/session boundary replaces Android settings and logging. |
| `data/jiosaavn/JioSaavnService.kt` | Same relative path | Original models, query parameters, URL decoding and conditional bitrate rendition selection; Java Base64/shared OkHttp replace Android Base64/Ktor. Requests do not fabricate client IP headers. |
| `data/webdav/WebDavClient.kt`, `WebDavConfig.kt` | Same relative paths | Original PROPFIND, filenames, Basic auth, non-overwriting upload and directory models; exposes single-directory listing and rejects external XML entities/oversized responses. |
| `data/smb/SmbClient.kt`, `SmbConfig.kt`, `SmbConnection.kt` | Same relative paths | Original SMBJ authentication, share/file access, paths and read contracts; exposes one-directory listing. |
| `data/remote/RemoteSong.kt` | Same relative path | Original remote filename/title/artist/source identity mapping. |
| `data/lyrics/EmbeddedLyrics.kt` | Same relative path | Original MP4/FLAC/Matroska lyric parsing, with a `Path` entry point in place of Android `Context`/`Uri`; adds FFmpeg tag fallback and ordinary same-name LRC sidecars. |
| `data/settings/DownloadQuality.kt` | Pure `DownloadQuality` and `LocalMusicSort` declarations from `data/settings/AppSettings.kt` | Original tiers, descriptions and order values, with desktop preferences kept outside the model. |
| `download/DownloadModels.kt` | `DownloadTarget`, `SavedCollection`, `DownloadedCollection` from `download/Downloads.kt` | Original collection metadata and track-ID records; no Android download service. |
| `data/LocalGroups.kt` | Same relative path | Original album/artist/folder identity, ordering, filter and group construction. Desktop collection page ID replaces the Android Downloads singleton; ordinary OS paths retain their roots. |
| `playback/loopmix/LoopMixPort.kt`, `LoopMixSession.kt`, `LoopMixRenderer.kt` | Same original mobile paths | Exact mobile plan/clock/render contracts hosted by a fresh desktop JNI/audio boundary. |
| `data/listentogether/ListenTogether.kt`, `PartyPreferences.kt` | Original mobile Listen Together repository | Android lifecycle/logging is replaced by the desktop session; shared party models and messages remain original. |
| `data/scrobbling/` | Original mobile scrobble services | Desktop credential/settings boundary; same service-specific signing and scrobble protocol. |
| `data/stats/` | Original mobile Replay aggregation | Desktop listening-event storage/lifecycle in place of Android persistence. |

Consult the comments at each destination for localized modifications. The integration package groups should be reviewed with their individual filenames when changing those services.

The original shared `CompactTrackRow` has one optional `showMenuButton` parameter, defaulting to `true`. The new desktop collection host sets it to `false` because it supplies its own trailing actions beside the duration. Original mobile callers retain their menu and gestures; this small host seam avoids rendering duplicate controls without replacing the original row implementation.

## Newly written platform boundaries

All files in `desktopApp/src/main/kotlin/com/music/bitchord/reworked/` are new platform host or feature implementations. In particular:

| New boundary | Original contracts/behavior it hosts |
| --- | --- |
| `Main.kt`, `MobileSession.kt`, `MobilePlayerHost.kt` | Mobile repositories and unchanged shared UI host contracts, windows and navigation. |
| `MobileYouTubeAccounts.kt`, `MobileYouTubeAccountModels.kt`, `MobileYouTubeAccountSettings.kt`, `DesktopYouTubeAccountStore.kt`, `DesktopYouTubeBrowser.kt`, `YouTubeCapturedSessionValidator.kt`, `DesktopBrowserAuth.kt` | Original mobile `auth/AccountSessions.kt` and `AuthStore` account/profile/channel contracts, captured YouTube configuration and original shared Innertube request/parser semantics. Fresh JVM account vault, isolated candidate validation and revision-fenced session lifecycle; no old desktop or web account implementation. |
| `DesktopPlayback.kt`, `MobileAudioDecoder.kt`, `RangedMediaInput.kt` and mobile DSP/analyzer adapters | Mobile queue/audio/analysis semantics, FFmpeg/Java Sound on the JVM, original shared musical planner/models. |
| `MobileSources.kt`, `MobileModuleSources.kt`, `MobileJioSaavn.kt` | Mobile source IDs, `TrackMatcher`, source order, protocol policies and the above direct mobile clients. |
| `MobileFiles.kt`, `MobileDownloadWriter.kt`, `MobileFileSettings.kt` | Mobile local-file metadata, independent download quality/export semantics, native codec-preserving packet remux and atomic file publication. |
| `MobileDownloadedCollections.kt`, `MobileLocalLibrary.kt` | Mobile `PlaylistSync` additive/paging/approval rules and `LocalFolder` views; direct pure `LocalGroups`/collection models with new desktop lifecycle, selection and settings. |
| `MobilePlaylistEditor.kt` | Original mobile `ui/components/ReorderPlaylistPage.kt` drag/edge-scroll behavior and `ui/MainViewModel.kt` exact-entry removal/reorder sequence. Original shared complete-entry loading and move planning; new desktop controls, local occurrence identities and profile/revision boundary. |
| `MobileVersionAligner.kt`, `MobileSilence.kt` | Original mobile `playback/smart/VersionAudioAligner.kt` FFT/envelope algorithm and playback service conservative Media3 silence policy, with fresh FFmpeg/Java Sound boundaries. |
| `MobileDiscord.kt`, `MobileDiscordSettings.kt` | Original mobile/shared Discord gateway/settings contract, desktop opt-in lifecycle and UI. |
| `MobileRemote.kt`, `MobileRemoteBridge.kt` | Mobile WebDAV/SMB clients; fresh native desktop settings, browse UI, tokenized loopback seekable transport. No web application is hosted. |
| `DesktopStore.kt`, `SongPersistence.kt` | Independent Reworked data/preferences and full shared `Song` record persistence. |
| `ReworkedPip.kt`, `ReworkedPipWindow.kt` | Revived mini-player behavior implemented against the new playback state and original mobile artwork/lyrics components. |
| `ReworkedUpdate*.kt`, `ReworkedPortableArchive.kt`, `resources/reworked/update-*` | Independent product-specific updater, new archive verification and platform handoff scripts. No Revived updater source is used. |
| `ReworkedTheme.kt`, `ReworkedAppearance.kt`, `DesktopWindowAppearance.kt`, `MobileDesktopCards.kt` | Original mobile typography/assets plus newly implemented desktop appearance behavior identified in the Revived audit. |

## Dependencies and product separation

The desktop uses Compose/JVM, Java 21, FFmpeg/JavaCPP/JavaCV, Java Sound, JavaFX for the explicit sign-in/PoToken boundary, QuickJS JVM for original mobile modules, SMBJ for the original mobile file-share client, and platform libraries for native media controls. Versions are declared in `desktopApp/build.gradle.kts`. Native installer versions map the public patch/Reworked revision into a monotonic numeric package field; full public versions remain unchanged in JVM properties, update tags and assets. [REWORKED_BUILD.md](REWORKED_BUILD.md) documents that mapping and the build commands.

The product is `BitChordReworked`, with a separate installer identity, Java Preferences node `com.music.bitchord.reworked`, and separate `BitChordReworked`/`bitchord-reworked` data directories. Existing Revived profiles are not read or overwritten. Test tasks use a separate preference node and temporary data roots.

The explicit `--offline-smoke` entry point creates a new empty temporary profile and preferences node before initializing the mobile host. Its shared HTTP policy and client/lifecycle guards are test isolation, not Revived performance features. It does not load existing account credentials or prove live service interoperability.

Updater candidates must belong to the same repository's **`reworked-v…` tag namespace** and carry exact **`BitChordReworked-…` asset names**, byte counts and SHA-256. A normal Revived or Android release cannot become a Reworked updater candidate. Windows portable replacement retains the previous application directory; AppImage replacement retains the previous image. Native package installs are managed through their package workflow.

See [REWORKED_FEATURES.md](REWORKED_FEATURES.md) for behavior coverage, limitations and verification evidence. Source presence is not evidence that an external service, a physical audio device or an operating-system installer has been exercised.
