# Phase 2 reference and visual evidence

Reference is always `348b613a0c3db1a54de56483ea6f8748d6465211`.
Run `python3 tool/phase2_reference.py --check` from `flutterDesktop/`.
The extractor reads Git objects, not an installed profile. CI fetches that exact
object, then checks byte-for-byte reproducibility. Generated shell copy preserves
the repository GPL-3.0 notice; font files are excluded.

`reference-inventory.json` contains all ten destination IDs, all 20 overlay/state
properties in DesktopOverlays, its ten settings pages, source hashes, line-numbered
state/default/visibility/empty/semantics excerpts across desktop Kotlin, and the
complete Settings section declaration/rendering block. The latter preserves all
18 sections, literal labels, visibility predicates and callback bindings instead
of pretending that every preference default is a constant. Source expressions
are not executed defaults. This is a source fixture, not a rendered golden.

Navigation/state catalog (paths below are relative to desktop Kotlin package):

| Surface | Reference / initial state | Flutter Phase 2 |
|---|---|---|
| Home / New | DesktopApp.kt; Loading -> Success/Error, shelves, recents, mood grid, continuations | Honest disconnected state, immediate tab change |
| Search / Explore | DesktopApp.kt, DesktopSearchSession.kt; empty suggestions/Explore landing; query/typeahead/filter/results/error, selected mood/genre | Both IDs catalogued; Search tab selected for Explore; disconnected body |
| Library / Spotify | DesktopApp.kt; signed-out, Loading, empty/content/error, show-all shelf, import | Disconnected; no invented account/library |
| History / Downloads / Local Music | DesktopApp.kt; local records, scan pending/loading/empty/error, download progress/failure | Disconnected; real repositories deferred |
| Settings | DesktopOverlays.kt settingsPage MAIN or null; ten pages, 18 groups in complete source fixture | Appearance shell plus zoom; remaining settings catalogued |
| Collection / artist / shelf / Replay | DesktopApp.kt openedCollection/openedArtist/libraryShowAll and DesktopShelfBrowsePage.kt; back snapshot restores selection/scroll; loading/paging/error | Catalog only, requires later domain/catalog services |
| Welcome | DesktopWelcomeOnboarding.kt, DesktopWelcomeScreen.kt; marker absent -> sign-in/skip/busy/error | Catalog only; never writes reference marker |
| Account / drawer | DesktopApp.kt and DesktopOverlays.kt; anonymous account list, anchor, outside click/Escape; narrow drawer transient | Empty account menu, outside/Escape/Back dismissal; background focus/semantics excluded |
| Player | DesktopSidePanel.kt LYRICS/QUEUE/DETAILS, DesktopPlayerSheet.kt, DesktopPip*.kt; empty/selected/loading/error/fullscreen | Empty dock only; unavailable transport remains disabled |
| Modal inventory | pipeline, audioOutput, listenTogether, songMenu, downloadManager, replay, playlistDialog, spotifyImport, rename, delete, nowPlaying, settingsPage, lastfmLogin, listenBrainzToken, discordToken, accounts, signIn, shortcuts, quickSearch, sidePanel | Accounts only; all names preserved in source fixture; no dummy functioning integrations |

Keyboard reference: DesktopShortcuts.kt, DesktopKeyboardShortcutsModal.kt,
DesktopApp.kt key handler. Implemented shell F11/Escape, Alt+Left,
Alt+Shift+H, Ctrl+Shift+L are nonrepeating. Full configurable shortcuts, text-entry
exceptions and Quick Search belong to their functional surfaces and remain open.
Tab/Enter uses Flutter InkWell/controls; reference `contentDescription` excerpts
are recorded, but no screen-reader equivalence is claimed. Modal background focus
and semantics are suppressed; full reference focus restoration remains open.

## Fixture provenance and comparison limits

Retain existing real screenshots rather than relabel them as new Phase 2 images:
- Linux: `../phase1-linux-final/capture.json` and four PNGs. Same isolated Xvfb,
  96 DPI, Light/100%, two client sizes, temporary profiles, Kotlin SOFTWARE and
  Flutter llvmpipe. Each image has its SHA/time/source/run. Earlier Flutter commit
  predates installed `bitchord-sans`; these images cannot verify the new font.
- Windows: `../phase1-shell-correction/` pinned Kotlin screenshots and provenance;
  final Flutter images plus hosted native JSON. Match client area, not outer frame.
- Current CI: `../hosted-run.json`, `../native-{linux,windows}.json` and PNGs are
  updated only by the guarded successful matrix retention job. Flutter-only,
  96-DPI native lifecycle evidence; not Kotlin visual or physical-GPU acceptance.

No new matched multi-DPI Kotlin capture or motion trace was obtained in this task.
Local disk has less than 1 GiB free; no full Flutter SDK or retained isolated
reference capture environment is installed. Hosted reference bundles can run,
but capturing all reference interactions at four display scales and native
compositor behavior requires a dedicated capture pass. This is an explicit
missing-evidence gate, not a JitPack failure or synthetic screenshot.

## Visual gap ledger

| ID / source | Flutter file | Fixture / measured result | Remaining gap |
|---|---|---|---|
| P01 Main.kt / Welcome | app/app.dart | Source fixture only | Welcome/sign-in states deferred; no onboarding parity |
| P02 DesktopApp.kt / sidebar width policy | features/shell/presentation/desktop_shell.dart | Existing 1220x780 and 900x600 pairs; 220/72 sidebar, 980 breakpoint, 7 dp row padding retained | Nested data-route restore, all content states; Explore alias |
| P03–05 Home/Search/collection components | same shell file | Source states in inventory, disconnected bodies | Catalog/search/detail visuals require real feature services |
| P24 PlayerDock / PlayerChrome | same shell file | 760x58 dock, original mark/transport; CI native capture | Full player/queue/lyrics/video/PiP, live controls unavailable |
| P28 DesktopShortcuts.kt | same shell file | Widget Back/drawer and modal tests; native F11/Escape smoke | Configurable shortcuts, text editing, physical screen-reader/focus trace |
| P29 DesktopTheme/Typography/UiScale | design_system/{theme,palette,desktop_scale}.dart | Both exact palettes and SVGs source-checked; source zoom 80/90/100/110/125; widget display DPI 100/125/150/200% | No new matched native DPI goldens, weight/glyph/wrapping measurements; installed font rights unverified; no binaries bundled |
| P30 DesktopSurfaces / SidebarMica / MenuPortal | design_system/theme.dart, shell presentation | Bounded clipped BackdropFilter, global blur disable, 180ms sidebar or zero with reduction; modal background excluded | Haze noise/gradient equivalence, Mica/native caption/icons, motion trace, menu edge clamp/focus restoration |
| P31 DesktopStrings / Settings | l10n/*, shell domain/data/presentation | 15 source shell catalogues; language -> English -> call-site fallback; OS-language change rebuild; legacy 5-field settings read | Language picker/RTL and feature strings, full Settings visuals/search/default/import matrix; this is a scoped resource pipeline, not full gen_l10n migration |

Display DPI and user zoom are independent. Widget checks preserve logical
1220x780 at DPR 1/1.25/1.5/2, navigate to Settings then select 110% and verify
sidebar scales to 242 logical pixels with transport disabled. Widget Ahem font
and synthetic DPR are layout/interaction checks, not physical text acceptance.
No performance measurements or audio implementation are part of Phase 2.
