# BitChord Desktop Revived — Phase 2: Reference Fixtures, Visual System and Shell Parity

> **Execution contract for Codex CLI** — Run only when the user explicitly requests this phase. Read root `AGENTS.md`, `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md`, `MIGRATION_ARCHITECTURE.md`, `PERFORMANCE_BASELINE.md` and `flutterDesktop/README.md` first. Verify branch `2.0.0-flutter`, cleanly handle existing changes, and compare to pinned Kotlin `1.9.2` reference (commit `348b613a0c3db1a54de56483ea6f8748d6465211`). Do not reuse `1.9.2-flutter-reworked` or modify Kotlin reference sources. Follow the *actual* dependency/acceptance gates in `MIGRATION_PROGRESS.md`; if a prerequisite is blocked, report it and perform only safe independent work, never silently skip it.

**Delivery automation for this phase:** implement in reviewable increments; run available focused formatting, analysis, tests and platform checks; update P-ID evidence and progress; commit only your task-related changes; `git push origin 2.0.0-flutter` (normal non-force push) after checks pass or are explicitly documented as environment-blocked. Pushing triggers the Flutter Desktop GitHub Actions Windows/Linux matrix. Wait for the triggered run's terminal outcome and inspect both jobs and evidence artifacts when tooling/access allows. Fix code/CI failures in scope, then push a new commit and recheck. A blocked CI or missing platform evidence means **INCOMPLETE**, never silently green. Do not amend/force-push, publish releases, or delete unrelated work. If network or runner access prevents checking the final run, report its URL/status as unverified instead of asserting success. Do not begin the next phase automatically.

## Prerequisites

Phase 1 runnable foundation plus reference capture access (blocker may remain documented)

## Implementation tasks

1. Recheck Phase 1: latest CI status, Linux dependency-install failure, generated platform-file retention, native launch evidence, and shell behavior. Repair in-scope baseline failures before asserting this phase complete.
2. Enumerate all Kotlin navigation destinations, overlays, default/empty states, appearance toggles, focus behavior and reference accessibility semantics. Link findings to P01–P05, P24 and P28–P31.
3. Capture reproducible Kotlin reference fixtures and motion traces on matching Windows/Linux setup if toolchains are available; record OS, source SHA, resolution, DPI/scaling, theme, font provenance and UI state. If JitPack or graphical access is blocked, log the blocker and do not synthesize screenshots.
4. Implement/verify measured Flutter design tokens, typography with rights-compliant assets, spacing, surfaces, gradients/blur, icons, motion, breakpoints, focus/hover, menu/overlay ordering, shell routes and localized resource structure. Retain honest empty player dock states and existing Phase 1 architecture.
5. Validate the same screen/state at 100%, 125%, 150%, 200% scaling as practical; test keyboard, pointer, resize, navigation, restore and disposal. Do not invent catalog data or implement live playback.
6. Produce a visual gap ledger with specific Kotlin file/component, Flutter file, fixture and remaining differences.

## Acceptance criteria

Document reproducible reference fixtures or explicit blocking reason; navigation/default state catalog; widget and native platform test evidence; accurate matched visual comparison where both apps can run. No assertion of pixel-perfect parity from Flutter-only captures. Both CI platforms must pass and GUI smoke evidence must exist before platform acceptance.

## Evidence and handoff

- For each affected P-ID, document Kotlin reference path/commit, Flutter/native implementation path/commit, scenario, tests and visual/runtime evidence, and one of PLANNED / IMPLEMENTED / STATICALLY_CHECKED / FIXTURE_TESTED / PLATFORM_VERIFIED / PARITY_ACCEPTED.
- Update `MIGRATION_PROGRESS.md` and `MIGRATION_PARITY.md`. Change `MIGRATION_ARCHITECTURE.md` only for justified architectural decisions, and `PERFORMANCE_BASELINE.md` only for real measurements or protocol changes.
- State explicitly whether **Phase 2** is COMPLETE, INCOMPLETE or BLOCKED, and why. Do not start Phase 3 without a new user instruction.
