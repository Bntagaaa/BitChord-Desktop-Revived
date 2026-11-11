# BitChord Desktop Revived — Agent Instructions

These instructions apply to this repository when working on the clean Flutter Desktop migration. The active migration branch is `2.0.0-flutter`. Do not assume an older migration attempt is the target.

## Mission and source of truth

- Rebuild the **Kotlin/Compose Desktop 1.9.2** application with **Flutter Desktop** on Windows x64 and Linux x64, preserving the full feature set, visuals, interaction behavior, and audio correctness.
- The Kotlin reference is branch `1.9.2`, pinned in migration documents to commit `348b613a0c3db1a54de56483ea6f8748d6465211`. Inspect its source as the behavioral specification. Never modify that branch or Kotlin reference implementation while performing migration work.
- New Flutter code belongs in `flutterDesktop/`. **Never** copy or merge code from `1.9.2-flutter-reworked` or its `flutterApp/` implementation. Do not overwrite existing Kotlin code.
- Do not claim 100% parity or reduced RAM usage based on compilation, screenshots alone, or incomplete features. Record objective evidence and outstanding gaps.

## Mandatory context recovery at the start of each task

1. Check the current Git branch and `git status`; preserve unrelated/uncommitted changes and do not switch branches destructively.
2. Read `MIGRATION_PROGRESS.md`, `MIGRATION_PARITY.md`, `MIGRATION_ARCHITECTURE.md`, and `PERFORMANCE_BASELINE.md`. Read `flutterDesktop/README.md` and the relevant source/tests.
3. Use the **latest verified** CI and commit evidence, rather than treating dated progress notes as current. Look up the current workflow status when needed.
4. Identify the **user-authorized phase** and its entry/exit gates. Do not silently begin the next phase, rewrite the roadmap, or mark blockers resolved without evidence.
5. For affected features, identify Kotlin reference paths and parity IDs (P01–P38) before implementation.

## Architecture contract

- Flutter/Dart owns UI, navigation, application coordination, presentation state, and data-facing adapters. Use the layered boundaries specified in `MIGRATION_ARCHITECTURE.md`; no network, SQL, filesystem, or FFI directly inside widgets. Follow the selected Riverpod/go_router conventions.
- The audio backend design is **native C++17 + CMake**, with a versioned C ABI exposed to Dart via FFI. Native code owns decoding, bounded PCM buffers, DSP, Automix/analysis, device output, and authoritative playback clock. Avoid an embedded JVM or a second competing audio engine.
- Do not call Dart, allocate, perform I/O, or acquire blocking locks on the real-time audio callback. Preserve native ownership, resource lifetime, cancellation, backpressure, thread boundaries, and platform-specific WASAPI/libpulse/ALSA contracts.
- Keep provider/auth integrations, cookies, signed URLs, and credentials out of logs and committed fixtures. Preserve the original Kotlin app's data; imports must be read-only against the reference.
- Match original assets, iconography, layout metrics, motion, themes, keyboard behavior, and responsive states. Do not invent music catalogs, mock successful playback, fake network responses in production, or substitute generic Material controls where the reference differs.
- Memory efficiency is an explicit objective, **not** permission to remove features or disable effects silently. Benchmark matched release workloads and track image/cache/decoder/resource lifetimes.

## Current phase discipline

- Phase 0 and Phase 0.5 are documentation/architecture work already recorded in the migration files.
- Phase 1 created a Flutter foundation/shell, but **its acceptance is not automatically complete**: the documented Windows CI passed while Linux dependency installation failed in run `43041242897`; Linux native launch, Kotlin-vs-Flutter visual comparison, and other Phase 1 gates remain unverified as of that run.
- Before any Phase 2 work, recheck current branch and CI status, then address or explicitly report outstanding Phase 1 gates. Do not infer a later result from this historical note.
- Follow the **actual** phase scopes and dependencies in `MIGRATION_PROGRESS.md` (not an older chat outline). Phase 2 includes reference fixtures, visual system, route/settings inventory, and shell/interaction parity; do not expand it into unrelated audio or advanced playback implementation.
- Only work on a phase the user has authorized. If blocked, finish safe independent tasks in that phase and report remaining prerequisites instead of fabricating passing evidence.

## Implementation procedure

1. Audit the relevant Kotlin implementation and Flutter code first; write a short implementation plan for nontrivial tasks.
2. Make small, reviewable changes with focused tests and minimal unrelated edits.
3. Use official Flutter desktop project generation where host platform files are needed; do not hand-fabricate generated hosts.
4. Run available checks: `dart format`, `flutter analyze`, Flutter tests, and applicable Windows/Linux builds and native GUI smoke tests. Record commands, platform, versions, and observed outcomes. Use GitHub Actions when local SDK bootstrap is unavailable.
5. Compare reference/Flutter UI with matching windows, DPI, content states, and theme; distinguish widget-test captures from real desktop visual parity.
6. Update `MIGRATION_PARITY.md` and `MIGRATION_PROGRESS.md` for affected P-IDs and blockers. Update architecture/performance documentation only when decisions or actual measurements change.
7. At completion, summarize changed paths, tests/builds that actually ran, any failures or skipped checks, parity gaps, and the next concrete action. Commit/push only when instructed or already explicitly authorized for the task; never force-push.

## Safety, verification, and resource limits

- Do not disable tests, weaken assertions, bypass TLS/integrity verification, or describe blocked dependency downloads as source-code failures.
- If `storage.googleapis.com` or `jitpack.io` is blocked locally, note the HTTP status and try supported CI/toolchain routes; do not fabricate successful Flutter/Kotlin execution.
- Keep iterative local work practical for an 8 GB RAM Linux machine: prefer targeted tests, avoid concurrent heavyweight Kotlin/Flutter builds, and use CI for Windows/release matrix builds.
- Do not delete unrelated files, overwrite user work, commit secrets, or make unrequested releases.
