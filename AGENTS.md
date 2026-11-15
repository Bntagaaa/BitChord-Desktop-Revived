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

## Whole-application exploratory UI parity is mandatory in Phase 6

The user-provided Home/player, Accounts and Settings screenshots are
**illustrative failures, not a scope restriction**. Codex must discover
the full Kotlin navigation graph and exercise all reachable Flutter
surfaces and states, including nested menus/dialogs, playback,
library/search/history/downloads, queue, Settings, accounts, overlays,
window sizes and themes. Never stop after fixing the three examples or
the first Home screenshot. Capture matched post-interaction evidence and
record every visited, mismatched, blocked and uninspected route. Do not
claim full parity with incomplete coverage. Visual/UI behavior fixes are
authorized; backend feature additions are not. See the comprehensive
exploratory protocol in `.codex/phases/phase-6.md`.

## Interactive UI parity is mandatory in Phase 6

Do **not** use the initial Home screenshot as the sole visual check. Run both
Kotlin and Flutter, play a real track, inspect the *playing* floating player
and fullscreen/lyrics, navigate the account popup and **every** Settings
category/subpage. Match window/DPI/theme/content and record per-state
screenshots, interaction steps and failures. Kotlin Settings must show its
entire original menu structure; unsupported features are visible but
disabled/greyed out, not omitted. Preserve working Phase 5 controls and
YouTube session. The detailed mandatory protocol is in
`.codex/phases/phase-6.md` and the user reference inventory in
`flutterDesktop/verification/phase6/user-references/README.md`.
No new Automix/ONNX/Loop & Mix features. Do not call Phase 6 parity complete
without a tested playing/fullscreen state, account and Settings inventory.

## Current phase discipline

### User-authorized Phase 5 checkpoint → Phase 6 handoff (2026-11-15)

Phase 5 advanced Automix/full transition planner, ONNX beat/vocal analysis
and Loop & Mix are **deferred** by user decision, not parity-complete. Preserve
their P10–P16 backlog and all incomplete acceptance gates. A verified, limited
EQ/spatial/speed/silence/sleep checkpoint can be merged into `2.0.0-flutter`
without claiming full Phase 5 completion. Windows/Linux current-source CI
must pass; do not merge a red or conflicted PR.

The user explicitly authorized Phase 6 after that merge. Start a fresh
`codex/phase6-player-lyrics` branch from the current `origin/2.0.0-flutter`,
read `.codex/phases/phase-6.md`, implement real player/lyrics P22–P27, and
open a PR to `2.0.0-flutter`. Do not auto-merge Phase 6 or start Phase 7.
These dated user-approved instructions supersede older generic "push directly
to 2.0.0-flutter" language for Phase 6; preserve historical evidence.

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
7. At completion, summarize changed paths, tests/builds that actually ran, any failures or skipped checks, parity gaps, and the next concrete action. For user-authorized phase execution, commit task-related verified changes and push normally to `2.0.0-flutter` (the user has authorized automatic push/build for Phases 2–8). Never force-push, overwrite unrelated work, publish releases or claim CI success before checking the run.

## Safety, verification, and resource limits

- Do not disable tests, weaken assertions, bypass TLS/integrity verification, or describe blocked dependency downloads as source-code failures.
- If `storage.googleapis.com` or `jitpack.io` is blocked locally, note the HTTP status and try supported CI/toolchain routes; do not fabricate successful Flutter/Kotlin execution.
- Keep iterative local work practical for an 8 GB RAM Linux machine: prefer targeted tests, avoid concurrent heavyweight Kotlin/Flutter builds, and use CI for Windows/release matrix builds.
- Do not delete unrelated files, overwrite user work, commit secrets, or make unrequested releases.

## Phase execution playbooks and automated delivery

The phase-specific instructions are in `.codex/phases/phase-2.md` through `phase-8.md`. Do **not** execute them all automatically. When the user requests a particular phase, open only that phase playbook along with the migration source documents; cross-check against the current roadmap because verified facts can change.

For **authorized Phase 2–8 work**, proactively run relevant local checks, commit task-related changes and `git push origin 2.0.0-flutter` without asking for a second push confirmation. The GitHub Actions workflow `.github/workflows/flutter-desktop.yml` triggers on relevant pushes, builds/tests Windows + Linux, and uploads artifacts. After pushing, obtain and inspect the actual workflow run and correct in-scope failures before declaring completion. The existing workflow's trusted retention job can commit verified generated host files after the matrix succeeds; do not interfere with its branch/SHA guard. If there are blockers, push honest partial work/docs where appropriate and mark the phase INCOMPLETE/BLOCKED. A pushed commit never proves successful build or parity.

Suggested Codex CLI request: `Read .codex/phases/phase-2.md, follow AGENTS.md, implement only Phase 2, then commit, push and inspect GitHub Actions.`
