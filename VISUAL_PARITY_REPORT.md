# BitChord desktop visual parity

Status: **In progress**. This is an initial real-application audit and a focused
presentation correction, not complete migration acceptance. Work is on
`codex/visual-parity`, targeting `2.0.0-flutter`; no direct target-branch push or
merge is authorized. Phase 4 remains INCOMPLETE; Phase 5 is not started.

## Reference, scope and implementation plan

The Kotlin worktree is clean at `348b613a0c3db1a54de56483ea6f8748d6465211`.
The locally rendered reference is the unmodified Linux distributable from
[reference run 43225291641](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43225291641),
whose `verification/reference-sha.txt` was checked. It was not rebuilt in or
modified through the reference worktree. Antigravity and integration worktrees
were not modified or merged.

Discovery covered AGENTS.md, repository/Flutter READMEs, migration progress,
parity, architecture, performance protocol, existing phase roadmap, Flutter
entrypoint/router/theme/catalog/shell/tests and reference workflows. Current
baseline [Flutter run 43557922499](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43557922499)
was confirmed successful rather than inferring current status from older notes.
Its source is `6ad9a1d0`, followed by retention `53b83330`, the clean starting
commit of this worktree. No previous Flutter rewrite was used.

Plan: render both apps with disposable profiles; inventory actual mismatches;
correct presentation defects supported by source and paired captures; add
repeatable capture/diff tooling; run local checks; publish a PR and inspect its
Windows/Linux matrix. Service dependencies remain with Antigravity.

Affected implementation IDs: **P02/P04/P29/P30**. Audit-only gaps additionally
cover P03/P05/P06/P08/P09/P20/P24/P27/P31.

Pinned reference paths, relative to `desktopApp/src/main/kotlin/com/music/bitchord/desktop/`:

- `Main.kt`: actual entrypoint, outer-window geometry, density override.
- `DesktopApp.kt`: `DesktopFrame`, `DesktopSidebar`, `DesktopSidebarItem`,
  `DesktopMiniPlayer`, `DesktopPageBackdrop`, desktop Search field slot,
  `DesktopLocalMusicPage`, `DesktopDownloadsPage`, `DesktopSettingsSection`.
