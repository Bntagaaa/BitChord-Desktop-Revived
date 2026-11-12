# Migration parity audit — Phase 0

## Reference and evidence contract

Reference: Kotlin branch `1.9.2`, commit `348b613a0c3db1a54de56483ea6f8748d6465211`. Migration branch: `2.0.0-flutter`, created directly from that commit. Date: 2026-11-11. No previous Flutter branch was checked out, merged, or used as implementation input. Neither `flutterApp/` nor `flutterDesktop/` exists in the reference tree. Phase 1 will create only `flutterDesktop/`; the Kotlin tree remains intact. No Flutter application is implemented in Phase 0.

This is a static architecture and feature audit, with a complete tracked-file inventory of the desktop, shared, native, model and validation surfaces below. Inventory coverage is not a claim of line-by-line semantic proof. Behavioral conclusions come from implementation contracts and regression tests; live provider, Windows device, window-manager, listening and rendering equivalence remain unverified. All Flutter equivalents below are **planned**, not implemented or accepted.

100% parity means every applicable behavior and visual state passes on Windows and Linux, including failure, cancellation, restore and resource cleanup. A matching name, package API, screenshot or successful compilation is insufficient. Tests in the inventory are reference specifications, not evidence that they passed in this session. Do not silently drop experimental, legacy-style or source-dependent features. Android-only implementations are context, not additional Android product scope.

Path aliases for the matrix: `D` = `desktopApp/src/main/kotlin/com/music/bitchord/desktop/`; `S` = `shared/src/`; `U` = `sharedUi/src/`. Filenames listed with `D` are relative to that directory. Shared `S`/`U` references are package suffixes or basenames within the indicated module source sets (commonMain, jvmSharedMain and jvmMain); the inventory below gives their complete paths. Wildcards denote file families, not literal paths. Destination paths below are relative to the future `flutterDesktop/`.

## Architecture observed

`Main.kt` installs shared host adapters, cookies, logging, lyrics cache, Spotify auth and InnerTubeX/PoToken resolution before opening Compose windows. `DesktopApp.kt` owns a large amount of navigation, account, queue, preference and asynchronous request orchestration. Shared UI is not merely reference screenshots: `AppUi`, `PlayerPlatform`, `PlayerHost` and JVM actuals connect Compose screens and player controls to desktop services. JVM-shared data owns network protocols, parsing, lyrics and queue/Automix rules. Native C++ owns analysis and loop rendering; ONNX models are packaged from Android assets. FFmpeg/JavaCPP decodes audio and media; Java Sound/native Windows helpers and Linux device controls deliver PCM. JavaFX WebView is used for authentication/token workflows, not the principal audio engine.

## Feature-to-implementation and verification ledger

Dependencies marked “spike” require platform validation before package selection is final. Each row requires deterministic tests plus real platform integration where indicated. IDs remain stable through Phases 1–8.

| ID | Kotlin evidence / component | Required behavior and edge states | Planned Flutter equivalent and dependency | Acceptance evidence |
|---|---|---|---|---|
| P01 | D `Main.kt`, `DesktopWelcomeOnboarding.kt`, `DesktopWelcomeScreen.kt` | Per-install welcome marker inspected before service writes; sign-in/skip/error paths; asynchronous bootstrap | `lib/app/bootstrap`, `features/onboarding`; Riverpod, path_provider | Fresh install, restart, interrupted welcome; window opens before nonessential IO |
| P02 | D `DesktopApp.kt:DesktopDestination`, `DesktopSharedPages.kt`, U `ui/screens/*` | Home, New, Explore, Library, Spotify, Search, History, Downloads, Local Music, Settings; collection/artist/shelf drilldowns; back history and scroll restoration | Typed go_router routes and page state controllers | Every destination and nested/back path, empty/loading/error/content; account change invalidates stale results |
| P03 | D `DesktopApp.kt`, `DesktopNewPage.kt`, `DesktopShelfBrowsePage.kt`; S `YtMusicRepository.kt`, `data/innertube/*` | Progressive Home, recents, top picks, moods/genres, continuations and radio; preserve shelf paging | `features/discovery`, catalog repository; dio, cancellable request generations | Controlled multi-page responses, retries and out-of-order completion; identical shelf order and actions |
| P04 | D `DesktopSearchClient.kt`, `DesktopSearchSession.kt`, `DesktopQuickSearch*.kt` | Suggestions/typeahead, full filters, Ctrl+K overlay, keyboard/mouse selection, dismissal and focus restoration | `features/search`; debounced controller + overlay | Replay DesktopQuickSearch/SearchPointer/SearchSession tests; stale query/account results rejected |
| P05 | D `DesktopApp.kt`, `DesktopPlaylistUi.kt`, `DesktopSongMenuUi.kt`; S `Models.kt`, `PlaylistOrder.kt` | Collection/artist details, credits, sort/order, playlist create/edit/remove, like/dislike, queue, share/link actions | `features/catalog`, `features/library`; typed catalog IDs | Duplicate songs retain entry identity; menus clamp at all edges/scales; remote mutation failure rolls back |
| P06 | D `DesktopLibraryLoadPolicy.kt`, `DesktopSpotifyLibrary.kt`; S JVM `data/spotify/*` | Lazy account library load; Spotify library/import and matching to playable catalog; pagination | Independent catalog/Spotify adapters | Account isolation, expired tokens, partial pages, cancellation, imported identity and counts |
| P07 | D `DesktopLocalMusic.kt`, `DesktopLocalMusicWatcher.kt`, `DesktopLocalArtwork.kt`, `DesktopEmbeddedLyrics.kt` | Deferred folder scan/watchers, supported metadata/formats, artwork and embedded lyric parsing, directory changes | `features/local_music`; file_selector, worker isolate, native metadata adapter | Temporary folder corpus: add/remove/rename, corrupt tags, inaccessible path, embedded lyrics, watcher disposal |
| P08 | D `DesktopDownloadQueue.kt`, `DesktopDownloadManager.kt`, `DesktopManifestDownload.kt`, `DesktopDownloadManagerUi.kt` | Quality choice, queued download lifecycle, manifest transport, progress/cancel/error, verified offline files and removal | `features/downloads`; dio streaming, native demux/remux as needed | HTTP range/HLS/DASH corpus, interruptions/disk-full/cancel, no partial file presented as valid, offline play |
| P09 | D `DesktopListeningStats.kt`, `DesktopPlaybackTracker.kt`, `DesktopReplayUi.kt` | History, listening time, period/month summaries, ranked artists/albums/tracks, Replay sharing | `features/history`, `features/replay`; SQLite aggregate repository | Deterministic clock/duplicate playback tests; restart, month/timezone boundaries and account switch |
| P10 | D `DesktopQueue.kt`, `DesktopAutoplay.kt`; S `playback/QueueBuilder.kt`, `QueueSections.kt`, `QueueTimeline.kt` | Personal/context/autoplay tiers, repeat OFF→ALL→ONE, shuffle restoration, drag/jump, duplicate entry identities | Pure Dart queue state machine + native playback commands | Differential queue traces; handoff/Next race, repeat wrap, jump preserves manually queued entries |
| P11 | D `DesktopAudioEngine.kt`, `DesktopAudioDecoder.kt`, `DesktopDecodeAhead.kt`, `DesktopPcmWrite.kt` | Serialized commands with consume-time generations, cancellable native opens, partial writes, decode-ahead, seek, pause, errors, device reconfigure | `native/audio` C++17 C ABI worker engine + Dart `application/playback`; FFmpeg native libraries | PCM fixture output, cancellation/load storms, zero stale track starts, underrun/repeated handoff tests |
| P12 | D `DesktopPlayback.kt`, `DesktopPlayhead.kt`; U `playback/PlaybackPosition.kt` | Song time paired with native sample timestamp; seek discontinuity; awaitingAudio; actual format/source/upgrade/mixing state | Immutable transport snapshots from FFI with monotonic timestamps | Pause/buffer/seek/speed traces; state agrees with submitted/audible sample timing, not UI timers |
| P13 | D `DesktopAudioSink.kt`, `DesktopWindowsAudio.kt`, `DesktopLinuxPulseAudio.kt`, `DesktopLinuxAudioWatcher.kt`, `DesktopAudioDevices.kt` | Precision/float negotiation, native rate, output selection/default/hotplug, volume, sink timing | C++ WASAPI shared-mode and libpulse/ALSA adapters behind C ABI | Real endpoint changes and sample-rate/channel matrix; loopback capture; no unconditional 48 kHz conversion |
| P14 | D `DesktopEqualizer*.kt`, `DesktopAudioProcessors.kt`, `DesktopAudioSpeed.kt`, `DesktopRateConverter.kt`, `DesktopTempoBuffer.kt` | Dynamic/manual seven-band EQ, tone/presets/balance, stereo widening, silence trimming and position accounting, speed/rate conversion | Native DSP graph; tested independent translations of Kotlin algorithms; Signalsmith where compatible | Impulse/sine/noise/silence fixtures, frequency/phase response, clipping/continuity, stereo-only widening, state reset on seek |
| P15 | D `DesktopTransitionRide.kt`, `DesktopTransitionEcho.kt`, `DesktopAutomixDuration.kt`; S common `playback/smart/*`, JVM `TransitionFilter.kt` | Crossfade 0–12 s, adaptive/8/16-bar Automix, timing/style/filter/echo/tempo rules and fallback when evidence absent | C++ analysis/planner/overlap renderer; Dart settings and plan-view contracts | Compare plans and PCM for same tracks/features; silence/live/spoken/tempo mismatch, seek/cancel during mix |
| P16 | D `DesktopTrackAnalyzer.kt`, `DesktopAnalysisRuntime.kt`, `DesktopAutomixWorkQueue.kt`; S JVM `playback/smart/*`; `native/analyzer/`, `native/loopmix/` | Beat/downbeat/vocal features, persisted analysis, bounded performance-mode work; loop regions/waveform/mix UI | C ABI analysis + ONNX Runtime sessions; bounded background worker | Same model hashes, tensor preprocessing, deterministic feature tolerances, six-track sequence, shutdown frees sessions |
| P17 | D `DesktopSourceRegistry.kt`, `DesktopSourceConfig.kt`, `DesktopTrackMatcher.kt`, `DesktopDirectSource.kt`, `DesktopJioSaavn.kt` | Ordered add-on/custom-module/JioSaavn/YouTube fallback, nonremovable YouTube; Low/Medium/High/Lossless ceilings; direct-source and live rendition upgrades | `data/sources` adapters + resolver coordinator | Same-recording/version matching, quality gates, timeouts, media failure fallback, upgrades preserve position/entry identity |
| P18 | D `DesktopAddon.kt`, `DesktopModules.kt`; S `data/sources/*` | Add-on HTTP API; user-configured module JS host, timeouts/HTTP bridge, URL normalization/order/duplicates | HTTP adapter + isolated native JS runtime spike (QuickJS compatibility candidate) | Recorded protocol corpus and module contract suite; abort runaway JS; no UI isolate execution |
| P19 | S `data/innertube/StreamResolver.kt`, `InnerTubeXResolver.kt`, `PlayerClient.kt`, `potoken/*`; D `DesktopPoTokenWebView.kt`, `DesktopRangeStream.kt`, `DesktopInnerTubeXStore.kt` | InnerTubeX cipher/client tiers, NewPipe fallback, client headers, PoToken minting, URL verification/expiry/coalescing, windowed range fetch | Streaming resolver contract + isolated JS/browser/native adapters; no assumed pure-Dart drop-in | Live legal test-account streams on both OSs, expired token/403 recovery, cipher fixture corpus, cancellation; major unresolved port dependency |
| P20 | D `DesktopAccounts.kt`, `DesktopYouTubeAuth.kt`, `DesktopYouTubeSession.kt`, `DesktopBrowserSignIn.kt`, `DesktopWebSignIn.kt`, `DesktopBrowserCookies.kt`, `DesktopWindowsCrypto.kt`, `DesktopSecretStore.kt`; Java `secrets/SecretServiceApi.java` | Multiple Google accounts/profiles, browser cookie import, embedded/browser sign-in, session refresh, logout; DPAPI/Secret Service semantics | `features/accounts`; flutter_secure_storage candidate + OS bridge/browser spike | Test-owned profiles only; missing/locked keyring, DPAPI same-user constraints, account switching, logout erasure, redacted logs |
| P21 | D `DesktopSpotifyLibraryAuth.kt`, `DesktopSpotifyChromiumToken.kt`, `DesktopSpotifyCanvas.kt`; S `data/spotify/*` | Spotify token/client-token acquisition/refresh and library/Canvas behavior | Separate Spotify token broker + browser adapter | Expiry/relogin, failed browser capability, no stale cross-account requests; endpoint compatibility remains unverified |
| P22 | D `DesktopLyricsClient.kt`, `DesktopLyricsSession.kt`, `DesktopLyricsSourcesUi.kt`; S `data/lyrics/*` | Source ordering/enabled set, hidden providers excluded, selected provider override, word-sync priority and cached/fallback states; one app-wide session even with main window hidden | `features/lyrics/application`, provider adapters and shared lookup session | Provider fixtures including miss/failure/cancel; prioritize-word on/off; useful duration gate; no mid-song refetch for rendition upgrade |
| P23 | U `ui/player/PlayerLyrics.kt`, `LyricClock.kt`, `LyricFocus.kt`, `LyricsControlsGesture.kt`, `LandscapeLyricEdges.kt`; S `EnhancedLrc.kt`, `SyllableRuns.kt`, `LyricAlignments.kt` | Word/syllable timing, duet/background voices, gaps, line focus, scrolling, offsets, translation, sharing, pause/buffer behavior | Dart parsers and clock; custom text layout/painters + ticker | Port lyric clock/parser regression corpus; only announced discontinuity reverses time; timing trace + frame-sequence comparison |
| P24 | U `ui/player/NowPlayingScreen.kt`, `PlayerDock.kt`, `PlayerChrome.kt`, `LandscapePlayer.kt`, `PlayerSidePanels.kt`; D `DesktopPlayerHost.kt`, `DesktopSidePanel.kt`, `DesktopPlayerSheet.kt` | Empty dock, compact/expanded/fullscreen player, metadata/marquee, transport/volume/quality, details/queue/lyrics, cover and responsive columns | `features/player/presentation`, shared view models | Empty/loading/error/mixing states; 100/125/150/200% scale, narrow/wide windows, focus and screen-reader controls |
| P25 | D `DesktopMusicVideo.kt`; U `MusicVideoLayout.kt`, `DesktopMusicVideoLayout.kt`; S `data/video/*` | Beta audio/video versions, quality, fit/layout, queue identity and synchronized clock; feature off preserves classic audio behavior | Shared native FFmpeg media sessions + Flutter texture surfaces | Rapid switches, seek/buffer/A-V drift, detach/reopen, no second competing audio output |
| P26 | D `DesktopCanvas*.kt`, `DesktopContinuedArtist*.kt`, `DesktopAppleArtist*.kt`, `DesktopArtistSignatures.kt`; S `AppleCatalogue.kt`, `AppleArtistPhotos.kt`, `canvas/*` | Spotify Canvas and artist motion/HLS/art metadata, static/error fallback, lifecycle-aware decoding | `data/artwork`, native video texture worker, bounded disk/decoded caches | Same selection policy, reduced-animation behavior, hidden window stops visual work, decoder/frame memory released |
| P27 | D `DesktopPip*.kt`, `DesktopWindowVisibility.kt`; U player host | Floating window independent of hidden main window; shared playback/lyrics, geometry/resize/settings/focus | Native window host spike; Dart `desktop_multi_window` candidate, one transport owner | Native PiP test scenarios, main close/hide/reopen, multiple DPI monitors, no duplicate player engine |
| P28 | D `DesktopShortcuts.kt`, `DesktopKeyboardShortcutsModal.kt`, `Main.kt` | Exact key combinations; key-down dedupe, text-editor/modal exceptions, focus-loss reset; Esc/F11 native fullscreen escape | Flutter Shortcuts/Actions plus native focus/fullscreen adapter | Event traces including autorepeat, text entry, lost key-up, embedded video focus |
| P29 | D `DesktopTheme.kt`, `DesktopTypography.kt`, `DesktopFontSettings.kt`, `DesktopUiScaleSettings.kt`, `DesktopAppIcon*.kt`; U theme/graphics/icons | Light/dark/system/pitch-black, accent/artwork palette, weight-specific fonts, scale and icon selection | Explicit Flutter design tokens, font assets, SVG/vector geometry; flutter_svg candidate | Palette/type metrics and glyph baselines, asset manifest/hash check; native caption/theme changes |
| P30 | D `DesktopSurfaces.kt`, `DesktopSidebarMica.kt`, `DesktopBlurCustomization.kt`, `DesktopAppearanceSettings.kt`, `DesktopFlyout*.kt`, `DesktopMenuPortal.kt`; U `OptimizedHaze.kt`, `BlurredBackdrop.kt` | ASTRA frosted surfaces, global blur override, old top-player and Explore styles, menu anchoring, hover/scroll animations | Bounded BackdropFilter/CustomPainter surfaces and native Windows material bridge | Home under-panel identity/scroll preserved; solid panel elsewhere; reduce blur applies everywhere; compare transition frames, not only endpoints |
| P31 | D `DesktopApp.kt:DesktopSettingsSection`, `DesktopContentPreferences.kt`, `DesktopTranslation*.kt`, `DesktopStrings.kt`, `DesktopScrobbleSettings.kt` | All 18 settings groups below, live apply, search/filter, stored defaults and legacy-value migration; localization | Typed settings repository + Flutter gen_l10n; migrations | Every exposed preference change/restart/default/reset path and translated fallback; no hidden controls resurrected from stale docs |
| P32 | D `DesktopTrayMenu.kt`, `DesktopStatusNotifier.kt`, `DesktopAwtTray.kt`, Java `tray/*`, `DesktopMpris.kt`, `DesktopWindowsMedia.kt` | Linux SNI/DBus menu and fallback tray; MPRIS/Windows SMTC metadata, seek/media commands; close-to-tray versus quit | tray_manager candidate + DBus/WinRT platform bridge; media-control adapter candidates only after desktop API validation; all commands target the one C++ engine | GNOME/KDE support/fallback, Windows media keys/thumbnail, quit drains persistence and releases native handles |
| P33 | D `DesktopDiscordIpc.kt`, `DesktopDiscordRpc.kt`, `DesktopDiscordSettings.kt`, `DesktopPresence.kt`, `DesktopScrobbling.kt`; S `com/my/kizzy/*`, `data/presence/*` | Discord activity modes/art/timestamps, public presence, Last.fm/ListenBrainz now-playing and scrobbles | Integration services isolated from playback; sockets/WebSocket/dio | Disconnected service never blocks playback; threshold/dedup/retry/auth failure and disable behavior |
| P34 | D `DesktopListenTogether.kt`, `DesktopPartySync.kt`, UI; S `data/listentogether/*`; `backend/` | Six-character room, member actions, synchronized queue/clock, reconnect/kick handling, personal queue stash/restore; Automix disabled in party | Dart WebSocket protocol adapter + clock estimator + queue coordinator; existing Go backend retained | Deterministic multi-client server traces, drift and stale events, disconnect/rejoin, leave restores personal queue |
| P35 | D `DesktopPersistence.kt`, `DesktopPreferenceChunks.kt`, `DesktopPlaybackWrites.kt`, `DesktopAccounts.kt` | Chunked Preferences, account data, serialized queue/cursor snapshots, delayed/coalesced writes, shutdown drain | SQLite transactional repositories + read-only Kotlin import tool planned | Import/export fixtures preserve identities/order/defaults; kill/restart and failed-write recovery; reference data never modified |
| P36 | D `DesktopArtwork.kt`, `DesktopByteBudgetLru.kt`, `DesktopMediaCache.kt`, `DesktopAudioCache.kt`, `DesktopPageMemoryPolicy.kt`, `DesktopMemoryOptimizer.kt` | Byte budgets, checksummed audio spans, page eviction, hidden-window trimming and deferred scans | Shared memory coordinator + bounded image/texture/audio ownership | See performance protocol; corrupt-cache recovery; many-hour navigation does not retain inactive pages/decoders |
| P37 | D `DesktopUpdate*.kt`, resources `updater/*`, `desktopApp/packaging/tests/*`, `packaging/aur/` | Stable channel, throttled checks, progress/cancel/verify, installation detection, disk checks, helper shutdown/relaunch readiness and recovery | Native update coordinator + platform installers; no generic package assumed equivalent | Actual Windows installed/portable and Linux AppImage/package-managed upgrade matrix, failed launch rollback, marker handshake, verified metadata |
| P38 | D `DesktopLicenses.kt`, `DesktopAboutVersionTitle.kt`, `DesktopOriginalVersion.kt`, `DesktopReleaseNotesMarkdown.kt`, resources | About/version, notices/licenses, release markdown, links, logs/diagnostics | `features/about`, flutter_markdown candidate, url_launcher, bounded redacted logging | Version/build provenance; exact links/text/actions; package notices and dependency license audit |

## Settings and source subtleties

The actual `DesktopSettingsSection` order is ACCOUNT, LISTEN_TOGETHER, SOURCES, AUDIO_QUALITY, DOWNLOADS, OUTPUT_PRECISION, AUDIO_OUTPUT, PLAYBACK, CONTENT, DESKTOP_PLAYBACK, APPEARANCE, LYRICS, LOCAL_MUSIC, STORAGE, MISCELLANEOUS, LANGUAGE, ADVANCED, APP_UPDATES. There are **18** groups. Preserve each row's availability predicates and persistence default, not just this enum. Playback includes video beta, crossfade, Automix duration/performance, silence skipping, spatial widening, Atmos preference and EQ. Desktop playback includes autoplay/shuffle/repeat/speed/direct source/sleep timer. Appearance includes theme/font/scale/icon, global blur/animation controls, unfocused lyrics, Mica, volume visibility and old player/Explore styles. Other groups cover accounts, providers/quality, output precision/device, local folders, storage/cache, integrations/tray, languages/translations and updates. Per-file evidence below locates the other controls; Phase 2 must extract literal row labels/defaults/visibility into executable UI fixtures before building those screens.

`DesktopAppearanceSettings.effectiveDesktopBlur` currently returns `!reduceDynamicBlur`; legacy per-area and performance-profile state still exists but is ignored by this effective function. Older performance docs describe stronger profile behavior; do not implement that older prose as the current contract. `DesktopPersistence.sourceConfigs` removes the withdrawn seeded MODULE source, retains CUSTOM_MODULE, and keeps YouTube enabled. An enum entry alone does not prove a user-facing feature is available. `DesktopStreamFormat.isDolbyAtmos` is codec classification, and stereo widening is DSP; neither proves object-based Atmos speaker rendering. Preserve observed decode/channel behavior and labels, without inventing capabilities.

Lyrics source declaration order: LRC_RED, BINI_LYRICS, BETTER_LYRICS, BETTER_LYRICS_PORTATO, PAXSENIX, PAXSENIX_SPOTIFY, PAXSENIX_MUSIXMATCH, LYRICS_PLUS, SIMP_MUSIC (hidden), UNISON, YOUTUBE_TRANSCRIPT, YOUTUBE_MUSIC, MEGALOBIZ (hidden), KUGOU, LRCLIB, MUSIXMATCH, GENIUS. Hidden sources are not offered or requested. Persist names rather than ordinal positions. Preserve ordered selection despite concurrent requests, lazy Genius fallback, word-priority fallback and provider status/cancellation distinctions. Translation and embedded local lyrics have separate adapters and cache rules.

## Visual and asset contract

Treat `docs/desktop/ASTRA.md`, transport icon attribution, source theme constants and actual render captures together. Capture reference frames before translating layout: initial/welcome, empty dock, every route and dialog, light/dark/pitch-black, album/artist hero, active/inactive/error lyrics, queue, search, menus near window edges, PiP, expanded/fullscreen, video/Canvas, reduced blur/animation and legacy appearance switches. Use the same content, fonts, size, scale, platform caption and timestamps. Preserve pointer targets, scroll/keyboard behavior, transitions and native decorations. Native antialiasing differences need documented regional review, never automatic blanket tolerance.

Desktop resources include SF Pro Display regular/medium/semibold/bold/heavy OTFs, `bintang_music_black_about.ttf`, BitChord logos/wordmark/icons and nine transport SVGs. Shared Compose resources contain additional output/quality/service glyphs and locale strings. Model assets are `app/src/main/assets/beat_this_final0_q8.onnx` and `vocals_umxhq_int8.onnx`; preserve hashes and preprocessing contracts. Existing possession of font/model files does not establish redistribution permission for a new package: review notices, font rights and native codec licensing before distribution. Do not silently substitute typography or icons. The full hash inventory below includes binary assets, locale files and native dependencies without copying them into Flutter.

## Current verification and unresolved evidence

The preceding environment setup verified a full Java 21 toolchain, Gradle 8.11.1, native AutoMix compilation/loading and a Java/Xvfb graphics smoke check. Those checks preceded the reference switch and are not claimed as a build of this pinned commit. A new reference JVM-suite attempt is recorded in this phase; see MIGRATION_PROGRESS for its result. No Windows runtime, physical audio output, authenticated streaming, reference visual captures or Kotlin/Flutter RAM comparison has been established. Flutter is intentionally not scaffolded. Package choices are candidates until their Windows/Linux capabilities and current maintenance are checked. Fonts, JS execution, browser/token plumbing, multi-window ownership, data import and updater compatibility are explicit gates, not hidden implementation placeholders.

## Concrete behavioral checkpoints from source

- `DesktopQuickSearchState.kt`: 300 ms debounce, at most five results, independent full-Search query, revision changes invalidate responses even after closing and reopening the same query. Port key navigation, hover selection, activation and error behavior, not merely the search box.
- `DesktopShortcuts.kt`: Ctrl+K quick search; Ctrl+/ shortcuts; Space play/pause; Ctrl+Left/Right previous/next; Shift+Left/Right seek; Alt+Up/Down volume; Alt+S shuffle; Alt+R repeat; Alt+Shift+H Home; Ctrl+Shift+L Search; Ctrl+Alt+L lyrics. Dispatch once per physical press, reset held keys on focus loss and retain editor/modal exceptions. Main.kt additionally handles Linux Esc/F11 fullscreen exit with native focus.
- `DesktopQueue.kt`: forward jumps rebuild upcoming tiers while retaining manual entries; backward jumps visit history. Rendition changes preserve queueEntryId/tier/origin. Preserve “original version” pins (`DesktopOriginalVersion.kt` and persistence) so a user can bypass recording substitutions; verify account/queue restart and source upgrade do not override those pins.
- `DesktopSourceRegistry.kt`: current implementation declares 20 s substitute and 90 s upgrade timeouts, three stream attempts, two-second same-recording duration tolerance and a 96 kbps upgrade gain threshold. Resolve these together with actual selection predicates in fixtures; changing a timeout is an intentional behavior change, not an automatic Flutter optimization.
- `DesktopAudioEngine.kt`: analysis/transition eligibility is conditional on speed, silence skipping, channel count, party and overlap state. Preserve these guards instead of enabling all advanced modes simultaneously. During overlap the incoming decoder has already consumed audio; lyrics/seek position must adopt that elapsed time on handoff.
- `DesktopPlayerThemeSettings.kt` and shared `PlayerEnums.kt`: DEFAULT cover-edge mesh, LEGACY four-color animation and SIMPLE pre-blurred cover are distinct options. Import the previous legacy boolean when the enum is absent. Appearance also exposes native Windows title-bar material, separately from opaque client content and Mica sidebar.
- `DesktopArtwork.kt`: decoded artwork budget is one-sixth of max JVM heap clamped to 32–128 MiB. `DesktopByteBudgetLru.kt` keeps the newest entry even when it alone exceeds the budget, so this is not a strict bound on all image memory. `DesktopMediaCache.kt` defaults to 512 MiB disk, with a configurable 128 MiB–10 GiB range. `DesktopAudioCache.kt` separately caps audio spans at 256 MiB and 1,024 files, with 512 KiB per span and integrity/expiry checks. Do not add these disk figures to RAM.
- `DesktopPageMemoryPolicy.kt`: resident memory, not just JVM heap, triggers inactive-page eviction at 1 GiB; preserve active-route identity and lightweight restoration while dropping heavy payloads. `DesktopMemoryOptimizer.kt` clears/trims on hidden/minimized windows. Flutter should improve ownership before pressure reaches this level rather than inherit a JVM-specific threshold blindly.
- `DesktopUpdateManager.kt`: stable is the only user-facing channel; persisted Preview is ignored. Automatic checks are throttled at six hours, while startup cleanup is independent of that setting. Cancellation/generation fencing must prevent older operations from overwriting a newer state. Relaunch health is a mapped/rendered window readiness acknowledgment in Main.kt, not a spawned PID.

## Phase 0.5 — detailed Kotlin audio audit and reuse decisions

This extends Phase 0 without replacing its matrix or immutable 779-file inventory. Language and boundary decisions are finalized in MIGRATION_ARCHITECTURE's Phase 0.5 decision record. Native means **C++17**, not Dart PCM or a generic Flutter music player. Kotlin remains the definitive behavior reference. Source anchors below refer to the pinned source, not later migration line numbers. These are static findings; none implies physical audio or Flutter parity has passed.

### Signal and control flow traced

1. `DesktopApp.kt`/`DesktopPlayerHost.kt` supply queue/settings/user intent to `DesktopPlaybackEngine`. `loadInternal/openTrack` resolve local saved files or source streams, open off the audio thread, then enqueue generation-tagged Select/Start/Replace commands. Foreground and speculative opens are separately serialized. A quality upgrade replaces the current deck without destroying prepared next audio. Consume-time generation checks close stale opened decoders.
2. `DesktopAudioDecoder.open` uses FFmpeg AVFormat/AVCodec, best available lossless-then-bitrate manifest rendition, discards other streams, honors stream start timestamps and codec packet timebase/priming. Actual decoder sample rate overrides the requested rate; float output and explicit channel shape are obtained via swresample. `DesktopRangeStream` provides seekable ranged AVIO; a server ignoring Range can fall back to streaming native HTTP instead of buffering the full file. Decoder interrupt callback supports cancellation.
3. `DesktopDecodeAhead` is sole owner of a decoder after construction; seek/close are sent to its worker. Float blocks cross a bounded-duration queue, with RUNNING/EOF/FAILED/CLOSED distinctions and seek generations. Normal speculative decode is two seconds; adopted/current/mixing capacity expands to eight seconds. Read-ahead does not wait to fill its full budget before returning first PCM. Empty queue is not necessarily EOF.
4. `DesktopAudioEngine.step` drains commands, handles loop runtime, obtains current samples, prepares/splits at the precise submitted-frame transition boundary, and processes the two decks. `readCurrent/readIncoming` perform needed rate conversion/tempo buffering. Widening and EQ are deck-local before summation; transition gain/filter/echo operate on the overlap. `renderProcessed` applies silence skipping then listener speed, then writes. Loop path uses its prepared native renderer and has its own eligibility/processing rules; it cannot be replaced with two independently playing plugin streams.
5. `writePcmFully` advances accepted frames only for positive whole-frame writes; partial writes retry the remaining samples. `DesktopAudioSink` converts float PCM/gain to negotiated output, preferring Windows native WASAPI and otherwise Java Sound. It negotiates requested format, float-to-S16, alternate rates and stereo fallback. In-flight device data is accounted separately from submitted audio.
6. `DesktopPlayhead` maps played frames through piecewise source-time segments for user speed, deck tempo easing and removed silence. `publishTrack/publishPosition` pair track position with native monotonic timestamps, buffering and discontinuity state. UI lyric clock uses samples as anchors, not a schedule for audible transitions.
7. At blend midpoint `switchDisplay` adopts incoming title/art/position and queue callback once, while outgoing audio remains live. `promoteUpcoming` later closes the old decoder, preserves incoming EQ history by swapping processors, eases tempo and subtracts queued source time from incoming elapsed audio. `prepareNext` after midpoint is deferred until overlap finishes. Seeking after midpoint targets incoming audio.
8. Ordinary EOF handoff consumes the prepared next deck without a crossfade; same-rate/channel output retains submitted-frame boundary and avoids draining/reopening. A format change drains/reconfigures, so “gapless” across arbitrary device/rate changes is not established. Sleep-after-track/party and repeat policy suppress inappropriate prefetch. A stalled worker uses bounded recovery; healthy PCM resets its recovery budget. `release/shutdown/closeEverything` cancel jobs and close queued/active native resources; analysis sessions wait for their owner to exit.

