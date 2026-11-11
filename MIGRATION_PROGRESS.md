# Migration progress and delivery backlog

## Phase 0 record

Reference branch: `1.9.2`; exact commit: `348b613a0c3db1a54de56483ea6f8748d6465211`.
Migration branch: `2.0.0-flutter`, created from `origin/1.9.2` after fetching that branch explicitly. No prior Flutter rewrite was fetched as a migration input, checked out, read or merged. Existing Kotlin source and reference branch remain unchanged. New app location is reserved as `flutterDesktop/`; no directory/scaffold or application feature is created in Phase 0. `flutterApp/` is absent at the reference commit and is never a migration destination.

Deliverables:

- [x] Inventory desktop, shared UI/data, platform/native, models/assets, packaging and verification surfaces.
- [x] Map feature families to planned Flutter services, dependencies and behavioral acceptance in MIGRATION_PARITY.
- [x] Define layered architecture, lifecycle, data import, native audio ownership and memory policy in MIGRATION_ARCHITECTURE.
- [x] Define benchmark protocol and explicit unavailable results in PERFORMANCE_BASELINE.
- [x] Define ordered Phases 1–8 and exact Phase 1 scope below.
- [ ] Obtain runnable Kotlin reference and live Windows/Linux acceptance captures (blocked; not required to author Phase 0 documents).
- [ ] Measure Kotlin performance; Flutter measurements await an implementation.
- [ ] Establish package/platform compatibility through Phase 1 tooling checks and later spikes.

Commit completion is established by the Git commit containing these four documents; do not put a self-referential commit hash in its own contents. Phase 1 is **not started** and requires a separate instruction.

## Validation in this session

Command from repository root after activating `/workspace/.cloud-setup/env.sh`:

```sh
./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopApp:test --console=plain
```

Result on the pinned reference: **FAILED before tests executed**, at `:shared:compileKotlinJvm`. HTTP 403 prevents retrieving `com.github.MetrolistGroup.innertubex:innertubex:v0.7.0` and `com.github.TeamNewPipe:NewPipeExtractor:v0.26.3` from `jitpack.io`. This is an environment dependency-access blocker, not evidence of failing Kotlin test assertions. No test count or pass result is claimed. Log in this workspace: `/workspace/.cloud-setup/phase0-reference-tests.log` (not committed). Earlier environment native/Java graphics smoke results are not a current-reference parity or benchmark result.

`flutter` and `dart` are not on PATH. No SDK was installed and no Flutter package compatibility result is claimed. No previous Flutter implementation was consulted. No runtime screenshots, authenticated provider requests or physical audio/device test was completed. Generated Gradle outputs are ignored; tracked application files remain unchanged.

## Exact Phase 1 scope — tooling and independently buildable foundation

Depends on the committed Phase 0 plan and a separate instruction to proceed. Deliver only a new, buildable Windows/Linux Flutter foundation in `flutterDesktop/`, plus tooling/architecture documentation and meaningful foundation checks. This is not a feature milestone and cannot be described as visual or functional parity.

1. Verify current stable Flutter/Dart releases and Windows/Linux desktop support; select and pin one SDK and minimal dependency versions after license/maintenance/platform checks. Document installation/activation and required compilers (Windows MSVC/CMake/Ninja; Linux Clang/CMake/Ninja/pkg-config/GTK development prerequisites as supported by the chosen SDK). Run `flutter doctor -v` on both platforms or explicitly block the unavailable platform gate.
2. Create the project from the official Flutter desktop template **only** in `flutterDesktop/` with Windows and Linux hosts. Select stable app identifiers and display name consistent with BitChord and document package identity decisions; no old Flutter code/assets/config are imported. Kotlin stays byte-for-byte unchanged.
3. Add the minimal four-layer structure, analysis/lint rules, formatting and dependency-direction checks. Add Riverpod and go_router only after the version audit. Domain failure/clock/cancellation and bootstrap contracts may be established; no empty feature controllers, fake production service or disabled parity claims.
4. Establish the neutral application root and native window lifecycle: launch a clearly identified foundation screen, resize, clean shutdown. It must not masquerade as an implemented Home/player. No album/search/player/settings UI, network/auth flows or playback. A template/foundation screen is tooling scaffolding, not a placeholder implementation for a missing feature.
5. Configure Windows/Linux CI to fetch the pinned SDK, run dependency resolution without altering a committed lockfile unexpectedly, format/analyze, execute actual foundation tests and produce release desktop bundles. Preserve independent Kotlin CI; avoid modifying its build declarations. CI changes belong to a later authorized Phase 1 commit.
6. Document build/run/test commands from `flutterDesktop/`, test artifact provenance, dependency licenses and future native library loading constraints. Confirm there is one app root and no reference to old Flutter implementation paths.

Acceptance: clean checkout builds release bundles on Windows and Linux; nonzero foundation tests verify real bootstrap/route/lifecycle contracts, analyzer passes, actual host opens/resizes/closes, all new app files remain within `flutterDesktop/` except explicitly reviewed CI/docs, Kotlin diff is empty, version pins and lockfile committed. Platform results must be backed by executed commands/CI, not assumed from the other OS. No lower-memory claim from an empty shell. Do not implement FFI audio, media/auth adapters, real app screens, migrations or performance optimizations during Phase 1. Failed platform prerequisites remain visible blockers.

## Phases 2–8

Each phase ends with an evidence ledger per P-ID and explicit unresolved items. A phase may not mark a row complete using only mocks, shared names or screenshots. No automated advancement beyond the user-authorized phase. Dependencies below are gates; independent preparatory tasks may proceed only within the authorized phase.

