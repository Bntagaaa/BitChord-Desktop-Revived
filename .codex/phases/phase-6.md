# BitChord Desktop Revived — Phase 6: Lyrics, Artwork/Video and All Player Surfaces

## CI-first execution policy — user instruction, 2026-11-15

GitHub Actions is the primary build and verification environment. Do not run
full Kotlin or Flutter production builds on the local Linux machine. Run
automated tests, Linux/Windows desktop builds, virtual-display screenshot
capture/comparison, and artifact/report generation in CI. Use local source
inspection and lightweight static checks during implementation. Monitor the
actual branch/PR-head checks and fix in-scope failures until they pass.
Push to `codex/phase6-player-lyrics`, open a PR into `2.0.0-flutter`, and
never auto-merge. This policy supersedes generic local-build/direct-push
commands below. Headless screenshots support the interactive audit; they
cannot establish full interactive parity. Report headless limitations and
request only the smallest remaining local verification when necessary.

> **Execution contract for Codex CLI** — Run only when the user explicitly requests this phase. Read root `AGENTS.md`, `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md`, `MIGRATION_ARCHITECTURE.md`, `PERFORMANCE_BASELINE.md` and `flutterDesktop/README.md` first. Verify branch `2.0.0-flutter`, cleanly handle existing changes, and compare to pinned Kotlin `1.9.2` reference (commit `348b613a0c3db1a54de56483ea6f8748d6465211`). Do not reuse `1.9.2-flutter-reworked` or modify Kotlin reference sources. Follow the *actual* dependency/acceptance gates in `MIGRATION_PROGRESS.md`; if a prerequisite is blocked, report it and perform only safe independent work, never silently skip it.

**Delivery automation for this phase:** implement in reviewable increments; run available focused formatting, analysis, tests and platform checks; update P-ID evidence and progress; commit only your task-related changes; `git push origin 2.0.0-flutter` (normal non-force push) after checks pass or are explicitly documented as environment-blocked. Pushing triggers the Flutter Desktop GitHub Actions Windows/Linux matrix. Wait for the triggered run's terminal outcome and inspect both jobs and evidence artifacts when tooling/access allows. Fix code/CI failures in scope, then push a new commit and recheck. A blocked CI or missing platform evidence means **INCOMPLETE**, never silently green. Do not amend/force-push, publish releases, or delete unrelated work. If network or runner access prevents checking the final run, report its URL/status as unverified instead of asserting success. Do not begin the next phase automatically.

## Comprehensive exploratory parity — entire application, not screenshot-driven (2026-11-15)

**The user screenshots and the named floating-player/Accounts/Settings defects are
examples, NOT the audit boundary, an exhaustive checklist or a prioritization
ceiling.** Codex must independently discover, exercise and compare **every
reachable user-facing surface and meaningful state** of the pinned Kotlin
reference against Flutter, even if the user never mentioned it and no PNG
exists. Do not optimize only for the four screenshots, only for Home, or
only for the Phase 6 player page. Think like a full-product exploratory
QA engineer, not a screenshot imitation task.

Before editing UI, inventory the Kotlin app's navigation graph and all
reachable routes, nested subpages, modal sheets, drawers, popovers, context
menus, dialogs, tabs, panels, tooltips, empty/loading/error/signed-in/out
states, scroll/hover/keyboard interactions, window sizes and themes.
Explore Home, Search, Library, playlists/albums/artists, history, downloads,
queue, player (idle/playing/fullscreen/lyrics), account management,
Settings and every other visible destination. The inventory is **discovered
from the running reference**, not assumed complete from this list.
Exercise real navigation, playback and representative user actions in
both apps; discover secondary screens through clicks, right-clicks,
keyboard shortcuts, menus and scrolling. Compare visual hierarchy and
behavior with identical content/state. Where an action is dangerous or
would mutate remote data, use safe non-destructive inspection or explicitly
record BLOCKED.

For every discovered route/state, record Kotlin entry path, actions to
reproduce, expected visual/interaction contract, matching Flutter state,
reference and Flutter screenshots, defects, severity and PASS/FAIL/BLOCKED.
Maintain a coverage matrix distinguishing **inspected, implemented,
matched, unimplemented-disabled, inaccessible and not-yet-inspected**.
Never call a state PASS without actually visiting it in both apps.
A screenshot can illustrate a defect but cannot substitute for exploring
the rest of the application. Fix regressions and meaningful UI mismatches
across the entire app within the approved visual parity scope, prioritizing
shared components and high-frequency interactions; preserve working
features and do not expand backend feature scope. If time/tools are
insufficient, list all uninspected routes as NOT VERIFIED and leave Phase 6
visual parity INCOMPLETE rather than claiming global 1:1.

