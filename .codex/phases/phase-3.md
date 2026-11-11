# BitChord Desktop Revived — Phase 3: Local Domain, Persistence and Native PCM Foundation

> **Execution contract for Codex CLI** — Run only when the user explicitly requests this phase. Read root `AGENTS.md`, `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md`, `MIGRATION_ARCHITECTURE.md`, `PERFORMANCE_BASELINE.md` and `flutterDesktop/README.md` first. Verify branch `2.0.0-flutter`, cleanly handle existing changes, and compare to pinned Kotlin `1.9.2` reference (commit `348b613a0c3db1a54de56483ea6f8748d6465211`). Do not reuse `1.9.2-flutter-reworked` or modify Kotlin reference sources. Follow the *actual* dependency/acceptance gates in `MIGRATION_PROGRESS.md`; if a prerequisite is blocked, report it and perform only safe independent work, never silently skip it.

**Delivery automation for this phase:** implement in reviewable increments; run available focused formatting, analysis, tests and platform checks; update P-ID evidence and progress; commit only your task-related changes; `git push origin 2.0.0-flutter` (normal non-force push) after checks pass or are explicitly documented as environment-blocked. Pushing triggers the Flutter Desktop GitHub Actions Windows/Linux matrix. Wait for the triggered run's terminal outcome and inspect both jobs and evidence artifacts when tooling/access allows. Fix code/CI failures in scope, then push a new commit and recheck. A blocked CI or missing platform evidence means **INCOMPLETE**, never silently green. Do not amend/force-push, publish releases, or delete unrelated work. If network or runner access prevents checking the final run, report its URL/status as unverified instead of asserting success. Do not begin the next phase automatically.

## Prerequisites

Phase 1 foundation and relevant Phase 2 state/fixture contracts; native dependency availability

## Implementation tasks

1. Trace Kotlin queue identities, playback transition rules, settings storage/import and local-media decoding with file-level evidence (P07, P10–P14, P35–P36).
2. Implement Dart domain IDs, deterministic queue/repeat/shuffle/cursor invariants, transactional repositories and versioned settings with a read-only, backed-up Kotlin data importer; never overwrite source Preferences/music files.
3. Implement the approved C++17/CMake native library and versioned C ABI, owned opaque handles, capability negotiation, asynchronous commands and bounded snapshots/events. Generate Dart FFI bindings with verified layout/ownership.
4. Implement real local-file decode using verified FFmpeg-compatible native dependencies and native bounded PCM pipeline with accurate played-frame clock, seek/rate/pause, teardown, decoder limit and cancellation/backpressure. Output via Windows WASAPI and Linux Pulse-compatible output with documented ALSA fallback behavior.
5. Add deterministic queue/import fixture corpus, native allocation/free and cancellation tests, real PCM/sink lifecycle tests, seek/hotplug/device tests and memory instrumentation. No Dart audio callback, Java runtime sidecar, fake sound, or finished Automix claim.
6. Package native libraries for both Windows/Linux and verify release loading and clean shutdown on real runner hosts.

## Acceptance criteria

Differential queue and migration fixtures; audible local output and correct authoritative clock on each supported OS; proven FFI ownership/shutdown and bounded memory behavior; Windows/Linux builds, audio tests and CI artifacts. If physical output/hotplug is untestable, mark those gates blocked.

## Evidence and handoff

- For each affected P-ID, document Kotlin reference path/commit, Flutter/native implementation path/commit, scenario, tests and visual/runtime evidence, and one of PLANNED / IMPLEMENTED / STATICALLY_CHECKED / FIXTURE_TESTED / PLATFORM_VERIFIED / PARITY_ACCEPTED.
- Update `MIGRATION_PROGRESS.md` and `MIGRATION_PARITY.md`. Change `MIGRATION_ARCHITECTURE.md` only for justified architectural decisions, and `PERFORMANCE_BASELINE.md` only for real measurements or protocol changes.
- State explicitly whether **Phase 3** is COMPLETE, INCOMPLETE or BLOCKED, and why. Do not start Phase 4 without a new user instruction.