- `DesktopTheme.kt`, `DesktopTypography.kt`, `DesktopFontSettings.kt`,
  `DesktopSurfaces.kt`, `DesktopSidebarWidthPolicy.kt`: tokens, font cuts,
  glass, responsive policy.
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt`
  and `ui/theme/BitChordTypography.kt`: search landing, filters and typography.

## Capture environment and evidence

2026-11-14, CachyOS Linux x64, kernel `7.3.3-1-cachyos`, KDE Wayland session,
both apps on the **same XWayland display `:0`**. X11 reports 1920×1080 pixels,
508×285 mm (nominal 96 DPI), desktop scale 1×, app UI zoom 100%.
Client captures are **1220×780** and **900×600**; window frames are excluded.
Kotlin's initial 1220×780 outer window yielded a 1220×752 client, which was
explicitly resized before comparison. The user's display mode was not changed.
An additional 1220×780 Dark pair was captured after changing only the temporary
profiles; both blank page samples are `(32,32,32)`. App ownership was checked
against window PIDs before retaining that pair.

Kotlin uses its packaged Java 21 runtime and explicit Skiko SOFTWARE rendering.
Flutter uses the pinned SDK **3.47.9**, framework `c197ca5199`, Dart **3.13.7**,
Impeller OpenGLES on Mesa Intel UHD Graphics (ICL GT1), Mesa 26.2.6. This renderer
difference limits antialiasing/material conclusions; these are still actual
running desktop applications, not widget screenshots. Release distribution
builds are reserved for CI; local builds were debug only.

Both apps use separate `/tmp/visual-profiles/` XDG directories. Kotlin also has
isolated `user.home` and Java Preferences. Flutter has a separate `--data-root`.
No existing account/profile was imported or signed in. Kotlin onboarding was
dismissed using **Skip For Now** in that temporary profile. Logs and profiles
are not committed. PNGs were visually reviewed; they show anonymous public
catalog artwork and signed-out account surfaces only.

Kotlin bundles five SF Pro Display OTF cuts and applies Default-profile weight
mapping. Flutter has no bundled font binaries: rights/provenance remain open.
The user's installed five-cut `bitchord-sans` family was inspected with
`fc-scan`. A task-local Fontconfig file exposes that directory while XDG data
is isolated. An earlier fallback-font trial was excluded from committed paired
evidence. Results on machines without the installed family need revalidation.

Each local capture has a timestamp/hash/source/state/geometry sidecar. Final Flutter
sidecars identify the starting commit plus the SHA-256 of the actual `lib/`
diff used for its debug build. Baseline captures use the initial source.
[Capture and comparison commands](visual-parity/README.md) are reusable.
Originals are preserved; full-frame comparisons deliberately retain visible
mismatches. Gray rectangles in explicitly named `-sidebar` comparisons exclude
the entire feed/dock from both metrics and derivative views.

## Observed corrections

1. **Light backdrop:** Kotlin's `DesktopPageBackdrop` uses `replaySurface`, not
   the general `background` token. Flutter's visible page fill changed from
   `(247,248,251)` to `(245,246,250)`, matching a blank reference sample at
   client `(1100,500)`. The palette definitions themselves were preserved.
2. **Search header:** removed the extra Search page heading and outer top inset.
   The real field moved from y=70 to **y=18**, matching Kotlin. At 1220×780 it
   occupies `(474,18,492,42)`; at 900×600 the tested bounds are
   `(240,18,492,42)`. The redundant title is absent.
3. **Content behind the player:** moved the 132-pixel dock reserve from the
   catalog viewport boundary into the scrollable lists. Real artwork now
   continues behind the dock at 900×600; the last rows retain scroll clearance.
   This fixes the blank area/incorrect glass input without changing catalog,
   authentication, persistence, networking or audio services.
4. **Selected navigation marker:** expanded navigation now uses the reference's
   3-pixel corner radius; the collapsed rail keeps its rectangular marker.
5. **Reusable evidence tooling:** real client capture, geometry/hash checks,
   side-by-side output, 50% overlays, amplified pixel differences and explicit
   masking/metrics. Changed hashes, unequal client sizes and incompatible
   state/theme/scale metadata are rejected; no automatic parity verdict.

## Screen inspection ledger

| Surface/state | Status | Actual evidence / remaining gap |
|---|---|---|
| Main shell, Light 100%, 1220×780 and 900×600 | In progress | Both rendered and recaptured; geometry/background corrected. OS chrome, other DPI/zoom/motion still need review. |
| Sidebar/rail/drawer, signed out, Light | Visually verified, scoped static geometry | 220 px expanded, 72 px rail, 980 px breakpoint; matched Home selection, footer/dividers, drawer captures. Residual glyph rasterization/brand edges remain. This is not whole-app acceptance. |
| Home | In progress | Real anonymous feeds differ in shelf identity/order/content. Under-dock viewport corrected. Matching Home/provider projection is an Antigravity dependency. |
| New | Needs revalidation | Baseline pair caught real loading states; no matched settled feed acceptance. |
| Search field | Visually verified, scoped geometry | Post-change pair and widget bounds at two client sizes; no duplicate heading. |
| Search landing/results/filter/source surfaces | In progress | Kotlin renders Moods/Genres landing; Flutter has an empty search body, source/add-on controls and generic chips. No matching landing adapter; Videos filter is absent from the domain contract. Source selection functionality was retained. |
| Library, signed out | In progress | Actual baseline pair: Kotlin local navigation/playlist tiles and sign-in prompt; Flutter account-library prompt only. Local playlist/aggregate UI needs its domain actions. |
| Downloads | In progress | Actual empty-store pair. Flutter lacks the reference's local search/sort/view/group tabs and icon/title empty state. Group metadata/persistence contracts need coordination. |
| History | Blocked | Reference empty state rendered; Flutter explicitly reports the unimplemented management surface. Do not replace that notice with a false empty-history result. |
| Local Music | Blocked | Reference local search/tabs/empty state rendered. Flutter scanner/library presentation is not implemented. |
| Spotify | Blocked | Reference connection/import surface rendered; Flutter Spotify integration surface is not implemented. |
| Settings | In progress | Reference settings root actually rendered; source inventory has 18 groups. Flutter exposes a small Appearance/About subset with generic controls. A screenshot of that subset does not verify the other groups. |
| Account menu | In progress | Both signed-out menus captured. Reference compact account chooser differs from Flutter's full session/login form. Coordinate presentation split with Antigravity's ongoing account work. |
| Login/account-connected states | Blocked | No credentials used. Browser launch, authenticated profiles/channel switching and signed-in visual states unverified. |
| Empty player bar | In progress | Both rendered at two widths; bounds/brand/control alignment close. Kotlin volume is black/active; Flutter empty-state volume is gray/disabled. Blur uses different live content, so material parity is unverified. |
| Queue | Blocked | Reference real empty Queue pane captured. Flutter's empty dock Queue action is disabled; no equivalent pane was rendered. |
| Lyrics | Needs revalidation | Reference Lyrics activation with no song left Home visible; this is recorded as inactive, not a rendered Lyrics screen. A real matching track/lyrics session is needed. |
| Expanded player / playback states / PiP | Not started | No matched playing track/session used; disabled empty-state controls cannot verify these surfaces. |
| Album / artist / playlist detail | Not started | No matched detail ID/content state rendered on both apps in this pass. Existing Flutter detail widgets are not promoted by source inspection. |
| Context menus / collection dialogs / loading/error variants | Needs revalidation | Native account and drawer states inspected; broader per-feature variants and pointer/focus/motion comparison remain open. |
| Windows / display DPI 125–200% / other UI zooms | Needs revalidation | Existing tests/hosted evidence are not a new matched native comparison. This capture pass covers Linux 1× / UI 100% only. |

Sidebar-only supporting metrics after correction:

| Client | Compared pixels | Excluded pixels | MAE RGB (0–255) | Pixels with any channel delta >8 |
|---|---:|---:|---:|---:|
| 1220×780 | 171600 | 780000 | 1.4843 | 2.9149% |
| 900×600 | 43200 | 496800 | 1.5019 | 2.4444% |

These metrics include residual antialiasing/icon edges and **exclude all page
content and the dock**. They do not establish pixel-perfect parity or a whole
screen pass. Full-frame differences show substantial remaining content/UI gaps.

Useful review links (reference is left in each pair):

- [Home, wide pair](visual-parity/reports/final-home-1220x780.png),
  [overlay](visual-parity/overlays/final-home-1220x780.png),
  [difference](visual-parity/diff/final-home-1220x780.png).
- [Home, rail pair](visual-parity/reports/final-home-900x600.png),
  [overlay](visual-parity/overlays/final-home-900x600.png),
  [difference](visual-parity/diff/final-home-900x600.png).
- [Search before](visual-parity/reports/baseline-search-1220x780.png),
  [Search after](visual-parity/reports/final-search-1220x780.png).
- [Accounts](visual-parity/reports/final-accounts-1220x780.png),
  [drawer](visual-parity/reports/final-drawer-900x600.png),
  [reference Queue](visual-parity/reference/audit-queue-1220x780.png).
- [Dark Home](visual-parity/reports/final-home-dark-1220x780.png),
  [overlay](visual-parity/overlays/final-home-dark-1220x780.png),
  [difference](visual-parity/diff/final-home-dark-1220x780.png).
- [Baseline Settings](visual-parity/reports/baseline-settings-1220x780.png),
  [Library](visual-parity/reports/baseline-library-1220x780.png),
  [Downloads](visual-parity/reports/baseline-downloads-1220x780.png).

## Actual commands and verification

Reference build entrypoint, discovered in README/build/workflow:
`./gradlew :desktopApp:run`; distributable task
`:desktopApp:createDistributable`, main class `com.music.bitchord.desktop.MainKt`.
No local Kotlin build was necessary. Actual local launch:

```sh
JAVA_TOOL_OPTIONS='-Duser.home=/tmp/visual-profiles/kotlin/home -Djava.util.prefs.userRoot=/tmp/visual-profiles/kotlin/prefs -Dskiko.renderApi=SOFTWARE -Xmx768m' \
XDG_CONFIG_HOME=/tmp/visual-profiles/kotlin/config \
XDG_DATA_HOME=/tmp/visual-profiles/kotlin/data \
XDG_CACHE_HOME=/tmp/visual-profiles/kotlin/cache \
/tmp/visual-kotlin-reference/desktopApp/build/compose/binaries/main/app/BitChord/bin/BitChord
```

The pinned Flutter SDK was bootstrapped at `/tmp/visual-flutter-sdk`, then moved
to ignored `flutterDesktop/build/visual-toolchain/flutter` to reduce tmpfs/RAM
pressure on this 8 GB machine. Signed Arch tools live in `/tmp/visual-tools`.
Commands below ran from `flutterDesktop/` with that SDK on PATH and the local
tool bin/lib paths configured:

```sh
flutter precache --linux
flutter pub get --enforce-lockfile
dart format lib/features/shell/presentation/desktop_shell.dart lib/features/online/presentation/catalog_surface.dart test/search_suggestions_test.dart
flutter analyze --no-pub --fatal-infos
flutter analyze --no-pub --fatal-infos lib test
flutter test --no-pub --concurrency=1
flutter build linux --debug --no-pub
python3 tool/check_boundaries.py
python3 tool/verify_reference_tokens.py
python3 -m unittest discover -s tool -p test_visual_parity.py
```

Results: analyzer **PASS**; **71/71 Flutter tests PASS**, no skipped tests;
Linux debug build **PASS** and real native windows rendered; layer and 20-asset
hash checks **PASS**; palette/vector/transport checks **PASS**; **3 Python diff
tool contracts PASS**. New widget coverage verifies the two Search bounds and
the full-height catalog viewport. Initial new-test import/cleanup failures were
corrected before the passing full suite; no assertion was disabled.

Actual Flutter native launch:

```sh
XDG_CONFIG_HOME=/tmp/visual-profiles/flutter/config \
XDG_DATA_HOME=/tmp/visual-profiles/flutter/data \
XDG_CACHE_HOME=/tmp/visual-profiles/flutter/cache \
FONTCONFIG_FILE=/tmp/visual-profiles/fontconfig.xml GDK_BACKEND=x11 \
build/linux/x64/debug/bundle/bitchord_desktop --data-root=/tmp/visual-profiles/flutter/local
```

Flutter SDK auto-added analyzer exclusions during local dependency setup; those
unrelated changes were reverted. No workflow, native engine, authentication,
provider, network, database or user data changes are included. No release,
physical audio, RAM/CPU improvement or full migration acceptance is claimed.

## CI / PR delivery

Published as [PR #139](https://github.com/Bntagaaa/BitChord-Desktop-Revived/pull/139),
source `3ffb3ac5a34c7ed05b7fa40eafa9fa71190b11fd`.
[Windows/Linux matrix 43584021965](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43584021965)
**failed the native drawer smoke probes on both platforms**; no complete
new-source CI pass is claimed yet. The existing
`.github/workflows/flutter-desktop.yml` handles pull requests; its shared
configuration is unchanged. Result and artifact links will be
recorded after observing the actual run. The successful baseline above is
not new-source verification.

First PR attempt: both platforms passed format/analyze, **71 tests**, native/
provider contracts and release bundle builds, then failed their drawer probes.
The fresh [CI PNG](visual-parity/flutter/ci-43584021965-drawer-900x600.png)
shows that `(880,500)` is now artwork, as intended after the viewport fix:
the log observed `(87,86,136)`. The safe right gutter `(895,500)` is
`(182,182,185)` under native Linux Impeller's scrim. The smoke previously
expected the old backdrop's `(183,184,186)`. Both native helpers now sample
the page gutter and retain exact renderer-specific RGB assertions; the local
software-Skia widget regression verifies `(181,182,185)`. The Linux outside
click also uses the gutter so dismissing a flyout cannot start a guest track.
No test was disabled and no workflow configuration was changed. The corrected
The Windows artifact subsequently confirmed native gutter `(182,182,185)` too;
its old probe was the red artwork pixel `(188,0,0)`. Native compositing differs
by one red-channel value from the software widget capture, so each retains its
measured exact expectation. Corrected matrix acceptance remains pending.

Next concrete work: coordinate matching Home/Search landing and missing
library/queue/settings capabilities with Antigravity; repeat equivalent settled
content states and complete remaining native DPI, interaction and material work.

Coordination files: `desktop_shell.dart` and `catalog_surface.dart` are shared
presentation surfaces. Review these focused hunks against Antigravity's account
and catalog work before integration; do not automatically merge its branch.
