# Feature parity against Revived 1.9.2

Baseline: `348b613a0c3db1a54de56483ea6f8748d6465211`. The supplied PDF is a visual
reference; Kotlin code decides behavior where screenshots are insufficient.
**Overall result: incomplete.** No percentage is assigned: counting a screen as
one item would conceal large differences in the services behind that screen.

“Implemented” below means executable production code exists. It does **not** mean
live-service, visual or native-platform acceptance has passed. “Partial” identifies
a working subset with an explicit boundary. Tests and platform reports provide
the separate verification evidence.

## Catalogue, navigation and user data

| Source contract | Flutter code / status | Remaining acceptance or implementation |
| --- | --- | --- |
| `DesktopApp`, `DesktopSharedPages`, `DesktopNewPage`, `DesktopShelfBrowsePage` | Implemented Home, New/Explore, search, album/artist/playlist details and collection lists | Validate all live renderer variants, account-dependent shelves and pagination; source-specific personalized Top Picks aggregation is partial |
| `DesktopSearchClient`, `DesktopSearchSession` | Implemented real Innertube search, filters, continuations and bounded query cache | Check production service behavior by region and account |
| `DesktopQuickSearch`, `DesktopQuickSearchState` | Implemented delayed typeahead, focus, keyboard selection and stale-query suppression | Rendered comparison and keyboard smoke tests |
| `DesktopQueue` | Implemented queue-entry identity separate from track identity, user/context/autoplay tiers, reorder/remove/play-next/shuffle/repeat | Controller tests and native long-queue acceptance |
| `DesktopPlaylistUi`, shared playlist calls | Implemented local create/rename/delete/pin/add/remove and online create/rename/delete/add/remove with owned-target validation | Cloud reorder route, remaining batch/privacy actions and large-library continuation need parity work; verify against a test account |
| `DesktopLibraryLoadPolicy`, liked/history views | Partial: online library feeds + local collections, ratings and persisted history | Liked songs and library songs currently show the first browse page; the exposed liked continuation is not wired to a load-more action. Saved album/artist subscription UI and analytics also remain incomplete |
| `DesktopLocalMusic`, `DesktopLocalArtwork`, `DesktopEmbeddedLyrics`, watcher | Implemented isolate scanning, depth-limited traversal, tags, embedded/folder artwork, embedded lyrics and watched-folder refresh | Large-library incrementality, permission/media edge cases and source scan-order parity |
| `DesktopPersistence`, `DesktopPreferenceChunks` | Implemented atomic JSON profile and read-only legacy XML migration including chunk reconstruction and complete stored song fields | Native Windows registry export UX and every dynamically generated setting need explicit migration validation |
| `DesktopPlaybackWrites` | Implemented one playback snapshot including duplicate queue entries and paused resume | Power-loss/restart acceptance and profile recovery on each OS |
| `DesktopListeningStats`, `DesktopReplayUi`, `DesktopPlaybackTracker` | Not ported as a complete feature | Lifetime listening accounting, replay, summaries and source UI |
| `DesktopWelcomeOnboarding`, `DesktopWelcomeScreen` | Not ported | Source first-run/onboarding sequence |

## Playback, sources and multimedia

| Source contract | Flutter code / status | Remaining acceptance or implementation |
| --- | --- | --- |
| `DesktopAudioEngine`, `DesktopAudioDecoder`, `DesktopAudioSink` | Implemented native media_kit/libmpv audio with play/pause/seek/volume/rate/output selection and cleanup | Audible streaming/local playback, device changes and minimized/background operation on every OS |
| `DesktopYouTubeSession`, `DesktopDirectSource`, `DesktopRangeStream` | Implemented signed-in Innertube direct streams, youtube_explode fallback and bounded range proxy | Service anti-bot/PO-token compatibility and all restricted-region/account cases; original native PO-token WebView is absent |
| `DesktopJioSaavn`, `DesktopTrackMatcher` | Implemented search, source metadata matching, public CDN URL decoding and quality-gated fallback | Live matches/versions and region availability; no claim that every match is the same mastering |
| `DesktopSourceConfig`, `DesktopSourceRegistry`, `DesktopAddon` | Implemented addon manifest/search/stream APIs, secure configuration, enable/remove/reorder, provider download policy and four audio quality rungs | Custom source JS module indexes remain absent. The withdrawn build-seeded MODULE is intentionally not restored |
| `DesktopAudioCache`, `DesktopDecodeAhead` | Partial: bounded URL/manifest caches and next-item prefetch | Original decoded PCM cache/instant-playback pipeline is not a byte-for-byte port |
| `DesktopAutoplay` | Implemented radio queue extension with deduplication and generation guards | Real radio continuation under signed-in/anonymous conditions |
| `DesktopEqualizer`, `DesktopEqualizerSettings`, audio processors | Implemented source seven-band frequencies, sixteen presets, interpolation/headroom and native filter application | Audible output and device/backend filter support |
| Crossfade in source audio engine | Implemented dual decks and time-based crossfade with seek/skip cancellation | Audible clipping/latency/transition smoke tests |
| `DesktopTrackAnalyzer`, `DesktopAnalysisRuntime`, `DesktopAutomixWorkQueue`, transition DSP | Partial: real Dart track-analysis model and transition-policy/planner port with source-derived fixtures | Native PCM beat/vocal analysis, cache/work scheduling, tempo/phase alignment, rides/echo/bass/vocal DSP and actual AutoMix execution are not complete. Crossfade is not advertised as AutoMix |
| `DesktopLoopRegion`, `DesktopLoopView`, speed/tempo processing | Partial playback rate only | A/B loop editing, waveform view and precision tempo/pitch path |
| `DesktopMusicVideo` | Implemented separate muted video deck synchronized to audio and decoder toggle | Full song/video counterpart selection, quality changes, decoder/device sync and fallback parity |
| `DesktopPipHost`, `DesktopPipController`, geometry/settings | Implemented separate native window/Flutter engine with metadata, controls and lyric line; no second audio player | Source mode variants, exact geometry, persisted bounds, click-through/native controls and platform smoke tests |
| `DesktopSpotifyCanvas`, `DesktopCanvas*`, Apple/Continued artist art | Not ported | Actual animated Canvas/artist providers, frame lifecycle, renderers and cache settings |
| `DesktopDownloadManager`, `DesktopDownloadQueue` | Implemented bounded concurrent direct-audio downloads, progress/cancel/retry, partial transport identity, range validation, source-policy checks and atomic publication | Segmented DASH/HLS/MPD downloader/remux and source's format/export options are absent; metadata/tag writing needs parity work |

