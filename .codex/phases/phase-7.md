# BitChord Desktop Revived — Phase 7: Desktop Integrations, Settings and Distribution

> **Execution contract for Codex CLI** — Run only when the user explicitly requests this phase. Read root `AGENTS.md`, `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md`, `MIGRATION_ARCHITECTURE.md`, `PERFORMANCE_BASELINE.md` and `flutterDesktop/README.md` first. Verify branch `2.0.0-flutter`, cleanly handle existing changes, and compare to pinned Kotlin `1.9.2` reference (commit `348b613a0c3db1a54de56483ea6f8748d6465211`). Do not reuse `1.9.2-flutter-reworked` or modify Kotlin reference sources. Follow the *actual* dependency/acceptance gates in `MIGRATION_PROGRESS.md`; if a prerequisite is blocked, report it and perform only safe independent work, never silently skip it.

**Delivery automation for this phase:** implement in reviewable increments; run available focused formatting, analysis, tests and platform checks; update P-ID evidence and progress; commit only your task-related changes; `git push origin 2.0.0-flutter` (normal non-force push) after checks pass or are explicitly documented as environment-blocked. Pushing triggers the Flutter Desktop GitHub Actions Windows/Linux matrix. Wait for the triggered run's terminal outcome and inspect both jobs and evidence artifacts when tooling/access allows. Fix code/CI failures in scope, then push a new commit and recheck. A blocked CI or missing platform evidence means **INCOMPLETE**, never silently green. Do not amend/force-push, publish releases, or delete unrelated work. If network or runner access prevents checking the final run, report its URL/status as unverified instead of asserting success. Do not begin the next phase automatically.

## Prerequisites

Functional core and player surfaces from previous phases

## Implementation tasks

1. Map Kotlin tray, SNI/MPRIS, Windows media SMTC, window captions/fullscreen, keyboard controls, Discord RPC, scrobbling, Listen Together server/protocol, history/replay, settings/about/licenses and updater/packaging (P09, P28–P34, P37–P38).
2. Implement platform adapters with correct ownership and permissions, share one transport between UI, media keys, tray and PiP, and preserve reference settings visibility/defaults/import behavior.
3. Preserve the existing Listen Together server and compatible wire protocol; validate room reconnection, queue reconciliation, clock estimate and party-mode interactions with Automix.
4. Implement Windows/Linux packages/installers and safe updater behavior where target-specific equivalent is feasible; verify signatures/checksums, cancellation, rollback/readiness and portable installs. Never auto-publish releases.
5. Test real desktop sessions, media devices, session/tray visibility, shutdown, multi-instance ownership, settings migration and update failure scenarios.

## Acceptance criteria

Windows installed/portable and Linux compositor/package matrix, real tray/media/rpc/party tests, settings/updates behavioral parity and license review; platform restrictions explicitly documented.

## Evidence and handoff

- For each affected P-ID, document Kotlin reference path/commit, Flutter/native implementation path/commit, scenario, tests and visual/runtime evidence, and one of PLANNED / IMPLEMENTED / STATICALLY_CHECKED / FIXTURE_TESTED / PLATFORM_VERIFIED / PARITY_ACCEPTED.
- Update `MIGRATION_PROGRESS.md` and `MIGRATION_PARITY.md`. Change `MIGRATION_ARCHITECTURE.md` only for justified architectural decisions, and `PERFORMANCE_BASELINE.md` only for real measurements or protocol changes.
- State explicitly whether **Phase 7** is COMPLETE, INCOMPLETE or BLOCKED, and why. Do not start Phase 8 without a new user instruction.
