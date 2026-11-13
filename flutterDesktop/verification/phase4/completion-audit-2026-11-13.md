# Phase 4 completion audit — INCOMPLETE

Reference: Kotlin `1.9.2`, `348b613a0c3db1a54de56483ea6f8748d6465211`.
Starting Flutter commit: `c086c85e`; clean `2.0.0-flutter` worktree.
The latest checked baseline matrix was successful run
[43364580612](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43364580612),
source `b839015cd004ae23d3f260eedc0ddc80ae71f3f9`. This does not verify this increment.
New matrix evidence must be recorded separately after push. Phase 5 is NOT STARTED.

## Audit and implementation order

1. Trace source catalog, session, download and source transport paths; preserve
   the user-verified direct guest playback, final-on-drag seek and restart/offline path.
2. Fix observable catalog defects with provider/parser/controller regressions.
3. Add cancellation-safe sequential catalog context and download queue; preserve
   the current decoder, source broker and resolver implementations.
4. Check scoped UI geometry, actual guest provider responses, controlled failure
   recovery and Windows/Linux CI. Keep missing authenticated/visual gates explicit.

Paths prefixed `D/` below are under
`desktopApp/src/main/kotlin/com/music/bitchord/desktop/`; `U/` is
`sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/`.
These source files were inspected on the pinned reference, whose tracked
`desktopApp`, `shared` and `sharedUi` trees remain unchanged.