| Phase / dependency | Actionable scope | Acceptance gate |
|---|---|---|
| 2 — reference fixtures and visual system; needs 1 and runnable reference | Extract exact route/settings/default/visibility inventory; capture Windows/Linux golden states and motion; review font/icon rights; implement tokens, typography, scaling, surfaces, navigation shell/focus/menus and localized resource pipeline (P01–05, P24, P28–31 presentation portions) | Reproducible fixture provenance pinned to reference; all routes/states catalogued; measured layout/type/color and pointer/focus checks at 100/125/150/200%; shell does not claim data/playback; unresolved assets block matching surfaces |
| 3 — local domain, persistence and PCM foundation; needs 1; uses 2 fixtures | Implement ID/queue rules and transaction repository; read-only Kotlin importer and versioned settings; spike then implement FFmpeg output C ABI, native PCM/DSP, devices and monotonic snapshots; offline/local playback and bounded prefetch (P07, P10–14, P35–36) | Differential queue and migration corpus, real local PCM and sink clock, seek/rate/hotplug/precision matrix on both OSs, allocation/free/cancel tests and offline memory telemetry; import leaves reference intact |
| 4 — online catalogs, accounts, sources and downloads; needs 3, consumes 2 UI | Prove browser/JS capabilities first; implement catalog/search/library mutations, Google/Spotify sessions, InnerTubeX/NewPipe-equivalent resolver, custom modules/add-ons/JioSaavn, matching/upgrade/fallback, downloads and offline verification (P03–09, P17–21) | Controlled HTTP fixtures plus live test-account playback on both OSs; expired auth and provider failure recover; upgrades preserve current identity/time; no partial download adoption or plaintext secret fallback; blocked resolver cannot be declared migrated |
| 5 — advanced listening; needs 3–4 | Port transition planning and PCM overlap/filter/speed behavior; native feature/model preprocessing and analysis cache; Automix duration/performance, loop/mix UI, sleep behavior, EQ and quality diagnostics (P14–16 and advanced P10–13) | Reference feature/PCM corpus within predeclared tolerances, actual multi-track transitions including cancellation/repeat/rate changes, listening checks and bounded model/decoder memory on both OSs |
| 6 — lyrics, media and all player surfaces; needs 2–5 | Implement every offered lyric provider/parser, priority/override/translation/share and monotonic lyric clock; compact/expanded/fullscreen/PiP surfaces, Canvas/artist motion, music-video switching and texture lifecycle (P22–27) | Provider failure/cancel/timing traces; native clock vs word/line animation under UI stall/buffer/seek; all window/frame-sequence visual gates; A/V switch no duplicate output; hidden/main/PiP lifetime tests |
| 7 — desktop integration and distribution; needs 3–6 | Implement tray/SNI/MPRIS/SMTC/caption/fullscreen, Discord/scrobblers/presence, Listen Together with existing server, history/Replay details, complete settings/about/licenses; package and updater workflows (P09, P28–34, P37–38) | Real Windows media/tray/installed+portable tests and Linux desktop/compositor/package matrix; multi-client party clock/queue recovery; update verify/cancel/readiness/rollback/upgrade tests; credentials/notice review |
| 8 — closure and performance; needs all earlier gates | Execute every P-ID on both OSs; complete all screen/state/interaction fixtures; run matched RAM/CPU/startup/audio soak benchmarks; fix leaks and drift; release QA and evidence review | No unexplained parity gaps; all supported features and legacy switches accepted; repeatable lower-memory evidence without feature suppression; no CPU/audio/frame regression outside approved thresholds; publish measured limitations rather than assert 100% prematurely |

## Risk and blocker register

| ID | Evidence / uncertainty | Required resolution and affected work |
|---|---|---|
| B01 | JitPack HTTP 403 blocks reference tests/build | Enable `jitpack.io` in environment network settings and rerun reference suite/build; prerequisite for current baseline and reference capture |
| B02 | No Windows executor/device/display evidence | Run Windows CI plus interactive reference/Flutter capture, WASAPI/hotplug/SMTC/caption/PiP/updater tests; required before Windows acceptance |
| B03 | No Flutter toolchain/platform package audit | Phase 1 pins supported versions and establishes both host toolchains; candidates are not guaranteed working dependencies |
| B04 | JVM resolver libraries/JavaFX/GraalJS have no proved Dart equivalents | Phase 4 browser, JS and resolver spikes; preserve exact headers/client/cipher/fallback semantics or report parity blocked |
| B05 | Native algorithm/FFI reuse and assets require provenance/license review | Keep native reference and notices; assess font/model/codec rights and independent C ABI before distribution; no silent substitutions |
| B06 | Flutter multi-window choice may start another engine | Measure PiP ownership/latency/RAM; one transport authority; choose native host or explicit IPC architecture only with evidence |
| B07 | Existing Java Preferences/chunked secrets differ from Dart storage | Fixture-driven, backed-up read-only importer; OS-specific secret handling; reference install must remain usable |
| B08 | Docs may describe older controls or example diagnostics | Current source/test/capture wins; record discrepancy and obtain observable contract; never treat example numbers as baseline |
| B09 | Packaged Kotlin updater helpers target JVM-era layouts | Revalidate identity, helper payload, launch readiness and rollback for Flutter layouts before enabling updates |
| B10 | Lower-memory goal has no measured baseline | Follow PERFORMANCE_BASELINE on matched real hardware; no arbitrary RAM result or partial-shell comparison |

## Completion discipline

For each P-ID record implementation commit, fixtures/reference SHA, Windows/Linux commands and versions, result counts, artifacts, known gaps and reviewer acceptance. Mark states separately: planned, implemented, statically checked, fixture-tested, platform-verified, parity-accepted. Missing credentials or external services are blocked, not passed. Phase 0 ends with this document commit; it does not authorize Phase 1, source edits, pushing a branch, merging or publishing a release.
