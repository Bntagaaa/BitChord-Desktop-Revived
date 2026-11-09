# Flutter desktop presentation: source and reference inventory

## Baseline and interpretation

The presentation port is based on the Kotlin source at commit
`348b613a0c3db1a54de56483ea6f8748d6465211` and the fifteen screenshots in the
user-supplied `Untitled design.pdf`. The PDF is a Canva export with raster images
only; it supplies visual examples, not animation timing or interaction rules.
White page margins and the desktop taskbar are outside BitChord's interface.

This document distinguishes implemented presentation code from validated visual
parity. A functioning route or matching color token does not establish a 1:1
match. Platform screenshots, native playback checks, and comparison against the
same account/catalogue state are still necessary before a parity claim.

## Source-backed design tokens

| Contract | Kotlin source | Flutter implementation |
| --- | --- | --- |
| Dark, light and pitch-black colors | `DesktopTheme.kt` | `presentation/desktop_theme.dart` copies the semantic color values. Pitch black changes content and navigation to black; the setting appears only for explicit Dark mode. |
| Default and Legacy type profiles | `DesktopFontSettings.kt`, `BitChordTypography.kt` | The original font assets and physical weight mappings are declared in `pubspec.yaml`; size/tracking roles are reproduced in `buildDesktopTheme`. |
| Native mobile navigation glyphs | `DesktopPrimaryNavigationIcons.kt` | Converted original vectors, `assets/icons/nav-*.svg`. |
| Brand and playback glyphs | Original logo, wordmark, no-music and transport resources | Original converted SVGs used in the sidebar, dock and transport controls. |
| Expanded / collapsed navigation | `DesktopApp.kt:DesktopSidebar`, `DesktopSidebarWidthPolicy.kt` | 220 / 72 logical pixels; automatic rail below 980; temporary expanded drawer does not overwrite the saved collapse preference. Short windows can scroll navigation. |
| Selection treatment | `DesktopTheme.kt`, `DesktopSidebarItem` | Gray selected surface, rose icon and 3-by-18 indicator. |
| Bottom dock | `DesktopApp.kt:DesktopMiniPlayer` | Maximum width 760, height 58 in both empty and playing states, 42 artwork, radius 16, bottom progress line. The no-music glyph remains 48 inside the same height. |
| Context menus | `DesktopSongMenuUi.kt`, `DesktopMenuPortal.kt` | Clipped blurred surface, compact rows, top quick actions, viewport-aware placement and maximum height 520. Transparent dismissal barrier; no screen-darkening scrim. |
| Blur preference | `DesktopAppearanceSettings.kt` | `reduce_dynamic_blur` switches glass surfaces to solid fills. No obsolete per-area controls silently override the global preference. |
| Now Playing metadata | Latest desktop player details source | Title, artist and Playing from update directly; the removed title-slide/fade choreography is not restored. Like remains present. |
| Equalizer | Desktop equalizer source, Dart `playback/equalizer.dart` | Seven real frequency controls and sixteen source presets; changes are sent to the native playback pipeline. |

## Screenshot coverage

| PDF page | Reference | Implemented surface / behavior | Remaining comparison |
| --- | --- | --- | --- |
| 1 | Home, Top Picks, Recents and dock | Live catalogue shelves, tall first-shelf artwork with clipped bottom blur, local recent history, global player | Personalized Top Picks generation and exact composition depend on catalogue/account data; reference account data is not fabricated. |
| 2 | Pink immersive player and Japanese lyrics | Artwork backdrop, left artwork, right lyrics, line sync, original transport glyphs | Compare actual Japanese font fallback, line wrapping, backdrop colors and scale on each desktop platform. |
| 3 | Song menu over Home | Like, Dislike, Share; playlist/radio/queue/download actions; album/artist links; information; sleep timer | Hover states and density require rendered comparison. Menu does not dim Home. |
| 4 | Quick Search and loading lyrics pane | Ctrl/Cmd+K popup, delayed query, stale-result protection, arrow selection, Enter play/open, full-search action | The PDF's dimmed screenshot is not used to restore a removed menu scrim. |
| 5 | Side lyrics | Light/dark semantic foreground, active-line emphasis, seek by line, manual-scroll grace period and auto-scroll | Exact motion easing and fallback-font typography require platform validation. |
| 6 | Queue side panel | Current item, autoplay origin, playback, removal, drag reorder and autoplay toggle | Queue item identity is preserved independently of video identity. |
| 7 | Full player and song menu | Title/artist/source, restored Like, transport, seek, volume, audio output and the shared song menu | Relative artwork/control geometry must be compared at the reference window dimensions. |
| 8 | New | Live explore shelves, songs and video cards | Server-dependent order is preserved; no screenshot titles are hardcoded. |
| 9 | Library | Remote library shelves, local playlists, liked tracks, history/download/local/Spotify links and real collection counts | The source's listening analytics cards are not represented as fabricated hours or totals. Current cards show stored collection counts. |
| 10 | Spotify | Public-playlist import, matching progress, cancellation, unmatched warnings and resulting local playlists | Full signed-in Spotify catalogue browsing is not established by the public importer. |
| 11 | Liked Music and playlist actions | Gradient artwork, list, play/shuffle/filter, queue/download/share, local pin/rename/delete | Online mutation coverage depends on the application/data services; local and cloud collections must not be conflated. |
| 12 | History | Persisted history list with playback and song actions | Date-group presentation and advanced listening statistics still need comparison. |
| 13 | Collapsed and expanded sidebar | Original primary SVGs, saved collapse state, responsive rail and temporary drawer | Compare scaling at 80, 100 and 125 percent. |
| 14 | Large synchronized lyrics | Synchronized scrolling, line seeking, word timing where present, TTML singer-side alignment, source translation/romanization plus explicit on-demand Translate/Romanize/Original actions | Animated backgrounds and alternate lyric styling are not inferred from this static screenshot. |
| 15 | Picture-in-picture | Main player launches the native PiP service without constructing a second player in presentation | Native window behavior and platform synchronization belong to the PiP implementation and need system tests. |