**Special requirement remains:** reconstruct the full Kotlin Settings
information architecture (every menu and nested screen), with unsupported
items clearly disabled/greyed out and inaccessible to click/keyboard.
These examples are minimum regression gates, not the maximum audit scope.

## Mandatory interactive visual parity protocol (user instruction, 2026-11-15)

**This is an acceptance gate, not an optional polish pass.** Prior agents
compared only the startup/home screenshot and missed player, account and
settings states. Never repeat that failure. Inspect the **real running Kotlin
reference and Flutter build**, on the same Linux desktop when available,
at the same resolution/DPI, scale, theme, account/content and playback state.
Do not infer parity from code, widget goldens, initial screenshots or mock data.

1. **Reference interaction discovery:** launch Kotlin `1.9.2` pinned reference
   and Flutter side by side. Navigate and interact in both: Home sections,
   recent/listen-again cards, floating player idle and actively playing,
   pause/resume, next/previous, seek, shuffle/repeat, volume, queue, lyrics,
   fullscreen player, account popup/account switching, Settings categories,
   submenus, toggles, search and overlays. **Actually play a real permitted
   track** in each app, then open Fullscreen Player while audio is playing;
   inspect controls, metadata, artwork, background, lyrics and transitions.
   Use a deterministic local test track if upstream login/provider blocks,
   document the limitation, and do not falsely claim authenticated/live parity.
2. **State-by-state capture matrix:** capture each matching Kotlin/Flutter
   interaction state (not just startup) in dark/light/pitch-black where
   applicable, at 1220x780 and 900x600 plus any relevant fullscreen size.
   Include window chrome, sidebar, spacing, card sizes, icon glyphs, text
   wrapping, floating player, popup anchor/position, hover/focus/pressed,
   animation/scroll, settings scroll/subpage and disabled states. Maintain a
   numbered click/keyboard reproduction script with track/source/state and
   timestamp; capture **after** navigation and interaction.
3. **Fix the three reported mismatches before claiming Phase 6 visual parity:**
   - Floating bottom player: the idle state may already match; **playing**
     state currently differs. Align layout, menus/buttons and their order,
     metadata/artwork, spacing, progress/volume, icons, and responsive
     behavior to Kotlin. Do not replace a functioning action with decoration.
   - Accounts: match Kotlin's avatar/menu popup, account rows, labels,
     selection indicator, add/manage actions, placement, sizing and overlay
     close behavior. Keep real persisted YouTube sign-in/session and account
     safety intact; grey out only genuinely unsupported actions.
   - Settings: match **the entire Kotlin Settings information architecture**
     first, not only the currently implemented Flutter Appearance/Audio page.
     Enumerate every Kotlin category, menu, subpage, row, control, ordering,
     grouping, label, icon, separator, search and scroll state. Build the
     visual structure **even when functionality is absent**. Unsupported
     controls must be visibly greyed out, non-interactive and accessible as
     disabled, never omitted, clickable-but-no-op, or deceptively enabled.
     Implemented Phase 5 EQ/speed/silence/sleep controls stay functional,
     but appear in the correct Kotlin location. Do not add new backend
     features or re-enable deferred Automix/ONNX/Loop & Mix.
4. **Screenshot references supplied by the user:** see
   `flutterDesktop/verification/phase6/user-references/README.md` and four
   PNG files when installed. They show side-by-side Kotlin/Flutter Home,
   active player, Accounts and Settings; use them as **defect examples**,
   not as a substitute for your own interactive captures. If PNGs are
   missing from the clone, stop claiming the user references were inspected;
   obtain/copy the supplied `phase6_visual_references.zip` into that folder
   and continue.