### Reuse and port map

`D` is the desktop Kotlin path defined above. Shared paths below are complete package suffixes located by the existing inventory. Reuse means algorithms or allowed native code from the **Kotlin reference tree**, never code from a previous Flutter rewrite. New bridges live only under `flutterDesktop/`; existing C++/Kotlin/model files are not edited. Source provenance, notices and hashes accompany each reuse. JNI signatures and process-global ownership are replaced, not linked into Flutter unchanged.

| Component / exact source family | Audited algorithm/dependency | Native/Dart disposition | Required regression oracle |
|---|---|---|---|
| D `DesktopAudioEngine.kt` (`drainCommands`, `step`, `finishTrack`, `promoteUpcoming`, `performSeek`) | Coroutine opens + concurrent command queue + serial pump; current/upcoming generations; upgrade/recovery | Independently port engine/control behavior to C++; Dart supplies semantic queue intent and projected UI state | `DesktopInstantPlaybackEngineTest`, `DesktopAutomixTest`; stale Start, Next/midpoint, queue edit and rendition races |
| D `DesktopAudioDecoder.kt`, `DesktopRangeStream.kt`, `DesktopAudioCache.kt` | FFmpeg JavaCPP wrappers, AVIO, swresample, seek trim, signed-client headers, bounded range cache | Use FFmpeg/libcurl native APIs and C++ RAII; reuse cache format rules if version-compatible; no JavaCPP/JVM | `DesktopAudioDecoderTest`, `DesktopAudioCacheTest`; FLAC/Opus/MP3 priming, HLS/DASH multi-audio, ignored Range, expiry/cancel/truncated media |
| D `DesktopDecodeAhead.kt`, `DesktopPcmWrite.kt` | Worker-exclusive decoder; seek generation; timed polling/lifecycle; partial whole-frame writes | C++ owned workers, pooled rings and typed frame counts; remove allocating retries from critical render path | `DesktopDecodeAheadTest`, `DesktopPcmWriteTest`; underrun vs EOF vs death, cancellation under IO, no old PCM after seek |
| D `DesktopPlayhead.kt`, `DesktopPlayback.kt`, `DesktopPlaybackDiagnostics.kt` | Segmented source-time mapping; played vs queued frames; sampled timestamps/seek count | Native authoritative clock/snapshots, Dart calibrated display interpolation | `DesktopPlayheadTest`; speed change after queued samples, silence discontinuity, deck tempo easing, output rebase |
| D `DesktopRateConverter.kt` | Blackman-windowed sinc; 24 zero crossings, 512-entry/frame table resolution, 0.95 cutoff margin | Translate algorithm to C++ with same phase/history/tail rules; no silent lower-quality resampler substitution | `DesktopRateConverterTest`; 44.1↔48/96 kHz frequency sweep, impulse, anti-aliasing and handoff rate |
| D `DesktopAudioSpeed.kt`, `DesktopTempoBuffer.kt` | Pitch-preserving correlation/overlap processing; speed short-circuit at untouched 1x; drain/reset continuity; deck rate clamp | Independently port these algorithms to C++; do not assume Signalsmith is a sound-identical replacement for listener-speed path | `DesktopAudioSpeedTest`, `DesktopTempoBufferTest`; transients, block partitioning, drain at 1x and seek resets |
| D `DesktopEqualizer.kt`; S `playback/EqualizerCurve.kt`, `EqualizerPreset.kt` | Ten filter sections serving seven-band/tone UI; topology-preserving state-variable filters, gain/Q/balance smoothing in 64-frame subblocks | Native curve/coefficient generation and per-deck state; Dart edits user parameters and paints EQ | `DesktopEqualizerTest` and new differential PCM corpus; bypass glide, strong bass history and seam transfer |
| D `DesktopAudioProcessors.kt` | Stereo width 2.5, output gain 0.82, crossfeed 0.2, low-pass 0.3, 15 ms delay; silence minimum 1 s, 100 ms each end, threshold 1024/32768 and retained gain 0.1 | C++ native processors preserve constants/ordering and source-time skipped-frame accounting | `DesktopAudioProcessorsTest`; mono bypass, stereo impulse response, threshold crossings, no inter-track silence carry |
| S common `playback/smart/TransitionPlanner.kt`, `TransitionPolicy.kt`, `BeatAwareTransition.kt`, `LoopMix.kt`, `TrackAnalysis.kt` | Evidence-based cue/window/style/gain/key/tempo plans; adaptive/phrase-bar mixes; live/spoken exclusions and fallback | Port pure planning algorithms to C++ preparation worker; pass native plan summaries to Dart; no Dart planner owner | `AdvancedAutomixPlannerTest`, `PhraseBarAutomixTest`, `BeatAwareTransitionTest`, `LoopMixTest`; same input→plan fields and eligibility |
| D `DesktopTransitionRide.kt`, `DesktopTransitionEcho.kt`; S JVM `playback/TransitionFilter.kt` | Per-deck tempo/filter/low-end handover/equal-power blending and echo tails | C++ renderer/DSP; preserve chosen audible overlap when settings change | `DesktopBeatAwarePcmTest`, `DesktopTransitionFilterTest`, `DesktopTransitionEchoTest`; PCM envelope/spectral/continuity checks |
| D `DesktopTrackAnalyzer.kt`, `DesktopAutomixWorkQueue.kt`, `DesktopAnalysisRuntime.kt` | One analysis task at a time; queued current+next only; optional loop enrichment cannot invalidate base analysis; persisted feature results | C++ bounded preparation/inference worker and versioned feature store; Dart receives progress/status | `DesktopAutomixWorkQueueTest`, `DesktopBeatModelTest`, `DesktopLoopRendererTest`; obsolete/canceled work publishes nothing |
| S JVM `playback/smart/TrackFeatures.kt`, `BeatTracker.kt`, `VocalTracker.kt`, `MelSpectrogram.kt`, `AnalysisStore.kt` | Structural tempo/key/energy + ONNX beat/downbeat/vocal inference and fixed tensor layouts; model-specific postprocessing | Port JVM orchestration/postprocessing/store semantics to C++; ONNX Runtime C API, no Java tensor boxing | Beat/model/stress suites and canonical feature corpus; shape/normalization/postprocess agreement and fallback |
| `native/analyzer/{audio_analysis,tempo_analysis,mel_spectrogram,vocal_spectrogram,resampler}.*` | Existing platform-neutral C++ feature/preprocessing primitives | Reuse reference native code through a new C ABI after license/provenance review; no JNI required | Canonical hash verification + native/feature differential tests; preserve numeric precision/FFT windows and rate |
| `native/loopmix/loop_mix_renderer.{h,cpp}`, D `DesktopLoopRegion.kt`, Kotlin `playback/loopmix/LoopMixSession.kt` | Two stereo decks, sample-indexed beat knots, region loops/release, envelopes, filters, preroll and Signalsmith Stretch | Reuse renderer algorithm/library, port preparation/session ownership; native render on one sink clock | `DesktopLoopRendererTest`, `LoopMixTest`; chunk-size-independent seams, pre-roll, release, cancellation, no render-time growth |
| `native/third_party/signalsmith-stretch/`, `signalsmith-linear/` | Vendored C++ headers: Stretch 1.3.2 (`88061fd4`) and Linear 0.6.6; MIT notices | Reuse pinned permitted dependencies, keep attribution; audit callback allocations | Header hashes, deterministic renderer fixtures, allocation instrumentation |
| `app/src/main/assets/beat_this_final0_q8.onnx`, `vocals_umxhq_int8.onnx` | Quantized beat/downbeat and vocal models; vocal fixed 960-frame layout | Reuse exact model assets and preprocessing with ONNX Runtime native provider; lazy initialization | Model SHA equality, tensor and feature fixtures on both OSs; no substituted model by filename |
| `app/src/main/cpp/jni/{analysis,mel,vocal}_jni.cpp`, `desktopApp/native/loopmix_jni.cpp` | JVM array conversion/native handle glue | Reference for call semantics only; replace with new C ABI and explicit ownership | No JNI exports/JVM dependency in Flutter backend; buffer lifetime tests |
| D `DesktopWindowsAudio.kt`, `DesktopAudioDevices.kt`, `desktopApp/native/window/window_jni.cpp` | Shared WASAPI, endpoint notification/reroute, submitted minus padding timing, gain/precision conversion; Java Sound fallback | Extract behavior into new C++ per-engine WASAPI adapter, remove JNI/global/window coupling; native fallback ladder | Windows endpoint/384 kHz mix-format scenarios, short writes, unplug while paused/playing, no-endpoint clock semantics |
| D `DesktopAudioSink.kt`, `DesktopLinuxPulseAudio.kt`, `DesktopLinuxAudioWatcher.kt` | Java Sound/ALSA sink; pactl endpoint inventory/route, app-PID-only moves, system-change poll | New C++ libpulse timing/output plus ALSA fallback; remove Java Sound/pactl runtime dependency while preserving observable selection/failure behavior | PulseAudio and PipeWire-Pulse, ALSA-only, Bluetooth, default/explicit/missing endpoint, route latency and other-app isolation |
| D `DesktopSourceRegistry.kt`, `DesktopPlaybackWrites.kt`, `DesktopQueue.kt`; shared queue rules | Resolution policy/upgrades, queue identities, coalesced durable snapshots | Dart policy/data stays separate; native requests source purpose/next nomination and reports exact adoption | Source/queue/instant playback traces; crash/restart snapshots and no duplicated history/scrobbles |

Header provenance is not uniform: TransitionPlanner, BeatTracker and DesktopTrackAnalyzer carry Orchard-derived AGPL notices, while loop renderer carries GPL and Signalsmith dependencies MIT. Record each file's actual notice and derived-work obligations in distribution review; do not infer “all MIT” from the third-party directory. This audit makes no new licensing determination and does not remove notices. Model redistribution and FFmpeg build/license configuration remain packaging gates.

### Audio risk register and prioritized proof

| Priority / risk | Specific failure mechanism | Required mitigation and acceptance evidence |
|---|---|---|
| A0 — stale commands / ownership | Late opens or queue edits replace a newer track; shutdown frees a live FFmpeg/ONNX context | Consume-time generations, per-owner disposal, terminal command IDs; stress select/seek/upgrade/quit under blocked IO and sanitize both OS builds |
| A0 — audible scheduling | Dart event-loop/UI stall shifts beat cue or causes double midpoint adoption | Native sample-frame seam/plan execution; stall Dart service/UI and verify audio continuity, one adoption and correct history |
| A0 — gapless / priming | Codec delay, unflushed resampler/timestretch tails, drain/reopen or miscounted partial writes create gaps/repeats | Gapless corpus with reference behavior for equal formats; retain frame boundary and tails; rate-change path explicitly tested, no universal gapless claim |
| A0 — output clock | Submitted vs presented frame confusion; device replacement resets counters; sample epoch mismatch drifts lyrics | Segmented time map, device/clock epochs and calibrated Dart mapping; loopback and deterministic clock traces across hotplug/speed/silence |
| A0 — DSP/audio quality | Different resampler, gain/clipping, float/int rounding, ordering or EQ state makes settings sound different | Preserve float graph and reference per-platform conversion; reference PCM/response tests before optimizing; bit-exact claim only for verified paths |
| A1 — Automix plan/renderer mismatch | Correct beat model but wrong confidence/window/postprocessing, tempo easing or loop knots | Native input→features→plan→PCM fixture chain, plan version hashes, both modes and six-track sequences; eligibility/fallback tested |
| A1 — buffering / network recovery | Empty queue treated as EOF or unbounded retry; server ignores Range; Dart source broker stalls | Explicit decoder lifecycle, bounded interruptible IO/recovery, two/eight-second policy, source-purpose deadlines; fault-injected HTTP and stale replies |
| A1 — memory/RT contention | Full analysis decode plus chunks+joined PCM, tensors, pre-roll regions, 64 MiB stack reservation and ONNX threads compete with audio | Native high-water accounting and bounded workers/pools; preserve analysis coverage, avoid redundant copies; profile and stress audio while analysis runs |
| A1 — platform dependency | Native DLL/SO/codec/provider absent, Linux server latency differs from Java Sound, WASAPI COM callback outlives engine | Capability/errors and supported packaging; clean OS bundle, output matrix and notification teardown tests; missing support is blocked, not a fake fallback |
| A1 — bridge pressure | UI isolate stalls, event queue fills or receipts are misread as applied audio | Admission credits, bounded journal and snapshot watermark; queue-full/cancel/dispose tests; memory plateaus under consumer stall |
| A2 — visual/media interactions | PiP starts second audio engine; video has competing output; decorative decoder survives hide | One transport owner, media audio routed through same backend, lifecycle/whole-process memory tests |

The reference has multiple fallback constants in different layers: DesktopAudioEngine uses a six-second Automix fallback, while TransitionPlanner also contains an eight-second auto fallback in its own decision paths. Preserve call/context behavior rather than collapse them into one global constant. Analysis EFFICIENT/BALANCED/PERFORMANCE modes request 1/2/4 inference threads clamped against available cores (leaving two where possible), with efficient yielding; they change CPU policy, not the audible mix algorithm. Native defaults must reproduce coverage and selection before tuning concurrency.

Phase 0.5 verification: `python3 tools/automix-validation/verify-upstream.py` passed, verifying 25 canonical AutoMix source/model files against `b690dc146009d4815825fa4a20815ef2e4ee8898`. This confirms manifest identity only, not audio quality, model accuracy or runtime parity. The earlier JitPack-blocked JVM test outcome remains unchanged evidence; no tests were rerun merely to reproduce that known access failure. No backend, bridge or Flutter feature was implemented.

## Phase 1 shell extraction — initial source audit (historical)

This records the 2026-11-11 checkpoint, when SDK bootstrap was blocked and
implementation had not begun. The later implementation, hosted validation and
remaining gaps are recorded in the Phase 1 updates below and in
`MIGRATION_PROGRESS.md`.

| Surface | Pinned Kotlin source anchor | Exact requirement for the Flutter shell |
|---|---|---|
| Window | D `Main.kt:desktopMain`, `MIN_WINDOW_WIDTH_DP`, `MIN_WINDOW_HEIGHT_DP` | Initial 1220×780; minimum 900×600; decorated, opaque, resizable native window. Match logical size and account for OS client insets/DPI |
| Window restoration | D `Main.kt`, `DesktopWindowMode.kt` | In-session placement/fullscreen restore with stale native placement guards; no persisted width/height/position restore found in Main's initial WindowState. Do not invent persisted geometry as reference parity |
| Sidebar | D `DesktopApp.kt:DesktopSidebar` | Expanded 220 dp, collapsed 72 dp; 180 ms width tween. Opaque surface, no sidebar blur; Windows wallpaper-tinted Mica is a separate native capability |
| Rail/drawer | D `DesktopSidebarWidthPolicy.kt`, `DesktopFrame` | Width below 980 dp automatically uses rail; transient expanded drawer overlays without shifting content. Auto-collapse does not overwrite stored `sidebar_collapsed`; resizing wide clears a stale drawer request |
| Sidebar rows | D `DesktopSidebarItem`, `DesktopPrimaryNavigationIcons.kt`; U `ui/icons/BitChordIcons.kt` | Home/New/Search/Library use exact custom tab vectors. History/Downloads/Local Music/Spotify remain distinct. Selected surface and 3×18 dp rose indicator; collapsed rows 42 high, radius 7; tooltips and accessible labels |
| Empty sidebar data | D `DesktopSidebar` | No invented playlist names; reference text is “Your playlists will appear here.” Account/backend actions must not pretend sign-in is implemented |
| Dock | D `DesktopMiniPlayer` | Maximum 760 wide, 58 high, radius 16; empty state centers original 48 dp brand mark, without fake title/artwork. Artwork when populated is 42 dp; transport uses supplied SVG geometry; disabled/unavailable controls must be truthful |
| Dock spacing | D `DesktopBottomChrome`, `DesktopFrame` | Outer padding 10 horizontally, 8 top, 20 bottom; reference content generally reserves 132 dp under floating player. Preserve parent/sidebar coordinate system when centering |
| Legacy top player | D `DesktopTopBar`, `DesktopAppearanceSettings.kt` | Legacy top playback bar is 64 dp and opt-in (`enableOldTopMusicPlayerStyle` defaults false); do not add duplicate playback chrome in default floating-player mode. Legacy control breakpoints refer to available content width: 720/430/350 dp |
| Page spacing | U `ui/components/Common.kt:PAGE_GUTTER` | 16 dp shared page gutter; do not assume Material default page padding matches |
| Theme | D `DesktopTheme.kt` | System follows OS by default; explicit Light/Dark; pitch-black applies only to explicit Dark. Reuse all semantic palette values rather than one generic dark ColorScheme |
| Typography | D `DesktopTypography.kt`, `DesktopFontSettings.kt`; U `ui/theme/BitChordTypography.kt` | SF Pro Display regular/medium/semibold/bold/heavy cuts. Default profile maps requested 500→400, 600→500, 700→600, 800→700; Legacy uses physical requested weight. Disable synthetic bold; glyph metrics still require comparison |
| Type scale | Same typography sources | Customized roles: display 34/800, headline large 30/800, headline medium 22/700, title large 20/700, title medium 16/600, body large 16/400, body medium 14/400, label medium 12/600, label small 11/600, with role/profile-specific tracking. Other inherited roles must be audited before use |
| UI zoom | D `DesktopUiScaleSettings.kt` | 80/90/100/110/125%, default 100; independent of OS DPI/text scale. Sidebar wordmark compensates below 100% to retain physical legibility |
| Interaction/motion | D `DesktopHover.kt`, `DesktopShortcuts.kt` | Circular hover wash 120 ms; no-hover song-row background 170 ms FastOutSlowIn; reduced-animation zeros these durations. Reproduce keyboard editor/modal exceptions; do not claim a single generic button animation covers all surfaces |
| Asset provenance | `desktopApp/PLAYER_TRANSPORT_ICONS.md` | Nine supplied transport SVGs have documented upstream geometry and GPL attribution. Custom tab vectors must be translated faithfully; not replaced with generic Material tabs. SF Pro redistribution permission is not established by the existing asset files alone |

At this initial checkpoint, visual differences could not be measured because
neither a Flutter build nor matched captures existed. The later hosted evidence
and its limits are recorded below. The original Phase 0 inventory remains
unchanged.

## Phase 1 source implementation status — historical pre-CI snapshot

The rows below describe the implementation before hosted runtime validation.
They preserve that checkpoint's authored scope; the hosted shell evidence
update that follows supersedes their pending-test/build statements. P01–P38
and the reference hash inventory remain specifications, not accepted feature
parity.

| Reference domains | Source now authored | Verification and remaining gaps |
|---|---|---|
| P02/P28 navigation and shell | go_router routes, bounded back history, focus traversal, nonrepeating F11/Escape and navigation shortcuts | Six tests authored overall; none run. No feature-content/search behavior claimed |
| P29–P31 design/settings | Exact semantic palette values, explicit dark-only pitch-black, source type sizes/default weight mapping, persisted shell preferences, original brand/transport/tab/service glyph assets | Python asset/layer checks pass; SF Pro not redistributed, so typography is fallback. UI/OS theme, hover/motion and glass remain unverified |
| P24 empty player/shell | 58-high/760-max dock with original transport SVGs, no fake song/position, transport disabled; 220/72 sidebar and transient narrow drawer | No player backend; no genuine rendered/reference comparison or accepted layout parity |
| P11–P16 native preparation | Pure domain bridge/snapshot/identity/command/error-outcome lifecycle vocabulary only | No FFI implementation, fake engine, decode, DSP, Automix or native playback |
| P01/P27/P28 lifecycle/platform | Real window_manager adapter and application observer with bounded caches, explicit close and ordered preference drain | Native host generation/build/launch is pending a usable SDK; Windows/Linux capabilities are not verified |

Official platform hosts will be produced solely by `flutter create` through the checked-in initialization tool on an SDK-capable runner. The Actions workflow has not been run here. Package source hashes and Dart grammar parsing cannot prove the code compiles. Standard header/disconnected content, fallback typography, template platform icons, missing Mica/caption materials, unimplemented legacy top player/PiP and incomplete motion are recorded visual gaps. No runtime parity percentage, pixel-perfect claim or RAM improvement is reported. Phase 2 remains unstarted.

### Phase 1 hosted shell evidence update — 2026-11-12

The Windows/Linux Flutter foundation is now buildable and launch-verified in
GitHub Actions run
[43156992243](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43156992243):
13 tests per OS, zero failures/skips, release bundles, two client sizes, six
appearance/account/drawer states, fullscreen restoration and successful native
close. The complete pinned Kotlin Windows/Linux tests and package builds passed
in [43156276992](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43156276992),
including protected-source preservation, native DLL/JNI/model checks, and a
fresh-profile Windows shell capture. The prior Windows `buildAnalysisNative`
failure is resolved using MSVC and externally supplied Windows SDK headers;
protected reference source remains unchanged.

At matched hosted Windows client sizes, the extra Flutter navigation row is
removed and sidebar, account footer, and dock geometry align closely with the
Kotlin shell. The Home title ink starts at y=16 in both Windows captures, though
fallback glyph shape and width still differ. Live Kotlin home-feed content and
the disconnected Flutter placeholder are different states and are excluded
from common-shell parity judgments. A repeat Linux pair at both client sizes
is retained in `flutterDesktop/verification/phase1-linux-final/`: same isolated
Xvfb display at 96 DPI, Light/100%, separate temporary profiles and D-Bus
sessions. Both headings start at y=16, while Flutter fallback ink is 4 pixels
wider and 1 pixel taller. Sidebar/rail/account-footer/dock bounds align closely;
the playlist caption still wraps differently. Kotlin required explicit Skiko
SOFTWARE rendering in this setup; Flutter used Mesa llvmpipe. This verifies
client geometry under software rendering, not native compositor behavior.
SF Pro redistribution permission, exact Haze/noise/feed material and motion,
native caption/Mica/icons, and broad DPI/focus/accessibility verification remain
open. This evidence does not establish full visual parity or authorize Phase 2.

## Complete tracked reference inventory

Generated from the pinned commit's tracked files, excluding generated build outputs and any former Flutter rewrite. SHA-256 records exact content; test entries are verification sources, not passed outcomes. Main/common/JVM sources are primary; Android actuals and Android native JNI/model dependencies are contextual. Every file below is assigned to a parity domain; the matrix supplies behavioral acceptance and planned destination for that domain. Broad classifications are navigation aids, not proof every helper is a separate feature.

Inventory contains **779 tracked files**. Full paths and SHA-256 values allow rechecking against the reference; build outputs and the migration documents themselves are excluded.