| Scope / IDs | Kotlin contract | Flutter result and remaining gap |
| --- | --- | --- |
| Home/recommendations, P03 | `D/DesktopApp.kt`, `DesktopSearchClient.kt`, `U/screens/HomeScreen.kt`: progressive feed, supplemental shelves, recents, moods, radio, account generation | Guest shelves/cards and Home continuation work live. Fixed next-page responses containing both an empty tab shell and `continuationContents`. Supplemental/recents/radio/account-dependent feed parity is incomplete. |
| New/Explore, P03 | `D/DesktopNewPage.kt`, shared discovery screens: scoped sections/categories and navigation | Live New/Explore browse responses contain real data. Dedicated source layout/category filtering and Search's Explore landing are incomplete. |
| Search, P04 | `D/DesktopSearchSession.kt`, `DesktopApp.kt:DesktopSearchField`, `U/screens/SearchScreen.kt`, `InnertubeParser.parseSearchSuggestions` | Real debounced suggestions, endpoint-query precedence, fill/submit, mouse-up preservation, arrows/Enter/Escape, edit cancellation, filters and continuation. No authenticated full search is made on keystrokes. Entity typeahead, recent-search persistence, Quick Search overlay, complete filter styling/loading/error/landing parity remain open. |
| Back/navigation, P02/P03/P04/P05 | `D/DesktopApp.kt`, `DesktopSearchSession.kt`: independent parent/search state | Three saved page snapshots plus the active page preserve query/filter/rows/continuation on Back; account cancellation erases cached results and current rows. Per-route scroll/sort/expanded state restoration remains incomplete. |
| Album/playlist, P05 | `D/DesktopApp.kt:DesktopCollectionPage/DesktopCollectionSongRow`, `DesktopSharedPages.kt` | Source-sized 286dp artwork, 30dp gap, release title/credit/year/count/duration, play/shuffle/find/More→download/share, numbered rows; album rows omit repeated artwork. Play uses loaded context; filter preserves original indices and duplicate occurrences. Full album credit links, album column metadata, sort, library/like/ownership/edit/remove menus and exact custom menu rendering are unfinished. |
| Artist/shelf detail, P03/P05 | `D/DesktopAppleArtistUi.kt`, `DesktopArtistBannerArtwork.kt`, `DesktopShelfBrowsePage.kt` | Real artist/detail headers, shelves and navigation load. Apple/static artist hero, subscription/library actions, source see-all adaptive grid and exact artist/shelf layout remain unfinished. Phase 6 motion/Canvas/player surfaces are excluded. |
| Library/playlist operations, P05/P06 | `D/DesktopLibraryLoadPolicy.kt`, `DesktopPlaylistUi.kt`, `DesktopSearchClient.kt`: account load, create/rename/delete/add/remove, like/library state | NOT IMPLEMENTED as complete Flutter flows; the existing Library surface explicitly says so. This is an implementation gap, not a successful empty library. |
| Accounts, P20 | `D/DesktopAccounts.kt`, `DesktopYouTubeAuth.kt`, `DesktopBrowserSignIn.kt`, `DesktopWebSignIn.kt`, `DesktopSecretStore.kt`, `DesktopWindowsCrypto.kt` | Existing process-only cookie verification and generation cancellation remain. Browser sign-in/import, protected persistent multi-account sessions, refresh/profile switching and secret erasure matrix are unfinished. User offered to sign in through UI; no credentials were requested or supplied, and no fake browser flow is shipped. |
| Spotify, P06/P21 | `D/DesktopSpotifyLibraryAuth.kt`, `DesktopSpotifyChromiumToken.kt`, `DesktopSpotifyLibrary.kt`, shared Spotify library/import | NOT IMPLEMENTED. Reference captures the real web player's bearer plus client token, with separate refresh and library paging/matching. A generic OAuth or guessed token is not a substitute. Canvas is outside this Phase 4 increment. |
| JioSaavn/add-ons/modules, P17/P18 | `D/DesktopJioSaavn.kt`, `DesktopAddon.kt`, `DesktopModules.kt`, `DesktopSourceRegistry.kt`, `DesktopTrackMatcher.kt` | Existing real typed JioSaavn and HTTP add-on search/resolve are retained and fixture-tested. Persistent source management/order/settings, isolated custom-module JS host, full matching/fallback/upgrade policy are incomplete. Add-on addresses remain session-only. |
| Streaming, P17/P19/P11/P12 | `StreamResolver.kt`, `InnerTubeXResolver.kt`, `D/DesktopRangeStream.kt`, `DesktopAudioCache.kt` | Direct-first guest resolver, authenticated extractor path, bounded broker, renewal/rendition checks and single seek on drag release are retained. Sequential loaded-context Next/Previous/EOF is added above the same port and native engine, fenced by the adopted native entry identity. No gapless/prepared handoff, upgraded-rendition proof, manifest/live/SABR support, complete native span-cache equivalence or long-duration physical matrix is claimed. |
| Download queue, P08 | `D/DesktopDownloadQueue.kt`, `DesktopDownloadManagerUi.kt`: four workers, deduplication, sequence, failure/retry/cancel/clear | Implemented real four-worker queue, key-isolated storage writers, progress, cancel/all, failed retry/clear and source-sized manager. Old cancelled writers keep their slot until quiescent; stale progress cannot resurrect a job. Jobs are transient as in Kotlin. Default is Lossless; full persisted quality/settings and filesystem destination parity are still open. |
| Offline/recovery, P08/P35/P36 | `D/DesktopDownloadManager.kt`, `DesktopManifestDownload.kt` | Separate staging/completed hash journals preserve a valid offline file when replacement fails; account-free identity/rendition validation, interrupted resume, promotion-crash recovery, duration metadata, corruption exclusion and removal are tested. Existing journals still read; older partials without rendition safely restart. HLS/DASH remux, disk-full/permission fault matrix, offline artwork and full destination/library integration remain open. |
| History, P09 | `D/DesktopListeningStats.kt`, `DesktopPlaybackTracker.kt` | Full online listening/history recording remains incomplete; later Replay/integration scope is not started. |

## Browser capability finding

