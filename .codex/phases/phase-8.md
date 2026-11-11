# BitChord Desktop Revived — Phase 8: Full Parity Closure, Memory Optimization and Release QA

> **Execution contract for Codex CLI** — Run only when the user explicitly requests this phase. Read root `AGENTS.md`, `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md`, `MIGRATION_ARCHITECTURE.md`, `PERFORMANCE_BASELINE.md` and `flutterDesktop/README.md` first. Verify branch `2.0.0-flutter`, cleanly handle existing changes, and compare to pinned Kotlin `1.9.2` reference (commit `348b613a0c3db1a54de56483ea6f8748d6465211`). Do not reuse `1.9.2-flutter-reworked` or modify Kotlin reference sources. Follow the *actual* dependency/acceptance gates in `MIGRATION_PROGRESS.md`; if a prerequisite is blocked, report it and perform only safe independent work, never silently skip it.

**Delivery automation for this phase:** implement in reviewable increments; run available focused formatting, analysis, tests and platform checks; update P-ID evidence and progress; commit only your task-related changes; `git push origin 2.0.0-flutter` (normal non-force push) after checks pass or are explicitly documented as environment-blocked. Pushing triggers the Flutter Desktop GitHub Actions Windows/Linux matrix. Wait for the triggered run's terminal outcome and inspect both jobs and evidence artifacts when tooling/access allows. Fix code/CI failures in scope, then push a new commit and recheck. A blocked CI or missing platform evidence means **INCOMPLETE**, never silently green. Do not amend/force-push, publish releases, or delete unrelated work. If network or runner access prevents checking the final run, report its URL/status as unverified instead of asserting success. Do not begin the next phase automatically.

## Prerequisites

All prior phase deliverables plus runnable reference and comparable workloads

## Implementation tasks

1. Audit every P-ID against pinned Kotlin source, actual implementation SHA, test/capture artifacts, and both OS platform evidence. Resolve every known high-severity functional/visual gap before declaring parity.
2. Run reproducible matched release comparisons on the same hardware: cold startup, idle, active playback, lyrics, fullscreen, library browsing, Automix and extended soak. Record process tree/RSS/private allocations where available, GPU/artwork/native cache, CPU, IO, latency, frame pacing and memory growth with repeated trials and protocol metadata.
3. Profile and address retained Dart providers, uncontrolled artwork/texture caches, native decoder buffers, C++ handles, ML sessions, caches, background tasks and extra processes; preserve feature behavior and audio fidelity.
4. Run end-to-end Windows/Linux CI and real native GUI/audio/desktop integration smoke tests, visual regression and long-running leak/teardown tests. Record unresolved environment/provider-dependent checks.
5. Review asset/font/model/dependency licensing, packaging provenance and release readiness. Do not publish or tag a release without separate user authorization.

## Acceptance criteria

No unexplained feature/parity gaps, repeatable benchmark results (or honest absence), no unacceptable regressions, audited distribution artifacts, all feasible platform gates passed. Do not report 100% or 'less RAM' without verified measurements.

## Evidence and handoff

- For each affected P-ID, document Kotlin reference path/commit, Flutter/native implementation path/commit, scenario, tests and visual/runtime evidence, and one of PLANNED / IMPLEMENTED / STATICALLY_CHECKED / FIXTURE_TESTED / PLATFORM_VERIFIED / PARITY_ACCEPTED.
- Update `MIGRATION_PROGRESS.md` and `MIGRATION_PARITY.md`. Change `MIGRATION_ARCHITECTURE.md` only for justified architectural decisions, and `PERFORMANCE_BASELINE.md` only for real measurements or protocol changes.
- State explicitly whether **Phase 8** is COMPLETE, INCOMPLETE or BLOCKED, and why. Do not start Phase 9 without a new user instruction.