| Source path | Parity domain | Role / declared anchors | SHA-256 |
|---|---|---|---|
| `.github/workflows/android.yml` | P37 | asset / configuration / specification | `e3c359a55a82c08ced69e082a3d95e80568da0297303e993e833e46299afd4af` |
| `.github/workflows/aur-package-check.yml` | P37 | asset / configuration / specification | `a893f4c94ffd87969c17efed3afd97038f18a03bc31fb6c01ce4e6e9c40d2974` |
| `.github/workflows/aur-update.yml` | P37 | asset / configuration / specification | `2ea9fcd216ef170ca89ba3fe794169a591447c2a639056627bfcafa915b07982` |
| `.github/workflows/automix-crossplatform.yml` | P37 | asset / configuration / specification | `7b5e901410a9fdf7245c51098cc3ddafaf171a59ccda2e54d9cc2e43c434fd02` |
| `.github/workflows/build-android-test.yml` | P37 | asset / configuration / specification | `e5bc378b0cfe95a1a24d59293decee644688ebd08679b449e922a5651f9fb221` |
| `.github/workflows/build-deb.yml` | P37 | asset / configuration / specification | `70fc07b124704d3effae62efc7930360c9ea935b741f6d3ae4953d3a2eff1ad3` |
| `.github/workflows/build-test-appimage.yml` | P37 | asset / configuration / specification | `cfcc9ba9e1a9094d2ed023a09429c1595c4b38d0d8b7062f00e7458b6fc268b9` |
| `.github/workflows/build-windows-portable.yml` | P37 | asset / configuration / specification | `92cebaa975473de314ec86c4a22f44333f5171b306bf464f8db4f17b847f8eb6` |
| `.github/workflows/build-windows.yml` | P37 | asset / configuration / specification | `518b2e34e9c147b3eef36c49c9a9d28d7bb8e1fda7ecb9643e51db5821b336ca` |
| `.github/workflows/check.yml` | P37 | asset / configuration / specification | `ef9465c88e313d9f17cb351ef6883680a2d78dfc80141c493f5dd5c6eb2d121d` |
| `.github/workflows/close-issue.yml` | P37 | asset / configuration / specification | `fe09ba04eab4993dfa585c29410238a2bb7941aa4e340dbb91f8bbc8d2953400` |
| `.github/workflows/contributors-image.yml` | P37 | asset / configuration / specification | `03ed0ecc761709ecb008c9ddd9dcd8511a30a1ca908598b85b4dae74aac62f07` |
| `.github/workflows/deploy-backend.yml` | P37 | asset / configuration / specification | `46582a599cd6f18f29b1fe63fdb950210450c0550984f1bd95adc7900f80127c` |
| `.github/workflows/linux-updater.yml` | P37 | asset / configuration / specification | `dfe2cd4d58a85d602a7ceb9eef91e081cdb723b03e863963462a64c12efed7d8` |
| `.github/workflows/release.yml` | P37 | asset / configuration / specification | `3c5eb770404fbb33dfc7a355554bfdc0a1b25ef5a3e4f86de8e390cf9ea12d55` |
| `.github/workflows/update-contributors.yml` | P37 | asset / configuration / specification | `7932f6052f5222829b8f152c1931ee608a552044b3626de87f23b875d2f8eb0d` |
| `.github/workflows/updater-reliability.yml` | P37 | asset / configuration / specification | `065a049eacc38e324048bfdfe9b95032d5fb5266f7da280344bbbe87996f1d9c` |
| `.github/workflows/windows-portable-inplace.yml` | P37 | asset / configuration / specification | `a688e53b1dca4004407154c81706530bd11fe85bd7c31a1495b58e041bd2a8a5` |
| `.github/workflows/windows-real-upgrade.yml` | P37 | asset / configuration / specification | `651bdabe3f52e5a3b7cdbf2bed1dfa441f547790543ad3c11b7d110c8eb32c63` |
| `.github/workflows/windows-setup-smoke.yml` | P37 | asset / configuration / specification | `c0c59bf75c773d08190abbb85dea940b6dea469bbc89ae80a918125fc0bdb179` |
| `.github/workflows/windows-updater-host.yml` | P37 | asset / configuration / specification | `0e15aa76d583f267af65f40bad0c5fbbe6a51bbd23b0de36bcceaf57ba74e809` |
| `DESKTOP.md` | P01/P02/P05/P06 | asset / configuration / specification | `1405f44c0624fe2877dbd11cfa11507b0af36ae69f3879e53455b1e9a3ae929b` |
| `LICENSE` | P01/P02/P05/P06 | asset / configuration / specification | `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986` |
| `README.md` | P01/P02/P05/P06 | asset / configuration / specification | `6d8a594633a76776f00cfc52ba81d28daa90a4eb3c66c40ecd07d3409c561804` |
| `app/src/main/assets/Logo.svg` | P01/P02/P05/P06 | asset / configuration / specification | `7d0560c2060cec0fb47a9edbb0569aecc7b60422bee366ee34e3d5505fa2b77f` |
| `app/src/main/assets/LogoTransparent.svg` | P01/P02/P05/P06 | asset / configuration / specification | `d763883bfe47fb114cfeaf792caf43cbfc9a76753f36d2c8ac38af00c77aa08c` |
| `app/src/main/assets/beat_this_final0_q8.onnx` | P15/P16 | asset / configuration / specification | `7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd` |
| `app/src/main/assets/vocals_umxhq_int8.onnx` | P15/P16 | asset / configuration / specification | `a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb` |
| `app/src/main/cpp/CMakeLists.txt` | P01/P02/P05/P06 | asset / configuration / specification | `a64569bfb3b32204edf15c34144ab3fd028bfdb658a53d36fb4da85b81bb776d` |
| `app/src/main/cpp/dsd/dsd_decimator.cpp` | P01/P02/P05/P06 | implementation / contract | `b4d60b6396228d43a2ff39928f8b0c184e6486f1ab4068d48cb7d43571eec64d` |
| `app/src/main/cpp/dsd/dst/COPYING.LGPL-2.1` | P01/P02/P05/P06 | asset / configuration / specification: definition | `20c17d8b8c48a600800dfd14f95d5cb9ff47066a9641ddeab48dc54aec96e331` |
| `app/src/main/cpp/dsd/dst/NOTICE.md` | P01/P02/P05/P06 | asset / configuration / specification | `eeded6d9ef2cb8ef081b2fedd15410eaf57205251cdaa010e3f9466c00a4e9ca` |
| `app/src/main/cpp/dsd/dst/compat.h` | P01/P02/P05/P06 | implementation / contract | `e3a3240f6adff62d2ded6061436e183a570a434fa0ee32f07e8d17cd3af08dd6` |
| `app/src/main/cpp/dsd/dst/decoder.c` | P01/P02/P05/P06 | asset / configuration / specification | `fdf2e4f03ce32fe504bcfc86830e751baab097350cde63469db278c7cbcf057b` |
| `app/src/main/cpp/dsd/dst/decoder.h` | P01/P02/P05/P06 | implementation / contract | `4f29a5c4f2c6557061f61aeac58a074333e4cc57f77eceb672cb69176b43801c` |
| `app/src/main/cpp/dsd/dst/jni.cpp` | P01/P02/P05/P06 | implementation / contract | `540806f7437b69c5883d87a5cb7e30cd08d1aabcd1b47b02847c58b686d86bf0` |
| `app/src/main/cpp/jni/analysis_jni.cpp` | P15/P16 | implementation / contract | `3c23e4cf9505d814c091ffbd36c3cf1d42f39cc70fc42da9840439b580136c27` |
| `app/src/main/cpp/jni/mel_jni.cpp` | P15/P16 | implementation / contract | `98b6af336d151ccb919dd0923ddefbc749ec616b164f73b7398271f4a3b45a44` |
| `app/src/main/cpp/jni/vocal_jni.cpp` | P15/P16 | implementation / contract | `f4b896e4a644659239086dce95ab5b93f4d17796a897fc3a56e7c1e8e307006b` |
| `backend/.gitignore` | P34 | asset / configuration / specification | `dd854e080cabbbbf611461be5201faf9e9e35e899cf6206bfd605ee5f0458b2c` |
| `backend/README.md` | P34 | asset / configuration / specification | `149bbe243370a1bcd8d4ea6c65410b5f5f6b36ab4204a1ba87aa818ad00b2c69` |
| `backend/clock/clock.go` | P34 | implementation / contract | `6ff05a90dba4b659574787423c2be4091b57bbd5d69c46c6dfe9dbca31ee4bb3` |
| `backend/codes/codes.go` | P34 | implementation / contract | `50dcc6631e6fa3c86162f755ad7cb79eb1fc9de78c0c2e3fcb22cf1150bb4b6d` |
| `backend/codes/codes_test.go` | P34 | reference test | `e9ee4b33b02d5b7a398256d3a0874e1eca6359e805048354aaacd13380eb52a1` |
| `backend/config/config.go` | P34 | implementation / contract | `5f3e8c583a43f99e59b1b6e8e31a6b64c9fcf775b761097937aa8888b461f8a7` |
| `backend/config/config_test.go` | P34 | reference test | `c4387effccd59dcf12f35cf1775843276dc248a40886f8fb34af42b32d553448` |
| `backend/deploy/.gitattributes` | P34 | asset / configuration / specification | `a79691a93b46e49ce460c26ef22afcc03d6eca1e63bf2edbc20e96159510f6c9` |
| `backend/deploy/Caddyfile` | P34 | asset / configuration / specification | `6833e1cfac64cb0024afd5b042ad1c8f7dc204332c4d1a4e90becd80b0cba13f` |
| `backend/deploy/bitchord-deploy.sh` | P34 | implementation / contract | `2760ef7373a57c97bc24faca4a123e19f5f4256df307f24cc50c2c21bd3d700a` |
| `backend/deploy/bitchord-jam.service` | P34 | asset / configuration / specification | `ecaa6f4dee946338fe90b897ec1563b64b42d70182540037da504fd51b3d9749` |
| `backend/deploy/bitchord-keepalive.service` | P34 | asset / configuration / specification | `a81974bd52b23aa52dd9496d08e359694460aa27fe55c4edc8a1862a42bd5bfd` |
| `backend/deploy/setup.sh` | P34 | implementation / contract | `b04b5829a77b8b8119de8cfcecb891a38cc2d419f14594b66a96bebe8855701d` |
| `backend/go.mod` | P34 | asset / configuration / specification | `a6866986960124c23b7620ff0421631bfc9a4ba164c718b884c065197452b17f` |
| `backend/go.sum` | P34 | asset / configuration / specification | `1737d5523b7264df09b253e86e29f96af295a7bd18e0aaa4373c6099317e065f` |
| `backend/hub/hub.go` | P34 | implementation / contract | `b40b0ffa5d4718a4a8182fdd8dfc0e28289a79b0b9f9ea5b7e7a6b2cb11fc8b8` |
| `backend/main.go` | P34 | implementation / contract | `2bfaa164220e489d96c787f396d9d1c1fa5fdb250ca11d1c6d8ab2981e2d02e2` |
| `backend/main_test.go` | P34 | reference test | `4874aa172e199dd587ac88d8e8f998d3ad5f8143e502eac879e775d58aa4e14a` |
| `backend/party/party.go` | P34 | implementation / contract | `d9dbdaf2fa3b3d831bc71420694f071d3211f646b9773e1aed25ca8adbe24762` |
| `backend/party/party_test.go` | P34 | reference test | `a734484f5101e4885ca25451cc5fde8aa0c85274b56d935c33c2b48789d229ab` |
| `backend/presence/presence.go` | P34 | implementation / contract | `79a9ffa2b4582ca6544c72c00652f3e971d93f1588c9b8f2ddef91b779d29de5` |
| `backend/presence/presence_test.go` | P34 | reference test | `489955b1d6946db0ec8821ee974786e432d55a61f1836df2da0653f05e898b37` |
| `backend/protocol/protocol.go` | P34 | implementation / contract | `5f6022b5d284d49c7392c7018c92a141d7d7946438803054bd48ced3be8bcb1c` |
| `backend/protocol/protocol_test.go` | P34 | reference test | `91b020943803b7bbd91338139f014718e507cadd0756c7892fbdd7fd042d2521` |
| `backend/render.yaml` | P34 | asset / configuration / specification | `5f94f18e54b96404c0ff5d7d970db8e8bc27363761b54d63f450c7145f0894a7` |
| `backend/tools/jam_probe.py` | P34 | implementation / contract: Member | `ae0f1a29aa8b81c4943e49976520cc3ed6544ac3a39831fe9b6c8daba5e6cc4b` |
| `build.gradle.kts` | P01/P02/P05/P06 | implementation / contract | `de08f1ec3c653225efead7f69ebdca6c491ebd92117cec1c5c5f35eafead9ce1` |
| `desktopApp/PLAYER_TRANSPORT_ICONS.md` | P01/P02/P05/P06 | asset / configuration / specification | `da180277411b4797dbabf8a16f77af1a3ac77405d5d9a4fea382e67c9a1bad2a` |
| `desktopApp/build.gradle.kts` | P01/P02/P05/P06 | implementation / contract: localProperty, runs, findOnPath, cmakeBinary | `0f7bd280598bde8f4954792b5b4a5ce9cae622c903cf921169f9a1393c4eebce` |
| `desktopApp/native/CMakeLists.txt` | P01/P02/P05/P06 | asset / configuration / specification | `fc320c4e492c84305d7cdf8deae28a252a04efead0ae7485c1819a40755d34ed` |
| `desktopApp/native/loopmix_jni.cpp` | P15/P16 | implementation / contract | `73f64cb0147d7076ef86c3f835ddb24988b41dfbe4b2970c87c63db7e165fa5e` |
| `desktopApp/native/mingw-w64.cmake` | P01/P02/P05/P06 | asset / configuration / specification | `3eaebac50baf7133887c4b7ebc673363e5c367445c6577a80c0d658b45812744` |
| `desktopApp/native/smtc/smtc_abi.cpp` | P01/P02/P05/P06 | implementation / contract: Hstring, ButtonPressed | `f80c27b27f2396cc79836e98eadf86339a2128acbab402dcc313d30d1727155c` |
| `desktopApp/native/smtc/smtc_jni.cpp` | P01/P02/P05/P06 | implementation / contract | `a1318e2b63ed6d1463b61b4fc58c96923f9ba18b9244a7d34096fb1cb80c195e` |
| `desktopApp/native/win32/jni_md.h` | P01/P02/P05/P06 | implementation / contract | `3cacac1e4802ec246ea7c0c6772d4ac40c9f7255d4df095cfffe601137689771` |
| `desktopApp/native/window/CMakeLists.txt` | P01/P02/P05/P06 | asset / configuration / specification | `6b5faa70bd67aeb7d0fdcfdd1e3c8df3905a24a4f342f8257dd25ae7993b0703` |
| `desktopApp/native/window/window_jni.cpp` | P01/P02/P05/P06 | implementation / contract: ComScope, EndpointWatcher | `927d673bcafa5fe595c15beb4d0884509c830df35e61f75998e6c4b220ed4a5f` |
| `desktopApp/packaging/appimage.sh` | P37 | implementation / contract | `a5be77d10bffad407c24cb07e085326a0b4e0eca2277351a4c9ec222685897c0` |
| `desktopApp/packaging/icons/AppIcon.ico` | P37 | asset / configuration / specification | `8e9cea81b74172c46300d9ed176eb498d7eb1974cfcd01474c0099e79d80c4bf` |
| `desktopApp/packaging/icons/AppIcon.png` | P37 | asset / configuration / specification | `3057d3d2bc3e084f38e36904471319199df9f72b64bd9a279398cb1c284a3e00` |
| `desktopApp/packaging/icons/README.md` | P37 | asset / configuration / specification | `656116167995b373fc4a9e99881df16f5869d3338e914d7f62d44d6841544acc` |
| `desktopApp/packaging/proguard-rules.pro` | P37 | asset / configuration / specification | `271e8d8f0e2d830cf82b4bd603cc92220055612fa35fd345d3f9cb28d8ed45de` |
| `desktopApp/packaging/tests/SeedUpdaterProfile.java` | P37 | reference test: SeedUpdaterProfile | `a66d96537ba76b04997e2a137e194abcaf1990c171e4d8d52263dccfd0f7bad9` |
| `desktopApp/packaging/tests/appimage-relaunch.py` | P37 | reference test: Attributes | `c1a9d540774cbf2bdfccc59d70a20112d529d4f932cb74e40ad6a9abb94fd607` |
| `desktopApp/packaging/tests/fixtures/README.md` | P37 | asset / configuration / specification | `d13cd43eda67462e9f88c666a46b3e8afba81d9c62a29efcad387f521f5465ce` |
| `desktopApp/packaging/tests/fixtures/appimage-update-7eaa.sh` | P37 | reference test | `026f263f41bd819894589b518c6453a0bf822b83f8c7b160922bf47288ca65d3` |
| `desktopApp/packaging/tests/linux-automix-check.sh` | P37 | reference test | `51f088a8a2455c839ee3dbd276a4f36c31e840be7cebb3a1fda0a1f488749917` |
| `desktopApp/packaging/tests/linux-updater-checks.py` | P37 | reference test | `0247f373e960d95941cf43e693993b8da4490e0dc487d0a8147d962195a653d9` |
| `desktopApp/packaging/tests/native/TrackFeatures.java` | P37 | reference test: TrackFeatures | `c4ae0c446b850e9a666d6be9477c98fc5af546056bd9877b54034541b4d1d435` |
| `desktopApp/packaging/tests/windows-automix-check.ps1` | P37 | reference test | `ff303f1af67237bd723522218a1d30190401dda5da400490e98521945185e518` |
| `desktopApp/packaging/tests/windows-portable-inplace-checks.ps1` | P37 | reference test | `eab3b01835f7a05e9fc656e9f4735f535dbf72ea40708fbbb918d3041d5d1e24` |
| `desktopApp/packaging/tests/windows-real-upgrade.ps1` | P37 | reference test | `753c65b8c3fe461463b31a7a69d81ab4e47728ced1a03de4e710b37dba59be51` |
| `desktopApp/packaging/tests/windows-updater-checks.ps1` | P37 | reference test: Fixture | `ce0f9a7ff9654181733c978a573b4b0ac6e041fb6b256def05fb96fe3ff633c8` |
| `desktopApp/packaging/tests/windows-updater-host-checks.ps1` | P37 | reference test: Fixture | `6e66a09296d6ebfa9cd645d65f23912ddaf6dc002b8b630349f5cd788ea7f5f5` |
| `desktopApp/packaging/tests/windows-updater-window-checks.ps1` | P37 | reference test | `b04946ac26f0951ba98934e02aa8464636852187a6da6607437c80bb957a18f9` |
| `desktopApp/packaging/windows/BitChordSetup.cs` | P37 | asset / configuration / specification | `90a4cf029e9bc96d61b93eaed8ee2d367343b54d8883ae83d1e8642ca0f65460` |
| `desktopApp/packaging/windows/create-setup.ps1` | P37 | implementation / contract | `795466bea722c2ad4959397b643c834a5e93fac4bab7954c52c56204439f392b` |
| `desktopApp/packaging/windows/recover-legacy-msi.ps1` | P37 | implementation / contract | `2ad96a7d43f8172ffbe47455dab433b73ba6bdd5e5e0d4e1f7dbe66e0688b819` |
| `desktopApp/src/main/composeResources/drawable/app_icon.png` | P29–P31 | asset / configuration / specification | `3057d3d2bc3e084f38e36904471319199df9f72b64bd9a279398cb1c284a3e00` |
| `desktopApp/src/main/composeResources/drawable/app_icon_dark.svg` | P29–P31 | asset / configuration / specification | `48f61ab8dd1da957f1bec28698b3e09c6ded68770fbfa320fddb6f0c602710ab` |
| `desktopApp/src/main/composeResources/drawable/app_icon_light.png` | P29–P31 | asset / configuration / specification | `846ea2f84fc45fd4f78806f5395f881e1c47677754a4d856599e696c0bec4395` |
| `desktopApp/src/main/composeResources/drawable/app_icon_light_source.svg` | P29–P31 | asset / configuration / specification | `4af5ded5369cc299cce2507dfcf714a71f01b9fcbf510426a7446c16a36ef7c4` |
| `desktopApp/src/main/composeResources/drawable/bitchord_logo_original.svg` | P29–P31 | asset / configuration / specification | `3c7c14ae5903eb9eeebb5ef54ccd3fd853fa512ac20f62a957c0b818d1e80102` |
| `desktopApp/src/main/composeResources/drawable/bitchord_no_music.svg` | P29–P31 | asset / configuration / specification | `86996d56e8f656d31c9ebf4117d0b62d0817099d4f14c19a7c84134964d9ad09` |
| `desktopApp/src/main/composeResources/drawable/bitchord_wordmark.svg` | P29–P31 | asset / configuration / specification | `c5bdee5d2b1dd7ed9a03c93183568ac06849779ddc959ad4fde119d842a1876a` |
| `desktopApp/src/main/composeResources/drawable/logo.svg` | P29–P31 | asset / configuration / specification | `818977d31d7442f2e3feac3f0aad82f057c61adb5cd7766ac05e53fe89114ea5` |
| `desktopApp/src/main/composeResources/drawable/logo_mark.svg` | P29–P31 | asset / configuration / specification | `b4f604535aeb8ad8a87a70eee74037e1a5b9db428c292ec5dc0703842e547642` |
| `desktopApp/src/main/composeResources/drawable/player_transport_lyrics.svg` | P22/P23 | asset / configuration / specification | `d38da6c927d130dadee90d19e54fed343939ac01c3256b872677d0b8a8c49003` |
| `desktopApp/src/main/composeResources/drawable/player_transport_next.svg` | P29–P31 | asset / configuration / specification | `6633d2551a9ff62f772f7c149f5a00f3e93e3755f8251f1f544871e2ba3173c0` |
| `desktopApp/src/main/composeResources/drawable/player_transport_pause.svg` | P29–P31 | asset / configuration / specification | `bce242f9e30fd678e093c8a982950127d697fdd0ccc1153dfbe3a29b38d680bd` |
| `desktopApp/src/main/composeResources/drawable/player_transport_play.svg` | P29–P31 | asset / configuration / specification | `c4d22be6c63eafa932e17b095e6dd56181a512b63f99af810428926a81328e94` |
| `desktopApp/src/main/composeResources/drawable/player_transport_previous.svg` | P29–P31 | asset / configuration / specification | `d5934e8492b3fd499b182391e0c52b749278a62ab648447cc5655f3bccacb049` |
| `desktopApp/src/main/composeResources/drawable/player_transport_repeat.svg` | P29–P31 | asset / configuration / specification | `d4244b58be59c9f85a1ca1175c2c36566125c5151c34a518117464f538b381c8` |
| `desktopApp/src/main/composeResources/drawable/player_transport_repeat_one.svg` | P29–P31 | asset / configuration / specification | `18af9d9d1ae2687406044f9fddc1d1459157911e46e9fe7fa35cfac42c272bdc` |
| `desktopApp/src/main/composeResources/drawable/player_transport_shuffle.svg` | P29–P31 | asset / configuration / specification | `5df5237121422113554c7ac03efb37a2d756afc16a9bcfd98f31a02635378ab6` |
| `desktopApp/src/main/composeResources/drawable/player_transport_up_next.svg` | P29–P31 | asset / configuration / specification | `6972559ebbd7d51f66456ddca94db7d8c351549069942b9ac20b844e6ecb1626` |
| `desktopApp/src/main/composeResources/font/bintang_music_black_about.ttf` | P29–P31 | asset / configuration / specification | `dbc7b5014205d52e6f66b321b2334d12f0a191a9399f2a1e5a07a281022f7a21` |
| `desktopApp/src/main/composeResources/font/sf_pro_display_bold.otf` | P29–P31 | asset / configuration / specification | `9661ed5e91885b6a80cd458d5ef068db320574c2cb7c1ed2d77864fb91cb8adc` |
| `desktopApp/src/main/composeResources/font/sf_pro_display_heavy.otf` | P29–P31 | asset / configuration / specification | `68020e8fbddc00c16ae939f337d65459632a1359f7b51656e14bf0d7140629d7` |
| `desktopApp/src/main/composeResources/font/sf_pro_display_medium.otf` | P29–P31 | asset / configuration / specification | `05865eda9d9a6880538034210becfcf21622fc1a0b30723acdb670009ddbf51c` |
| `desktopApp/src/main/composeResources/font/sf_pro_display_regular.otf` | P29–P31 | asset / configuration / specification | `7dc04ac11000f759c973a45c6109e2c164372db0db23dd4796c887df73048cd0` |
| `desktopApp/src/main/composeResources/font/sf_pro_display_semibold.otf` | P29–P31 | asset / configuration / specification | `6c4fa1be203ec1b7a5135cf636d87e1e23a91a9aabd556309664251745fd8249` |
| `desktopApp/src/main/java/com/music/bitchord/desktop/secrets/SecretServiceApi.java` | P20/P21 | implementation / contract: SecretServiceApi | `a0b7d754293bfa290fd2a6d13743591b4a9ed50b95bcafb1b013e6b6b8055449` |
| `desktopApp/src/main/java/com/music/bitchord/desktop/tray/DBusMenu.java` | P01/P02/P05/P06 | implementation / contract: DBusMenu | `5cf5ffebd0ddc3da00b64418d6f15818337bdeab5f85fdda8085bd2c4b22e970` |
| `desktopApp/src/main/java/com/music/bitchord/desktop/tray/DBusMenuEvent.java` | P01/P02/P05/P06 | implementation / contract: DBusMenuEvent | `2fb4ec006e0a5f38d0ce7f5adf8737e6307b6b18cad948211219096a15a254d5` |
| `desktopApp/src/main/java/com/music/bitchord/desktop/tray/DBusMenuItem.java` | P01/P02/P05/P06 | implementation / contract: DBusMenuItem | `b64d6a6ebd12f0011010a9373f333d4ecfa3495c0c3e96174b49c1bcbf36c500` |
| `desktopApp/src/main/java/com/music/bitchord/desktop/tray/DBusMenuLayout.java` | P01/P02/P05/P06 | implementation / contract: DBusMenuLayout | `428d6bd59f39a02930d0d48dcad02dabf0c29925d6b62a386fffab33eee74030` |
| `desktopApp/src/main/java/com/music/bitchord/desktop/tray/DBusMenuProperties.java` | P01/P02/P05/P06 | implementation / contract: DBusMenuProperties | `950cca63da88334c46d8c7f8693c43a4ca6a1459368927c81f692b6ee78ca304` |
| `desktopApp/src/main/java/com/music/bitchord/desktop/tray/DBusMenuShowGroup.java` | P01/P02/P05/P06 | implementation / contract: DBusMenuShowGroup | `18a9e0fa8cc1a6e3299f40d3127210e5f3e32e6045f993644e4ee71ba95a73e3` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAboutVersionTitle.kt` | P38 | implementation / contract: DesktopAboutVersionTitle | `f2968c244210841866816bd2481363978204133ea251cb8ddb2656f4d2566af1` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAccountUi.kt` | P20/P21 | implementation / contract: DesktopAccountButton, DesktopAccountSelector, DesktopProfileRow, DesktopSelectorAction, DesktopSignInDialog | `a0300c30dcaf0a4ec9c6ad17b032c8c9003fcb329af5321ce9abee4803eac98d` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAccounts.kt` | P20/P21 | implementation / contract: DesktopYouTubeProfile, DesktopGoogleAccount, DesktopAccounts, DesktopAccountParser | `a54b713157d8bb3ed64a44f73c643aa1b36e464d76ab3373fbf916674be028c3` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAddon.kt` | P17–P19 | implementation / contract: DesktopAddonManifest, DesktopAddonSetting, DesktopAddonSettingOption, DesktopAddonSearchResponse, DesktopAddonTrack, DesktopAddonStream, DesktopAddonSettings, JsonElement, DesktopA | `829d4a5b0cfd520cbd027ad7fd93803b2a7b72307237918d362f98f21e99ce9a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAnalysisRuntime.kt` | P15/P16 | implementation / contract: DesktopAnalysisRuntime | `9d1a417c5bba6bc738ebb714ba9b59930559f91de253f10ece10db50b4c464a4` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt` | P01/P02/P05/P06 | implementation / contract: desktopChipColors, desktopSwitchColors, desktopColorScheme, desktopTypography, DesktopDestination, DesktopRepeatMode, BitChordDesktopApp, DesktopTopBar, DesktopToolbarButton, Deskt | `ca0c4125bfc91dad0161ade6a5f2db5d8b74abba2ada26ece8a93eba37d96e8b` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAppIcon.kt` | P29–P31 | implementation / contract: DesktopAppIcon, DesktopAppIconSettings | `b38a77957163e359c03a52f329ee738638da94b717e3e881280cc10e4d6056d7` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAppIconPicker.kt` | P29–P31 | implementation / contract: DesktopAppIconPicker | `ceccecc870639f033bc0e16a2cf989d276f1acf12d95074198d264ad5f77d9bd` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAppearanceSettings.kt` | P29–P31 | implementation / contract: DesktopBlurArea, DesktopPerformanceProfile, detectHardwareProfile, effectiveDesktopBlur, DesktopAppearanceSettings | `9770e3585dd2b27fde8e7924604a0e34e5c02e1913d87e390eee112d0e668fe1` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAppleArtistArt.kt` | P25/P26 | implementation / contract: DesktopAppleArtistArt, DesktopAppleArtistArtRepository, canonicalAppleArtistName | `8e2394314ac8ec675cb8c764e2721d111fbdb0d4154f3a54ddb2bfb391ce8bcd` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAppleArtistUi.kt` | P25/P26 | implementation / contract: appleArtistWash, artistHeaderImage, DesktopAppleArtistHeader, DesktopAppleArtistButton, DesktopAppleHeading, DesktopAppleSongRow, AppleSongLine, DesktopAppleSongGrid, AppleSongGrid | `25aea4e5a81e520db642152ee0cffa82347ee48ce3dd6a75b7fae795ace031eb` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopArtistBannerArtwork.kt` | P01/P02/P05/P06 | implementation / contract: DesktopAppleArtistHero, DesktopAppleArtistLogo | `00753361c5795a121a0f956d210e7fde7d507414091e8b53d43864f709369b51` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopArtistSignatures.kt` | P25/P26 | implementation / contract: DesktopSignatureSettings, signatureEndpoint, signatureRequestUrl, DesktopArtistSignatures, pickSignatureUrl, wikimediaSvgRaster, JsonObject | `1879f84ba664dfd700efda22de4a9bbe4f784ae3de8c33ce3644abd6bf2621d2` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopArtwork.kt` | P01/P02/P05/P06 | implementation / contract: DesktopArtworkCache, DesktopArtwork | `e9f99ad1b74ee1970e889f3b0484fb7e9db5041d96b48a7501bbcdacbdc42176` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopArtworkMesh.kt` | P01/P02/P05/P06 | implementation / contract: DesktopArtworkMesh | `9c2d92c2f884df61859fae9c958f390734e2606c2928a9fbfa1bcc6e21baaefd` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopArtworkPalette.kt` | P01/P02/P05/P06 | implementation / contract: DesktopArtworkPalette, rememberDesktopArtworkPalette | `afcf988b5e5202ec573c2618df596d30f5b0c3d901cca8dd605b6c16df02e289` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioCache.kt` | P11–P14 | implementation / contract: DesktopAudioCache | `1badcae15d28f37bb190e568cf9d1d40852e8080d52866dde6558f80940919eb` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioDecoder.kt` | P11–P14 | implementation / contract: DesktopPcmFormat, DesktopAudioDecoder | `4a6a1c85a83fe5345e5d2134c18726b28c1957740fa40dece824d6cb0765cffa` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioDevices.kt` | P11–P14 | implementation / contract: DesktopAudioDevice, DesktopAudioDevices | `bed8bb857c307ac2035e8ae20bc7042f311a7e64dd1bbdcba7aa4c50202cf75e` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt` | P11–P14 | implementation / contract: blendElapsedUs, DesktopPlaybackEngine | `6b0848e7e7fe909c0a56d2197ad813a96a4b8eb3958d603c60f4f54efbfee7a9` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioOutputUi.kt` | P11–P14 | implementation / contract: DesktopAudioOutputDialog, OutputRow | `6c4a457d39786d65d559e55b49168a6bf33c218d5a0181c6f729d1a624a43de0` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioPipeline.kt` | P11–P14 | implementation / contract: DesktopAudioPipeline | `2eec20c26040089625e73a703727ccca110630e58cd32fe6c3b062d9e56c9c68` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioPipelineUi.kt` | P11–P14 | implementation / contract: DesktopAudioPipelineDialog, PipelineStage, PipelineRow, channelLabel | `0d46a23704c5915128d8eed6e751bbfa9fd4332ef4e206ee040255a8fd9f7253` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioProcessors.kt` | P11–P14 | implementation / contract: DesktopSpatialAudio, DesktopSilenceSkipper | `c6c6dd62664f1a9c7ba3d7834595fdd1f9f01ff1057b114ac66ab4ca7b742a57` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioSink.kt` | P11–P14 | implementation / contract: DesktopAudioOutput, DesktopAudioSink, DesktopPcmFormat | `417f69ebd2de7b43f161098d1ddb968275f84fa1e6b3fc72ef71eb916637a73d` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioSpeed.kt` | P11–P14 | implementation / contract: DesktopAudioSpeed | `06cb4d3255b6d3598b9f761593f8c48d94a3cf6ed2ec11d5334edd662b9bf31c` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAutomixDuration.kt` | P15/P16 | implementation / contract: DesktopAutomixDuration | `8a76af5a02c544c465d3ff38817ee28f548f47c394437f38fb0719a56c933017` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAutomixWorkQueue.kt` | P15/P16 | implementation / contract: DesktopAutomixWorkQueue | `e961e6b5c0190c0407d098f0e1570183bad1980fd569cbb245046a528cdb0090` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAutoplay.kt` | P10 | implementation / contract: DesktopAutoplay | `c923be35997552ca97e908caaf06689272e36d068f342f212c36db12e3077c41` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAwtTray.kt` | P32/P33 | implementation / contract: DesktopAwtTray | `888a60f26b57fd61515795cd663bddea7c54416eeb50d01c4fee2c8eae88d008` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopBlurCustomization.kt` | P29–P31 | implementation / contract: DesktopUnifiedGraphicsAndBlurSettings, DesktopBlurCustomization | `81e616a2ac7089c190fe6b1a4ce93f764f44b7c9a167de841a80675f45477d98` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopBrowserCookies.kt` | P20/P21 | implementation / contract: DesktopBrowserCookies | `e22a069d188620669b819579bb325dc35a6555753ec48828a19b97857d403926` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopBrowserSignIn.kt` | P20/P21 | implementation / contract: DesktopBrowserSignIn | `92ef1d258560f9c92bddac1bc1a40d519b214bceef56c146771017e28a2b2baf` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopByteBudgetLru.kt` | P35/P36 | implementation / contract: DesktopByteBudgetLru | `bb208203384a2e04a17620c561b739a342fcffbd7821e45bc21001f1816cd026` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCanvas.kt` | P25/P26 | implementation / contract: DesktopCanvasArtwork, DesktopCanvasClient, String, isManifest, DesktopCanvasCache, DesktopCanvasView | `55103a861e9b83d10d039fd585e5b1adf52557fc8b8f725f959f23bd4a697882` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCanvasBackdrop.kt` | P25/P26 | implementation / contract: DesktopCanvasBackdrop | `0ed74d497f15ec1902faa7f4828e6d4981155b24263c261e899ae817ee83f03e` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCanvasDecoder.kt` | P25/P26 | implementation / contract: DesktopCanvasDecoder | `f41a2835767ddd682cc648129f65c403f261a9617d9cfdba1173772db9ec9a7c` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCanvasSources.kt` | P17–P19 | implementation / contract: DesktopCanvasSource, String, splitCanvasArtists, canvasMatches, canvasGet, query, parse, JsonObject, DesktopAppleMusicCanvas, DesktopTidalCanvas, DesktopCommunityCanvas | `b04b649511578233b200351f5d9fbdf40d13ab9f542f1108b030163137e01988` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCanvasVideo.kt` | P25/P26 | implementation / contract: DesktopCanvasVideo, DesktopCanvasArtwork | `f7a19720179e782f9e7ca7dce51d1ac7dcbea7184d30e6635dd5b260039c232f` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCodecs.kt` | P11–P14 | implementation / contract: DesktopCodecs | `9f6c6bfdc62b12f313f17b0923f668b0cf476a53714b3745ee6029d3247ec77d` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopContentPreferences.kt` | P35/P36 | implementation / contract: DesktopContentPreferences, DesktopContentPreferencesPage, PreferenceSeparator, PreferenceOption | `abbec9a19f429bec74372dda949c9812748a07a78cc8395b24327727d18997ca` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopContinuedArtistDecoder.kt` | P25/P26 | implementation / contract: DesktopContinuedArtistDecoder, selectContinuedArtistVariant | `7bcd05695b3b362f602b57b4b37930342a646d8fdaad6f0f6785939c9b1d1e8e` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopContinuedArtistMotion.kt` | P25/P26 | implementation / contract: DesktopContinuedArtistMotionView | `8655354c1168cb0bac18d8a7f86490ecbe5bd8117a0542bc1cbdd8cdb421a565` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDecodeAhead.kt` | P11–P14 | implementation / contract: DesktopSampleSource, DesktopDecodeState, DesktopDecodeAhead | `b9a356347b580c62e097fc3d8ed70191419e6c0a68acdafd2ea34c1251101def` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDirectSource.kt` | P17–P19 | implementation / contract: DesktopDirectSource | `e4f6d1bd7db48d435a12dd6a03814d6fc1a10d97a576fbea650367ea871497dc` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDiscordIpc.kt` | P32/P33 | implementation / contract: DesktopDiscordIpc, discordFrame | `2719c847fe2ea1f217cc7682caff95990b0c5b9d851ab977a43430d65aa4c36e` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDiscordRpc.kt` | P32/P33 | implementation / contract: DesktopDiscordRpc | `3e9ad54673e97a3140163f4785bb5f696edc1b1d66cbf7cd94e0977add5fa200` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDiscordSettings.kt` | P32/P33 | implementation / contract: DesktopDiscordSettings | `8f96e2e5f74cabfc96441a7c80fe01771fbbf4bc6947cabd7b50a6056c0a6ec6` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDownloadManager.kt` | P07/P08 | implementation / contract: DesktopDownloadManager | `07cb8aee44289905c011a5e09e5e2a6c67ca48cfca7aca3ef62650680c14734e` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDownloadManagerUi.kt` | P07/P08 | implementation / contract: DesktopDownloadManagerDialog, DownloadRow, summary | `eb9177c49824618ffe8b2616892a7ddf740fa99554929da558ea378f34039d13` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDownloadQueue.kt` | P07/P08 | implementation / contract: DesktopDownloadState, DesktopDownloadItem, DesktopDownloadQueue | `05d998bd1bbed4aec4328111185298edcd13fafa0864d1f2fe037ee9485b996a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEmbeddedLyrics.kt` | P22/P23 | implementation / contract: DesktopEmbeddedLyrics | `6629d58fa7a6b32fdbcaff24f1fa30795bbecb5a49d024f576c1c14feaf930db` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEqualizer.kt` | P11–P14 | implementation / contract: DesktopEqualizer | `6309195c6b3460bd4fb8cc44996904a6b9573db085cfff42984184cc16564abe` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEqualizerSettings.kt` | P11–P14 | implementation / contract: DesktopEqualizerMode, DesktopEqualizerSettings | `8a677c3f59192ff8b859373d63da7913f392d5db545de57c2f5018bcd24dadf7` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEqualizerUi.kt` | P11–P14 | implementation / contract: DesktopEqualizerDialog, ModeTabs, SectionLabel, DynamicTab, Readout, WidthChoice, TonePad, stepAt, ManualTab, BandSliders, BandFader, PresetList, BalanceControl, snapGain, formatGa | `3a01c6cf77e1d8d3a182ede1fa10b350d7737a211b3a40ed982218760bff51e1` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopExternalLinks.kt` | P01/P02/P05/P06 | implementation / contract: DesktopExternalLinks | `6c617d5946fd9ecd59aae8d334fd89dcc218d8b02d2bf8a4b3d970d30280f4ef` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopFlyoutHost.kt` | P01/P02/P05/P06 | implementation / contract: DesktopFlyoutHost, DesktopRegisterFlyout | `48261fda1aecff1d4329c494d7099ad7494a2056874592f587201064437b3239` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopFlyoutState.kt` | P01/P02/P05/P06 | implementation / contract: DesktopFlyoutLayer, DesktopFlyoutSnapshot, DesktopFlyoutState | `0311676c5503652102611b39038e6589f6703a71e05a5c1e0ae3974fd28e8563` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopFontMetadata.kt` | P29–P31 | implementation / contract: fontBytesWithWeight | `51d979855a44f036bb5b366e32b806442fdd5a30d6ecd6d89b410441df63775a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopFontSettings.kt` | P29–P31 | implementation / contract: DesktopFontMode, DesktopFontSettings | `752e1b8c7853ad983b8ba9c54d105ae55df1177b9cfac67b121259f63213cedc` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHorizontalScroll.kt` | P01/P02/P05/P06 | implementation / contract: DesktopHorizontalScroll | `497451f2082f4fbf4de0be5d97691b8922217d770fc2b36eb9a5bf3b4f2e6413` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHover.kt` | P01/P02/P05/P06 | implementation / contract: Modifier | `dc3480277c85ce9c24dc34627c266207557f6640429cff11695d8a80d4234857` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopInnerTubeXStore.kt` | P01/P02/P05/P06 | implementation / contract: DesktopInnerTubeXStore | `0f268230da24b4d183f6af0e890380a0d1d1b9ffd5ed30480117f8786991db00` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopIntegrationsDialog.kt` | P01/P02/P05/P06 | implementation / contract: DesktopIntegrationsDialog, SpotifySection, DiscordSection, ScrobblingSection, RichPresencePreview, PreviewButton, DiscordChoice, DiscordField, PanelGroup, PanelSwitchRow, PanelValu | `8945022b6b7146e404509d95365f30878ccdc5a9c00d01595bfc8f8a915a3751` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopIntegrationsUi.kt` | P01/P02/P05/P06 | implementation / contract: DesktopLastfmLoginDialog, DesktopSpotifyCanvasDialog, DesktopListenBrainzTokenDialog, DialogHeading, DialogField, DialogActions, DesktopDiscordTokenDialog, DesktopTextValueDialog | `f89590b8921f87867e8a88add958e261cd065042d95b1a157398201d01ea033f` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopJioSaavn.kt` | P17–P19 | implementation / contract: DesktopSaavnSearchResponse, DesktopSaavnSong, DesktopSaavnMoreInfo, DesktopSaavnArtistMap, DesktopSaavnArtist, DesktopJioSaavn, DesktopMusicSources | `1e1a1ac7378f6035d48c88e5861575ae86169e9e71b0af95eedd05a16262c629` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopKeyboardShortcutsModal.kt` | P27/P28 | implementation / contract: DesktopKeyboardShortcutsModal, ShortcutKeycap | `44cc3046d394486da6985cd000e5fa2189bac9d67aba1312efda53de2cc36e9a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLibraryLoadPolicy.kt` | P01/P02/P05/P06 | implementation / contract: DesktopLibraryLoadPolicy | `62efd3b9fcc15ee196f97e42028e5be4a03ca806525346ffbbf1fbcdaaac9887` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLicenses.kt` | P38 | implementation / contract: DesktopLicense, DesktopLicenseGroup, DesktopLicenses | `9c00a8334cc8ae18abdc13136cbb4154f25a62c5efddbc160aa39dd28ce8c148` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLinuxAudioWatcher.kt` | P11–P14 | implementation / contract: DesktopLinuxAudioWatcher | `6a9f50a360162de3ce4d68df44fbac4faa594a8bcf832a27e1fd9697b2630421` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLinuxPulseAudio.kt` | P11–P14 | implementation / contract: DesktopLinuxPulseAudio | `2f106ef2a9b7d51ba6ba87f8b90834fa78cf430eab6d784844428e685dc72dc4` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopListenTogether.kt` | P34 | implementation / contract: DesktopListenTogether | `b469ac288cbce10fc8e7dec5dfa2071dcaa496cbae01f442f780e218214bd3ae` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopListenTogetherUi.kt` | P34 | implementation / contract: DesktopListenTogetherDialog, PartyLanding, PartyChoiceCard, JoinConfirmation, PartyRoom, RoomHero, NowPlayingCard, QueueCard, MembersCard, HostControls, ActivityCard, MemberRow, Pa | `267ddd795258b3989a38ce90c5e89d147c8fb556b37680bb33e7551227060f4b` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopListeningStats.kt` | P09 | implementation / contract: DesktopReplayEntry, DesktopRankedEntry, DesktopReplayPeriod, DesktopReplaySummary, DesktopListeningStats | `6b0f6495ea8041fad7c8048f6c21689722dd03ac10926f4d27aba2c6bd03b7c9` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLocalArtwork.kt` | P07/P08 | implementation / contract: DesktopLocalArtwork | `3c470b1838722e9d1a037458dba6750df734d860665782cbd60dc6d9ad247f0b` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLocalMusic.kt` | P07/P08 | implementation / contract: DesktopLocalMusic, DesktopLibrarySort, DesktopLibraryView, List | `ec51fef33a57a5c089d715ece8e1195009dd34b8e57fe79b985078272137986d` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLocalMusicWatcher.kt` | P07/P08 | implementation / contract: DesktopLocalMusicWatcher | `a9c93de5a81fdb1a2d30e9f65da061d298d254c2d25f8a4de39f9759795827e6` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLoopRegion.kt` | P15/P16 | implementation / contract: desktopLoopRegion | `4d9726e811e99df8da8fc68118dfd62c7a2f7bd217c1ca47f4fc13b1e8544d25` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLoopView.kt` | P15/P16 | implementation / contract: desktopLoopView | `ed7d0995ac8dbc37bf15a94544b53030d3e9b87a52cbd6f358133a40028de8d8` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLyricsClient.kt` | P22/P23 | implementation / contract: DesktopLyrics, DesktopLyricsClient | `cdfecd4e3316f8e66b0c32c42538d986f8fd2dd83e8491c307e40858c115ef21` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLyricsSession.kt` | P22/P23 | implementation / contract: DesktopLyricsRequest, DesktopLyricsSnapshot, DesktopLyricsSession | `684d9156ba9b20703833b4c7657bd4088da145bb97b79dce0ce147692ed53477` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLyricsSourcesUi.kt` | P22/P23 | implementation / contract: DesktopLyricsSourcesDialog, ReorderableSourceList, reorderTargetSlot, CheckableRow | `c87062c1095676496d683c84916b021c768cd938a7dffd66ac66cd79340a3ab2` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopManifestDownload.kt` | P07/P08 | implementation / contract: DesktopManifestDownload | `6906781b01bb039cb4b6a4ac507847687be2174c477024c5d6a770652644da7c` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMediaCache.kt` | P35/P36 | implementation / contract: DesktopMediaCache, formatCacheSize | `99b6f66d2f18f6f31a7257c3c782b94c8b5ed7538f99be84b5503ecfcd5d6909` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMemoryOptimizer.kt` | P35/P36 | implementation / contract: DesktopMemoryOptimizer | `6f3ed092e3d13d935e92506df1f0d19e6e8999d922dc84b7b3fbd78da051dd11` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMenuPortal.kt` | P01/P02/P05/P06 | implementation / contract: DesktopMenuPortalState, Modifier, DesktopMenuPortalEntry, DesktopMenuPortal | `cec29c1b252d0b09fc329ec3ca5e1a12c65cf5dbe6bdeb2a8eb5528ba1fed007` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMixWaveform.kt` | P01/P02/P05/P06 | implementation / contract: desktopMixWaveform | `0ea0964f04b591e239a0aa0df2ee3a11f43be4c52f6d4cca6421e20a4b014485` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopModules.kt` | P17–P19 | implementation / contract: DesktopSpineModule, DesktopModuleSearchResponse, DesktopModuleTrack, DesktopModuleStreamResponse, DesktopModuleStreamTrack, DesktopHttpResponse, DesktopJsJson, DesktopModuleHttp, D | `2fe6089e7c1154bef5977a6a37e01e19cb9250917326f38c0ddefe87ebdccd50` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMpris.kt` | P32/P33 | implementation / contract: MprisMetadataType, MprisRoot, MprisUriSchemesType, MprisMimeTypesType, MprisPlayer, DesktopMprisController | `19c5473ab06a60bff30bc9a8d5084a57a7094bc5200270a6b672a454e23e9e4d` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMusicVideo.kt` | P25/P26 | implementation / contract: DesktopVideoDecoder, DesktopVideoSession, DesktopMusicVideo, videoClockPosition, VideoFrame, DesktopMusicVideoSurface | `53ea8e676670768fca3a8ea3ad9f73143ea303e5ee9f51d5d6fe86cecf400149` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopNewPage.kt` | P01/P02/P05/P06 | implementation / contract: DesktopNewPage, NewSectionHeader, NewAlbumShelf, NewTrendingShelf, NewVideoShelf | `7a8eb0b126ae5e795d1f687a7bd36e1fb4be15eb4b994655c683d13c8c290ab2` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopNowPlayingDetails.kt` | P01/P02/P05/P06 | implementation / contract: DesktopNowPlayingDetailsPanel, detailsLyricWindow, LyricsPreviewCard, ArtistOverviewCard, CreditsPreviewCard, detailsCanvasCaptionInk, lyricsPreviewSurfaceColor, tintSurfaceFromArt | `af1cd4f817387eafc6f404137cb544f5ae12b0bd97499f8db9383375ecfd18f9` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopOriginalVersion.kt` | P38 | implementation / contract: DesktopOriginalVersion | `f1c6f708c6038b62fbd837d84352686423b982ed556f9e1ef7ae7230276223a4` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopOverlays.kt` | P01/P02/P05/P06 | implementation / contract: DesktopOverlays, DesktopSettingsPage | `aeed124abb346118070aff1c8fb9d9d839eea265ef117e05ef511ed06d6e2f1b` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPageMemoryPolicy.kt` | P35/P36 | implementation / contract: DesktopPageMemoryPolicy | `8777995da8430b8054e936cfd6b0dc533266ebe528b0ad0e8938bc318fe05fbf` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPageWithSidePanel.kt` | P01/P02/P05/P06 | implementation / contract: DesktopPageWithSidePanel | `9884012f35c33b6622f131ca744923093eb02909ae7cb2c403d0a3bb37664276` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPartySync.kt` | P34 | implementation / contract: DesktopPartySync, Song, PartyTrack | `ecd9cd28a2255f3c5ec6b2da20c299b89f771ab7092451e9635fdf94e9f0dfed` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPcmWrite.kt` | P11–P14 | implementation / contract: writePcmFully | `ccc92e0c0e50ddd033cd80a9bf41cd1075fd1c6ab02bd65dc8bf43b737f1a04a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPersistence.kt` | P35/P36 | implementation / contract: DesktopPersistence, DesktopPlaylist | `aec6bcb7b0b2e8a0bea453001687dc03b17d8e1d29e42b7a20c58eba6f2c005a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPhoneMenuIcons.kt` | P29–P31 | implementation / contract: DesktopPhoneMenuIcons | `fd545368dd14cfeb0f2ce66cbe45298a50a2bf7c01729bf5c86994f346bc838d` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPipController.kt` | P27/P28 | implementation / contract: DesktopPipController | `b1ecab493b8b44b1eb3026b369d8ef60b7268b7c6cbfb61f225f05516a4b0067` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPipGeometry.kt` | P27/P28 | implementation / contract: DesktopPipBounds, DesktopPipGeometry | `a47a9a6632aa91747b23686f1020b9ce914a4739ac93c9edbd4416478079bd1b` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPipHost.kt` | P27/P28 | implementation / contract: DesktopPipSession, DesktopPipHost, DesktopPipHostWindow | `4abf3f4d31e33fd8a3ee3270fb52317ff9a85b4b17d80d9330596dd08e73d1e5` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPipSettings.kt` | P27/P28 | implementation / contract: DesktopPipPreferences, DesktopPipSettings | `c27101f6ea23a7b479ed808ed2f0a21864feeea3432d47717f42595d70b9359c` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPipState.kt` | P27/P28 | implementation / contract: DesktopPipState, DesktopPipLayout, DesktopPipLyricSelection, desktopPipLyricSelection | `0ac5072905acacba737c53e3b2922285d51155b9f076de4e2ad6df13780db669` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPipUi.kt` | P27/P28 | implementation / contract: DesktopPipUi, rememberPipArtwork, PipBackground, PipCover, PipMetadata, PipAction, PipProgress, pipTime, PipLyrics, DesktopPipLauncher | `349830b66098035466bf964a1e9476e9d20ea45c5e605a0e960ef8c967bd1cc4` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPipWindow.kt` | P27/P28 | implementation / contract: DesktopPipWindow | `0daf96088d29f6c3eed81dd9eb97e244c5658190497bc07fd70c6b0e208ffcec` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlatform.kt` | P01/P02/P05/P06 | implementation / contract: DesktopPlatform | `0d2a9d0f081b5e1457266bcfad2969d5c5b20e0ef0d451d8b2b1ff08ae8fe26f` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayback.kt` | P24 | implementation / contract: DesktopPlaybackState, DesktopStream, DesktopStreamFormat, DesktopStreamClient | `67c0d6322c029e92852a908f9885eb9e545a19c3635c4ea9c04a241b0d56b516` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlaybackDiagnostics.kt` | P24 | implementation / contract: DesktopPlaybackDiagnostics, DesktopPlaybackTrace | `04a118775510fdaf0785271e2f227d8973df883198bee122481c90294ba879e4` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlaybackTracker.kt` | P09 | implementation / contract: DesktopPlaybackTracker | `fa3f1be0515c7b19a4f7c2b792e95085d8770947441ad71830dacbbd326897ce` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlaybackWrites.kt` | P35/P36 | implementation / contract: DesktopPlaybackWriter, DesktopPlaybackWrites | `892d0088f4069f978b69db265ec5ffe15f718f17faed29b49aad030654f5c00a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayerHost.kt` | P24 | implementation / contract: DesktopPlayerHost, DesktopPlayerSettings, kindOf, DesktopAudioPipeline, lyricsSourceNamed | `35b76a39129566e49ab08196b73e0c61292aec496720540f0b3dafc2fae1fd2a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayerSheet.kt` | P24 | implementation / contract: DesktopPlayerSheet | `a1dde4274fc2995cafdb3d806c5cff31597513a79daf0643873aeadb7c7131f3` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayerThemeSettings.kt` | P29–P31 | implementation / contract: restoreDesktopPlayerTheme, DesktopPlayerThemeSettings | `4d5eae1bc2acdfa1cf22b23f9c05373d4637da21f75d5a36ebbe1118dbaf7302` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayhead.kt` | P24 | implementation / contract: DesktopPlayhead | `6ad79918328b0a9cb66835444d24b1b7c388eabc1814f50cc7a0382c5729aabb` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlaylistUi.kt` | P01/P02/P05/P06 | implementation / contract: DesktopPlaylistDialog, NewPlaylistForm, DesktopRenamePlaylistDialog, DesktopDeletePlaylistDialog, DesktopDialogFrame, DialogHeading, DialogSectionHeading, DialogError, DialogAction | `9dc3f2268c437020adf885c9291093cc081f801d7643b4a1d14fb876fafb429a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPoTokenWebView.kt` | P20/P21 | implementation / contract: DesktopPoTokenWebView | `e74c5c3b45a38ba8c27a4740be38a82bca02b366efec9a1336fc469198c6ad4a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPreferenceChunks.kt` | P35/P36 | implementation / contract: DesktopPreferenceChunks | `c5783d8f237aa9eca35350b47d78444f8ebdc837193614087384147f660a95da` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPresence.kt` | P32/P33 | implementation / contract: DesktopPresence | `d67832fc61791dc3bcc89e75ff6b003a700dcd1385ae3f5af5d7147e4f4508ac` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPrimaryNavigationIcons.kt` | P29–P31 | implementation / contract: DesktopPrimaryNavigationIcons | `a3d3753360303407e47c18ce68ac6b980dd9c22914424b708ca76840e3abcba8` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopQrCode.kt` | P01/P02/P05/P06 | implementation / contract: DesktopQrCode, renderDesktopQr | `9719a7a88caa456fa2d41cc3328defa82cb36444c2d46d53039f4cbd12c46f97` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopQueue.kt` | P10 | implementation / contract: DesktopQueue | `a59d77875cbc92a8ad74a425478d66f8d479fddeea41fc2a047772121c795eba` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopQuickSearch.kt` | P04 | implementation / contract: quickSearchEntries, quickSearchEntryKey, DesktopQuickSearch, QuickSearchRow, QuickSearchKeycap | `138101267890b04a555528e10928a31988bb644f29512485da205f220c7ebf6e` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopQuickSearchState.kt` | P04 | implementation / contract: DesktopQuickSearchRequest, DesktopQuickSearchSnapshot, DesktopQuickSearchState, DesktopQuickSearchKeyAction, DesktopQuickSearchKeys | `1fccdc18176de2af0f2007d68f5e9f084e925796d231f3978fe9183ff7fb4343` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopRangeStream.kt` | P17–P19 | implementation / contract: DesktopRangeStream | `348a1855c841c71746653de0d165fdd0adddf77c368b574e26294b15c3e673fc` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopRateConverter.kt` | P11–P14 | implementation / contract: DesktopRateConverter | `b76e31046a7e1a3f0e8964a9388ac5c1d906774197171245cbfdfbc15ba07601` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopReleaseNotesMarkdown.kt` | P01/P02/P05/P06 | implementation / contract: DesktopReleaseNotesMarkdown, Node, DesktopReleaseNotes, DesktopReleaseWhatsNew, ReleaseMarkdownBlock, markdownImages, imageAlt, hasRenderableText, ReleaseMarkdownInline, AnnotatedS | `c77ca4f1d38456c68e28adae1641a89c51e1ae09247e58f0bdbded91c0a062fa` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopReplayUi.kt` | P09 | implementation / contract: DesktopReplayPage, PeriodPicker, LazyListScope, ChartRow, RankBadge, InitialTile, Habits, StatTile, Note, EmptyReplay, DesktopMesh, DesktopReplayRow, DesktopReplayHeroCard, Desktop | `231be268e5792f623bc8365c7516edd51dfbcca3117053da64ed75753f11861f` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopScrobbleSettings.kt` | P32/P33 | implementation / contract: DesktopScrobbleSettings, String | `65f4b29720539f31117ac980459040eb55ad23f5ef3e77e8d3667b031478d307` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopScrobbling.kt` | P32/P33 | implementation / contract: DesktopScrobbling | `4106afb53132671be4c65bb1cf0bee45ac0dcadde2c6edba16c394d19af93bce` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSearchClient.kt` | P04 | implementation / contract: DesktopSearchClient, DesktopCollection, browseTypeOf | `156b2be9da9e2843a7ccb6e02c564d1b85a5528b874698e65b641ed112d01e5f` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSearchSession.kt` | P04 | implementation / contract: DesktopSearchSession | `b725507c8e6894e273589b9e867957feaa4b45698149f7f6306a1737aea062d4` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSecretStore.kt` | P20/P21 | implementation / contract: DesktopSecretStore | `18404821f68eff6df36f8b4abc6f5d7b30625895d238a374c7b8de9f695a8ed0` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSharedCalls.kt` | P01/P02/P05/P06 | implementation / contract: DesktopSharedCalls | `b2138ca865e3c910e05c0c5b2a010da01fabc5f3f85ae120546f46d4a2ac0380` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSharedPages.kt` | P01/P02/P05/P06 | implementation / contract: DesktopAppUiHost, DesktopSearchHistory, DesktopLibrarySortMenu, DesktopPlayPill, DesktopCircleButton | `2b054b37ab02443044bb706ca8db92527acbce279772ec2ce96c821429486624` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopShelfBrowsePage.kt` | P01/P02/P05/P06 | implementation / contract: DesktopShelfBrowsePage, DesktopBrowseGridCard | `e4370b839bbc00993bb0f9a34372a5ecc5ba271a33c4e135fa8731df7736d6dc` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopShelfMedia.kt` | P01/P02/P05/P06 | implementation / contract: ShelfItem | `dc9d5f675ef0192499dd739a51cb1be6e859839ba2e3df3de7dbe55741c0ea13` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopShortcuts.kt` | P27/P28 | implementation / contract: DesktopShortcutCategory, DesktopShortcut, DesktopShortcutKey, DesktopShortcutDispatcher | `d17ee2c6f62fb99ff3b758a0310ccf654f04e0fbfabdfa981bedb3187465e6c1` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSidePanel.kt` | P01/P02/P05/P06 | implementation / contract: DesktopSidePanel, DesktopSidePanelColumn | `5e446d881b87d3c2e27dba5c53c012bfd11a28350eda47a5c001c012e195569b` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSidebarMica.kt` | P29–P31 | implementation / contract: DesktopWallpaperUser32, DesktopSidebarMica | `49f2d17e605d35c40cd278d8f05f573fbd7636be0a345c9641797f168723f0ee` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSidebarWidthPolicy.kt` | P01/P02/P05/P06 | implementation / contract: DesktopSidebarWidthPolicy | `b8596d6727949fcb72adf0b84791bffd5721415d7864cfc1a18f6a5afdd3be6f` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSleepTimer.kt` | P01/P02/P05/P06 | implementation / contract: DesktopSleepTimer | `701e8dc6d8685bdf3a65b3890b1baf106ba9cb006b24a4d95e0c7df8b245549f` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSongMenuUi.kt` | P01/P02/P05/P06 | implementation / contract: DesktopSongActions, DesktopSongMenuAnchor, DesktopSongMenuFor, DesktopSongMenu, DesktopCollectionMenuActions, DesktopCollectionContextMenu, DesktopSpotifyPendingSongMenu, sleepTime | `8d69dd5ec4574a02b0947516d52bbf61ff2b781e9cd3318f50c1e7105e74caca` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSourceConfig.kt` | P17–P19 | implementation / contract: DesktopSourceKind, DesktopAudioQuality, DesktopSourceConfig, List, canonicalSourceUrl, moveUserSource | `e9877fe8d35f37b0faaf694125abad82517e0f3b362717bb8a5c3e9912e2d398` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSourceRegistry.kt` | P17–P19 | implementation / contract: DesktopLiveResolution, DesktopSourceDescriptor, DesktopSourceRegistry | `4ca9534b201f19dba58a52c04bf3945617b891acad6ea667ac10248310d14042` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSpotifyCanvas.kt` | P25/P26 | implementation / contract: DesktopSpotifyCanvas, ProtoReader, ByteArrayOutputStream, DesktopSpotifyToken | `79c13ab10ad7d65c4bd2f60919446b7ec341a62c9d49c98ec134135e9dd99963` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSpotifyChromiumToken.kt` | P20/P21 | implementation / contract: DesktopSpotifyChromiumToken | `8fb58c3aa0d2256af020e5f97361e1d8fbfa8e35da49cd717774ace1f5623923` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSpotifyLibrary.kt` | P01/P02/P05/P06 | implementation / contract: SpotifyPlaylist, SpotifyTrack, Song, loadSpotifyPlaylist, DesktopSpotifyLibraryPage, DesktopSpotifyImportDialog | `4463b9b4ba7767d985fbc7a876513dd4c3524f7abb5d369160270012f9716250` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSpotifyLibraryAuth.kt` | P20/P21 | implementation / contract: DesktopSpotifyLibraryAuth, DesktopSpotifyTokenBridge | `fb38cece99a2c98960f47526cf5409da425206292393a59e36de526b3a6f7fa7` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopStartupDiagnostics.kt` | P38 | implementation / contract: DesktopStartupDiagnostics | `8f863a899d4aa0b9a0454dcd01f192ca36b9aa641dc70332270f62af0184e593` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopStatusNotifier.kt` | P32/P33 | implementation / contract: StatusNotifierWatcher, TrayPixmap, StatusNotifierItem, DesktopStatusNotifierController, renderTraySvg, isLinuxSession | `1772ac637b904ed259781a6207c7772a9f55881fb77c6f3d3a73a3003cf90b16` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopStrings.kt` | P29–P31 | implementation / contract: DesktopStrings, DesktopLanguage | `01d580def87f7411c30a5d60ee1ec783cc0c25276216fc999078791158c94126` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSurfaces.kt` | P29–P31 | implementation / contract: Modifier, DesktopCardRule, DesktopSettingsPageFrame, ColumnScope, panelInset, DesktopDialogPanel, DesktopFlyoutCard, DesktopFlyoutBackdrop, DesktopDropdownMenu, DesktopDropdownMenu | `8b5c962a2abffc90290e18af82e5e644f191a5af16c15d78b63fbe0c877a9ed4` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSystemTheme.kt` | P29–P31 | implementation / contract: DesktopSystemTheme | `41a40fd8dbb1aac31a1124ab6672f0a50424a49064e5b2df6192186f930079d2` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTempoBuffer.kt` | P11–P14 | implementation / contract: DesktopTempoBuffer | `65a4021d70d0d86890ae97abacf27bbb799a076181793ed5c99ee2da3fbb2384` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTheme.kt` | P29–P31 | implementation / contract: DesktopThemeMode, DesktopTheme, DesktopThemePalette | `d5036bd035adc0b643315e90b452bbac194c44581c0a23bf0399ce05728f2e54` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopThinSlider.kt` | P01/P02/P05/P06 | implementation / contract: DesktopThinSlider, DesktopMixSheen | `c179fd664591f9a289bb55047b311ee76fb41a3bdbaa0d90d83e31304473a53c` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTopBarWidthPolicy.kt` | P01/P02/P05/P06 | implementation / contract: DesktopTopBarWidthPolicy | `324e094229a743d8cb7312f04739e7cb764c9b60cb57e2aa4c5697bde4584d2c` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTrackAnalyzer.kt` | P15/P16 | implementation / contract: DesktopTrackAnalyzer, Song, String | `308dcd5db8d30a5dee3c1e3e8dd211df752563043362eac01db8462b913d60e2` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTrackLog.kt` | P38 | implementation / contract: DesktopTrackLog | `e6d03f64933a229a7696e71bb5c3f5a5a6a704f048cc65d03ed29bad74b6e2db` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTrackMatcher.kt` | P17–P19 | implementation / contract: DesktopTrackMatcher | `68ab3da8755f42d163de09c90a4922af0c5351ac8e5ed18ba5be1b3a90c2140b` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTransitionEcho.kt` | P15/P16 | implementation / contract: DesktopTransitionEcho | `9449dbd7632cac06096f8a43dbab7016f1fff8e49647afe2c2ab0a3dba30e152` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTransitionRide.kt` | P15/P16 | implementation / contract: DesktopTransitionRide | `91f26a1b60143ea915435ccf742a12dc0a76b9e00e97c28d5baf5b2bbb75ac41` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTranslationLanguageUi.kt` | P29–P31 | implementation / contract: DesktopTranslationLanguageDialog, LanguageRow | `47776e997c4eda47cd632d471302aea8940facbde2d1cc9f4c5f19504412305d` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTranslationSetting.kt` | P29–P31 | implementation / contract: DesktopTranslationSetting | `62280be885be87af35f70cc2d9ec1c8d74b8b1e27cd7dd8e43280395fb5218f7` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTrayMenu.kt` | P32/P33 | implementation / contract: DesktopTrayMenu | `8939683e8566edd4bcf62b1bd91d743c1f64e4a069f4631aa1d230d0e0eab113` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTypography.kt` | P29–P31 | implementation / contract: sfProCutResource, desktopSfProFamily, desktopSfProFont, desktopSfProTypography | `08e0e8d30df371ccc108302c030fb6548bb70e02bcd030262ca9e758ffed74b4` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUiScaleSettings.kt` | P29–P31 | implementation / contract: DesktopUiScaleSettings | `9976eca32d5eaf7a511c1124c69af7c4285af3a1326eaea3fb37ede264aa3b4a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateChecker.kt` | P37 | implementation / contract: DesktopUpdateChecker | `f5477ad66db3d0a2e1d744209fb1b7bd7f5299250c04ff91c4e92359bf1f42b7` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateDiagnostics.kt` | P37 | implementation / contract: DesktopUpdateDiagnostics, DesktopUpdateProcess | `0a9691cb58c97fa37ce3d3df62ff1c332ef3a5a9f0983cb07a125403ce0c226a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateDialog.kt` | P37 | implementation / contract: DesktopUpdateSettings, DesktopUpdateDialog, sizeLabel, updateStatus | `9fd1995bd550a1575a9c2627b5f786f44d2087217222143be6e09aff7b5344e9` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateDiskSpace.kt` | P37 | implementation / contract: DesktopUpdateDiskSpace | `0a10618a2d26cd18c9c622510cc9849ac94c441df393b0d26cfc69611552d4c4` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateDownloader.kt` | P37 | implementation / contract: DesktopUpdateDownloader, openUpdateStream, allowedUpdateDownloadUri | `599b384c640786cfe14b5f98670a982159191c36c772c3ab3a772ac72d81f815` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateInstallation.kt` | P37 | implementation / contract: UpdateFormat, UpdateInstallation | `2c0ce7e85f55de20af327ee376298d4fb6a0c4ace3c1a8cb76296a992ba49686` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateInstaller.kt` | P37 | implementation / contract: DesktopPreparedUpdate, DesktopUpdateInstaller | `2bac85463b99614da5253870638d9e239d78e2e37fd7a376c817de101a3db010` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateManager.kt` | P37 | implementation / contract: UpdatePhase, DesktopUpdateState, DesktopUpdateService, DesktopUpdateManager | `bbe03ce93dd942fff9dcb1bb5312010e250f3fe8a276520f190175ef2653ef99` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateReadiness.kt` | P37 | implementation / contract: DesktopUpdateReadiness | `81152afa2325e597cacdb221ae59723ec376c3e2a5b1184bbc6e9b7cf9603ea5` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateShutdown.kt` | P37 | implementation / contract: DesktopUpdateShutdownCoordinator | `a3d86191aaa29f43140bd8eed06443e5331ca2352ee213eb9ecbc546a869019b` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateVersion.kt` | P37 | implementation / contract: DesktopUpdateVersion | `1a89005f64e552d6f117a6642594ca17639cdb6703ed04696271bcb81e20241a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopVideoBeta.kt` | P25/P26 | implementation / contract: DesktopVideoBeta | `eadcfc4460ddd4fba157313bd4e07e17487d41f8408f44207d6434f42ef7ca60` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWebSignIn.kt` | P20/P21 | implementation / contract: DesktopWebSignIn, DesktopWebCookies | `47fb78c1db55f9d41331e025f7ca5ceda0169a9da6e2fbe4bc2db6cbbb18c01d` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWelcomeOnboarding.kt` | P01/P02/P05/P06 | implementation / contract: DesktopWelcomeOnboarding | `32986bf943cb2b5e923f7c31eae203b3bbb6e1d83110eef43c374da5e68bcc18` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWelcomeScreen.kt` | P01/P02/P05/P06 | implementation / contract: DesktopWelcomeScreen, WelcomeAction | `9e4fc364630a48e93fd08e3db9148b46d76a27a9f933ded1e45096723061ba76` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowBackdrop.kt` | P27/P28 | implementation / contract: DesktopBackdrop, DesktopWindowBackdrop | `bf75dc04cd82918a0914390bf52d6cfde7dffbd3470349f44c2027c76c6c6d7a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowMode.kt` | P27/P28 | implementation / contract: DesktopWindowPlacementController, shouldIgnoreStaleFullscreenObservation, linuxFullscreenRestorationPath, DesktopWindowMode | `864ffb9a0f3a87aa955d599207c6a917c3bb66ff46bccfb782c1afe86bbb8fea` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowVisibility.kt` | P27/P28 | implementation / contract: DesktopWindowVisibility, shouldAnimateDesktopLyrics | `eb1c2b23159e5053eaa114147200d8ad381ddcedfdd51b1a822e17596ff001c2` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsAudio.kt` | P11–P14 | implementation / contract: DesktopWindowsAudio | `df1cc682cf4f7fc6bf286674d13040ffaaf323eac30e1b6a4cd263a9604dc227` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsCaptionAppearance.kt` | P27/P28 | implementation / contract: DesktopDwmApi, DesktopWindowsCaptionAppearance | `74fe0b3e0be5a96bea110e986faf4ba5049a243cf4acd8cda05462a1f538db02` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsCrypto.kt` | P20/P21 | implementation / contract: DesktopWindowsCrypto | `72c9a5a035a55255bdf9e1f2ad4ab42aaa40d9945c4f487cc8653ae97594e6a5` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsFullscreen.kt` | P27/P28 | implementation / contract: DesktopWindowsFullscreen | `a4e2819820c8826618a11cbe7359583c957ffae40e4c15aa86b0b9c87d74ab7a` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsMedia.kt` | P32/P33 | implementation / contract: DesktopWindowsMedia | `ea1189fe6889395c93c28e2cad35ef0df71ab3079989097cdbc97a066ef6e247` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsUpdate.kt` | P37 | implementation / contract: DesktopWindowsUpdate | `0aaa9656dab914420dde78440f04e0e91dcc64ff851af57217077deaaddfec3b` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopYouTubeAuth.kt` | P20/P21 | implementation / contract: DesktopYouTubeAuth | `e249f00146f9103552927c8ca1302d1eccd93d9638f2a92a6299ec7e12290084` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopYouTubeSession.kt` | P01/P02/P05/P06 | implementation / contract: DesktopYouTubeSession | `6ef790b8fb5b9e36401ecd2f811935aa8ef06933cf6eb641866036ae5b157d20` |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/Main.kt` | P01/P02/P05/P06 | implementation / contract: main, desktopMain | `5a8d313611e922c2d35deb50182ebcba97151254afd7b6378f3262b85bbaf7c7` |
| `desktopApp/src/main/kotlin/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | P15/P16 | implementation / contract: LoopMixRenderer | `34bc96861cb33ba98ebd6b3e26b5674eb020550113885bdb9c9c4fad5b8fa212` |
| `desktopApp/src/main/kotlin/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | P15/P16 | implementation / contract: LoopRegion, LoopMixSession | `79cb059bc2d3cf72e7e82e599287e841e872b79947d71fb2b3b802173c4ab2a9` |
| `desktopApp/src/main/resources/strings/de.xml` | P29–P31 | asset / configuration / specification | `0603518d240a42e1d88ad0dab553d695eff0094c58c3db2a1023e70aabc98cab` |
| `desktopApp/src/main/resources/strings/desktop-de.xml` | P29–P31 | asset / configuration / specification | `055f6829d7ab6e330b6aa5fd89d3472bfff1b25846af989774e86a602d888c39` |
| `desktopApp/src/main/resources/strings/desktop-en.xml` | P29–P31 | asset / configuration / specification | `25033be9ea1a9fc28b9ecb12921254c24bd455280e5be38bbc422dc726342573` |
| `desktopApp/src/main/resources/strings/desktop-es.xml` | P29–P31 | asset / configuration / specification | `e5417f474492ce1be273470c4fd9654692e276ceee16da2732fa263381415c55` |
| `desktopApp/src/main/resources/strings/desktop-fr.xml` | P29–P31 | asset / configuration / specification | `cef6602cd7abc1b5cdb0c99599a234683264b53e5e34631d7b6890872af3922d` |
| `desktopApp/src/main/resources/strings/desktop-he.xml` | P29–P31 | asset / configuration / specification | `c6f0325be493f82c7aa3f017bf8679a33f030cdd20d96ba7ab60ce4e7f015632` |
| `desktopApp/src/main/resources/strings/desktop-hi.xml` | P29–P31 | asset / configuration / specification | `caa2ebfd84cc6b0700382f449e0cae9bcab98ce52bf394234954d19a34c519c3` |
| `desktopApp/src/main/resources/strings/desktop-id.xml` | P29–P31 | asset / configuration / specification | `79a3f2d63f17fd2a1156060b4b14fe91653e915a48ccf797d72294eba644f2cf` |
| `desktopApp/src/main/resources/strings/desktop-it.xml` | P29–P31 | asset / configuration / specification | `fd90e932ec35c4766337c7f2e2a72312367d21e75b8b8fb00b330a2a74da072b` |
| `desktopApp/src/main/resources/strings/desktop-ja.xml` | P29–P31 | asset / configuration / specification | `d9ce9f7b8daa9f1c9d6d1f8ffcb546f91f4a4049f2b3912e987892404c61f8f4` |
| `desktopApp/src/main/resources/strings/desktop-ko.xml` | P29–P31 | asset / configuration / specification | `e9a39b4683cbba9a99a3cfa968c58ebf29faf6dd4851ab8a6d2f776170d09403` |
| `desktopApp/src/main/resources/strings/desktop-pl.xml` | P29–P31 | asset / configuration / specification | `4748b6d0538a75a87bb75e08001c8f138bf9842372506c44d45ffcceaee6af4f` |
| `desktopApp/src/main/resources/strings/desktop-pt.xml` | P29–P31 | asset / configuration / specification | `e223e48018ecdc9ec744c41b095b17d0a17809128e53ddb6d421958becb46f6e` |
| `desktopApp/src/main/resources/strings/desktop-ru.xml` | P29–P31 | asset / configuration / specification | `2a86da0a65d59d4c876e697b87f9c7fd7fbba10efb26e2e632a29e6babcb40a4` |
| `desktopApp/src/main/resources/strings/desktop-tr.xml` | P29–P31 | asset / configuration / specification | `be30fc6715f066dab70336a7fbd6d6e262806732ca8ffdc04f016b12baf335ed` |
| `desktopApp/src/main/resources/strings/desktop-zh.xml` | P29–P31 | asset / configuration / specification | `e483acbee2ad8db4ed4904686699e9cf7f845aa38aa80c2ac622164e028040a7` |
| `desktopApp/src/main/resources/strings/en.xml` | P29–P31 | asset / configuration / specification | `eda27fe5d1f7801d74d96ea014b749be3d153cd577a5a2aa201196c78ec71d5a` |
| `desktopApp/src/main/resources/strings/es.xml` | P29–P31 | asset / configuration / specification | `8252a3d8f48aa723500d08b6f7c261922ee1205797778797e1d04b98b9464be4` |
| `desktopApp/src/main/resources/strings/fr.xml` | P29–P31 | asset / configuration / specification | `b987eba69163cf6303b367696e20df067c3bc6066484476ba375ac606c9bdba8` |
| `desktopApp/src/main/resources/strings/he.xml` | P29–P31 | asset / configuration / specification | `4528a0514310ba663de4f336db3c926da44d6cca6f6c9153b6e1d07e55f49e97` |
| `desktopApp/src/main/resources/strings/hi.xml` | P29–P31 | asset / configuration / specification | `f7e4a50f580f43d6fcb465253286bc33a6f565280b3718b315f13d6bbcdd8b05` |
| `desktopApp/src/main/resources/strings/id.xml` | P29–P31 | asset / configuration / specification | `1ffda07b511ec72728ccfdb998a7c1928b1bd37215b17f73564a897eac33ecc3` |
| `desktopApp/src/main/resources/strings/it.xml` | P29–P31 | asset / configuration / specification | `2c42dbfcc2a5b756957134e6ba90d0cfcd6f419dd4c206d6b406641a9d630bfa` |
| `desktopApp/src/main/resources/strings/ja.xml` | P29–P31 | asset / configuration / specification | `908b57f6b8c32ab8b714adb3c22b80e4d0e27f4307eb218c7fe57d8840abb5ef` |
| `desktopApp/src/main/resources/strings/ko.xml` | P29–P31 | asset / configuration / specification | `998c458232dc339b6bd58632dc1f4ba028bf02bce8bb796e8a0a37669a86d171` |
| `desktopApp/src/main/resources/strings/pl.xml` | P29–P31 | asset / configuration / specification | `0142d74324997aac67d8377da79634edd8a88f9a49649602f280e6f719f937b2` |
| `desktopApp/src/main/resources/strings/pt.xml` | P29–P31 | asset / configuration / specification | `b4649f5a8e08d8483517e391108c608ff6fab7ee2794ca57ad7d42ccd8963d39` |
| `desktopApp/src/main/resources/strings/ru.xml` | P29–P31 | asset / configuration / specification | `bd573f5242bba8188ae61fbb6bbca28ca2786f8ae0458ba3065e0a5936a338d2` |
| `desktopApp/src/main/resources/strings/th.xml` | P29–P31 | asset / configuration / specification | `7955fe6b1430bdc8b257aab996f2eb0622982a7ea08a661637616211f3582166` |
| `desktopApp/src/main/resources/strings/tr.xml` | P29–P31 | asset / configuration / specification | `59ac0ed3903a977f33e0418314174c43a44148a8f0e80b663a2c3164a9ee0d85` |
| `desktopApp/src/main/resources/strings/zh.xml` | P29–P31 | asset / configuration / specification | `90bbc83b11b64f7b489c8f1864da4f5afdfde232fa464fac531fcbd42601958d` |
| `desktopApp/src/main/resources/updater/appimage-update.sh` | P37 | implementation / contract | `a036a9189bab77052760dd710622661c891bc5ade10f4d53e6da4dd858f265cb` |
| `desktopApp/src/main/resources/updater/linux-updater-ui.sh` | P37 | implementation / contract | `9b421f31a8cca70d44731cc62257929eb3ef2d868d074a90012fe41d6db39193` |
| `desktopApp/src/main/resources/updater/windows-msi-source.ps1` | P37 | implementation / contract | `0640b105495de7c7c6ea978d9313534335b9d6d86582b5bd0df5cb746fcc55ef` |
| `desktopApp/src/main/resources/updater/windows-portable-update.ps1` | P37 | implementation / contract | `82de6312dc7922cc9021c1780ddc9f0703beba9b9c208b8cd540d335a085a1e0` |
| `desktopApp/src/main/resources/updater/windows-update.ps1` | P37 | implementation / contract | `6998daf5bba12658f18b98cd38732ced366775b284e97e01865fe7144f7ffa3c` |
| `desktopApp/src/main/resources/updater/windows-updater-host.ps1` | P37 | implementation / contract | `41bcaa9a531cc9abd88f850b85fb1584d1f7de53fde87b06f5c41e75b5a58653` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAboutVersionTitleTest.kt` | P38 | reference test: DesktopAboutVersionTitleTest | `38a36d3b8c3f6cdd0f261ac9a29b69600719f8d6bf5d9369431b1d7a889e0d9d` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAddonPolicyTest.kt` | P17–P19 | reference test: DesktopAddonPolicyTest | `65d807979226cbc462f9109aa5a3059aef92a6651db0ff8c594723eaf1fcf59d` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAddonServerTest.kt` | P17–P19 | reference test: DesktopAddonServerTest | `c4bdcba4710e18bd208983c77d6877dc35ee92d6c7e1b3a7d151a0e9d2cc9b19` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAddonTest.kt` | P17–P19 | reference test: DesktopAddonTest | `371a3439f9b7df02bb2b89e7964d471d50acd2cada8674f9cafbaa511c11d4a2` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAnalysisTest.kt` | P15/P16 | reference test: DesktopAnalysisTest | `49839afc6bdaab6c47abc86c4e06dd7d2aeb74e29761f5b900768a0a20df3754` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAppIconTest.kt` | P29–P31 | reference test: DesktopAppIconTest | `8360b678df8290c43fa64fc58e9f506a8858d2a2b72a142ad65aa3e5cb342ef3` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAppleArtistArtTest.kt` | P25/P26 | reference test: DesktopAppleArtistArtTest | `c82e7cfd6782088147d68daab3a0989e3d84e24732f4ff4bfef56bf5731ef972` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopArtistSignaturesTest.kt` | P25/P26 | reference test: DesktopArtistSignaturesTest | `ec480e4e1bef1e50a0427284468a3acd0e26f9b230b64bbebd8ae74a70f4c330` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopArtworkCacheTest.kt` | P35/P36 | reference test: DesktopArtworkCacheTest | `d9442c18b55bfadf2c5d8779a198a4817903036a9a7651b85092c170888cba52` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAudioCacheTest.kt` | P11–P14 | reference test: DesktopAudioCacheTest | `c7df087ea121ab8e43cc2714b2e3c3e1a579d59dfaf9083f03e48f267d88b4c0` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAudioDecoderTest.kt` | P11–P14 | reference test: DesktopAudioDecoderTest | `313499980bb9e5c51391fc744a7fd6fb5d5cb184d2cf93636efa939cc2763d26` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAudioProcessorsTest.kt` | P11–P14 | reference test: DesktopSpatialAudioTest, DesktopSilenceSkipperTest | `cb5bed2a0e75b2069fac7d199970600cfbaa58885f449db463d665964d3289dd` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAudioSpeedTest.kt` | P11–P14 | reference test: DesktopAudioSpeedTest | `b2977c0a22fb12bbb6cff17a549d57b53c8ecaedca918eac842adac0edc39c8f` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAutomixDurationTest.kt` | P15/P16 | reference test: DesktopAutomixDurationTest | `2aedb2c21c98c922713ea088fe231bdb05cd5be3e73fa0efcae783b5d108e0cc` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAutomixTest.kt` | P15/P16 | reference test: DesktopAutomixTest | `a5108c7a1b2ae4393c30f0fc830eb9eccaaafabc214ed8a23c7fc567eb10e8e6` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAutomixWorkQueueTest.kt` | P15/P16 | reference test: DesktopAutomixWorkQueueTest | `7e3446990b5340e837e87b2d044993e4199cc9fc3f71af3297d82d4575986496` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAutoplayQueueTest.kt` | P10 | reference test: DesktopAutoplayQueueTest | `a284cb8db659082be29c7c725577ad314c43b4171814a2f2a731755dc2fdfcd2` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopBeatAwarePcmTest.kt` | P15/P16 | reference test: DesktopBeatAwarePcmTest | `273f9a4d2e56d270d8f4449eea6526bc4e030f18520b0b5e0602ba2293bb8ff4` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopBeatModelTest.kt` | P15/P16 | reference test: DesktopBeatModelTest | `551e35000224fc994f13f92f50e238625c20e25af66935424f92ab91e0069bfb` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopBeatStressTest.kt` | P15/P16 | reference test: DesktopBeatStressTest | `07ddbb35c65d2226ee3478c9c281848b4aa3fb8915a2cf6a949ab19d655cc791` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopBlurCustomizationTest.kt` | P29–P31 | reference test: DesktopBlurCustomizationTest | `3d9a7088a066fb6cb5255bc83be94bb0b53b701a3411c8be2a579323854f898e` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopBrowserCookiesTest.kt` | P20/P21 | reference test: DesktopBrowserCookiesTest | `ce0c0c25ad093b04caabf78a20f91e7f38a189c481178ade4afb212437b26590` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopCanvasTest.kt` | P25/P26 | reference test: DesktopCanvasTest | `23944be5971deadb0a99282a072f53ffc4910ed678b2d8074304ea07494301d9` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopCodecParityTest.kt` | P01/P02/P05/P06 | reference test: DesktopCodecParityTest | `f18f66efa58ac2e50adc6094e374961e14e3d0b1dc8a7deed047ee64811cd170` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopContextMenuAppearanceTest.kt` | P29–P31 | reference test: DesktopContextMenuAppearanceTest | `e3a44bf8fc1374e879dc7a491169355f7595b052c0050cc4d2d3df095cabc79a` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopCrossfadePositionTest.kt` | P01/P02/P05/P06 | reference test: DesktopCrossfadePositionTest | `5b0665562b04b5c610f47b86a5be09a159a1964f1cea66986ffded83d8a8f38f` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopDecodeAheadTest.kt` | P11–P14 | reference test: DesktopDecodeAheadTest | `a492d4bbdf370b43a262045fd7fb0705de35e1733cf41cb9584e278c3b4c606c` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopDiscordIpcTest.kt` | P32/P33 | reference test: DesktopDiscordIpcTest | `23c7cd0dd87de7b485746d2769866e0122de92c81908d24ae7ed2ca7c460b9c9` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopDownloadQueueTest.kt` | P07/P08 | reference test: DesktopDownloadQueueTest | `3413392b7788fea9838f481c16221642bbee0aaad415bbee0481e6ebf46a0dee` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopDownloadRecordTest.kt` | P07/P08 | reference test: DesktopDownloadRecordTest | `37b653b031a56d3a5a6a2a13e3e54c01b5665b9d98a13caaff891d78a34ab81f` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopEmbeddedLyricsTest.kt` | P22/P23 | reference test: DesktopEmbeddedLyricsTest | `49db46665485f90fa739b83cafbadf98b7e77630c4c775e702fef5e51e73b4bf` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopEqualizerTest.kt` | P11–P14 | reference test: DesktopEqualizerTest | `7b51dece2c5dbb7a7f45139a31e4f3648ee7032799c501141e745b790d915c38` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopFlyoutStateTest.kt` | P01/P02/P05/P06 | reference test: DesktopFlyoutStateTest | `be3840e8257161b207c081424753d183a39a57187c716bce86de601eae46097b` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopFontMetadataTest.kt` | P29–P31 | reference test: DesktopFontMetadataTest | `0d5320321664ce582988c28c337356f75ba125b13eacc56b2ed5d247c591008b` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopFontRenderingTest.kt` | P29–P31 | reference test: DesktopFontRenderingTest | `8d5f6adb2b66229f97cf1e148378189ad2d729537476518ca38df93b566bd7b1` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopFontSettingsTest.kt` | P29–P31 | reference test: DesktopFontSettingsTest | `3ef61a857926c7ea77062c73276c47eeec64f39d3533bd5c55487755792b65a7` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopInstantPlaybackEngineTest.kt` | P24 | reference test: DesktopInstantPlaybackEngineTest | `84cc51bb4a9a87f76c89c67152de552b18fbfd2132fd8323b40c16dde5c0c436` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopLandscapePlayerTest.kt` | P24 | reference test: DesktopLandscapePlayerTest | `bdc1da8bdaecc7ed05863d598dc5461a4e4964c0166c755afd76d41b63e6053f` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopLibraryLoadPolicyTest.kt` | P01/P02/P05/P06 | reference test: DesktopLibraryLoadPolicyTest | `97f9c546915dd7ff500293bd6673adc05ee31aa66809bf4f370b780e8f4d5448` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopListenTogetherTest.kt` | P34 | reference test: DesktopListenTogetherTest | `cb546cd4f76b8ec73acacfb395cb96dd84679fc2f3c6a6f5f1403cd9e65a3e9a` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopLocalArtworkTest.kt` | P07/P08 | reference test: DesktopLocalArtworkTest | `7adef0e89af278267b3970f396dc9648e11874fa411d3005c9596a585723bcbd` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopLocalMusicArtworkFallbackTest.kt` | P07/P08 | reference test: DesktopLocalMusicArtworkFallbackTest | `a29fe0303779c10484988cbd56e4cfaa702c58d5fa87950dead5cf653f0d3010` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopLoopRendererTest.kt` | P15/P16 | reference test: DesktopLoopRendererTest | `358c3706aeb48fe00d1ff8ed8cea7094998177e19f6893cfdb28776d695c558d` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopLyricsAnimationPolicyTest.kt` | P22/P23 | reference test: DesktopLyricsAnimationPolicyTest | `d9659ec2c25d5463c7268f95f199677916e01773eac8313d8a01cc888d688636` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopLyricsSessionTest.kt` | P22/P23 | reference test: DesktopLyricsSessionTest | `c2b474eaf1f061195b4e8b30ae085515103b7778719e80da271f61cd0ee07ed3` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopLyricsSourceOrderTest.kt` | P22/P23 | reference test: DesktopLyricsSourceOrderTest | `8e79f88cf2832499fd13ed52764f969398bbacc8eb278e71e12efc5518fabe9a` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopManifestDownloadTest.kt` | P07/P08 | reference test: DesktopManifestDownloadTest | `d3fce42b1ac1276918a8d4438cfb593715e70aaf12d90d5a09f8abccbf37fad8` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopMatcherTest.kt` | P01/P02/P05/P06 | reference test: DesktopMatcherTest | `94d321811e524fd5ee7de7a096a622492c07bbb312d7d21d9a71375d9666d8f7` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopMediaVersionTest.kt` | P38 | reference test: DesktopMediaVersionTest | `b3bca19c99517dd85ee51e1c0ec13f55354fe3667f2fed6918ee9793b189a5cc` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopNowPlayingDetailsTest.kt` | P01/P02/P05/P06 | reference test: DesktopNowPlayingDetailsTest | `cddef0803522a04d72ce5492ea1217d9099c7af24a4ccbae2ecad92f5cc994c8` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPageMemoryPolicyTest.kt` | P35/P36 | reference test: DesktopPageMemoryPolicyTest | `9b25331e0def7a07b4e88274f17a361e414fa0c3eca457657e15a65a67ef72ce` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPanelMenuTest.kt` | P01/P02/P05/P06 | reference test: DesktopPanelMenuTest | `b385c5a3fb48892cbc3ce92117984191549170d660c9f877d4dc1b8030e107cd` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopParityTest.kt` | P01/P02/P05/P06 | reference test: DesktopParityTest | `f09c4ccac2f17148fc07de8739eb8e104cad6a61dcd2901a1fd9677a6ec8e659` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPartySyncTest.kt` | P34 | reference test: DesktopPartySyncTest | `5185db7a8cea0cea1ca50d3a28c8c85c74d102e4391f02490f3ebcc8434a871f` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPcmWriteTest.kt` | P11–P14 | reference test: DesktopPcmWriteTest | `d83d511d23eed3248996eb68abe188e24004f9db4a224e445b28270da29d2e4e` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPerformance183Test.kt` | P01/P02/P05/P06 | reference test: DesktopPerformance183Test | `15056de75c5f1e3ed71c4d9d55339dab578d99e4e9f57831de0b0eee60f79e09` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPipControllerTest.kt` | P27/P28 | reference test: DesktopPipControllerTest | `77d28d90212212988497ed537a787692f09de4319bf2eb820b2a10b5027bde18` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPipNativeTest.kt` | P27/P28 | reference test: DesktopPipNativeTest | `78bbbcb96d5e57caad7819f3b8e49061ffe3f2babe86f8dc41dc556aaa5a74ab` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPipStateTest.kt` | P27/P28 | reference test: DesktopPipStateTest | `f6562a7159d1bae68e1b189290a6046284a7a78d10b1fea5a977a86fb9478161` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPipUiTest.kt` | P27/P28 | reference test: DesktopPipUiTest | `067a341f745a0218ecfa5008819a41483806bace586bdef43a5c8b0342d76201` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPlaybackBenchmarkTest.kt` | P24 | reference test: DesktopPlaybackBenchmarkTest | `36e05c3aad80abd179052acdc4c26d1fc4111f455a9cdf165b7a0d5883da0f8b` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPlaybackCodecMatrixTest.kt` | P24 | reference test: DesktopPlaybackCodecMatrixTest | `64c8014f7da97ae68b7ec116a3537c5be551099c6c9a110d9614c2a1e579cb59` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPlaybackWritesTest.kt` | P35/P36 | reference test: DesktopPlaybackWritesTest | `3a1ab8cdbed63ab29d46bfa4e3683d9ddb5c24682e8af17f304c2ee44c2e5690` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPlayerThemeSettingsTest.kt` | P29–P31 | reference test: DesktopPlayerThemeSettingsTest | `5c06ced3635726dfaad27878c8fd8c116ab0bf5c7a7d54a471c9b3720b30b841` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPlayheadTest.kt` | P24 | reference test: DesktopPlayheadTest | `5a67d26a761b0da0d34a1917a2d7747d04ce8928788701e01f915d4f547df000` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPreferenceChunksTest.kt` | P35/P36 | reference test: DesktopPreferenceChunksTest | `023f29bc7edeedf723893283c54fecc56634f64697da3c35959157919029f95c` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPrimaryNavigationIconsTest.kt` | P29–P31 | reference test: DesktopPrimaryNavigationIconsTest | `a81e6b204fa20eb35b2d9528b2eadf1958309a1ad095678c7db0ed2fccc2f8e4` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopQualityTest.kt` | P01/P02/P05/P06 | reference test: DesktopQualityTest | `6df1e349c0eb5f8c88e0767001c23c142b503dfd1b8cc62c1cfb70a7c28d949c` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopQueueTest.kt` | P10 | reference test: DesktopQueueTest | `bf4e14afb2adfdae8381a2f46d621d5fce9ff4e4adb13f87d6fb602530a5ca9b` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopQuickSearchIntegrationTest.kt` | P04 | reference test: DesktopQuickSearchIntegrationTest | `39ee6dd66b1842ce4f1ac639e2a82f675317eef1d2860bfee8003a67745a846f` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopQuickSearchTest.kt` | P04 | reference test: DesktopQuickSearchTest | `375e4e3d765f356b5494b10591b28360d748223c61f5283f8e0964507e84faa7` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopRangeStreamTest.kt` | P17–P19 | reference test: DesktopRangeStreamTest | `2b4890c3c2e61bf632a1d2b1db15060164d9d85a4de83945dec4d334d7630655` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopRateConverterTest.kt` | P11–P14 | reference test: DesktopRateConverterTest | `ab9416ad2ca0098bd39a86d6253c46004006dae1d203f82b1cf1753ceca27aee` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopReleaseNotesMarkdownTest.kt` | P01/P02/P05/P06 | reference test: DesktopReleaseNotesMarkdownTest | `e7235d4967978d716173ecf92d537441901ac580bf63d0d728e1ca93f7bfb596` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopReorderTest.kt` | P01/P02/P05/P06 | reference test: DesktopReorderTest | `b2867b3e5c5eaff9dbb8e75851bfc7c623b7641825f7884328b3b28c7d32b0a3` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopReplayTest.kt` | P09 | reference test: DesktopReplayTest | `f459039eb386aea270ebe985f606b446fd3e239f0a10b13242eb78d46bceaee5` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopScrobbleTest.kt` | P32/P33 | reference test: DesktopScrobbleTest | `14ef6cb59ddaf9148cbcc4b0628b92f172b054d13f9190422a20df2bc00c2cc9` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSearchGlassTest.kt` | P04 | reference test: DesktopSearchGlassTest | `9c016c1763f0404da9cba6eabb889bbaebd943443805baa1e67806d2b163b5b1` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSearchPointerTest.kt` | P04 | reference test: DesktopSearchPointerTest | `a8d1250f82bdead6b3296d51b41dea5da43a74a3d929b6103917ea7095286856` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSearchSessionTest.kt` | P04 | reference test: DesktopSearchSessionTest | `10a33dab58255f49fb00f5bf37a40036b06a7893eb18cad993a02b79462e7765` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSharedCallsTest.kt` | P01/P02/P05/P06 | reference test: DesktopSharedCallsTest | `c4119a1c612c18b46ea93fc31938d113f9c25c482f1e2f5dfc466c0a4d5b39b3` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopShelfMediaTest.kt` | P01/P02/P05/P06 | reference test: DesktopShelfMediaTest | `bd11b751f511933ef62716ae074e59600957b59e6071482c5a8fa29da0661162` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopShortcutsTest.kt` | P27/P28 | reference test: DesktopShortcutsTest | `54f156848d786f5231c36cc79459f64477f09775e5580d7c8937efb9742fe507` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSidebarMicaTest.kt` | P29–P31 | reference test: DesktopSidebarMicaTest | `a7acad60749da7628b17a11c84f341e74d81eec359214b6c485b7e2ab36a0943` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSidebarWidthPolicyTest.kt` | P01/P02/P05/P06 | reference test: DesktopSidebarWidthPolicyTest | `d6d4a8ae296c1c93d833bd6fff760442bf298e069974e3a9af3f3c15de50d569` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSleepTimerTest.kt` | P01/P02/P05/P06 | reference test: DesktopSleepTimerTest | `cf9788847f5d68b6b387f77c9642ae2a06873a1a1c3896ecabf09270060a8729` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSmallSettingsTest.kt` | P01/P02/P05/P06 | reference test: DesktopSmallSettingsTest | `72082bccee37390cadaeb08b28c6a8baf3da3bfadc1637e3efec9752adea1a49` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSourceMatchTest.kt` | P17–P19 | reference test: DesktopSourceMatchTest | `2c24b3b3de678a1e32fcf4322782b5c14b29dcb451e54179004650548b5a75e5` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSourceParityTest.kt` | P17–P19 | reference test: DesktopSourceParityTest | `9d6c026b57861c62a540ed868bae4120b4f646f374e2210f843fe93829455fbe` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSpatialGuardTest.kt` | P01/P02/P05/P06 | reference test: DesktopSpatialGuardTest | `3e8d6c2a8796529a2c937f41c7e5f28340027b73fd4057706b9b467ae0ba6f77` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSpotifyPlaybackTest.kt` | P24 | reference test: DesktopSpotifyPlaybackTest | `dc7c3abf308fd27f2c5f418166c61998ec341415d2b57e2f18f1cb4196e3a0d6` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopStatusNotifierRestartTest.kt` | P32/P33 | reference test: DesktopStatusNotifierRestartTest | `c446317de560ba4d244eb803d13f5fe559bf7d0db5f5dc98bbc4a350acfaa9fc` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopStreamFormatTest.kt` | P01/P02/P05/P06 | reference test: DesktopStreamFormatTest | `695f8afcc1c2d1ae3c25ccc5ceb25db99329b3d5453db7c214aadbfc22fa750f` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopStringsTest.kt` | P29–P31 | reference test: DesktopStringsTest | `90541831dbcfb8793d52fc33a93e1d185887d83f99c54c191d39a1a10be32b5e` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopTempoBufferTest.kt` | P11–P14 | reference test: DesktopTempoBufferTest | `6fc3fc8fca72e1c3c6fb650041260949372d914da6772957a9b5d79b62317639` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopThemeTest.kt` | P29–P31 | reference test: DesktopThemeTest | `6a238dfcd6159277353b84456befd090e3b23a90ed716f8b875158ea349c7453` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopTopBarWidthPolicyTest.kt` | P01/P02/P05/P06 | reference test: DesktopTopBarWidthPolicyTest | `a6c25d408d2d710cf1bd9e9cee75fb1a0015abe511cd035ce1ca195a212b5d67` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopTransitionEchoTest.kt` | P15/P16 | reference test: DesktopTransitionEchoTest | `993863d060c6096f190cdf1bfc6090d7bf2271912fc82206c2c44a2058179a11` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopTransitionFilterTest.kt` | P15/P16 | reference test: DesktopTransitionFilterTest | `23fbc8d1e147b898351b71d36525ac94b45ff4124dd16dde92edf0b3f9e4dcf5` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopUiScaleSettingsTest.kt` | P29–P31 | reference test: DesktopUiScaleSettingsTest | `f067808087eaa99deda953c0f4eb4c076bf5268d07141309f1eb470a1a162eb9` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopUpdateCheckerTest.kt` | P37 | reference test: DesktopUpdateCheckerTest | `a7c6e24187d51f8316804155e3500f0829c287fa90e753ccaf77d694ccb5bd8f` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopUpdateHelperTest.kt` | P37 | reference test: UpdaterHelperFixture, DesktopUpdateHelperTest | `35e571fe83cf409410b174c6e32ea3b53a7cbd185945c9d35a516b430782eff2` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopUpdateReliabilityTest.kt` | P37 | reference test: DesktopUpdateReliabilityTest | `163b18c163e50fe0b87d3743bc14fe44a90dc193f2c8ebdc9bc34de1c575219e` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopUpdateServiceTest.kt` | P37 | reference test: DesktopUpdateServiceTest | `b31514cf7d559da8b333ddfa95f07a7c4bb1d76b89122cc426eb64a1d0bcf540` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopUpstream1109Test.kt` | P01/P02/P05/P06 | reference test: DesktopUpstream1109Test | `bcfdfb63ebafac5a26b84898516eac751c181a8e15286252f8fd5c0e935842b8` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopUpstreamCurvesTest.kt` | P01/P02/P05/P06 | reference test: DesktopUpstreamCurvesTest | `85b5516aedc05b93c55fae6d487c6693ee1df6def33de11643f271eae4c4d589` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopVideoCardsTest.kt` | P25/P26 | reference test: DesktopVideoCardsTest | `a2fd105aefbe54ea757c356fc85e2df988074bfbb4817a706775992ef54ed79f` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopVideoLayoutTest.kt` | P25/P26 | reference test: DesktopVideoLayoutTest | `7409c8ab859a827423d56d2556505147f500391bfd6e7cc366aecf59052053a5` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopVideoPlayerTest.kt` | P25/P26 | reference test: DesktopVideoPlayerTest | `1804a72d7bd65410d40d7b9490feb3896cd199c3ffab99b35501acfd65bce9ad` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopVideoSessionTest.kt` | P25/P26 | reference test: DesktopVideoSessionTest | `29a63150b638e26d142f12f7bd050aa84ad9724891a82ce2164fbe44f7aaca40` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopWelcomeOnboardingTest.kt` | P01/P02/P05/P06 | reference test: DesktopWelcomeOnboardingTest | `3ec3e0d64fba12a0805b9dcac4964ab8655afb0f880a442dd5133d52ec05d960` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopWindowModeTest.kt` | P27/P28 | reference test: DesktopWindowModeTest | `a7a00140c04681f707406b323468f73c379a570c539ab93ee89a8a596ec110e1` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopWindowsCryptoTest.kt` | P20/P21 | reference test: DesktopWindowsCryptoTest | `424b1cd87f19491b4cc5a41603eec3dc50e5063902a0c7162b3b526c2f44773c` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopWindowsMediaTest.kt` | P32/P33 | reference test: DesktopWindowsMediaTest | `7a1e353b8646eb86bd7c295c616a4ac78e2fd193d324fac02a8f19bfb81add26` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopWindowsUpdateCleanupTest.kt` | P37 | reference test: DesktopWindowsUpdateCleanupTest | `f91a802e0aae77df5a4611a78d756c370d7a15413eea4afa9355712efa28b71e` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopWindowsUpdateTest.kt` | P37 | reference test: DesktopWindowsUpdateTest | `269345c2d8deedb56f418a53b7443c41a8d497bb6ef571f702c8a0efc898551b` |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/SpotifyCanvasQueryTest.kt` | P25/P26 | reference test: SpotifyCanvasQueryTest | `c92e3f6686d187fad8a560261f2f48777e770fd713cbcc9a6f2fa4e5e98dc922` |
| `gradle.properties` | P01/P02/P05/P06 | asset / configuration / specification | `d926644dd3a7e856e5fcd0abf4461182596ba9bbc97913a44bca0b8a27cc19ad` |
| `native/analyzer/audio_analysis.cpp` | P15/P16 | implementation / contract | `21499a80af677f79f8d4ef9787d6c4f9a4c574f4005bd78422b5cf5092ed41b3` |
| `native/analyzer/audio_analysis.h` | P15/P16 | implementation / contract | `36d65e53755dd4c11fdf2b377428e2411fcc033a17f58e2c26229beb3c461d1e` |
| `native/analyzer/mel_spectrogram.cpp` | P15/P16 | implementation / contract | `21e87174ee94a53514f72eac8fb3a6a709b2366e68bf4de2eea620c448267e55` |
| `native/analyzer/mel_spectrogram.h` | P15/P16 | implementation / contract | `569d28ca3afa8e5d306cb9e48a0f1200003a7e749e804246c96d11e0f537ef9c` |
| `native/analyzer/resampler.cpp` | P15/P16 | implementation / contract | `377c7ef1d87fb99ce5214f85bd4cddddb62d4afd6ed7ef534d862d01f15d0c69` |
| `native/analyzer/resampler.h` | P15/P16 | implementation / contract | `93ae72db79f64efb5c8e0bb3296a565ae7286185482de781134bccb621ce149e` |
| `native/analyzer/tempo_analysis.cpp` | P15/P16 | implementation / contract | `f6cde3e8a87fb1f42e356649fffe2a87b36f857be28d360b326c14d9c478bf2f` |
| `native/analyzer/vocal_spectrogram.cpp` | P15/P16 | implementation / contract | `c01b4b02bac5c33d3034693dc51247a9f4de1f4eedac0d02249e306cbf497fea` |
| `native/analyzer/vocal_spectrogram.h` | P15/P16 | implementation / contract | `325b98115a7191067417184b90f1b54ed8149141d4c626ee0a9f11eeb00ea0aa` |
| `native/ffmpeg/build.sh` | P01/P02/P05/P06 | implementation / contract | `25a7b47b1e7b8af95d5cbba3a0831acf7652f0d2dd1dfdfd892543b1a8850456` |
| `native/ffmpeg/ffmpeg_jni.cc` | P01/P02/P05/P06 | implementation / contract | `a8f9a9e37314a2b4991a886bec1ef7d4079aa26ec613ffc90ac94e7f4e5298e9` |
| `native/loopmix/loop_mix_renderer.cpp` | P15/P16 | implementation / contract | `94b1879a04c321557cc018c4938779a97a90a2583bf6eaca531c89028f608e79` |
| `native/loopmix/loop_mix_renderer.h` | P15/P16 | implementation / contract: Renderer | `9f77debc851cd03545f03cea3a98e3653375a186cf79388b7a5840a96fd3afe2` |
| `native/third_party/README.md` | P01/P02/P05/P06 | asset / configuration / specification | `567b2f324027e1fec0afd208fd9366d89f58162dfc1b70efce39ec8bac3da962` |
| `native/third_party/signalsmith-linear/LICENSE.txt` | P15/P16 | asset / configuration / specification | `072b5e9eb5b22880bdf6256324654cf3ce53dba828edb911921121e1be09d9c3` |
| `native/third_party/signalsmith-linear/approx.h` | P15/P16 | implementation / contract | `bf6f703193253a241e7a80d44547bb73c383a9a1f1ebe1f7fefdcf3d234515bd` |
| `native/third_party/signalsmith-linear/fft.h` | P15/P16 | implementation / contract | `fb55f69ac14cdbac66b9c76053069ff63bc36d18af20d8c028b9acc0aad6516a` |
| `native/third_party/signalsmith-linear/linear.h` | P15/P16 | implementation / contract | `bf0d23a37efd4d89e5baf2b2969374748f23690697a5158774feb0d00cf9502b` |
| `native/third_party/signalsmith-linear/platform/approx-cmsisdsp.h` | P15/P16 | implementation / contract | `ec2e21691147246ad3a1ae95804decdc6adba6197596b38aac4e2cc2de64feb4` |
| `native/third_party/signalsmith-linear/platform/basic-fill-warnings.h` | P15/P16 | implementation / contract | `c2ebbedbbbcf4b13fa236404a249d418a07f6edfc5ef2effd08d8be1e594b02e` |
| `native/third_party/signalsmith-linear/platform/fft-accelerate.h` | P15/P16 | implementation / contract | `969751cc7f800bf83bd5511a9e9293172465d931f6f3a8adec4ddca15c859fa0` |
| `native/third_party/signalsmith-linear/platform/fft-cmsisdsp.h` | P15/P16 | implementation / contract | `def81ee9bdf261c39b44bef8d2e2e6873844f5d7b02373fe6b8b845685a02f54` |
| `native/third_party/signalsmith-linear/platform/fft-ipp.h` | P15/P16 | implementation / contract | `e0c212f625b7990d520b45d22120d409d535989caf4c92e5fb874dc06adcaab7` |
| `native/third_party/signalsmith-linear/platform/fft-linear.h` | P15/P16 | implementation / contract | `7c2ed61df7895af7b1a37f1073f833abdc971953277ece58128c9c7b4ca6cef2` |
| `native/third_party/signalsmith-linear/platform/fft-pffft-double.h` | P15/P16 | implementation / contract | `359cd0108c2e470b3641aea216747b18bd56a367615fbb185bbbe5d1ab23566b` |
| `native/third_party/signalsmith-linear/platform/fft-pffft.h` | P15/P16 | implementation / contract | `28b83cc0d5b680b1f85915a5ea9ddd97308a106e8632a797d360b423e6bc62b2` |
| `native/third_party/signalsmith-linear/platform/linear-accelerate.h` | P15/P16 | implementation / contract | `36fdc4a431847cdfdb23c80f7331b4e41a438a6274efcf1d112d5d7baa240283` |
| `native/third_party/signalsmith-linear/platform/linear-cmsisdsp.h` | P15/P16 | implementation / contract | `d21a8491444b2d00425c684a10eabc33c5504b3549d604fae1c34a409e6a2549` |
| `native/third_party/signalsmith-linear/platform/linear-xsimd-dispatch.cpp` | P15/P16 | implementation / contract | `c2a2b8c657be023aaefa1ff0003643c7dd9f4e0d6db588279dd481e2932ee86b` |
| `native/third_party/signalsmith-linear/platform/linear-xsimd.h` | P15/P16 | implementation / contract | `bf99af5932b37960c49c68af32ff19dfc7ff648e44d76799259fbdbbbaef483e` |
| `native/third_party/signalsmith-linear/stft.h` | P15/P16 | implementation / contract | `20f3695bb8d162cf4b3f1aacdca477f0c8a301ffebd6fec53b437e9ce623462b` |
| `native/third_party/signalsmith-stretch/LICENSE.txt` | P15/P16 | asset / configuration / specification | `ee2ef82481ffb445ecdd4b3a4c1f82c0cddb2da8fe39d8e8dc384fffb3e7f06f` |
| `native/third_party/signalsmith-stretch/signalsmith-stretch.h` | P15/P16 | implementation / contract | `1188667959ac19dd40c0a6abbce694e44705615ec4f0e8db8af0f1cfb4c5dea7` |
| `packaging/aur/.SRCINFO` | P37 | asset / configuration / specification | `e4fb2e2bcd0eb06e7f8ead7efc3c85bb4ae45531500eb5dd7f3ca3fba4d31592` |
| `packaging/aur/PKGBUILD` | P37 | asset / configuration / specification | `cbbe4e4f193649c30a3577f56655774df77bb56407f6718ee23b57f1ec197aec` |
| `packaging/aur/README.md` | P37 | asset / configuration / specification | `2f330c1d3d7ff3b6603c153402ba27e301e2bc3c1184cbbb5f5690d7a398766a` |
| `packaging/aur/prepare_release.py` | P37 | implementation / contract | `3ee9f5d03f4b8fdefc748d04e3039caac1fb63d7ee554b68f1f220b89a086b2f` |
| `packaging/aur/test_prepare_release.py` | P37 | implementation / contract: PrepareReleaseTests | `2576c19bae37b635ee487715f1e2ba16de179fbfadc620e4e7c5d90ef1dd3436` |
| `packaging/aur/test_update.py` | P37 | implementation / contract: AurUpdaterTests | `0d100a52024cb1e612c35e469ec424d2d0eb3424ab041014d774c0beb4c1662c` |
| `packaging/aur/update.py` | P37 | implementation / contract | `136f2f1f38f410f12728815e5b08a40e1cde5b932cf029593c70463e7bddc7c4` |
| `settings.gradle.kts` | P01/P02/P05/P06 | implementation / contract | `8b8d6ce45b5cf8f72d2b8745d9f6d13249d82e30ee5ae1d029de5d4cf6b88107` |
| `shared/build.gradle.kts` | P01/P02/P05/P06 | implementation / contract | `67ec5313b3901ce9e5613e9a1a5671f5bab873f0a8a17403c6592a7712f03e26` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/listentogether/JamInvite.kt` | P34 | implementation / contract: JamInvite | `fb7a620ac4e70a408c78505ab08ad248196dda6b5b4796983bcf3cc8367fb5a9` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/listentogether/PartyModels.kt` | P34 | implementation / contract: PartyTrack, PartyMember, PartyPlayback, PartyQueue, PartySnapshot, PartyPreview, PartyPreviewMember, PartyMembership, PartyActivity, JoinRequest, ApiError | `818ef35a4ad4fdde01e629a433a09eac1fc9c3846349c21d7660444ceccdd929` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/listentogether/PartyQueueReading.kt` | P34 | implementation / contract: partyQueueIndexOf, partyUpcomingAfter | `2e185b8d6d9ac1f231e36c33bbf221acd506410b9d91dbbf7930c0338a168738` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/listentogether/ServerClock.kt` | P34 | implementation / contract: ServerClock | `b859def34f2da82be4a575a03c12b07472998f5d6515f8c636dd20d7b3696f77` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/model/Models.kt` | P01/P02/P05/P06 | implementation / contract: QueueTier, ArtistRef, Song, String, BrowseType, PlaybackSourceType, BrowseItem, SearchResult, SearchFilter, ShelfItem, ShelfLayout, Account, AccountChannel, HomeShelf, shelfLayoutF | `e506df900a719bba071e5e93c67567031389eb1ec39d89c356dbd863abbfe880` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/model/PlaylistOrder.kt` | P01/P02/P05/P06 | implementation / contract: PlaylistMove, playlistMoves, longestIncreasingRun | `09a162092929d91ffb04336b4dc4f05637f99566668b4460e9a204069f5123fd` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/model/SpotifyMatch.kt` | P01/P02/P05/P06 | implementation / contract | `42869500c299c9927f8a0c6914bfa967130b3f9f8ab0c6c1ac189dee961d0375` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/model/TopPicks.kt` | P01/P02/P05/P06 | implementation / contract: WatchTarget, TopPickKind, TopPickMood, TopPick | `893efc8aaee9a53f60d112157136f04e11525bffc8cf9b24736c0cbe1dfcb745` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/settings/AutomixPerformanceMode.kt` | P15/P16 | implementation / contract: AutomixPerformanceMode | `da041c3548bb9322769cf19856150eaec6bc157f983a42401a3e69dca809b1a7` |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/settings/TrackAnalysisState.kt` | P15/P16 | implementation / contract: TrackAnalysisState, SmartAnalysis, TransitionWindow, MixBlend, MixDeck, MixTransitionView | `49d587e46e5fe65e82a8201fdc09bba54cdc6931249c39623fd40c3f66303ad2` |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/EqualizerCurve.kt` | P11–P14 | implementation / contract: FilterKind, FilterSlot, EqLayout, EqCurve, manualCurve, toneCurve, preampFor, responseFrequency, sectionGainDb | `d446357392bb8c05cdcb848ab284753f9efd4063924509dfc0ac88675fb74ce5` |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/EqualizerPreset.kt` | P11–P14 | implementation / contract: EqualizerPreset | `05239c9152db5c010497b9abcd74d29ae596ec4cb7828c81978c431d732a4122` |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | P15/P16 | implementation / contract: AnalysisLog | `cb74dd804280aae65f8efcce196d4200b783572c6b880514eec87243e12f2268` |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AutomixAnalysisSource.kt` | P15/P16 | implementation / contract | `7db01a3d27c3fed5f30066751c1872535027ea31e50da59ba871cb2f94d09c9b` |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/BeatAwareTransition.kt` | P15/P16 | implementation / contract: transitionPrefixFrames, lateTransitionFallback, overlapHeadroom | `142a15b4ca43a01e13c24b855d2b5d5f61cb61983d074e12fbfb5f0b87db0abc` |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | P15/P16 | implementation / contract: LoopMix | `639415c950b1f1ca3a8b64e9c31b69adb838eabd7a814c7e058762bacd0755c8` |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | P15/P16 | implementation / contract: TrackAnalysis, EnergySample, MixCandidate, RankedMixCandidate, MixOutAnchor, TransitionPolicyVerdict, TransitionTier, BeatSide, CurveWindow, levelFromRms, rmsFromLevel | `b4b61d8975904a5ff65ef96abd179ca260d1c108800ca088972c4a3630351ed8` |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | P15/P16 | implementation / contract: CrossfadeMode, TransitionTrackInfo, isSpokenOrLive, isSpokenOrLiveForTest, TransitionStyle, FilterTransitionVariant, TransitionPlan, blocked, trackDurationSeconds, itemText, isUnmi | `26b6316901f950001578a49ff7771dd6285f5b2dd310eee1c2d9db05abfc9e99` |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | P15/P16 | implementation / contract: incomingRateBetween, stretchReachable, heuristicVocalOnModelScale, alignTempoOctave, vocalActivityBetween, isVocalClash, vocalOverlapAmount, simultaneousVocalFraction, audibleSecon | `5369860876566d7ccc92f041a299dd47a5437dc5c80b53a2a7420b6b841cd089` |
| `shared/src/commonTest/kotlin/com/music/bitchord/data/listentogether/JamInviteTest.kt` | P34 | reference test: JamInviteTest | `40422c8c80de92ccf1a6d6ceffb30407893fe6b206b42a1862327d5892752bbe` |
| `shared/src/commonTest/kotlin/com/music/bitchord/data/listentogether/PartyQueueReadingTest.kt` | P34 | reference test: PartyQueueReadingTest | `d23dcc4c578c0b1f4e3fcd0fc382e0361703b49ff4c85d116be824c9bad30fe5` |
| `shared/src/commonTest/kotlin/com/music/bitchord/data/listentogether/ServerClockTest.kt` | P34 | reference test: ServerClockTest | `2dd4223b0a5ee3d2bed5759cd76756c0f79a396835f4f0bc00c7dd8d7c9f13e5` |
| `shared/src/commonTest/kotlin/com/music/bitchord/data/model/ModelsTest.kt` | P01/P02/P05/P06 | reference test: ModelsTest | `a75afa17ef55737b169fb7eea9758d90ea154205074077ccc3390966bb3f169e` |
| `shared/src/commonTest/kotlin/com/music/bitchord/data/model/PlaylistOrderTest.kt` | P01/P02/P05/P06 | reference test: PlaylistOrderTest | `ac891684860a7ac8c04e4b69db0bd72bb4c7bc4a0ea53ac2edd90af86ca5c508` |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/AdvancedAutomixPlannerTest.kt` | P15/P16 | reference test: AdvancedAutomixPlannerTest | `b057f0bcd24ae09fbbf59a69e1d0c65273e41eea5614d129e420479819be388c` |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/BeatAwareTransitionTest.kt` | P15/P16 | reference test: BeatAwareTransitionTest | `a192f9240150197321fb7a195b9f3a88900abab517682ac699e1dde862348618` |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/LoopMixTest.kt` | P15/P16 | reference test: LoopMixTest | `b1c4f839c88c307eacb4d6afab979b8064ac46d6c23b9e25ea098970dfc54801` |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/PhraseBarAutomixTest.kt` | P15/P16 | reference test: PhraseBarAutomixTest | `685d0d99c5dd6706334d60d3faebeb49c379de08a028db1cb8883e6cc554e273` |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/SpokenOrLiveTest.kt` | P15/P16 | reference test: SpokenOrLiveTest | `560cd64aa1ccdd65d4670d92202e5ebbef5b3d0cce3d22a38ccde4aa79d49183` |
| `shared/src/jvmMain/kotlin/com/music/bitchord/data/spotify/SpotifyImporter.kt` | P20/P21 | implementation / contract: SpotifyImportTrack, SpotifyImportResult, SpotifyImporter | `32b5c5ac66e550f3a0bfdb3f989eae0b8a6eb3f30b0de849318d564bd12be41d` |
| `shared/src/jvmMain/kotlin/com/music/bitchord/data/spotify/SpotifyLibrary.kt` | P20/P21 | implementation / contract: SpotifyPlaylist, SpotifyTrack, SpotifyLibrary, parsePlaylistPage, parseTrackPage, parseLikedPage, largestSource, coverUrl, JsonArray, JsonObject | `f43141ab9eae36bb108adb2cbc9a8832ced016c91bcc286d6995d5f1e9688584` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/auth/DataSyncId.kt` | P01/P02/P05/P06 | implementation / contract: normalizeDataSyncId | `f254c4f9ec003a84164603344aa1457c30b6b42c4b1cb0c9762a11e40f2ca600` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/AppleArtistPhotos.kt` | P25/P26 | implementation / contract: AppleArtistPhotos | `72b7302d31058cb4af109b8f603a9d92d7935987efec77566423244bc125419a` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/AppleCatalogue.kt` | P25/P26 | implementation / contract: AppleCatalogue | `a442b2c91fc109ada983dc4c4153b68c2f109c1680c55c7f1dbb088f86f4b512` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/DebugLog.kt` | P38 | implementation / contract: DebugLog | `cc4777bad0416a833d50e7f0a8e47b57d5ab357ab114f1bbc1073b232033152a` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/Http.kt` | P01/P02/P05/P06 | implementation / contract: Http | `d6d0b70452cbf67e6e69ad44f1004a7d2a39b0037be9c9d1fb3de5410357cd76` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/LikeState.kt` | P01/P02/P05/P06 | implementation / contract: LikeState | `417766bfa3df2e362d6a530c02ee8a53557ebca675868760b31640b782c400e9` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/LocaleTags.kt` | P07/P08 | implementation / contract: LocaleTags | `2292e3c39b2737b7d5383ff531fa69b782d7350ab8d3bd3c4444ac858dbda36d` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/MonotonicClock.kt` | P01/P02/P05/P06 | implementation / contract: MonotonicClock | `6abe41e2f8fefb58d9ec2c3bb1ad6a6d6191e85c064b6fb5f9dba5ee5acfc018` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt` | P01/P02/P05/P06 | implementation / contract: TelemetryProvenance, NerdStats | `b3bd8dcc7b41874699e62c91ee0166b210555d9796d74c198f89142c88b645c6` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/TopPicks.kt` | P01/P02/P05/P06 | implementation / contract: TopPicks | `7315a7f6b0d67402a4fd2680903af3691ca2cad288a37fe1c2524f4a00163016` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/TrackLog.kt` | P38 | implementation / contract: TrackLog | `547bafee3e56482682fc436534bad9ce52261e38f4a004265dfe365cdf0726fd` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt` | P01/P02/P05/P06 | implementation / contract: YtMusicRepository | `493549fa493f66458f96b766262f94c502c4fc48d9a391640c5e1f9a73a6434d` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/CanvasArtwork.kt` | P25/P26 | implementation / contract: CanvasSource, CanvasArtwork, String, splitArtists | `3f402efe10c7f026764e9ecb6b2783fa7821b084e322def9684bf74d5ff2f843` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/SpotifyCanvasQuery.kt` | P25/P26 | implementation / contract: SpotifyCanvasQuery | `9fe776bc406901651d94c44a2cd0fbe58199b8a03a52360b87af5e0a309b6d26` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnerTubeXResolver.kt` | P17–P19 | implementation / contract: InnerTubeXResolver | `b3ff38aed1e49347047dcf3916c28b0fb4cbc7cfd071e31998c42f4ebb66d29f` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/Innertube.kt` | P17–P19 | implementation / contract: Innertube | `ef8339616d45915ed2594659e5d771205a7d79416c1ed8359c2bd048d02ad955` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt` | P17–P19 | implementation / contract: InnertubeParser, JsonElement, JsonArray | `846d3818e233bce07799197880629f5ffc60d4f9658bd23ae31fdf48849325ae` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/PlayerClient.kt` | P17–P19 | implementation / contract: PlayerClient | `b42afb45ff61c2e3c203b721b9bf09f4cf658865b417ce68e7970c96d481dc1b` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt` | P17–P19 | implementation / contract: StreamResolver | `0b9d0f35a2619aff002b3855028bfc632077e572e5874119942cd10c2f4a9436` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/potoken/JavaScriptUtil.kt` | P17–P19 | implementation / contract: parseChallengeData, parseIntegrityTokenData, stringToU8, u8ToBase64, descramble, base64ToU8, newUint8Array, base64ToByteString | `ee337d403a18b73be2345376ea9a677cfb35477fb2405665f17528c25def54fe` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/potoken/PoTokenException.kt` | P17–P19 | implementation / contract: PoTokenException, BadWebViewException, buildExceptionForJsError | `e2f1085b5a5ee6fea44a64a61fbb91a51ff8e7e734490c6609d25260e4e6bc91` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/potoken/PoTokenGenerator.kt` | P17–P19 | implementation / contract: PoTokenMinter, PoTokenGenerator | `98ffdd4439f6e457633161d8a3e54ace21afc38242fd63a1b7bfacf61d42fbe5` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/potoken/PoTokenPage.kt` | P17–P19 | implementation / contract | `34a257ca000e04869388cda4c237bc2386515c78e95dafd5b8f5c63b52af180f` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/potoken/PoTokenResult.kt` | P17–P19 | implementation / contract: PoTokenResult | `c914f4ccf05dfb12d9c247d1a7a567a69ac0d8699ba5dd732d9f480699af2c76` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/BackgroundVocals.kt` | P22/P23 | implementation / contract: List, LyricLine, bracketStart | `11c19485335a0d665ec6e08fd6d806d49ec4771b57f3f044eef73b8fd83254f3` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/BetterLyrics.kt` | P22/P23 | implementation / contract: BetterLyrics | `b2a69dcc5d49f0a27a4f391ff4a6f2aa2f8f7b5db121da2ffc382225a2f7f2a8` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/BiniLyrics.kt` | P22/P23 | implementation / contract: BiniLyrics | `071f2dbaf55b75c5124dcb4a23ac3219d27083d1fd96346979cb4fdf68a1a137` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/EnhancedLrc.kt` | P22/P23 | implementation / contract: EnhancedLrc | `4576fd002f1de7c3e3b2a37741a990d454b5087d973512626ab7f20a17c050ef` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Genius.kt` | P22/P23 | implementation / contract: Genius | `8ad8b28f27c94982a6fc7bde9e591ce1ed122b673259d4885dda5082a8347e2c` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/KuGou.kt` | P22/P23 | implementation / contract: KuGou | `130c49927172995b9187139747a2f5be2dca1b9a4b2d1a5d665b378bbb76f6d6` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcLib.kt` | P22/P23 | implementation / contract: LrcLib | `e8ed3e98e9b3b42448ff69830e8f06c677d98ac956e91a631954a5dff0bfdba9` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcRed.kt` | P22/P23 | implementation / contract: LrcRed | `29d2de643c588d42b4950589924db8b744cb78bd245f9707791c4cf070f67ffc` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcWriter.kt` | P22/P23 | implementation / contract: List, LyricLine, stamp, wordStamp, clock | `a1acf30509b091348533da7f3a3a0e50b73840b511ee381da183ff2c57fb292f` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricAlignments.kt` | P22/P23 | implementation / contract: lineAlignments | `b988417827d8b79ce497a3a749b002e7128de40075db7aab293c61d4550f7f2d` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricGaps.kt` | P22/P23 | implementation / contract: List | `1d05a764c8681193552888947223ac9addade01299d00d3c457fb116732024fc` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricLine.kt` | P22/P23 | implementation / contract: LyricWord, LyricSyllable, LyricAlignment, LyricLine, GrowingWord, CharGrowth, liftHeight, smooth, decayRate, Char | `9c5627b46c71914f0562336190cc33d515411f2b3da3eb55d4397f9c61c4e22e` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricSections.kt` | P22/P23 | implementation / contract: isGeniusSectionHeader | `ff4c363e10619d9d087c2443d15d86564b8343406b6401d24b187d840f808f5e` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsHttp.kt` | P22/P23 | implementation / contract: lyricsGet, lyricsGetBearer, lyricsGetAuthorized | `8fdc7f17c53cbfd41a3de695baeaee21e0d484271cc0e2630e22969388b3e51f` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsPlus.kt` | P22/P23 | implementation / contract: LyricsPlus | `e6e4f82dafe01fca62d25bcb0c450ecf16e944dec0f9c9235c6fc9c17e1ea569` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsQuery.kt` | P22/P23 | implementation / contract: String | `f44fa1af872df1de7e1e97bd420ff44793aa5e344e423a547f8ef329d22ec7ef` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt` | P22/P23 | implementation / contract: LyricsRepository | `644a00624a63cc1bd846931f5aff8a4dbe5696c32537dc66875b762750152de3` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsSource.kt` | P22/P23 | implementation / contract: LyricsSource | `5e7b41a75d55b27e11ac24b7f76bbc5b171378fb7ff61a9387aa6c98634fa383` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt` | P22/P23 | implementation / contract: LyricsTranslation | `a90a3fb999fd26061f8c2fe60fbf0a0f7b5c04d78f8b1d9cdaa136741e89fac9` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Megalobiz.kt` | P22/P23 | implementation / contract: Megalobiz | `2077036b94e36479147f24348091396aca7cae8a879b81f9881a6891e02be6cd` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Musixmatch.kt` | P22/P23 | implementation / contract: Musixmatch | `49729ff9152879f378ab6e53ee1e8908c537328386a70965cf49d097e85f1162` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/PaxSenix.kt` | P22/P23 | implementation / contract: PaxSenix, normalizePaxSenixApiKey | `356b47ee1025173e2b35fd997d8537a7f866eb766248afbfb8711f2c8d15feaf` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/ProviderLyrics.kt` | P22/P23 | implementation / contract: ProviderLyrics, KaraokeLrc | `68f451c193bd307dc8cb6d26cf8330f2ed755d71aae4d792457dc8a836804931` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/SimpMusicLyrics.kt` | P22/P23 | implementation / contract: SimpMusicLyrics | `86b1765c4d2d5c7dd24567b47b9830ae906a62b65bd87eb5126b94ec6f80a120` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/SyllableRuns.kt` | P22/P23 | implementation / contract: TimedRun, wordsFromRuns, Char | `46e3ee2e1789085b87122044cf885265e1526ba7e3e8f40ab065b3c25c973390` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/TranslationLanguages.kt` | P22/P23 | implementation / contract: TranslationLanguage, translationLanguageName | `bf8a7ac39884008a75ce561e7bdc22acc7ef5c027c2adb2dc8dcddf615b4c159` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/TtmlLyrics.kt` | P22/P23 | implementation / contract: TtmlLyrics | `d6b665f991014801a5d7057080d1ebf9d3c7ef71661d726916a7a2f28e7fc148` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Unison.kt` | P22/P23 | implementation / contract: Unison | `983df086549c39c3303adbd05114b8d82170ca8dd595ee0a46e61b36a9bb1cde` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/YouTubeLyrics.kt` | P22/P23 | implementation / contract: YouTubeMusicLyrics, YouTubeTranscriptLyrics, JsonElement | `24720485febf4a5e3851a26836211e46f04d1e8d88c1360ecea77324ac3e86db` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/model/ArtistCredits.kt` | P01/P02/P05/P06 | implementation / contract: normalizedArtistName, isExactArtistMatch, ArtistCredit, CreditSegment, creditSegments, String, ArtistNameIndex, completeArtistCredits, fragmentsOf, fragmentCutter | `e5567ef1e0f60699b9ddf7e28e996fe36f99ef508b88bb50e440487e63bb1c89` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/model/SearchHistoryEntity.kt` | P09 | implementation / contract: SearchHistoryEntity, EntityType | `413a965abba0ffa653d473713ffe0a578ccc4ca7994ac230cff52f5ac7f334b7` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/presence/Presence.kt` | P32/P33 | implementation / contract: PresencePlatform, Presence | `0fa49f9ba634f44c0b671e5f7cce09e4193cf230501eb727038610d3be2be0e2` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/settings/LibraryEnums.kt` | P29–P31 | implementation / contract: LibrarySort, LibraryViewType | `a87d1bc0299b8b669655a3bf11e77991c1d01c51b1454416ae4817f3c8fca623` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/settings/PlayerEnums.kt` | P29–P31 | implementation / contract: AudioQuality, PlayerTheme, LastPlayerScreen | `dea3c6c68ded7ed8a120a2f3657f4d9807d6b96675bc08d12f3c7108077d5821` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/SourceTrackKeys.kt` | P17–P19 | implementation / contract: SourceTrackKeys | `4aa34b9c937b0befb448d28507c2481bd1803acb4472736ee61da1f4ef9edeb6` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/StreamFormat.kt` | P01/P02/P05/P06 | implementation / contract: StreamFormat | `7ccfa1bda735bbb5cc177d10f29a6612e362547e3f85927f168ce511fe187fd1` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt` | P17–P19 | implementation / contract: TrackMatcher | `2fa30f66eac634f462ec3b7a6bf23209a29eda44a6e8e0df4ea5448d89aeb50f` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/webdav/WebDavAuth.kt` | P20/P21 | implementation / contract: WebDavAuth | `e578958689dba130e53016cccfed583a521b9474b965d1e1ded28be1afea2e5e` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueBuilder.kt` | P11–P14 | implementation / contract: QueueBuilder | `0259a48a1d21f46f6fef0ea939a872fe205d117a68883fce238316eb645ee96f` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueSections.kt` | P11–P14 | implementation / contract: autoplaySectionStart | `4d156555ee755cac0897c84d9f2f71dd32193d653b7f804c35ed3baaeba23110` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueTimeline.kt` | P15/P16 | implementation / contract: QueueSource, ContextQueueResult, QueueTimeline | `d796020aee30c079abfbf745d2e77810b3579162fcaa4589f88b80759275c835` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/TransitionFilter.kt` | P15/P16 | implementation / contract: TransitionFilter | `8ae4db7c945cc7cc86a0e5139c5e834c3ee8b9d97dc30e202828176ee794349a` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/AnalysisStore.kt` | P15/P16 | implementation / contract: AnalysisStore | `3b4ade20175d9196203f24c30a0ca2951371660032884116cfa78a885317ed9f` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | P15/P16 | implementation / contract: BeatTracker | `af07fc4fea10427d7319502ce0a78d348f3e437d175b569b599b8cc4c168bf8b` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.kt` | P15/P16 | implementation / contract: MelSpectrogram | `bb930c4797433a5089ebe3ecb9cbc528ee8d9bf3f14c4759584bcb641f1889f0` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NativeAnalysisLibrary.kt` | P15/P16 | implementation / contract: NativeAnalysisLibrary | `15e9eb16f43d5fa797c6eb67ad912c886f0bb3df976c6aa8fc7a7545d150e1fd` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/TrackFeatures.kt` | P15/P16 | implementation / contract: TrackFeatures | `28a380a933d84fa5740362ee89bddc80d1f2a8c6ffc1a18ff808cb895f2728ee` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | P15/P16 | implementation / contract: VocalSpectrogram, VocalTracker | `a9880bb69335d8d394f8da2aa94c6dd3138bb7b6f670a5b89cb266b8f3fbab6b` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/DiscordWebSocket.kt` | P32/P33 | implementation / contract: DiscordWebSocket, elapsedRealtimeMs | `6acf262798ec934376150d45a7404ce37ee473b12e91caf0765565e855f7cb9e` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/HeartBeat.kt` | P15/P16 | implementation / contract: Heartbeat | `8001fad81da7b73e00057382cfe57a91edbbe013bcd2e9864309786fb0123dcd` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/Identify.kt` | P32/P33 | implementation / contract: Identify, ClientState, Properties | `a0d1ba43ecda59d15a21e5831b4f3ececc3c449d13a283076faca1be445b621a` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/Payload.kt` | P32/P33 | implementation / contract: Payload | `b1f99a4bb97a96d847501e8867f4ce61c3fbd76ee647cbc63b0ac31bd6a365ca` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/Ready.kt` | P32/P33 | implementation / contract: Ready | `4d4744f3dc437120d73cd42a5baa3f21038dfd43178c0d188427989cf4b7eee1` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/Resume.kt` | P32/P33 | implementation / contract: Resume | `077d9f87d6a209294a85225ed18fa54b48bb8bf4891da075362064a6163c0686` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/op/OpCode.kt` | P32/P33 | implementation / contract: OpCode | `e4f6c43f29b3535eddf880eb1c391ac50e222cafbb4bc53ea901145e8ccda103` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/op/OpCodesSerializer.kt` | P32/P33 | implementation / contract: OpCodeSerializer | `c5214f1387466b009b81c8b701ad772f80ff975291fd770e6f46bc1485bdefef` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/presence/Activity.kt` | P32/P33 | implementation / contract: Activity | `10d65e32ac383ace1dddd18bff73c6a3ff2d215f9665a3914463e8ad93af4ec4` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/presence/Assets.kt` | P32/P33 | implementation / contract: Assets | `9dafd92962604265e909aaccf0692dce9426f3149570891a8cb086c4cc460ac8` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/presence/Metadata.kt` | P32/P33 | implementation / contract: Metadata | `42ce93a87737716c98b1832de2a22222d54365016ab68f7b55d1458a9c830665` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/presence/Presence.kt` | P32/P33 | implementation / contract: Presence | `a7057b491a901385e8bb0d5e09405d1168ed55424f51fc67618c8f7d6e292067` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/presence/Timestamps.kt` | P32/P33 | implementation / contract: Timestamps | `f2d3a536bbeeee3b0963062c542fa4bff018244dbee982f1a125923e42fcb65f` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/rpc/ArtworkCache.kt` | P32/P33 | implementation / contract: ArtworkCache | `8278b0efa64abd803d646ef2721a7be99211c75b3f0cb0ce5724c14e69bd83c8` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/rpc/ExternalAssets.kt` | P32/P33 | implementation / contract: ExternalAssetResponse, fetchExternalAsset | `d717ef2da7c40462b786267ba7296d9e8facf9d1a69fb9a202424cef08451170` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/rpc/KizzyRPC.kt` | P32/P33 | implementation / contract: KizzyRPC | `1b2bc246c68a537d12c8f1ebdb5616690ee4c020b05b67d8e48a8141b8eb64ec` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/rpc/RpcImage.kt` | P32/P33 | implementation / contract: RpcImage | `337ca067029611831c1f6360442e04f04897994f9b7a15d8d27fca35147b6d25` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/rpc/UserInfo.kt` | P32/P33 | implementation / contract: UserInfo | `f8b01998933f8e4b8a3f7608970c2b2ad377983ceed5ab42fbf4a899df08437e` |
| `shared/src/jvmSharedMain/kotlin/com/my/kizzy/utils/Ext.kt` | P32/P33 | implementation / contract: String | `79ab8e6ade21096e244e624f162bb6311bbe65beb6f6b91c6a3fbd7828ba67e2` |
| `shared/src/jvmTest/kotlin/com/music/bitchord/data/presence/PresenceTest.kt` | P32/P33 | reference test: PresenceTest | `dee3fda64b8671ee78dcefbb7cd057b5b961030771aab97adfda33a043cc2bd5` |
| `shared/src/jvmTest/kotlin/com/music/bitchord/data/spotify/SpotifyLibraryTest.kt` | P20/P21 | reference test: SpotifyLibraryTest | `07e30fb55d9d142cdd226fb0ccb91ae110fd2a0ac60cd6b69703140dbff5dc22` |
| `sharedUi/build.gradle.kts` | P01/P02/P05/P06 | implementation / contract | `0204f57346ab6306342b00eac989ff2148f8bfb6527bd2e5e93561638da0243a` |
| `sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/components/BlurredBackdrop.android.kt` | P29–P31 | implementation / contract: rememberBlurredBackdrop, BackdropBlur | `6b4b01b6a0e3c22b2d48764ff0182330ebda92bd89bd56d399c9b9aa15c42704` |
| `sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/components/ContextClick.android.kt` | P01/P02/P05/P06 | implementation / contract: Modifier | `b64a519b3dab3e8ec7fb97f9a1c197b2090b7e0894ea28489eefe0d9ad9e9b95` |
| `sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/graphics/ArtworkPixels.android.kt` | P29–P31 | implementation / contract: ImageRequest, Image, imageBitmapOf, paletteSwatches | `a8e4c8f3926438b69c4ae0e3c9fa4c4657b204d05b2f2dcfac597568781e6d56` |
| `sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/haptics/Haptics.android.kt` | P01/P02/P05/P06 | implementation / contract: Haptics, rememberHaptics, Beat, rhythmOf, HapticDevice, Primitives, TouchVibration | `074cb0c645dac155555bbd57378b0ed027b8f025771d145daaa2999c2bc683ab` |
| `sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/player/PlayerPlatformUi.android.kt` | P24 | implementation / contract: uptimeMillis, KeepScreenOn, appLanguageTag, PlayerBackHandler, OverlayBack, DrawScope | `986f2e05b1600a640d3d9ec5fa3fd14db439163910d1018717a6f773e21b2048` |
| `sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/theme/ScreenOrientation.android.kt` | P29–P31 | implementation / contract: ActivityOrientation, NoOrientation, rememberScreenOrientation | `3bec19c41a6e5e1528b35ce8f58f7df509ac3005c35e4a803516a8ad4bd8c1ee` |
| `sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/theme/StatusBarIcons.android.kt` | P29–P31 | implementation / contract: StatusBarIcons, HideStatusBar, HideSystemBars, stableStatusBarInsets, findWindow | `e5566a58b43104aeeb79aa6d3cdd753258db84b9907cab06d595fbd049cd7d12` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_dolby_atmos.xml` | P29–P31 | asset / configuration / specification | `d04f13413c9c128a0450f9397f1c41e1d8a7cd2f75e712d574cd18d4fd5adcc4` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_google_g.xml` | P29–P31 | asset / configuration / specification | `37c12c24b11c018369d170f93a7decfff7c5b97bda1c4947f6f749983eb04783` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_logo.xml` | P29–P31 | asset / configuration / specification | `c415ff9cd30a0d249da442946e31e05c4bd19380ec64fa66a6015f3d42219bad` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_lossless.xml` | P29–P31 | asset / configuration / specification | `ddd028ac0c35d7c130127b1ba7372d43b1f7d9fd809979c08aa01b3ba88a0172` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_output_buds.xml` | P29–P31 | asset / configuration / specification | `49113eaaba8f10a2eb6ebdab226742c82c1b7262db0cd266da9c4e78ce088552` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_output_phone.xml` | P29–P31 | asset / configuration / specification | `45ef3a10db2276e02c3dd83eb0b15c3d2a5d85e92d13fb79c4206a034d581f9b` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_output_speaker.xml` | P29–P31 | asset / configuration / specification | `579d521ec0ff67692dfce11d228c44a69875ba529e794b738e0c147766139976` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_player_next.xml` | P29–P31 | asset / configuration / specification | `4f4e6d1e43e1f6bec7a9de596394ddee70e2d28e868283c3aa4d6f54ae45bbc8` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_player_pause.xml` | P29–P31 | asset / configuration / specification | `f2279435c4f5e277236a2208087e74355209391db44d2766deaae0ba51c6f24c` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_player_play.xml` | P29–P31 | asset / configuration / specification | `decaa6580e3def2920b6260fd032788577c85353b9cb94110b38231c2b2f7f8e` |
| `sharedUi/src/commonMain/composeResources/drawable/ic_player_previous.xml` | P29–P31 | asset / configuration / specification | `8cadb83d4584ac761479ee2dcd9bb8a67da93add763d9fc18a74d7501f092d58` |
| `sharedUi/src/commonMain/composeResources/drawable/spotify_logo.webp` | P29–P31 | asset / configuration / specification | `c27c022857a854141d8727a8ec36160d8848bf4acbcfe99286f45d9f16392599` |
| `sharedUi/src/commonMain/composeResources/values-de/strings.xml` | P29–P31 | asset / configuration / specification | `31676ec8a6cd1397edc36c53c330019f31095ef13246f4acad6e9a678d74f088` |
| `sharedUi/src/commonMain/composeResources/values-es/strings.xml` | P29–P31 | asset / configuration / specification | `553fc6c484a98d2ffcc4e8bf14ac33382547d177bd98a7ba97075d3293cc6704` |
| `sharedUi/src/commonMain/composeResources/values-fr/strings.xml` | P29–P31 | asset / configuration / specification | `82400cab2a35759117c80971d5a330b2749f157ea88fd8b45479d69b2b5aa371` |
| `sharedUi/src/commonMain/composeResources/values-he/strings.xml` | P29–P31 | asset / configuration / specification | `81d7182724b50791ac6f4ee56bfd18d400dca979148367499bf389f859ab6de8` |
| `sharedUi/src/commonMain/composeResources/values-hi/strings.xml` | P29–P31 | asset / configuration / specification | `f4767290d2c900d0df4588c96ff55b21ef07324249a2d9c29d48b93444073eab` |
| `sharedUi/src/commonMain/composeResources/values-id/strings.xml` | P29–P31 | asset / configuration / specification | `69a6f66e2949e78420d98862c08dec6f78bf75d27cff6fe647cb660b6b4db848` |
| `sharedUi/src/commonMain/composeResources/values-it/strings.xml` | P29–P31 | asset / configuration / specification | `8cab3682fe328bffd52180d8b8438c2648c431f87b4ab768c8b2be12ab0050e7` |
| `sharedUi/src/commonMain/composeResources/values-ja/strings.xml` | P29–P31 | asset / configuration / specification | `74d81f667de133428a5b1d61a1333a4ea2c8118695faf6ad86f679256c234e68` |
| `sharedUi/src/commonMain/composeResources/values-ko/strings.xml` | P29–P31 | asset / configuration / specification | `7091cb64628081bea32c272865dc7f25524b8521705c0cf41d5822e468aa8938` |
| `sharedUi/src/commonMain/composeResources/values-pl/strings.xml` | P29–P31 | asset / configuration / specification | `ed0a34752f482754c9dcbee3b2322a9c3e676ef031a14259d4845c211fc2b8ad` |
| `sharedUi/src/commonMain/composeResources/values-pt/strings.xml` | P29–P31 | asset / configuration / specification | `b6cb16f83374f371cd5e7f02f1a53cf71def135befe0809155abb4ecca5c64ee` |
| `sharedUi/src/commonMain/composeResources/values-ru/strings.xml` | P29–P31 | asset / configuration / specification | `8e9a17925b4291d9023c77c875f07b0d4f82765f944fbae84c6afff3c383a380` |
| `sharedUi/src/commonMain/composeResources/values-th/strings.xml` | P29–P31 | asset / configuration / specification | `93120344df4da738b3dc9e2bffdedc75ded0adc1fb123360ada5d3392f33412a` |
| `sharedUi/src/commonMain/composeResources/values-tr/strings.xml` | P29–P31 | asset / configuration / specification | `537d6a3b9ad9504c60a500e12b90dc54e33ada13a737a4ff7a3597467e4e41cb` |
| `sharedUi/src/commonMain/composeResources/values-vi/strings.xml` | P29–P31 | asset / configuration / specification | `2c5c1517961a96b64eb7b07cdf9e7c22258f71b5837ea15ab658154dd97600eb` |
| `sharedUi/src/commonMain/composeResources/values-zh/strings.xml` | P29–P31 | asset / configuration / specification | `399ccfc28aca7f7f8a064fd9d324db67497f7b118a27c5cc5e40ce11011331d8` |
| `sharedUi/src/commonMain/composeResources/values/strings.xml` | P29–P31 | asset / configuration / specification | `d796430dc5d7b29e5350dd3dee20f20e66140748df8452a4f5eb0b82ac3659ea` |
| `sharedUi/src/jvmMain/kotlin/com/music/bitchord/ui/components/BlurredBackdrop.jvm.kt` | P29–P31 | implementation / contract: rememberBlurredBackdrop, DesktopBackdropCache, blurReleaseBackdrop, blurBackdropPixels, boxBlur | `6917d2676de0062eb11cf9fd2ecc03bf52b5a5a6ac94602d44aec481fa7e2825` |
| `sharedUi/src/jvmMain/kotlin/com/music/bitchord/ui/components/ContextClick.jvm.kt` | P01/P02/P05/P06 | implementation / contract: Modifier | `83d5c23ab4804795d220458a62f2e663173e2381b2479e842adb57b3a1962198` |
| `sharedUi/src/jvmMain/kotlin/com/music/bitchord/ui/graphics/ArtworkPixels.jvm.kt` | P29–P31 | implementation / contract: ImageRequest, Image, imageBitmapOf, paletteSwatches | `a963d4be4afb811120567dea8489f43f58edebb53e8b573cbcdc377fafdd3e7c` |
| `sharedUi/src/jvmMain/kotlin/com/music/bitchord/ui/graphics/PaletteQuantizer.kt` | P29–P31 | implementation / contract: PaletteQuantizer | `5b5682e0c2239e45bec083dfe2b0ac1aed4c7c1ae0c81e4734c7667406527995` |
| `sharedUi/src/jvmMain/kotlin/com/music/bitchord/ui/haptics/Haptics.jvm.kt` | P01/P02/P05/P06 | implementation / contract: Haptics, rememberHaptics | `9a8b4a3001fbce8db995371dc00a38bf3e341433c417bd328a884121e48410dc` |
| `sharedUi/src/jvmMain/kotlin/com/music/bitchord/ui/player/PlayerPlatformUi.jvm.kt` | P24 | implementation / contract: uptimeMillis, KeepScreenOn, appLanguageTag, PlayerBackHandler, PlayerBack, DrawScope | `f2ff0980b9ec431a495622f0af12b08010bc7ab8809a74fbd988a54151146eb3` |
| `sharedUi/src/jvmMain/kotlin/com/music/bitchord/ui/theme/ScreenOrientation.jvm.kt` | P29–P31 | implementation / contract: NoOrientation, rememberScreenOrientation | `cf3d146369d8fc690b683e1a05b0455df03db49a79959ba2e0d1f89fdb3cbbb4` |
| `sharedUi/src/jvmMain/kotlin/com/music/bitchord/ui/theme/StatusBarIcons.jvm.kt` | P29–P31 | implementation / contract: StatusBarIcons, HideStatusBar, HideSystemBars, stableStatusBarInsets | `4d1c6fb8a505c715cd1e735b4e2ae8af0ff4317e8fa41a3c90956080ccc0ec0d` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/playback/PlaybackPosition.kt` | P11–P14 | implementation / contract: PlaybackPosition | `d83eedca52d8d0dada1d1644adf87f6e4f868a0929d2932d61b78be3afc847a0` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/AppUi.kt` | P01/P02/P05/P06 | implementation / contract: AppUiHost, AppUi | `60b73b0fc6224a7ffa1e66afa0fdb797802fda05c3fcf46c1d3ba6f349823bb9` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/ForegroundState.kt` | P01/P02/P05/P06 | implementation / contract: rememberIsForeground | `77900493d6d8db32214e826bfece08637901370f1cd29fc11ae6bf98b8681518` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/LyricsProviderState.kt` | P22/P23 | implementation / contract: LyricsProviderState | `db562f6681fcb80e8c2b18cb0e7230c3d9d788d4edee7d6eb667cf0baee68008` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/BlurredBackdrop.kt` | P29–P31 | implementation / contract: rememberBlurredBackdrop | `5d8de3e3592cf9342b79638f9212c20d2cf4305ec534f8849b35c14b0abc159b` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt` | P01/P02/P05/P06 | implementation / contract: ArtworkHoverMode, ArtworkHover, Modifier, VideoThumbnails, heroCardWidth, trackColumnWidth, LibraryGridSpec, libraryGrid, SongRow, QueueSwipeBackground, QueueSwipeLabel, SongRowCon | `fcac54c8c0c723f5bc3e2e79d31d30349ad70287e8daa6ec9fe671f311292a75` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/ContextClick.kt` | P01/P02/P05/P06 | implementation / contract: Modifier | `e483da91e0158e09ed26f582ecb28d2832047a7cf0e16f556d2bd2f8684fa0c1` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/LongPressOrigin.kt` | P01/P02/P05/P06 | implementation / contract: HeldItem, LongPressOrigin, Modifier, HoldCapture | `0b55cd635a62f0d49eb26b3f55c9aff82f38f061cea7c77973389b893d702aaa` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/OptimizedHaze.kt` | P01/P02/P05/P06 | implementation / contract: Modifier | `2ca1c680390d65e650060fcfd20600541fe63b3de189a6de182c3ceba082dee8` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SearchField.kt` | P04 | implementation / contract: SearchField | `23ceb4d3ef6a1ee5c7f0352552e3e10f2dafdb5d8ed23d3f278c01265c8afc25` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/ShelfRow.kt` | P01/P02/P05/P06 | implementation / contract: ShelfRowChrome, NoShelfRowChrome, ShelfRow | `c48a2bc382e15bc3436253b96b7322493bccfec41d88dcfc09b713358a647064` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Skeletons.kt` | P01/P02/P05/P06 | implementation / contract: ShimmerBox, SkeletonLine, SectionHeaderSkeleton, SongRowSkeleton, LazyListScope, HeroShelfSkeleton, RecentsHeaderSkeleton, ShelfSkeleton, ReplayCardRowSkeleton, DetailActionsSkelet | `870dbd2063564ce6a5e2bcc5b88b5c8f151add63af549aeec2693365243ad33f` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SongText.kt` | P01/P02/P05/P06 | implementation / contract: Modifier, ExplicitSongTitle, FadingLine, FadingText, ExplicitBadge | `506cd2275a4e02d2a641dfa04d02d1ce46326315ebf8a99dfb787aed541a171e` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/graphics/ArtworkPixels.kt` | P29–P31 | implementation / contract: ImageRequest, Image, imageBitmapOf, ImageBitmap, Swatch, paletteSwatches | `cb2224aada68ca29800cfb4198d7df784eb8b23e9a0a36cd8476ab24efb65b05` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/graphics/ColorUtils.kt` | P29–P31 | implementation / contract: ColorUtils | `ab9d5bc6bf0b889e7593927d0f615e420e0a467b5cbe3d6f24fa8032c2255b03` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/graphics/FadeCurve.kt` | P29–P31 | implementation / contract: smootherStep | `47ac727960534069785d7711aca61c1d5e6a176118fcdfbb350955a488b41ec5` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/graphics/FadingEdges.kt` | P29–P31 | implementation / contract: Modifier | `d327c1bafc726d65083be688c25f58de7a3e01f3ae6726879e3bb30d94ecc769` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/haptics/Haptics.kt` | P01/P02/P05/P06 | implementation / contract: Haptic, Haptics, rememberHaptics | `029e2eb277ed54070a2a0741f17fb9de97f411e1d061a3280983fa49f5ade814` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/icons/BitChordIcons.kt` | P29–P31 | implementation / contract: BitChordIcons | `f14f968be47a64732fc9466a93d20feb97877d49e31caca3ff6fd6e6886e88f6` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ArtistCredits.kt` | P24 | implementation / contract: artistCredits, splitCreditLine | `d829c56145657132c1f44357edd57c03d5f6af5ef7aa6382d6dca219b31565f0` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt` | P24 | implementation / contract: ArtworkMesh, rememberFullArtworkBlurImage, FullArtworkBlurBackdrop, rememberArtworkMesh, ArtworkMeshBackdrop, DrawScope, easedRamp, ImageBitmap, boxBlurPass, meshOf, edgeMean, IntA | `d062189b490654c978fc87de0a1d8469caf09c96d5dde45c099a3c5dff736e2a` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/AudioOutputLabels.kt` | P11–P14 | implementation / contract: outputLabel, rememberAudioOutputName | `efccd8d603b17e4aa43fb3895f49ff9f6de3fd98bfc48a14226be74900d68be8` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/AudioOutputSheet.kt` | P11–P14 | implementation / contract: AudioOutputSheet, AudioPipelineRow, CastRow, OutputRow, VolumeRow, iconFor | `1d12a7064a142e7fc73dd73aa7427bf92414ff5a2b209adc1c7252f4f7e2e873` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/DesktopMusicVideoLayout.kt` | P25/P26 | implementation / contract: desktopVideoSidebarWidth, DesktopMusicVideoLayout | `a1e85b789998358b09820e2f8a683631d5c35b03c666d8fa62e23ee8bbbe2e51` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/FrameHeuristics.kt` | P24 | implementation / contract: isLikelyBlackFrame | `6ae274301120416dc70140cab20db5019e677c5ff9becfae4be1cb80fcf08f20` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapeHeaderPolicy.kt` | P24 | implementation / contract: landscapeLikeVisible | `e83c14404ae7573544b7c8bfd0938a6b9a64a671da2474d7cb5052e523bcb630` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapeLyricEdges.kt` | P22/P23 | implementation / contract: Modifier | `830689361b320a2458ebcf6512254a15169d77cc5dd78a01770528d5c90fd0c0` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapeLyricsFrame.kt` | P22/P23 | implementation / contract: LandscapeLyricsChrome, LandscapeLyricsFrame | `e72c266bd9c852f51b63be5a37886ac3db9efd35df78570c768b5cc0654df182` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapeMetadataMotion.kt` | P24 | implementation / contract: MetadataPose, MetadataFrame, LandscapeMetadataState | `c6ee6ebd096702361b8836eaf9e276ae5e57f5376251034822d3788f29a34475` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapePlayer.kt` | P24 | implementation / contract: LandscapePlayerLayout, LandscapeArtwork, LandscapeMainPane, LandscapeTrackMetadata, LandscapeTrackHeader, landscapeArtistTextStyle, LandscapeCredits, LandscapeCreditsText, Landscap | `29363ad9756d912c875a51f182c50a022d036bec399028bf62c2b7e16f9744e5` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapePlayerColumns.kt` | P24 | implementation / contract: PlayerPane, LandscapeSlot, LandscapeColumnGeometry, landscapeColumnGeometry, LandscapePlayerColumns | `f107989bdf281b9fe98889e2f38178cf3484e4d35ba7e22de760a4ca42c72e86` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ListenTogetherMembersSheet.kt` | P34 | implementation / contract: ListenTogetherMembersSheet, MemberDrawerRow, ManageRow | `389015b74796320dbc8beb140f9d51002cd35270a1cbbf2532ed1feab7e75afe` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricClock.kt` | P22/P23 | implementation / contract: LyricClock, smoothstep | `d8d3bae9f78af6b0cd9c50a5ee4bf7af0040a8fb2043ad285c7d3c9999456e66` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricFocus.kt` | P22/P23 | implementation / contract: activeLyricRows | `6432eb97f9e9c0beac68939dfd823c4f2d049f5ca0f5bf17c967fee72fc43fa5` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsControlsGesture.kt` | P22/P23 | implementation / contract: Modifier | `4ffcec2ceff3ca7eb16a65a29313aa7181100055c78ff2b40b9a2e1600d50710` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsOffsetSheet.kt` | P22/P23 | implementation / contract: LyricsOffsetSheet, OffsetButton, offsetToFraction, fractionToOffset, formatOffset | `2025dcd8454c66e3ff57d547854b3abade44cccab9b8be02fa9ad4e1d9e382d8` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsProviderSheet.kt` | P22/P23 | implementation / contract: LyricsProviderSheet, LyricsProviderMenuRow, LyricsProviderRow | `7315b38db30038ed0df9aec2ec76d788e60793681483f8d96caa9d562ba3980f` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsShare.kt` | P22/P23 | implementation / contract: LyricsShareLine, LyricsShareRequest, fitsOnCard, LyricsPicker, rememberLyricsPicker, LyricsPickBar, RowScope | `36c1e6bb450600c61685f02de83bd68b8ca7101020bed1621b1a2b8feaa71572` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsSidePanelTheme.kt` | P22/P23 | implementation / contract: LyricsPanelColors, lyricsSidePanelColors | `9c8be5e512e134dfea6b731f69be972c0e26503075a72e3595aa0f6bf518aa8c` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MeshGradient.kt` | P24 | implementation / contract: MeshPalette, MeshGradientBackground, cyclingMeshPalette, rememberArtworkColors, paletteOf, List, Color, artworkMeshColors | `c88c84d9a9768e9086c7ea6ea6980c39ed18799727596f9322527f758f3ca0f3` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt` | P24 | implementation / contract: WaveColors, MixViewPopup, MixViewContent, DeckHeader, MixDecks, DrawScope, rememberWaveColors, waveColorsOf, hueDistance, hsvOf, sample, formatDeckDuration, Camelot, camelotOf, Mix | `d4af3815753067cbe15fd4aca75d05c525bdce60dd922faa8cf994f1b0432227` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MusicVideoLayout.kt` | P25/P26 | implementation / contract: FullscreenVideoPlayer, VideoLoadingBackdrop, VideoCornerControls, DesktopVideoQualityControl, MusicVideoQualityUi, VideoQualityBox, VideoQualitySheet, QualityRow | `ee13b488722cb4e5960e8bc8702314ccb53313363daac7f80957884f39ae9af7` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt` | P24 | implementation / contract: DockFrame, LandscapeDockPose, WhileShown, fullBleedArtworkAvailable, playerFillsWindow, tabletSizedPlayer, landscapePlayerAvailable, playerDeckSlideIn, playerDeckSlideOut, SlidingP | `c55892a49e53824b9af48e240dee078c6328452e2751c2df18c884f80cb4c1f4` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerChrome.kt` | P24 | implementation / contract: playerDiscPaintAlpha, canUseDesktopVibrantChrome, desktopVibrantChromeEnabled, mobileChromeAlpha, chromeWhite, desktopDirectChromeAlpha, playerSecondaryColor, shouldUseOffscreenChr | `0a192ec25863584b6832fdaa54ecb40b495c867760d23b50143170b47266f361` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt` | P24 | implementation / contract: playbackOriginText, PlaybackOriginCaption, PlayerScrubber, MixingLabel, PlaybackQualityLabel, SleeveNerdStats, TransportRow, VolumeRow, PlayerActionRow, CircleGlyph, TransportGlyph | `c93afe8a4df9ba36b99ec1ef6382d150bcc7aca2d707d0e7c4be52254e9198f2` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerDock.kt` | P24 | implementation / contract: PlayerDock | `00b61a242a9447a66ffb45686dd4a146c6a22dd17cb61f54f2fc6a3ddb370b47` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerDrawer.kt` | P24 | implementation / contract: PlayerDrawer | `cd8616e229074e935f896efa5e32e508b9245c2f055ffda32bb3973cf822a6ff` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt` | P24 | implementation / contract: PlayerHost, DesktopMenuGlassStyle, MusicVideoQualityUi, videoQualityLabel, showsMusicVideo, MusicVideoSurface, WatchOutputUi, CastUi, PlayerPlatform, PlayerSettingsSource, SystemVo | `0807d920c1bcbde813ed649233413327efdc39706760a58dc48fd61a3bb9de7f` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt` | P22/P23 | implementation / contract: isRtl, textDirectionFor, alignsRight, voiceStyle, ScrollRun, scrollLead, LyricsTranslationUiState, LyricsDisplayMode, TranslationParticle, LyricsStatusWithChange, adjustedLyricsPos | `2772de30d318b881207105e267b1508deaa4b28f6dd8c7b701765010d08bc10d` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerMarqueeMotion.kt` | P24 | implementation / contract: rememberPlayerMarqueeOffset | `307273e131c79a650b15867f7b7799a118e80146e85b99a8aa9fe20b8a4df5f1` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerPlatformUi.kt` | P24 | implementation / contract: uptimeMillis, KeepScreenOn, appLanguageTag, PlayerBackHandler, DrawScope | `31608d209dadfc7295454071e98fb84dc1de020963fd6e213e2e157ebd15c06c` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt` | P10 | implementation / contract: QueuePanelColors, keepScrollInList, Modifier, QueueTrack, QueueSection, QueueTracks, splitQueue, LazyItemScope, DesktopVideoQueue, InlineQueue, LazyListScope, QueueHeading, QueueCl | `e55e2fbfb900e732a9e59c6017eb4c2934888c30d7d73b7b62e712af271896ec` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerSidePanels.kt` | P24 | implementation / contract: LyricsSidePanel, QueueSidePanel | `5603af1521dc67c289e8427e5422e3c6eb85461ee4afb79ac363d316792ddd93` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerState.kt` | P24 | implementation / contract: rememberCanvasArtwork, PlayerArtwork, rememberPlayerArtwork, PlayerScrub, rememberPlayerScrub, PlayerVolume, rememberPlayerVolume | `fe37cf10898ff0c559d746b361d59921fcb234d86d3d4e3e743b63ed0b3e0acd` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ThinSlider.kt` | P24 | implementation / contract: ThinSlider, MixPulse, rememberMixPulse, beatShape, MixSheen | `f71928542bb6e67dd0b531239fa5efc78b6ee14dbf62b2bbdd4832825570e89b` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/replay/ReplayCard.kt` | P09 | implementation / contract: ReplayCreditCard, Embossed, RankBadge, InitialTile | `1b9bc73c95138872a90946183909c80d3660a9edb5aaebd4ac647bbe29472412` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ExploreScreen.kt` | P01/P02/P05/P06 | implementation / contract: ExploreScreen, MoodGenreRow, MoodGenreCard, OldMoodGenreCard, moodColor, ModernMoodGenreCard, MoodTone, moodTone, duotone, fallbackMoodColor, moodColumns, ExploreSkeletonRows, Mood | `4f92f42ea2992f8cb4fd73091d66f2a95df0eacad7b3be03aa0c3413556d0b5d` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt` | P01/P02/P05/P06 | implementation / contract: Modifier, ShelfItem, HomeScreen, androidx, RecentShelf, RecentSectionHeader, RecentTrackRow, CompactTrackRow, HomeTitle, SectionHeader, localizeShelfTitle, localizeShelfSubtitle, l | `7d6ab8d5492d390c8c53f6224314c91081db8d8cfb8293af3e942cee93557846` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/LibraryScreen.kt` | P01/P02/P05/P06 | implementation / contract: LibraryScreen, LibraryLink, LibraryLinkList, PlaylistShelf, libraryShelfCardWidth, LibraryGridShelf, LibraryGridPage, HomeShelf | `ba2b9c857c11b567d84f45a0a6f90873461c0fee50f953117d719bc3352d2083` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt` | P04 | implementation / contract: SearchScreen, SearchSection, searchSections, LazyListScope, TopResultCard, SuggestionRow, TypeaheadSongRow, RecentSearchEntityRow, BrowseRow, SearchFilterTabs | `424559aa29116b7463257b4e66d9771cacf099998aa8fcbf2ea94fa6c7e15765` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/TopPicksShelf.kt` | P01/P02/P05/P06 | implementation / contract: topPickWidth, TopPicksShelf, LazyListScope, CardText, cardText, TopPickCard, CardCaption, ReleaseArt, CardMotionSource, TopPick, CardMotion, PausedWhileScrolling, ClipSlot, clipOwn | `574a351ed1676ca53f39c189b4b3ed7119f2645f27d374978db92226f118571b` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/ArtworkPalette.kt` | P29–P31 | implementation / contract: ArtworkPalette, ArtworkKeyColors, rememberArtworkPalette, rememberArtworkTopBandLuminance, rememberPlayerTint, Seed, Color, rememberArtworkSeed, seedOf, ImageBitmap, averageRelativ | `f155d52b9e419593193c05a26052697353ddcc3e5cefd2e32daf5515a2bed6dd` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/BitChordTypography.kt` | P29–P31 | implementation / contract: bitChordTypography | `a58a9d1ee3d0e67489c0a70adf565d78eda81bb5cddb8f9b64cd8408fc5abfb0` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/ScreenOrientation.kt` | P29–P31 | implementation / contract: ScreenOrientation, rememberScreenOrientation | `43d52b54b83757f3d74ade18df67bf71f91887d0e185888c5dd5740fe01fbd1e` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/StatusBarIcons.kt` | P29–P31 | implementation / contract: StatusBarIcons, HideStatusBar, HideSystemBars, stableStatusBarInsets | `514649a8e5281dafee8ee0cd4c5fc5ea689f1d36f0803a1b23e7d2928ef63aef` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/utils/SheetGestureGuard.kt` | P01/P02/P05/P06 | implementation / contract: Modifier, SwallowNestedScroll | `6ada19eb33cee39327e66434bf58175dc1b9988c776b313e7230d909bd832bb2` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/LandscapeHeaderBehaviorTest.kt` | P01/P02/P05/P06 | reference test: LandscapeHeaderBehaviorTest | `c2e02ab4bb6082e50106ea3a7d5b6b86e294a97cd38e45ab0a04a118ce303e3a` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/LyricAnimationWindowTest.kt` | P22/P23 | reference test: LyricAnimationWindowTest | `c9536727edfa7ba79058546ad7819171a17b868903e25116b52224bb95e151e2` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/LyricClockTest.kt` | P22/P23 | reference test: LyricClockTest | `a48c80bb61a1750b5d8b8cc5c6b2521c9d15c9d985e10e2eeabae8536e33b43c` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/LyricFocusTest.kt` | P22/P23 | reference test: LyricFocusTest | `e1722d505ad93394acc077022a01ec543058a3226bf662809f623039f9d49e76` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/LyricsOffsetTest.kt` | P22/P23 | reference test: LyricsOffsetTest | `c3358b50247a6ac8ccb242a8e70ccbc1ef31e9756714202db1b3fbede8684702` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/components/BlurredBackdropJvmTest.kt` | P29–P31 | reference test: BlurredBackdropJvmTest | `6581eef0c7cebc5b2c3572fe726a50a533726cb03dc6e1666f7eaa2ab02bb516` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LandscapeArtistTextStyleTest.kt` | P24 | reference test: LandscapeArtistTextStyleTest | `5581ebf8ba2fc3a90f8b1647b8f70358aaeb842d1fee981305a031319c4be759` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LandscapeLyricsFrameTest.kt` | P22/P23 | reference test: LandscapeLyricsFrameTest | `111c30b04264282ae0c406cab84272bbca417f2957c2bc88a36a7571787464b7` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LandscapeMetadataStateTest.kt` | P24 | reference test: LandscapeMetadataStateTest | `5304dbf934fc6cbbf37c9341e68b435b5a3b66f92640ce52081dbfdcbfff80a0` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LandscapePlayerMotionTest.kt` | P24 | reference test: LandscapePlayerMotionTest | `cb63b7e5b07405e908bf724e39c65851e5f87a083b92f65aa7d04fbeec0e68b1` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LandscapeTrackHeaderTest.kt` | P24 | reference test: withLandscapeTestHost, LandscapeTrackHeaderTest | `96c089215c7c17817fed4ef6a65e319545bd013f02070c9e05adba88ad04d54e` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LyricsSidePanelThemeTest.kt` | P22/P23 | reference test: LyricsSidePanelThemeTest | `c4484b985d7ef61f5fe994eb4d1e3d16f11fe96ff649c9c7b4e2320e2396104d` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/PlayerChromeTest.kt` | P24 | reference test: PlayerChromeTest | `3299b7b5bc6bf46a58d00cb821b8c93b7b0081fc81f202a87a9747821298decf` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/screens/LibraryShelfCardWidthTest.kt` | P01/P02/P05/P06 | reference test: LibraryShelfCardWidthTest | `3b6876bef868a1c906c9f0f3ba76e05fa0329ac788e6b61edc21a53a8056e139` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/theme/ArtworkPaletteTest.kt` | P29–P31 | reference test: ArtworkPaletteTest | `dbb4f6b1b184211c36a91d64222077fe90cbb3297971321fb99539f78afa4a9f` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/theme/BitChordTypographyTest.kt` | P29–P31 | reference test: BitChordTypographyTest | `431804a9bc00b5ba8993bbe280e1f5920fed78b5706483a82656108f75586d68` |
| `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/theme/StatusBarContrastTest.kt` | P29–P31 | reference test: StatusBarContrastTest | `4ad26dbd64d1c6dbb75281df53cd3f7bc4d6f3bef7ea6a45cdfaa192ee4fd339` |
| `tools/automix-validation/build.gradle.kts` | P15/P16 | implementation / contract | `fd7cf164bbf23196d626f4a0f7f98aa77974511f816a559a7ad7a14406ded0a1` |
| `tools/automix-validation/settings.gradle.kts` | P15/P16 | implementation / contract | `bee0bd3a0f76c8ce4615b7cbb8509028c8f8fd321bd2759f555e2800ad8152de` |
| `tools/automix-validation/upstream-manifest.json` | P15/P16 | asset / configuration / specification | `793cb7eb86bb7eee3ae4a3b1ef8df21f4f7a519a825b6f889b7aaa59f3b72ff8` |
| `tools/automix-validation/verify-upstream.py` | P15/P16 | implementation / contract | `893a8f2e9c1c341041aaf65c16423cc5afa134a44fb114395e74f2b8369b5ad2` |

