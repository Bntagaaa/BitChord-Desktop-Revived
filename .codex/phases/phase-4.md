# BitChord Desktop Revived — Phase 4: Online Catalogs, Accounts, Streaming and Downloads

> **Execution contract for Codex CLI** — Run only when the user explicitly requests this phase. Read root `AGENTS.md`, `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md`, `MIGRATION_ARCHITECTURE.md`, `PERFORMANCE_BASELINE.md` and `flutterDesktop/README.md` first. Verify branch `2.0.0-flutter`, cleanly handle existing changes, and compare to pinned Kotlin `1.9.2` reference (commit `348b613a0c3db1a54de56483ea6f8748d6465211`). Do not reuse `1.9.2-flutter-reworked` or modify Kotlin reference sources. Follow the *actual* dependency/acceptance gates in `MIGRATION_PROGRESS.md`; if a prerequisite is blocked, report it and perform only safe independent work, never silently skip it.

**Delivery automation for this phase:** implement in reviewable increments; run available focused formatting, analysis, tests and platform checks; update P-ID evidence and progress; commit only your task-related changes; `git push origin 2.0.0-flutter` (normal non-force push) after checks pass or are explicitly documented as environment-blocked. Pushing triggers the Flutter Desktop GitHub Actions Windows/Linux matrix. Wait for the triggered run's terminal outcome and inspect both jobs and evidence artifacts when tooling/access allows. Fix code/CI failures in scope, then push a new commit and recheck. A blocked CI or missing platform evidence means **INCOMPLETE**, never silently green. Do not amend/force-push, publish releases, or delete unrelated work. If network or runner access prevents checking the final run, report its URL/status as unverified instead of asserting success. Do not begin the next phase automatically.

## Prerequisites

Phase 3 operational native playback and prior UI contracts

## Implementation tasks

1. Inventory reference discovery/catalog/search/library flows, YouTube/InnerTubeX/NewPipe-equivalent resolver behavior, JioSaavn, custom modules/add-ons, Spotify accounts/library and existing cache/download paths (P03–P09, P17–P21).
2. Evaluate Windows/Linux embedded browser, OAuth, cookie import and JS execution capabilities first; prove needed cipher/client/PoToken and headers/transport parity. Do not assume desktop webview plugins expose necessary functionality.
3. Implement typed Dart provider adapters, account generations/refresh, safely scoped secrets, cancellation-aware HTTP and source resolution, bounded HTTP range/cache and native source broker. Protect credentials, signed URLs, browser cookies and persisted user data.
4. Implement real catalog pages, search, pagination, artist/album/playlist/library mutations and download/offline lifecycle against validated provider contracts. No fictitious results or mock successful sign-in in production.
5. Test controlled HTTP fixtures plus authorized live test-account scenarios: pagination, token expiry, failures, format upgrades, missing content, queue identity, cache eviction, offline reopen, interrupted download and corruption recovery.

## Acceptance criteria

Real provider/account/resolver and playback evidence on Windows/Linux with failure recovery; downloaded media integrity, isolation and persistence; no credential leaks. If required third-party access is unavailable, leave corresponding parity IDs explicitly blocked.

## Evidence and handoff

- For each affected P-ID, document Kotlin reference path/commit, Flutter/native implementation path/commit, scenario, tests and visual/runtime evidence, and one of PLANNED / IMPLEMENTED / STATICALLY_CHECKED / FIXTURE_TESTED / PLATFORM_VERIFIED / PARITY_ACCEPTED.
- Update `MIGRATION_PROGRESS.md` and `MIGRATION_PARITY.md`. Change `MIGRATION_ARCHITECTURE.md` only for justified architectural decisions, and `PERFORMANCE_BASELINE.md` only for real measurements or protocol changes.
- State explicitly whether **Phase 4** is COMPLETE, INCOMPLETE or BLOCKED, and why. Do not start Phase 5 without a new user instruction.