## Working interactions added beyond the screenshots

- Search exposes real songs, videos, albums, artists and playlists. Catalogue
  continuation is available on supported Home, Search and detail pages.
- Local music uses the import/scan service; artwork is decoded at the rendered
  physical size rather than holding full-resolution album images for every row.
- Downloads expose resolving, transfer, completion, failure, retry and cancel
  state from the actual download service.
- Spotify import displays actual progress and unmatched-track warnings.
- Account import uses a masked session input and the secure authentication
  service. The UI does not claim that opening an unrelated browser tab completes
  sign-in.
- Settings include working theme/font/scale/blur/player preferences, audio
  output, seven-band equalizer and presets, crossfade, autoplay, supported lyric
  provider order, synced-lyrics enable/disable, timing/size, catalogue
  region/language, legacy profile import,
  and music-source management. Unsupported native features are not shown as
  apparently successful toggles.
- All six source content preferences are wired: music-only version normalization,
  Recents, video shelves/items, Top Picks, mood shelves and video thumbnail shape.
  The application/playback layers resolve music-only versions lazily.
- The exact fourteen source keyboard shortcuts share one dispatch/help table,
  including five-second seek, five-percent volume adjustments and editor-focus
  suppression. Native media keys and common Search/Back aliases are also supported.
- Disabling synced lyrics clears the lyrics panel, hides provider controls and
  instructs the application service to stop fetching remote lyrics.
- Translate, Romanize and Original actions call the real lyric transformation
  service with stale-track protection. Transformation failures remain visible.
- Owned cloud playlists have authenticated create, rename, delete, add and remove
  operations. The picker only lists targets whose current server response confirms
  ownership; local playlist identifiers are kept separate.
- Timed lyrics listen to playback separately from the catalogue. The active
  word line alone consumes sub-line position updates; the catalogue is not
  rebuilt on every playback clock tick.
- Queue drag handling uses Flutter's `onReorderItem` final-index callback, so a
  downward drag is not adjusted twice before mutating the queue.
- Sleep timers support preset/custom durations and end-of-track behavior.

## Explicit outstanding parity areas

The following must be checked against the implementation as development
continues. Their absence must not be obscured by a working button or a screenshot
of another route:

- Full authenticated Spotify browsing, Canvas configuration and animated artist
  artwork are not equivalent to importing a public playlist.
- Every original settings section needs a backend capability audit: Listen
  Together, integrations/scrobbling, update installation, platform-native title
  materials, advanced audio precision and source-specific configuration.
- Personalized Top Picks, mood stations, yearly replay and historical listening
  analytics need their actual source services/data models.
- Some secondary menu glyphs use Flutter icons. Primary navigation, brand and
  playback transport use the source vectors; a blanket claim of identical icons
  would be inaccurate.
- Animated mesh, Canvas and native compositor effects need their own parity and
  performance checks. Static raster screenshots cannot certify them.

## Verification

`flutterApp/test/presentation/desktop_layout_test.dart` exercises the principal
routes at narrow/regular desktop dimensions, the fixed-height dock, transparent
menu barrier plus actual sleep action, light-theme lyrics contrast, and Quick
Search focus. These tests use explicit local fixtures and do not substitute
sample songs for production services.

Dart formatting/parsing and scoped static analysis of presentation and widget
fixtures succeeded during authoring. Widget execution was attempted but blocked
before the tests by the environment's exit-255 failure while running the
`objective_c` package's native-asset Dart hook. This is an execution blocker, not
a passing widget result. Runtime results and pixel comparisons must be recorded
from actual execution; no cross-platform approval is implied by static analysis.

Recommended visual captures use the same viewport and UI scale for: Home,
Search, a populated collection, light and dark lyrics panels, queue, full player,
context menus and PiP. Capture both active playback and an empty dock. Check
keyboard navigation, small-window scrolling, source failure states and modal
dismissal alongside the images.