Inventory scope includes all desktop source/resources/tests/packaging, both shared modules including Android contextual actuals, native and vendored sources, model/JNI assets, Go backend, Automix verification and platform CI. It does not treat generated outputs as evidence. Matrix row acceptance applies to these groups; implementation must further split rows into testable assertions rather than use group counts as a parity score.

## Phase 1 evidence update — 2026-11-11

Evidence source: GitHub Actions run 43041242897,
implementation commit `529c8d60`.

| Area | Confirmed evidence | Remaining verification |
| --- | --- | --- |
| Windows foundation | Official generation, dependency resolution, analysis, six passing tests, release build | Native GUI launch and comparison with Kotlin |
| Linux foundation | CI execution started | Final build, tests, and native launch results |
| Expanded shell | Widget layout checks at 1220×780 | Comparison with Kotlin at identical dimensions |
| Narrow shell | Widget layout checks at 900×600 | Reference behavior and native resizing comparison |
| Settings and navigation | Tests cover theme behavior, persistence, bounded history, navigation and keyboard actions | Complete reference interaction audit |
| Assets | Source and asset provenance checks passed in Windows CI | Runtime appearance comparison |
| Player dock | Empty layout with disabled transport controls | Playback belongs to future phases |
| Typography | Fallback typography is implemented | SF Pro redistribution rights and visual matching |