## Lyrics, authentication and integrations

| Source contract | Flutter code / status | Remaining acceptance or implementation |
| --- | --- | --- |
| `DesktopLyricsClient`, `DesktopLyricsSession`, source provider UI | Partial: 9 of 15 offered providers, priority/enable list, synced/plain/word-timed/TTML/duet parsing, offsets and synchronized views | Six adapters are unported: the three PaxSenix variants, KuGou, Musixmatch and Genius. Full background-vocal/syllable processing and exact fallback/selection policy remain incomplete; implemented adapters still need live-service acceptance |
| `DesktopTranslationSetting`, shared lyric transforms | Implemented actual translation and romanization requests with line identity, language setting and stale-track guards | Service responses, mixed-script line alignment and original interaction/animation comparison |
| `DesktopYouTubeAuth`, `DesktopBrowserSignIn`, `DesktopWebSignIn`, `DesktopAccounts` | Partial: secure cookie-session import/restore/sign-out and account loading | Browser-assisted sign-in, embedded authentication, account switching and PO-token lifecycle |
| `DesktopSpotifyLibrary`, auth/token helpers | Partial: real public Spotify playlist import with matching, progress, cancellation and unmatched warnings | Full signed-in Spotify library/search, saved albums/artists/playlists and refresh/session management |
| `DesktopMpris` | Implemented Linux D-Bus metadata/transport/seek/volume/repeat/shuffle/rate/raise/quit | GNOME, KDE, X11/Wayland and session-bus lifecycle tests |
| `DesktopWindowsMedia` and macOS controls | Implemented audio_service adapters | Windows SMTC and macOS now-playing/hardware-key runtime tests |
| `DesktopAwtTray`, `DesktopStatusNotifier`, `DesktopTrayMenu` | Implemented native tray commands and close-to-tray fallback | GNOME/KDE tray hosts, Windows and macOS tray/menu interaction checks |
| Window visibility/fullscreen/caption code | Partial: native window bounds, focus/minimize animation policy, fullscreen and title updates | Original platform title materials, every native gesture and multi-monitor/dpi behavior |
| `DesktopScrobbling`, `DesktopScrobbleSettings` | Not ported | Last.fm and ListenBrainz authentication, queued/retried submissions, timing and preferences |
| `DesktopDiscordIpc`, `DesktopDiscordRpc`, presence/settings | Not ported | Rich Presence IPC, activity/button/privacy preferences and lifecycle |
| `DesktopListenTogether`, `DesktopPartySync`, associated UI | Not ported | Room transport, shared queue/time synchronization, reconnect and hosting UI |
| `DesktopExternalLinks`, link handling | Partial: safe external share/browser actions | Native URL/protocol registration and source deep-link dispatch |
| `DesktopUpdate*` and platform packaging | Not ported | Existing self-update protocol, installers, rollback/recovery, disk-space checks, release signing and distribution integration |

## Presentation and settings

The implementation reuses the original app/transport/navigation/wordmark assets
and both SF Pro Display weight profiles. Source dark/light/pitch-black tokens,
72/220 sidebar, 58-pixel bottom dock, clipped glass menus and major player views
have concrete implementations. Full details and differences are in
[visual-parity.md](visual-parity.md).

Working settings include theme/pitch black, Default/Legacy font, UI scale, old
top-player layout, blur reduction, supported animation/focus options, volume-bar
visibility, content filters, audio/download quality, source order and enable state,
audio output, EQ, crossfade, autoplay, lyric providers/offset/size/translation
language, catalogue language/region, local folder and legacy import. Persisting a
legacy key does not mean its UI or underlying behavior has been ported.

The remaining settings audit must explicitly cover native Mica/backdrops,
per-area blur/performance profiles, every player theme, Canvas/artist animation,
PiP modes, lyrics styles/syllable preferences, playback precision/cache/AutoMix,
source custom modules, export/cache management, integrations, all interface
translations, updates and onboarding. The generated literal preference index
makes omissions visible; dynamically built `source_*_enabled` and shared settings
are not fully described by literal extraction.

## Verification boundary

Static analysis and deterministic tests validate code contracts. They do not
certify audible output, successful third-party login, live Google/Spotify service
availability, a pixel match, an installer, or a RAM improvement. A release review
must resolve each of those separately. The branch remains an incomplete rewrite
checkpoint until every applicable acceptance gate in the user's assignment is
satisfied.
