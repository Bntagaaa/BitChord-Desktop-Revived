# BitChord Desktop Revived — Phase 5: Advanced Audio, DSP, Automix and Analysis

> **Execution contract for Codex CLI** — Run only when the user explicitly requests this phase. Read root `AGENTS.md`, `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md`, `MIGRATION_ARCHITECTURE.md`, `PERFORMANCE_BASELINE.md` and `flutterDesktop/README.md` first. Verify branch `2.0.0-flutter`, cleanly handle existing changes, and compare to pinned Kotlin `1.9.2` reference (commit `348b613a0c3db1a54de56483ea6f8748d6465211`). Do not reuse `1.9.2-flutter-reworked` or modify Kotlin reference sources. Follow the *actual* dependency/acceptance gates in `MIGRATION_PROGRESS.md`; if a prerequisite is blocked, report it and perform only safe independent work, never silently skip it.

**Delivery automation for this phase:** implement in reviewable increments; run available focused formatting, analysis, tests and platform checks; update P-ID evidence and progress; commit only your task-related changes; `git push origin 2.0.0-flutter` (normal non-force push) after checks pass or are explicitly documented as environment-blocked. Pushing triggers the Flutter Desktop GitHub Actions Windows/Linux matrix. Wait for the triggered run's terminal outcome and inspect both jobs and evidence artifacts when tooling/access allows. Fix code/CI failures in scope, then push a new commit and recheck. A blocked CI or missing platform evidence means **INCOMPLETE**, never silently green. Do not amend/force-push, publish releases, or delete unrelated work. If network or runner access prevents checking the final run, report its URL/status as unverified instead of asserting success. Do not begin the next phase automatically.

## Prerequisites

Phase 3 engine + Phase 4 playable sources

## Implementation tasks

1. Compare Kotlin reference signal chain, transition planning, EQ, filters, silence handling, crossfade, playback speed, Automix/loopmix, sleep timer and diagnostics against pinned source/fixtures (P10–P16).
2. Port planners, buffer/PCM overlap and timing algorithms into the native C++17 backend, preserving two-deck state, incoming-track midpoint identity/time and gapless suppression/interaction rules.
3. Reuse legally approved native source/models after provenance checks; verify ONNX preprocessing, resampling, mel features, confidence/fallback and bounded analysis queue/session lifecycle.
4. Integrate UI controls for EQ, Automix, mixing waveform/loop view and diagnostics only when true engine capability exists. Never execute transition timing on Dart UI thread.
5. Build differential golden feature/plan/PCM corpus; test queue changes during analysis, cancellation, seek, rate changes, repeat, device changes, stale command generations and resource release. Track callback deadlines and underruns.

## Acceptance criteria

Reference differential fixtures with explicitly declared numeric tolerances, real transitions and advanced listening on both OSs, verified audio output, bounded model/decoder allocations and regression-free normal playback.

## Evidence and handoff

- For each affected P-ID, document Kotlin reference path/commit, Flutter/native implementation path/commit, scenario, tests and visual/runtime evidence, and one of PLANNED / IMPLEMENTED / STATICALLY_CHECKED / FIXTURE_TESTED / PLATFORM_VERIFIED / PARITY_ACCEPTED.
- Update `MIGRATION_PROGRESS.md` and `MIGRATION_PARITY.md`. Change `MIGRATION_ARCHITECTURE.md` only for justified architectural decisions, and `PERFORMANCE_BASELINE.md` only for real measurements or protocol changes.
- State explicitly whether **Phase 5** is COMPLETE, INCOMPLETE or BLOCKED, and why. Do not start Phase 6 without a new user instruction.