These results establish foundation checks, not complete feature or visual parity.

### Known visual gaps

- SF Pro font binaries have not been redistributed; typography uses fallbacks.
- Native decorated windows and current application chrome still require
  comparison with the Kotlin reference.
- Blur, gradients, shadows, focus states, animation timing, and icon rendering
  require runtime comparison.
- Flutter test captures do not replace Kotlin-versus-Flutter comparison.

The Kotlin reference remains unchanged. No previous Flutter rewrite was used.

### Later CI status — 2026-11-12

Run `43041242897` finished with Windows **success** and Linux **failure**
at `Install Linux desktop dependencies`. Linux native visual verification
remains unperformed; generated-platform retention was skipped.

### Phase 1 resumption evidence — 2026-11-12

Run [43121851901](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43121851901)
at `3c9abb6a` supersedes the prior build status: Linux and Windows both passed
analysis, six tests (zero failed/skipped), and release builds. Linux's mapped
window capture was black and the smoke failed; retention did not run.

- P01/P28: native lifecycle remains unaccepted. Linux capture/log handling is
  corrected for GL child surfaces and bounded paint readiness; a Windows native
  smoke is added. Their results must be inspected after push.
- P02/P24/P29–P31: widget captures use Ahem test glyphs, so they verify structural
  layout, not typography or actual desktop appearance. Native captures and a
  matched pinned-Kotlin comparison are still required. Existing font, native
  caption/Mica/icon, focus/DPI and motion gaps remain open.
