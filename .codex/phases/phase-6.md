# BitChord Desktop Revived — Phase 6: Lyrics, Artwork/Video and All Player Surfaces

> **Execution contract for Codex CLI** — Run only when the user explicitly requests this phase. Read root `AGENTS.md`, `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md`, `MIGRATION_ARCHITECTURE.md`, `PERFORMANCE_BASELINE.md` and `flutterDesktop/README.md` first. Verify branch `2.0.0-flutter`, cleanly handle existing changes, and compare to pinned Kotlin `1.9.2` reference (commit `348b613a0c3db1a54de56483ea6f8748d6465211`). Do not reuse `1.9.2-flutter-reworked` or modify Kotlin reference sources. Follow the *actual* dependency/acceptance gates in `MIGRATION_PROGRESS.md`; if a prerequisite is blocked, report it and perform only safe independent work, never silently skip it.

**Delivery automation for this phase:** implement in reviewable increments; run available focused formatting, analysis, tests and platform checks; update P-ID evidence and progress; commit only your task-related changes; `git push origin 2.0.0-flutter` (normal non-force push) after checks pass or are explicitly documented as environment-blocked. Pushing triggers the Flutter Desktop GitHub Actions Windows/Linux matrix. Wait for the triggered run's terminal outcome and inspect both jobs and evidence artifacts when tooling/access allows. Fix code/CI failures in scope, then push a new commit and recheck. A blocked CI or missing platform evidence means **INCOMPLETE**, never silently green. Do not amend/force-push, publish releases, or delete unrelated work. If network or runner access prevents checking the final run, report its URL/status as unverified instead of asserting success. Do not begin the next phase automatically.

## Prerequisites

Phase 2 visual contracts and Phases 3–5 audio/source behavior

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