Linux workstation exposes WebKitGTK 4.1/6.0 **2.54.2** and libsecret **0.21.8.2**
through pkg-config. That proves installed native libraries, not a Flutter browser
or protected-store bridge. The Windows source uses a dedicated browser sign-in
profile and a separate session capture pass; the Spotify source harvests a real
web-player response. No cross-platform Flutter bridge has yet been implemented
or validated against those contracts. Do not attribute this missing code to the
user lacking an account: the user is willing to log in when a concrete UI is ready.

## Visual evidence and limits

Source measurements were applied only to online surfaces. Existing global shell,
sidebar, theme definitions and native window geometry were not redesigned.

- Search: 42dp pill; maximum 540dp area including 24dp horizontal insets;
  18/14dp top/bottom spacing, 9dp inner inset, original Search SVG; source palette
  fill/border; query/suggestion actions and keyboard/fill behavior are tested.
- Collection: 286dp/8dp artwork, 30dp gap, 36dp lower margin; source text roles,
  50dp controls, 28dp number column, 44dp playlist artwork and striped rows.
- Download manager: maximum 520dp card, 20dp radius, source palette, 20/8dp row
  insets, 40dp/6dp artwork, 2dp list gaps, 3dp progress, maximum 360dp list.
- `../phase4-search-download/*-widget-900x600.png` are **widget diagnostics** in
  Light/Dark at DPR 1, not native platform captures or Kotlin goldens. Ahem font
  rectangles intentionally cannot establish font/icon/ink or pixel equivalence.
  Material popup/focus/hover animation details still require source comparison.

No matched Kotlin/Flutter Phase 4 native screenshot set has been captured for
every page, theme, content, resize and DPI. Existing checked-in Kotlin shell
screenshots cannot close that gate. Full functional and visual **1:1 parity is
NOT established**. Loading/error/empty/custom menus and remaining unavailable
surfaces in the table still fail the phase's complete-UI requirement.

## Checks actually performed locally

- Official Flutter **3.47.9**, framework `c197ca5199`, engine `89c39edfdf`,
  Dart **3.13.7**; official standalone Dart archive was SHA-256 checked before
  the matching Flutter toolchain was installed in `/tmp`. Lockfile enforced.
- `dart format --language-version=3.11 lib test`; fatal analyzer passed.
- Full Flutter suite: **56 tests passed** after catalog-context addition.
- `tool/phase4_check.dart`: existing HTTP/provider/YouTube regressions plus new
  pagination/suggestion/storage scenarios passed; real broker→native decode
  produced **384000 exact PCM frames**, zero surviving decoders.
- Local native library/stream test built with **Clang 23.1.4** and pkg-config
  FFmpeg/Pulse/ALSA/SQLite flags. Local CMake executable and Xvfb are unavailable;
  no local CMake/release/native GUI pass is claimed. Windows/Linux official
  CMake builds/release/GUI validation are delegated to the pushed CI matrix.
- Layer/asset/reference token and Phase 2 inventory verifiers passed. Kotlin
  reference source trees are unchanged. No performance improvement is measured.
- [Redacted live report](catalog-live-2026-11-13.json): Linux guest Home 30 items,
  next Home 29/3 shelves; New 49; Explore 68; All 30; each filtered Search 20 and
  next page 20; real album/artist/playlist details; six suggestions. These are
  provider response observations, not physical playback or equality to Kotlin.

The earlier user acceptance of fast YouTube startup, immediate scrubbing and
offline playback after restart remains USER_VERIFIED. This increment's automated
fixtures do not turn that report into independent physical Windows/Linux proof.

## Remaining Phase 4 gates

Implement and validate the browser/protected-account bridge, authenticated
library mutations and Spotify library/token flows first; then finish static
artist/shelf/discovery/search/menu parity, module/source management, persisted
download quality/manifest/cache/fault handling and matched native visual/live
account/physical playback evidence. Use the user's offered interactive login
only after a real UI exists. Do not request cookies/passwords in chat, mark the
phase complete, or start Phase 5 to work around these unfinished gates.