- All 779 recorded reference hashes were rechecked locally against
  `348b613a0c3db1a54de56483ea6f8748d6465211`; no Kotlin source changed.

No P-ID is parity-accepted from these build results; no Phase 2 work has begun.

### Native lifecycle evidence — 2026-11-12

Run [43126119556](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43126119556)
at `72dd48d6` passed Linux analysis, six tests, release build and native
1220×780/900×600 client paint/resize/close checks (exit 0). Both Linux captures
were reviewed: expanded sidebar, automatic rail, original glyphs and empty
disabled dock are present. Windows passed compilation/six tests and captured
both clients at 96 DPI on a 1600×1200 hosted display; close crashed with
`0xC000041D`. P01/P28 therefore remains unaccepted pending shutdown correction.
The native-close path and two drain/idempotence/failure tests address this;
results are pending. Neither hosted captures nor source tokens replace the
missing pinned-Kotlin comparison, font rights/metrics, full DPI/focus/motion,
native caption/Mica/icon or accessibility gates (P02/P24/P29–P31).

### Current Phase 1 acceptance evidence — 2026-11-12

[43126969435](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43126969435)
at `ea6b5c90` passed the full Windows/Linux matrix: generation, resolution,
format/analyze, **8 tests per OS with zero failures/skips**, release builds,
both painted client sizes/landmarks and native close/exit 0. Retention commit
`6f67ac2f` contains official hosts, lockfile, formatter output and the actual
native PNG/JSON/test evidence. P01/P28's foundation lifecycle is now
platform-verified for these hosted environments; full feature/shortcut parity
is not accepted.