5. **Evidence and objective comparison:** save `reference-*.png` and
   `flutter-*.png` for each state, a comparison sheet or diff, screenshot
   dimensions/theme/state, a checklist of every inspected control and
   per-state PASS/FAIL/BLOCKED. Fix visible discrepancies, recapture, and
   repeat. An inaccessible account, provider, GUI, video, or Kotlin build
   is **BLOCKED/NOT VERIFIED**, never PASS. Include at least one actual
   playing/fullscreen comparison and a full Settings navigation inventory.
   Record parity IDs and remaining defects in MIGRATION_PARITY/PROGRESS.
6. **Regression and delivery:** verify normal authenticated playback,
   persistent session, queue and Phase 5 DSP/speed/sleep still work;
   run formatter/analyzer/tests and both OS CI. Phase 6 uses a separate
   branch `codex/phase6-player-lyrics` and PR to `2.0.0-flutter` without
   auto-merge. Never silently mark the phase done because the first screen
   looks good.

## User-approved Phase 6 entry and branch workflow (2026-11-15)

The user authorized Phase 6 **after** merging the verified **limited Phase 5
checkpoint** (PR #141). The original full Phase 5 remains INCOMPLETE because
advanced Automix/planner/ONNX/Loop & Mix were explicitly DEFERRED. Phase 6
must not depend on those absent capabilities, advertise them, or erase their
P10–P16 acceptance backlog. Phase 4 and physical-audio/close reliability
limitations are still open; do not promote them via a successful UI build.

Once PR #141's Windows/Linux jobs pass and it is merged, base Phase 6 on the
latest `origin/2.0.0-flutter` commit and work on
`codex/phase6-player-lyrics`; do not reuse the Phase 5 worktree branch as
the PR head. Open a PR targeting `2.0.0-flutter`, verify both OS CI results
and native GUI/lyrics evidence, and **do not auto-merge**. This branch/PR rule
supersedes the generic direct-push instructions above. Do not start Phase 7.

Priority: faithful Kotlin Fullscreen Player, dock/expanded/queue surfaces and
synced Lyrics, then Canvas/video/PiP with real capabilities and lifecycle
proof. Use the existing authoritative native source-time clock; on seek,
silence skip, playback speed, pause, buffering, source change or UI stall
handle discontinuities before showing synchronized text/frames. Persist
source/provider choice and offsets; avoid mock playback or invented lyric
results.

## Prerequisites

Phase 2 visual contracts and verified native Phase 3/4 audio/source contracts,
plus the merged **scoped Phase 5** EQ/speed/sleep checkpoint; advanced
Automix/ONNX/Loop & Mix are deferred, not entry blockers.

## Implementation tasks

1. Inventory Kotlin player dock, compact/expanded/fullscreen/PiP, queue/sidepanels, artwork mesh, Spotify Canvas, video, lyrics parser/providers/translation/share/offset and animation rules (P22–P27).
2. Implement real lyric provider priority/fallback, word/line/duet/background timing, translation, offsets, source visibility and state restoration using saved fixture corpus. Never poll providers from paint loops.
3. Drive visual lyrics/animation using sampled monotonic native playback clock, buffering flags and discontinuity generations. Preserve sync under seek, speed change, underrun, pause and UI stalls.
4. Implement all original player layouts, queue and source panels, gestures, animations, video/Canvas and PiP ownership with one underlying audio authority. Dispose textures, artwork frames and inactive views promptly.
5. Compare reference captures and frame sequences at matched DPI/sizes/themes; test provider failures, artwork fallback, sidepanel/lifecycle, A/V switching and multi-window consistency on Windows/Linux.

## Acceptance criteria

Real provider/timing tests, image/video lifecycle evidence, no duplicate playback output, all available player surfaces and matching reference visual/interaction fixtures. Mark unavailable media/DRM/screenshot cases unverified, not accepted.

## Evidence and handoff

- For each affected P-ID, document Kotlin reference path/commit, Flutter/native implementation path/commit, scenario, tests and visual/runtime evidence, and one of PLANNED / IMPLEMENTED / STATICALLY_CHECKED / FIXTURE_TESTED / PLATFORM_VERIFIED / PARITY_ACCEPTED.
- Update `MIGRATION_PROGRESS.md` and `MIGRATION_PARITY.md`. Change `MIGRATION_ARCHITECTURE.md` only for justified architectural decisions, and `PERFORMANCE_BASELINE.md` only for real measurements or protocol changes.
- State explicitly whether **Phase 6** is COMPLETE, INCOMPLETE or BLOCKED, and why. Do not start Phase 7 without a new user instruction.