The pinned Linux Kotlin reference passed 857 tests (78 shared, 85 sharedUi,
694 desktop, no failures/errors/skips) and built its distributable in
[43125482870](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43125482870).
Windows reference tests/build fail `:desktopApp:buildAnalysisNative` with CMake
exit 1 in that run and [43126572145](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43126572145).
The diagnostic standalone configuration succeeds with GNU 14.2 and JDK 21,
but Gradle's native build fails. No protected reference source was changed to
work around this blocker.

Actual local Linux light-theme pairs at 1220×780 and 900×600 are retained in
[`flutterDesktop/verification/phase1-local-comparison/`](flutterDesktop/verification/phase1-local-comparison/).
`capture.json` records source/run/image hashes, actual client dimensions and
limits. Temporary test-owned profiles contained no accounts; Kotlin welcome was
completed in its isolated Preferences, and Flutter Light was selected through
real controls. The Flutter comparison artifact is `72dd48d6` (UI unchanged by
the subsequent close fix). Both apps used the same KDE/XWayland session; local
numeric DPI was not independently measured. XWayland allowed client drawable
capture, not root-screen capture. Cleanup via SIGTERM is not lifecycle proof.
Kotlin loaded a genuine anonymous live catalog and Flutter shows its honest
disconnected page; this is a common-shell review, not a full-frame golden.

| P-ID / region | Observed common-shell comparison / remaining gap |
|---|---|
| P02 Home header | Flutter's extra 48-dp navigation/status row moves its heading down; reference Home starts directly at the page gutter |
| P02/P29 sidebar | 220/72 widths hold; brand sizing, text metrics and vertical spacing differ; the account row/additional rail action is missing |
| P24 empty dock | 760×58 bounds hold; Flutter brand mark is visibly larger/darker; reference has additional actions/volume and different empty-control appearance |
| P29 typography/icons | SF Pro remains solely in the reference; fallback text differs. Native template taskbar/app icons remain uncustomized |
| P30 glass/motion | Different feed backgrounds prevent blur equivalence; reference feed-under-dock fade/other motion/material behavior remains unmatched |
| P28–P31 platform | Windows Kotlin pairing is blocked; full DPI/theme/focus/fullscreen/accessibility and caption/Mica checks remain unverified |

**Phase 1 foundation checks pass; visual acceptance remains INCOMPLETE/BLOCKED.**
No P-ID's full parity, memory reduction or Phase 2 completion is inferred.


### Phase 1 shell correction and Windows native diagnosis — 2026-11-12

This continuation supersedes the specific missing-shell observations above, not
full P-ID acceptance. Protected Kotlin/shared/native source remains unchanged;
Phase 2 has not begun.

- P01/P28: run [43132763894](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43132763894)
  at `95859ac9` passed **12 tests per OS**, analysis, release builds, two native
  client sizes, F11/Escape fullscreen/restore and clean native close (exit 0) on
  Windows and Linux. Only retention failed: it correctly rejected a newer branch
  head. No newer source is inferred validated from that run.
- P02/P29: removed the invented 48-dp navigation row; Alt+Left retains back.
  Header/row/divider/footer dimensions follow `DesktopApp.kt` Sidebar/SidebarItem.
  Empty account row/avatar/menu and narrow-rail playlist action now exist. Add
  account is explicitly unavailable until the authorized account implementation.
- P24: original padded gray `bitchord_no_music.svg` replaces the oversized white
  mark. Dock 760×58, reference action/button slots and 72×4 unavailable volume
  track are present. No playback or volume engine is simulated.
- P29: compact Home heading is 30 sp; custom role line heights no longer inherit
  unrelated Material defaults; default-role physical weights/metrics are mapped.
  SF Pro is not bundled. Its rights and actual ink/line metrics remain unresolved.
  The local expanded playlist caption still wraps where SF Pro fits one line.
- P30: original chrome tint/blur parameters are used for dock/menu. Haze noise,
  renderer-specific blur, feed-under-dock fade and animation equivalence are open.

Repeated local Linux light captures of both apps at both client sizes are in
[`flutterDesktop/verification/phase1-shell-correction/`](flutterDesktop/verification/phase1-shell-correction/).
They use the real pinned Kotlin distributable and corrected Flutter `95859ac9`,
sequentially in the same KDE/XWayland session, isolated empty profiles and actual
Light selection. `capture.json` records run/source/image hashes and timestamps.
Review confirms the corrected sidebar/footer/dock geometry and absence of the
extra row. Home ink positioning/width and playlist wrapping still differ by
font. Different real feed/disconnected content prevents full-frame or matching
blur acceptance. Local numerical DPI is unmeasured; SIGTERM cleanup is not
native-close evidence.

Focused Windows diagnostic [43129985058](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43129985058)
proved GNU 14.2 builds the analyzer, then fails the SMTC target's incomplete
MinGW WinRT types. The native task name obscured the actual failing target.
The reference already supports MSVC/C++/WinRT; an external Gradle init script
selects it without editing protected source. The window bridge additionally
needs SDK property-key prerequisites included before its original headers.
[PROPERTYKEY belongs to wtypes.h](https://learn.microsoft.com/en-us/windows/win32/api/wtypes/ns-wtypes-propertykey);
[DEFINE_PROPERTYKEY/Initguid behavior](https://learn.microsoft.com/en-us/windows/win32/api/propkeydef/nf-propkeydef-define_propertykey)
is supplied through compiler `/FI` options. All original targets/tests remain
required. Focused run [43134106555](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43134106555)
passed all three DLLs with MSVC 19.44 / SDK 10.0.26100.0 and empty protected diff;
`windows-native.json` records hashes. Full reference tests/package/capture are
separate gates; the focused compile does not prove them.

Full foundation run [43134106705](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43134106705)
at `3b2a8747` subsequently passed both OSs and retention (`32f8150b`). Six
additional account/drawer/settings/Light/Dark/Pitch-black native captures per OS
were reviewed. Rendered page colors, F11/Escape size restoration and exit 0 pass
on both platforms. This adds environment-specific evidence, not full P-ID parity.

Pinned reference run [43134106568](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43134106568)
passed 857 tests on both Windows and Linux, zero failures/errors/skips. Windows
also built the full distributable and passed packaged JNI/model verification.
Only its visible window capture failed: no main window was detected within
30 seconds, so the cross-platform visual comparison remains incomplete. A
focused retry uses the verified build and inspects the launcher process tree.

Current remaining acceptance gaps: matched Windows reference capture, full
DPI/theme/focus/accessibility/motion checks, native caption/Mica/app/taskbar icons,
font rights/metrics and exact material behavior. Account/data/content/player
functionality is outside this foundation. No full parity or memory reduction
is claimed. Phase 1 visual acceptance remains **INCOMPLETE**.

### Actual pinned Windows reference capture

Capture-only run [43155307079](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43155307079)
reused the Windows package only after its 857 tests, distributable, protected
source and JNI/model checks passed in run `43134106568`. It captured the actual
pinned executable at 1220×780 and 900×600 client sizes at 96 DPI. Captures and
SHA-256/run provenance are retained under
[`phase1-shell-correction/`](flutterDesktop/verification/phase1-shell-correction/).
The process-window enumeration fixes the previous helper's `.NET MainWindowHandle`
false negative. The first paired shell review confirmed the Home title was lower
in Flutter. Pixel checks at 96 DPI found Home title ink at y=16 in Kotlin; the
prior Flutter captures were at y=27 on Windows and y=23 on local Linux due to
fallback font insets. The final code measures 5 dp for Windows and 9 dp for Linux,
with a metric assertion for both platforms and a Linux widget bounds assertion;
that final adjustment awaits hosted
native captures. No
Windows reference lifecycle pass is inferred from capture-only termination.

## Phase 1 user-corrected visual parity ledger — 2026-11-12

This section updates the interpretation of earlier screenshots without deleting source-trace evidence.

| Area | Latest implementation/evidence | Remaining acceptance |
| --- | --- | --- |
| Sidebar 100% spacing (P02/P24) | Kotlin 110% vs Flutter 100% was an invalid spacing comparison; the user reports matched 100% looks the same. Keep reference 7-dp vertical row padding and current Flutter geometry | Recheck matched visual states if layout changes |
| UI scaling (P29–P31) | Kotlin offers UI Scale 110%; Flutter 100% comparison is not evidence of 110% support | Implement and verify original scale semantics with matching 100%/110%/DPI fixtures |
| Navigation motion (P01/P02) | Flutter routes changed from Material's default page transition to `NoTransitionPage`; regression test added | User manual test of tab switch; native motion/focus checks |
| Typography (P29–P31) | User provided static `bitchord-sans` font cuts 400/500/600/700/800. Code prefers installed `bitchord-sans` and retains platform fallback; binaries not packaged | Confirm font license/redistribution rights, installed-family resolution, weight mapping, glyph ink/wrapping and reference parity |
| Player dock responsive behavior (P24) | Compact layout added for constrained width with dedicated widget regression; original normal-width dock preserved | Native very-small-width and 100%/110% inspection; player functionality belongs later |
| Windows/Linux foundation | Hosted matrix [43163359448](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43163359448) PASS for both platforms, release bundles, tests, native shell checks and retention | Latest font-specific CI [43169153250](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43169153250) was still running when recorded; manual visual approval outstanding |

The user has **not accepted Phase 1 visual parity**, and this table does not claim completed Phase 2 implementation. Kotlin reference files remain the specification. Do not record source-font binaries as licensed, embedded, or verified merely because the font family name is configured.

## Phase 1 user acceptance — 2026-11-12

The user explicitly reported that the latest Phase 1 UI looks OK after testing. Mark the **foundation/shell visual acceptance gate USER_ACCEPTED for Phase 1 scope**: basic navigation (including instant tab transitions), sidebar at matched 100% scale, appearance, account overlay, and empty player dock. Hosted Windows/Linux foundation CI previously passed in run `43163359448`.

This acceptance is scoped to the Phase 1 shell and is **not** evidence that every Kotlin screen, media service, advanced native effect, accessibility scenario, font licensing condition or high-DPI setup has reached full parity. Keep exact Haze/Mica, DPI/accessibility, complete font glyph/weight comparison and future content-related checks open under Phase 2/Phase 8. The user's supplied `bitchord-sans` remains system-installed only; no redistributed font binaries are claimed. The later font change CI `43169153250` was still pending its terminal outcome at the time of this entry. Phase 2 work has not begun.

## Phase 2 presentation evidence — 2026-11-12

Reference for all rows: `348b613a0c3db1a54de56483ea6f8748d6465211`.
Implementation commit is the commit adding this entry (CI SHA recorded later);
full paths/scenarios/gaps are in `flutterDesktop/verification/phase2/README.md`.

| P-ID | Evidence status in this change | Scope and limits |
|---|---|---|
| P01 | STATICALLY_CHECKED | Welcome/default/semantics catalog only; no onboarding implementation |
| P02 | IMPLEMENTED | All ten IDs catalogued, existing immediate routes; drawer Back dismisses before history; nested data routes remain later work |
| P03–P05 | STATICALLY_CHECKED | Source loading/empty/error/overlay inventory; disconnected shell remains honest |
| P24 | IMPLEMENTED | Existing disabled empty dock retained across independent display-DPI/zoom widget scenarios; functional player remains unimplemented |
| P28 | IMPLEMENTED | Nonrepeating shell shortcuts, drawer Back and modal background focus exclusion; full shortcut/focus/accessibility oracle open |
| P29 | IMPLEMENTED | Exact source zoom choices and nearest-option normalization, geometry+type scaling; source palettes/icons verified; native multi-DPI and font licensing/glyph evidence open |
| P30 | IMPLEMENTED | Existing clipped blur/reduction and motion tokens retained; modal background semantics excluded; Haze/Mica/motion equivalence open |
| P31 | IMPLEMENTED | Complete source Settings render block and declaration inventory, generated 15-language shell copy/fallback; 5-field persisted settings remain readable and zoom extends snapshot; broader Settings/localization/RTL still open |

No P-ID is promoted to PARITY_ACCEPTED. Widget/platform results must be read
with the specific subsequent CI evidence; static extracted expressions are not
proof that runtime defaults and visibility have been exercised.

### Phase 2 platform verification result

Tested implementation `4d17527a4e3a0b2f34e827a2b22c2a66bedb6aa3` passed
[43179670749](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43179670749)
on Windows/Linux: 26 tests each, no failures/skips, format/analyze, release
builds and native appearance/drawer/fullscreen/restore/close smoke. Both downloaded
artifacts' test summaries and native JSON were inspected. Evidence retention
`83e5007a` includes source/run provenance and both platforms' PNG/JSON.
P02/P24/P28/P29/P30/P31 **shell-only implemented scenarios** are now
PLATFORM_VERIFIED at hosted 96 DPI; P01/P03–P05 remain STATICALLY_CHECKED for
source cataloguing only. Whole feature rows are not complete or PARITY_ACCEPTED.
DPR 100/125/150/200% widget checks and 110% zoom interaction are FIXTURE_TESTED,
not native multi-DPI acceptance. Phase 2 remains INCOMPLETE for the visual gates
listed in its ledger. No Phase 3 implementation or memory result is included.

### Phase 2 complete user-reported manual checklist — 2026-11-12

**Phase 2 scoped shell/visual manual audit: USER_ACCEPTED / ALL CHECKLIST ITEMS REPORTED PASS.** The user stated that they had tested **everything in the manual Phase 2 checklist** and all was OK, then approved advancement to Phase 3. This includes navigation/sidebar and overlays, scaling/window behavior, font/theme/material presentation, Settings interaction and persistence *as judged by the user*. Record the full manual-test report rather than merely marking acceptance without testing.

Keep evidence provenance clear: no per-item screenshots or independent measurement logs were provided with that attestation. Hosted CI run [43179670749](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43179670749) separately verified 26 tests per OS, builds and native lifecycle. User sign-off does **not** turn deferred whole-app P-IDs, pixel-perfect Haze/Mica, fonts' redistribution permissions, accessibility or native multi-DPI reference fixtures into experimentally proven `PARITY_ACCEPTED` states.

Phase 3 is now authorized to begin. No Phase 3 implementation or Phase 4 authorization is implied by this documentation entry.

## Phase 3 foundation evidence

Pinned reference remains `348b613a0c3db1a54de56483ea6f8748d6465211`.
See `flutterDesktop/verification/phase3/README.md` for exact source paths,
implementation paths, commands, platform limits and required follow-up evidence.

- P10: FIXTURE_TESTED, 450 transitions against executed original Kotlin queue
  sources, plus duplicate identity, seeded shuffle restoration and repeat tests.
- P35: FIXTURE_TESTED, versioned local SQLite CAS and read-only selected-key import
  with verified private backup; pinned Kotlin encoder fixture. Accounts/secrets
  and broader data migration remain open; source Preferences/music are untouched.
- P07/P11/P12/P36 foundation: FIXTURE_TESTED on Linux for real local WAV decode,
  exact frames/samples/seek, bounded ring cancellation, ABI command/event ownership,
  repeated shutdown and caller-buffer allocation balance. Full local feature rows
  and sustained resource/codec matrices remain open.
- P13: IMPLEMENTED, new direct WASAPI/libpulse/ALSA adapters; platform results are
  pending, and physical audibility/loopback/hotplug gates are BLOCKED.
- P14: IMPLEMENTED for native volume/resampling-rate primitives only. Reference
  pitch-preserving speed and advanced DSP remain Phase 5.

Phase 3 **INCOMPLETE**; no whole feature is marked PARITY_ACCEPTED. Hosted software
sink output and decoder sample tests are not speaker/loopback proof. Phase 4 has
not started.

Phase 3 additional local evidence: real Pulse-compatible **software sink** clock
hold, active/paused seek, ~1.998x measured source-time progression for 2x rate,
72000 exact same-format handoff frames/no reopen, seven synthetic real codec
fixtures with FFmpeg-produced PCM oracles, and ASan/UBSan lifecycle/decoder passes.
These strengthen FIXTURE_TESTED P11/P12/P36, not physical P13 acceptance. The
first CI run `43188018937` failed on Pulse EOS behavior and a Windows test include;
follow-up validation is required before any PLATFORM_VERIFIED promotion.
