# Migration progress and delivery backlog

Latest Phase 1 status: see the **latest verification and Linux comparison** at the end.
Earlier dated records are historical evidence, not the current gate status.

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

Commit completion is established by the Git commit containing these four documents; do not put a self-referential commit hash in its own contents. Phase 1 was subsequently authorized by the visual-shell request; its current blocked status is recorded below.

## Phase 0 validation record

Command from repository root after activating `/workspace/.cloud-setup/env.sh`:

```sh
./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopApp:test --console=plain
```

Result on the pinned reference: **FAILED before tests executed**, at `:shared:compileKotlinJvm`. HTTP 403 prevents retrieving `com.github.MetrolistGroup.innertubex:innertubex:v0.7.0` and `com.github.TeamNewPipe:NewPipeExtractor:v0.26.3` from `jitpack.io`. This is an environment dependency-access blocker, not evidence of failing Kotlin test assertions. No test count or pass result is claimed. Log in this workspace: `/workspace/.cloud-setup/phase0-reference-tests.log` (not committed). Earlier environment native/Java graphics smoke results are not a current-reference parity or benchmark result.

`flutter` and `dart` are not on PATH. No SDK was installed and no Flutter package compatibility result is claimed. No previous Flutter implementation was consulted. No runtime screenshots, authenticated provider requests or physical audio/device test was completed. Generated Gradle outputs are ignored; tracked application files remain unchanged.

## Phase 0.5 finalization record

Extends Phase 0 commit `28b6ac40`; no restart or new reference branch. C++17 is selected over Rust for the native audio backend, with CMake and versioned C ABI/Dart FFI. Flutter owns all UI, Dart navigation/state/UI application logic, native code all decoding, buffering, DSP, Automix analysis/planning/rendering, sink control and authoritative time. The detailed source/reuse audit is appended to MIGRATION_PARITY; MIGRATION_ARCHITECTURE specifies commands, event/ack pressure, snapshots, source broker, buffers, ownership, threading, Windows/Linux adapters and shutdown.

- [x] Trace current/upcoming, upgrade, gapless, overlap-midpoint, EOF/recovery, DSP, played-frame mapping and disposal paths.
- [x] Separate directly reusable reference C++/model algorithms from Kotlin translations, JNI glue and JVM-only dependencies.
- [x] Decide C++17; document Rust safety advantages and mixed-toolchain costs without assuming a performance winner.
- [x] Fix the boundary: no Dart transition planner/PCM loop or UI-thread audio waits.
- [x] Define native WASAPI shared mode and Linux libpulse/ALSA output contracts and missing-platform evidence.
- [x] Define prioritized native proof gates and exact Phase 1 below.
- [x] Verify 25 canonical AutoMix source/model hashes via the existing offline verifier (passed against `b690dc146009d4815825fa4a20815ef2e4ee8898`).

No backend or audio bridge is implemented. No Kotlin source changes, previous Flutter implementation input, new runtime parity evidence or measured memory savings. Phase 0's JitPack-blocked JVM suite and unavailable Windows/Flutter baselines remain unresolved; hash identity is not a substitute for those tests.

## Priority order for hybrid implementation

1. **Before backend implementation:** obtain reference fixture/run access and preserve source/model/license provenance; freeze ABI ownership, identities, time units and failure/pressure rules (Phases 1–2 contracts/evidence). Blocking a required platform remains visible.
2. **First native milestone (Phase 3):** local decode→bounded PCM→device output→accurate clock, cancellation/seek/shutdown and C ABI lifetime proof on Windows/Linux. Validate partial writes, priming/tails and same-format prepared handoff before any advanced mixing.
3. **Source integration (Phase 4):** asynchronous Dart resolver broker and native interruptible transport/range cache; prove headers, manifests, expiry, upgrades and failure recovery. No blocking Dart decoder callback.
4. **Advanced parity (Phase 5):** port native planner and DSP, preserve two-deck state/time and ONNX preprocessing; reuse allowed C++ loop/analysis primitives through new owners. Test feature→plan→PCM chain and gapless/Automix interaction before UI declares availability.
5. **Finish surfaces/integrations (Phases 6–7):** native clock drives Flutter lyric/video/PiP projections; one engine across windows/OS media/party. Then full parity and matched memory/CPU closure (Phase 8). Gates supplement the existing backlog; no original feature is dropped or moved outside migration scope.

## Historical Phase 0.5 Phase 1 scope — superseded by visual-shell authorization

Historical scope preserved for provenance. The subsequent Phase 1 request explicitly expands this tooling-only plan to include the original design system, desktop visual shell and service interfaces; that newer request takes precedence. Deliver only a new, buildable Windows/Linux Flutter foundation in `flutterDesktop/`, plus tooling/architecture documentation and meaningful foundation checks. This is not a feature milestone and cannot be described as visual or functional parity.

1. Verify current stable Flutter/Dart releases and Windows/Linux desktop support; select and pin one SDK and minimal dependency versions after license/maintenance/platform checks. Document installation/activation and required compilers (Windows MSVC/CMake/Ninja; Linux Clang/CMake/Ninja/pkg-config/GTK development prerequisites as supported by the chosen SDK). Run `flutter doctor -v` on both platforms or explicitly block the unavailable platform gate.
2. Create the project from the official Flutter desktop template **only** in `flutterDesktop/` with Windows and Linux hosts. Select stable app identifiers and display name consistent with BitChord and document package identity decisions; no old Flutter code/assets/config are imported. Kotlin stays byte-for-byte unchanged.
3. Add the minimal four-layer structure, analysis/lint rules, formatting and dependency-direction checks. Record C++17/CMake as the native target and the future `native/audio` location. Verify the host C++ compiler/CMake versions and plan FFmpeg/libcurl/ONNX/WASAPI/libpulse/ALSA dependency packaging; do not create a stub audio library or require Rust/Cargo. Add Riverpod and go_router only after the version audit. Domain failure/clock/cancellation and bootstrap contracts may be established; no empty feature controllers, fake production service or disabled parity claims.
4. Establish the neutral application root and native window lifecycle: launch a clearly identified foundation screen, resize, clean shutdown. It must not masquerade as an implemented Home/player. No album/search/player/settings UI, network/auth flows or playback. A template/foundation screen is tooling scaffolding, not a placeholder implementation for a missing feature.
5. Configure Windows/Linux CI to fetch the pinned SDK, run dependency resolution without altering a committed lockfile unexpectedly, format/analyze, execute actual foundation tests and produce release desktop bundles. Preserve independent Kotlin CI; avoid modifying its build declarations. CI changes belong to a later authorized Phase 1 commit.
6. Document build/run/test commands from `flutterDesktop/`, test artifact provenance, dependency licenses and future native library loading constraints. Preserve the Phase 0.5 C ABI protocol specification as an explicit contract covering field widths/alignment, IDs/generations, clocks, capacities/acknowledgments, source-purpose replies, terminal results and shutdown. Record native provenance/dependency matrix and both platform toolchain findings; no exported backend functions, generated FFI bindings or feature adapters yet. Confirm there is one app root and no reference to old Flutter implementation paths.

Acceptance: clean checkout builds release bundles on Windows and Linux; nonzero foundation tests verify real bootstrap/route/lifecycle contracts, analyzer passes, actual host opens/resizes/closes, all new app files remain within `flutterDesktop/` except explicitly reviewed CI/docs, Kotlin diff is empty, version pins and lockfile committed. Platform results must be backed by executed commands/CI, not assumed from the other OS. No lower-memory claim from an empty shell. Acceptance artifacts must include the SDK/dependency lockfile, command/CI results per OS, actual window lifecycle evidence, dependency-direction checks, native toolchain/dependency findings and reviewed ABI ownership specification. Do not implement FFI audio, native audio library/stub, DSP/Automix, media/auth adapters, real app screens, migrations or performance optimizations during Phase 1. C++/FFI implementation begins in Phase 3, not as a fake Phase 1 bridge. Failed platform prerequisites remain visible blockers.

## Phases 2–8

Each phase ends with an evidence ledger per P-ID and explicit unresolved items. A phase may not mark a row complete using only mocks, shared names or screenshots. No automated advancement beyond the user-authorized phase. Dependencies below are gates; independent preparatory tasks may proceed only within the authorized phase.

| Phase / dependency | Actionable scope | Acceptance gate |
|---|---|---|
| 2 — reference fixtures and visual system; needs 1 and runnable reference | Extract exact route/settings/default/visibility inventory; capture Windows/Linux golden states and motion; review font/icon rights; implement tokens, typography, scaling, surfaces, navigation shell/focus/menus and localized resource pipeline (P01–05, P24, P28–31 presentation portions) | Reproducible fixture provenance pinned to reference; all routes/states catalogued; measured layout/type/color and pointer/focus checks at 100/125/150/200%; shell does not claim data/playback; unresolved assets block matching surfaces |
| 3 — local domain, persistence and PCM foundation; needs 1; uses 2 fixtures | Implement ID/queue rules and transaction repository; read-only Kotlin importer and versioned settings; implement the selected C++17/CMake engine and C ABI, FFmpeg decode, WASAPI/libpulse/ALSA output, base PCM/DSP, devices and monotonic snapshots; offline/local playback and bounded prefetch (P07, P10–14, P35–36) | Differential queue and migration corpus, real local PCM and sink clock, seek/rate/hotplug/precision matrix on both OSs, allocation/free/cancel tests and offline memory telemetry; import leaves reference intact |
| 4 — online catalogs, accounts, sources and downloads; needs 3, consumes 2 UI | Prove browser/JS capabilities first; implement catalog/search/library mutations, Google/Spotify sessions, InnerTubeX/NewPipe-equivalent resolver, custom modules/add-ons/JioSaavn, matching/upgrade/fallback, downloads and offline verification (P03–09, P17–21) | Controlled HTTP fixtures plus live test-account playback on both OSs; expired auth and provider failure recover; upgrades preserve current identity/time; no partial download adoption or plaintext secret fallback; blocked resolver cannot be declared migrated |
| 5 — advanced listening; needs 3–4 | Port transition planning to C++ and PCM overlap/filter/speed behavior; native feature/model preprocessing and analysis cache; Automix duration/performance, loop/mix UI, sleep behavior, EQ and quality diagnostics (P14–16 and advanced P10–13) | Reference feature/PCM corpus within predeclared tolerances, actual multi-track transitions including cancellation/repeat/rate changes, listening checks and bounded model/decoder memory on both OSs |
| 6 — lyrics, media and all player surfaces; needs 2–5 | Implement every offered lyric provider/parser, priority/override/translation/share and monotonic lyric clock; compact/expanded/fullscreen/PiP surfaces, Canvas/artist motion, music-video switching and texture lifecycle (P22–27) | Provider failure/cancel/timing traces; native clock vs word/line animation under UI stall/buffer/seek; all window/frame-sequence visual gates; A/V switch no duplicate output; hidden/main/PiP lifetime tests |
| 7 — desktop integration and distribution; needs 3–6 | Implement tray/SNI/MPRIS/SMTC/caption/fullscreen, Discord/scrobblers/presence, Listen Together with existing server, history/Replay details, complete settings/about/licenses; package and updater workflows (P09, P28–34, P37–38) | Real Windows media/tray/installed+portable tests and Linux desktop/compositor/package matrix; multi-client party clock/queue recovery; update verify/cancel/readiness/rollback/upgrade tests; credentials/notice review |
| 8 — closure and performance; needs all earlier gates | Execute every P-ID on both OSs; complete all screen/state/interaction fixtures; run matched RAM/CPU/startup/audio soak benchmarks; fix leaks and drift; release QA and evidence review | No unexplained parity gaps; all supported features and legacy switches accepted; repeatable lower-memory evidence without feature suppression; no CPU/audio/frame regression outside approved thresholds; publish measured limitations rather than assert 100% prematurely |

## Risk and blocker register

| ID | Evidence / uncertainty | Required resolution and affected work |
|---|---|---|
| B01 | Historical JitPack 403; hosted Linux reference now builds and passes 857 tests in run `43125482870` | Dependency access is verified for that runner/reference; do not generalize to every environment. Windows reference native build remains blocked separately |
| B02 | Windows Flutter build, eight tests and native lifecycle now pass; pinned Windows Kotlin reference native build fails | Obtain a runnable pinned Windows reference for matched capture. Physical DPI/theme/accessibility and later WASAPI/SMTC/PiP/updater gates remain open |
| B03 | Flutter 3.47.9/Dart 3.13.7 and shell packages now pass both hosted toolchains with committed lockfile | Foundation compatibility is verified; future audio/browser/multi-window package capabilities still need their own gates |
| B04 | JVM resolver libraries/JavaFX/GraalJS have no proved Dart equivalents | Phase 4 browser, JS and resolver spikes; preserve exact headers/client/cipher/fallback semantics or report parity blocked |
| B05 | Native algorithm/FFI reuse and assets require provenance/license review | Keep native reference and notices; assess font/model/codec rights and independent C ABI before distribution; no silent substitutions |
| B06 | Flutter multi-window choice may start another engine | Measure PiP ownership/latency/RAM; one transport authority; choose native host or explicit IPC architecture only with evidence |
| B07 | Existing Java Preferences/chunked secrets differ from Dart storage | Fixture-driven, backed-up read-only importer; OS-specific secret handling; reference install must remain usable |
| B08 | Docs may describe older controls or example diagnostics | Current source/test/capture wins; record discrepancy and obtain observable contract; never treat example numbers as baseline |
| B09 | Packaged Kotlin updater helpers target JVM-era layouts | Revalidate identity, helper payload, launch readiness and rollback for Flutter layouts before enabling updates |
| B10 | Lower-memory goal has no measured baseline | Follow PERFORMANCE_BASELINE on matched real hardware; no arbitrary RAM result or partial-shell comparison |

## Completion discipline

For each P-ID record implementation commit, fixtures/reference SHA, Windows/Linux commands and versions, result counts, artifacts, known gaps and reviewer acceptance. Mark states separately: planned, implemented, statically checked, fixture-tested, platform-verified, parity-accepted. Missing credentials or external services are blocked, not passed. Phase 0.5 ends with a documentation-only commit on `2.0.0-flutter`; it does not authorize Phase 1, source edits, pushing a branch, merging or publishing a release.

## Phase 1 — Flutter foundation and visual shell: started, toolchain blocked

The user authorized implementation on the actual migration branch `2.0.0-flutter` after Phase 0.5 commit `bc800fc0`. The revised scope includes the reusable design system, sidebar/navigation, top bar/content region, empty player dock, responsive layout, OS-theme/focus/window lifecycle, and future service interfaces. This supersedes the historical restriction to a neutral foundation screen; backend/DSP/Automix implementation, real catalog/search/library features and Phase 2 remain unauthorized. No previous Flutter rewrite has been consulted.

### Work performed and observed blocker

- Confirmed branch and clean worktree, read the four plans, and inspected the pinned Kotlin theme, typography/font mapping, sidebar/frame/dock, window modes and UI-scale/width policies.
- Discovered neither Flutter nor Dart on PATH. The required Linux build prerequisites also lack Clang, Ninja and GTK3 development metadata (`pkg-config --modversion gtk+-3.0` fails). Existing user-owned CMake from onboarding remains available after activation; no system installation is claimed.
- Read the enforced environment network policy. `storage.googleapis.com` is absent from its allowed destinations. A request to the official Linux release metadata at `https://storage.googleapis.com/flutter_infra_release/flutter/releases/releases_linux.json` failed with `curl: (56) CONNECT tunnel failed, response 403`.
- Successfully checked out the official Flutter **toolchain source**, outside the project at `/workspace/.toolchains/flutter`. Stable points to `c197ca5199e9ee5661e865596c9c07294dfab51c`, source tag `3.47.9`, engine `89c39edfdfae2949f6806e4a315c0c08522238b7`. This identifies a candidate SDK; it is not an executable/version-validated SDK installation or a project pin.
- Ran that checkout's `bin/flutter --version` with analytics suppressed and a workspace pub cache. Exit **56**: the official bootstrap could not download its Linux x64 Dart SDK because the proxy returned HTTP 403. Log: `/workspace/.cloud-setup/phase1-flutter-bootstrap.log` (local, not committed).
- The environment draft read failed twice with “The current environment draft could not be verified.” No configuration edit/persistence is claimed, and no unknown allowlist was replaced. No network-policy bypass or disabled TLS verification was attempted.

This is an external dependency-access blocker to creating, resolving, formatting, analyzing, testing or building the official Flutter project. No `flutterDesktop/` application, handmade template, mock screen, native stub or placeholder feature has been created. Only source-audit and status documentation is committed. Phase 1 is **not complete** and no runnable Flutter foundation is claimed.

### Verification ledger

| Required check | Result in this attempt |
|---|---|
| Branch/reference preservation | Confirmed `2.0.0-flutter`; Kotlin source unchanged |
| Flutter toolchain bootstrap / version | Failed before Dart SDK installation: proxy 403 |
| `dart format` | Not run: Dart unavailable |
| `flutter analyze` | Not run: Flutter bootstrap blocked |
| Widget/layout tests | Not implemented or run: toolchain unavailable; no test pass claim |
| Linux release build / graphical launch | Not run: SDK unavailable; Linux development prerequisites also need setup |
| Windows release build / native behavior | Not run: no Windows executor; later CI/native validation required |
| Same-dimension Kotlin/Flutter comparison | Not run: Flutter unavailable and reference build still has the prior JitPack dependency blocker |
| Performance | Not measured; no RAM-reduction claim |

### Exact resume actions and acceptance

1. Add **`storage.googleapis.com`** to this environment's allowed destinations in environment settings, retaining existing package-manager access. **`jitpack.io`** is also required to unblock the Kotlin reference build for the requested visual comparison. Credentials are not the diagnosed issue. The failed draft read means these changes were not saved by the agent.
2. Recheck current policy and retry the official Flutter bootstrap with fresh connections. Verify the candidate stable SDK/version against official release metadata/checksum, then pin the selected working SDK and project dependencies. Install Linux desktop development prerequisites (Clang, CMake, Ninja, pkg-config, GTK3 development libraries and their dependencies) through supported environment/user-owned package setup. Do not assume the presence of GTK runtime libraries means development headers are available.
3. Generate Windows/Linux hosts with the official Flutter template into `flutterDesktop/` only. Implement initialization, owned lifecycle/error/logging services, Riverpod composition and go_router navigation with distinct UI/application/data/platform layers. Interfaces describe the Phase 0.5 native ABI ownership/time/generation contracts; no native backend implementation or fake production service.
4. Translate measured Kotlin tokens, custom vector geometry, font profiles, actual shell/default states and responsive policies. Preserve no-data/disabled semantics; no invented catalog content or functioning-looking fake playback. Verify asset notices/rights, especially bundled SF Pro, before packaging; report typography differences if reuse cannot be established rather than silently substituting.
5. Add meaningful layout/focus/theme/lifecycle tests; execute format/analyze/tests; build and launch Linux; establish Windows build/host results in an available Windows environment/CI. Compare actual reference/Flutter captures at 1220×780 and 900×600 logical outer-window dimensions with matching DPI/UI scale/client insets/content/theme. Capture a narrow-window drawer, keyboard navigation and light/dark/pitch-black states. Record visual differences, missing evidence and native-caption differences explicitly.
6. Commit implementation and verification evidence on this branch only. Acceptance requires a real runnable shell, meaningful passing checks, truthful per-platform results and documented gaps; this documentation-only attempt does not satisfy it. Do not start Phase 2 automatically after resumption.

## Phase 1 continuation — safe source implementation and GitHub-hosted validation prepared

Continued from `3a2b1c22` on `2.0.0-flutter`. The local environment blocker is **not resolved**. A bounded search of workspace, /opt, /usr/local, /usr/lib and user cache/data locations found only the incomplete official toolchain checkout and its launcher wrappers; `bin/cache/dart-sdk` contains no Dart binary. Proxy metadata shows enforced restricted egress through `proxy:8080`; no proxy credentials were printed or changed. Previously denied official URLs were not repeatedly retried.

The `storage.flutter-io.cn` candidate mirror is recognized in the official Flutter source's HTTP-host validator tests, but its release metadata request was also denied with `curl (56) CONNECT tunnel failed, response 403`. The attempted official website documentation URL returned 404; no stronger mirror-provenance claim is made from it. No mirror SDK was accepted, so mirror SDK integrity/version validation could not complete. No TLS verification, checksum check or network security control was bypassed.

Useful independent work now exists under `flutterDesktop/`: real Dart bootstrap, Riverpod state/composition, go_router navigation with 32-entry history, real appearance/sidebar preference storage, native-window adapter, lifecycle/logging/error ports, source-transcribed semantic tokens and glyphs, responsive shell and disabled empty dock, plus future native-audio domain interfaces. No simulated audio/catalog service or old Flutter implementation was used. Fonts remain an explicit visual gap rather than an unlicensed silent asset copy.

No generated Windows/Linux platform files were fabricated. `tool/initialize_platforms.py` requires the pinned official SDK, runs `flutter create` in a temporary directory and preserves Dart source while copying genuine generated hosts. It was tested locally only in its refusal path: without Flutter it exits “Flutter is required; no platform files were generated.” Platform initialization has **not** happened locally.

A new independent workflow `.github/workflows/flutter-desktop.yml` uses `subosito/flutter-action` (pinned source commit) on GitHub-hosted Ubuntu 22.04 / Windows 2022 to perform official initialization, formatting, analysis, tests, release builds and an actual Linux window-launch smoke. It exports the complete generated/formatted project and lockfile for review, plus screenshots/logs/bundles. It never auto-commits code from PRs. The GitHub API probe for repository Actions permissions returned Forbidden; no dispatch, push, GitHub run, download of run evidence or successful platform build is claimed in this session. The workflow must first be published to the remote migration branch and executed there by an authorized actor with Actions access.

| Check | Actual result in this continuation |
|---|---|
| Dart source grammar | 14 files parsed using tree-sitter; syntax-only, not compiler/type/API verification |
| Layer boundaries / asset integrity | Python checks passed: domain/application/presentation import constraints, 19 asset hashes, SVG XML validity; both palettes, nine transport assets and three tab outlines compared with Kotlin source |
| Python helper syntax | `python3 -m py_compile flutterDesktop/tool/*.py` passed |
| Direct dependency source provenance | Five selected pub.dev archives SHA-256 verified; API signatures inspected; not `pub get` or a lockfile |
| `dart format` / `flutter analyze` | Not run: Dart SDK remains unavailable |
| Flutter tests | Six tests authored, **zero executed**; no passing Flutter tests claimed |
| Official host generation | Refusal without SDK verified; actual generation not run |
| Linux / Windows release build | Not run; workflow prepared but not executed |
| Linux GUI launch / native resizing | Not run; future smoke command authored |
| Kotlin runtime comparison | Still unavailable due prior JitPack restriction; source-level comparison only |
| RAM/CPU and full feature parity | Not measured / not established |

Phase 1 remains **in progress and not acceptance-complete**. The next gate is a successful hosted or newly permitted local SDK/official-template run; obtain and review its generated native trees, formatter output and `pubspec.lock`, fix any analyzer/test/build failures, then compare actual captures. See `flutterDesktop/README.md` for commands and explicit visual gaps. Do not start Phase 2 automatically.

## GitHub retry — branch published and hosted bootstrap exercised

After the user connected GitHub and requested another attempt, no GitHub connector tools were exposed, and the CLI request to `api.github.com` still returned Forbidden. The existing HTTPS Git proxy did permit a normal, non-force push. The migration branch is now published at `origin/2.0.0-flutter`; the first push created [run 43036353656](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43036353656) for `dd5f71db`.

GitHub's ordinary web run/job pages and XMLHttpRequest fragments are accessible. These exposed job/step conclusions even though raw logs and artifact downloads required authenticated access. The Windows hosted `subosito/flutter-action` step succeeded in the first two runs, resolving the SDK installation step **on the hosted Windows runner**, not on this local machine. The next grouped generation/source-check step failed before dependency resolution, formatting, analysis or tests. The failure cause was not initially visible in the public annotation, so it was not labeled an application defect.

Commit `096c6032` added a checked-command driver that preserves exit status, retains diagnostic output, and emits bounded/redacted failure annotations. A local controlled process confirmed exit 7, a saved log and the annotation. A second local experiment reproduced a Windows-encoding bug: cp1252 output caused `UnicodeEncodeError` before a Unicode diagnostic could be emitted. Commit `068efec9` sets UTF-8 explicitly and catches driver failures. The same cp1252 experiment then preserved the child's exit 7 and emitted its annotation correctly.

These instrumentation changes were pushed and triggered follow-up runs. They do not change Kotlin or application behavior and do not establish any Flutter test/build pass. The UTF-8 follow-up run was [43037846942](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43037846942) for `068efec9`; its inspected Windows failure is recorded below. Earlier in-progress Linux jobs were superseded by the follow-up commits under the workflow's existing concurrency policy.

Run `43037846942` exposed `boundaries: Asset differs: Clock.svg` on Windows after official host generation. This was a Git CRLF checkout difference, not a changed icon or codec artifact. Commit `22a48743` pins new Flutter text assets to LF and compares reference SVG text with checkout CRLF normalized. Expected asset hashes were not changed; local palette/glyph/provenance checks still pass. In follow-up [run 43038447764](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43038447764), Windows passed SDK setup, official generation/source checks, dependency resolution, formatting and analysis. Tests had started but had no terminal result at the time of the next instrumentation update; no pass/failure count is inferred from that.

The validation driver now streams output instead of buffering it until a potentially long test finishes, publishes test-start/count notices, and rejects successful processes that execute no passing tests. These counting rules were checked with controlled JSON protocol fixtures, not described as Flutter results. A separate retention job is restricted to trusted push events on this repository's migration branch, requires the complete Linux/Windows matrix (including Linux graphical smoke) to pass, checks that the branch still matches the validated SHA, and only then commits official generated hosts, the resolved lockfile, formatter output and bounded render evidence. PR validation remains read-only; newer work is never force-overwritten. This avoids hand-fabricating platform files or needing inaccessible authenticated artifact downloads locally.

## Phase 1 verification update — 2026-11-11

Last confirmed pushed implementation commit: `529c8d60`
Branch: `2.0.0-flutter`

GitHub Actions run:
https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43041242897

### Confirmed results

The Windows job completed successfully:

- Flutter SDK installation using subosito/flutter-action.
- Official Flutter project generation.
- Architecture boundary and reference asset checks.
- Dependency resolution.
- Dart formatting.
- Flutter analysis.
- Six tests passed, with zero failures and zero skipped tests.
- Windows release build.
- Artifact upload.

The six tests cover theme policy, settings persistence and bounded navigation,
expanded shell layout, narrow-window navigation, settings and keyboard actions,
and rendered shell captures at 1220×780 and 900×600.

### Pending verification

The Linux job was last observed installing desktop build dependencies.
Its final outcome has not been confirmed.

The Linux native launch and screenshot checks have not been confirmed.
The workflow job that retains generated platform files, formatting, lockfile,
and verification artifacts has not been confirmed.

### Blockers and limitations

- Local Flutter bootstrap remains blocked by HTTP 403 responses for SDK artifacts.
- The candidate Flutter artifact mirror was also inaccessible.
- TLS verification and integrity checks were not disabled.
- Kotlin runtime comparison remains blocked by JitPack dependency resolution.
- No identical-dimension Kotlin-versus-Flutter visual comparison is complete.
- Windows release compilation does not establish native GUI launch verification.
- No audio backend or Phase 2 functionality has been implemented.

Phase 1 remains incomplete until the outstanding verification and visual
acceptance criteria are resolved. Do not begin Phase 2 automatically.

### Later GitHub verification — 2026-11-12

Run `43041242897` has now completed with **failure**. Windows finished
successfully; Linux failed during **Install Linux desktop dependencies**,
before native launch and screenshot checks. `retain-verified-generation`
was skipped. The earlier pending-status paragraph above is retained as the
historical 2026-11-11 observation, not the current run status.

## Phase 1 resumption — 2026-11-12

User authorized completing Phase 1, committing/pushing to `2.0.0-flutter`, and
monitoring Actions. Started from clean `3c9abb6a`; fetched remote branch and
confirmed the same head. Phase 2 is not authorized.

Latest inspected pre-change run: [43121851901](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43121851901),
source `3c9abb6a`. **Both platforms passed** official host generation, dependency
resolution, format, analysis, six tests (zero failed/skipped), and release build.
Linux failed the painted-window check; generated-host retention was skipped.
Downloaded Linux artifacts confirm a black native capture; widget captures use
the test Ahem font and establish layout only. They are not reference goldens.

The older Linux setup failure was DNS resolution of `azure.archive.ubuntu.com`,
followed by stale indexes and installation timeout, not a Dart/compiler failure.
The CI setup now scopes signed official Ubuntu HTTPS repositories to its own
APT commands and fails on index errors. Linux smoke uses an isolated profile,
D-Bus/Xvfb/Openbox, a larger virtual display, bounded paint polling, visible-screen
client capture (including GL children), actual image dimensions, and separate
application/stage logs. A Windows smoke now checks visible painted client windows,
two client sizes and native close/exit. These changes await hosted execution;
no passing result is inferred from their source.

Local checks: Python compilation; layer/19-asset integrity; reference palettes,
nine transport SVGs and three custom glyphs; all 779 inventory SHA-256 entries
verified against the pinned reference; `git diff --check`. No local Flutter SDK
is on PATH. Kotlin paths remain unchanged. Remaining gates include successful
native lifecycle evidence, retained/reviewed official hosts and lockfile,
matched Kotlin/Flutter captures, DPI/native-theme/focus verification and explicit
font/material/icon/motion gaps. Phase 1 remains **INCOMPLETE** pending evidence.

Follow-up run [43123831091](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43123831091)
at `b227b10f`: Linux setup, analysis, six tests, release build and basic lifecycle
smoke passed; Windows analysis, six tests and release build passed, but native
client-size verification failed. Artifact review found stale/corrupt Linux
capture content despite the basic nonblank check. This is **not visual acceptance**.
The stronger check now waits after native resize and verifies the responsive
sidebar edge and centered dock logo, rejecting both flawed captures locally while
accepting both widget-layout fixtures. Windows records actual geometry and adjusts
the observed frame insets during resize. No assertions were removed.

This workstation now reaches JitPack with HTTP 200; the historical cloud 403 is
not a current local result. Local Flutter is absent, Java is 27 (no full JDK 21),
and workspace free disk is under 1 GiB. A separate manual, read-only workflow
checks out only the immutable Kotlin reference on hosted Linux/Windows with
JDK 21, runs its tests and attempts a distributable for comparison. It neither
changes the Kotlin branch nor imports prior Flutter work; results remain pending.

Run [43125383087](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43125383087)
at `dc3fba4b`: builds/analysis/six tests passed on both OSs. Delaying after resize
produced a correctly painted Linux 1220×780 client (reviewed). The landmark check
itself failed because ImageMagick interpreted crop coordinates relative to PNG
screen-origin metadata; resetting the virtual canvas before cropping fixes this.
Contrast in the dock-mark region now distinguishes actual painted glyphs from
the blank stale dock without depending on antialiasing color counts. Local
fixtures reject both stale native images and accept the correct native image and
both widget renders.

Windows diagnostics show a 1024×768 hosted display clamping the client to
1028×749. CI now requests a 1600×1200 hosted display before launching, with
the original 1220×780/900×600 assertions intact. Windows also checks sidebar/dock
landmarks. The trusted retention job keeps its original branch/SHA/matrix guards
and additionally retains Windows capture/geometry evidence. No renderer, blur or
other visual effect was disabled.

Manual dispatch of the newly added reference workflow returned HTTP 404 because
it is absent on the default branch. A path-filtered push trigger on the migration
branch started [43125482870](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43125482870)
at `56b3a228`. It is still running. Local offline reference tests were attempted
with Gradle 8.11.1 and a separate `/tmp` cache: the sandbox first blocked its
daemon socket; the permitted retry then failed before tests with Java version
`27.0.1.0.3`. No local test pass is claimed.

Run [43126119556](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43126119556)
at `72dd48d6`: Linux passed all checks, including both reviewed native captures,
responsive landmarks and native close/exit 0. Windows passed analysis/six tests,
release build, hosted 1600×1200 display setup and both painted client-size checks,
but close exited with `-1073740771` (`0xC000041D`). Retention was correctly skipped.
The application now flushes settings, removes its listener, releases close
prevention and requests a normal native close instead of the plugin's force-quit
path. The pinned plugin source shows that force-quit posts WM_QUIT while its
HWND remains alive. Two new lifecycle tests cover pending-drain ordering,
repeated close and failed-drain cleanup; their hosted execution is pending.
Linux smoke logs also contain GTK accessibility/initial-frame and implicit-view
teardown warnings; passing exit/paint checks do not establish accessibility or
physical GPU correctness.

## Phase 1 acceptance audit — 2026-11-12, foundation verified; visual acceptance BLOCKED

Successful run: [43126969435](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43126969435),
source `ea6b5c90`; trusted retention commit `6f67ac2f` was fetched and reviewed.
The native-close change resolved Windows exit `0xC000041D` without weakening the
exit-code assertion. Both platforms now close normally with exit 0.

| Gate | Observed result |
|---|---|
| Official Windows/Linux hosts, SDK pin and lockfile | PASS; official generator output/provenance, resolved lockfile and formatted source retained in `6f67ac2f`; no restart or handcrafted host |
| Format, analyzer and architecture/asset checks | PASS on both hosted OSs; future pushes check formatting without silently repairing it |
| Foundation tests | PASS, 8 per OS, zero failed/skipped; includes two new ordered-drain/idempotent/failure-close regressions |
| Linux release build/native lifecycle | PASS, Ubuntu 22.04, Clang 14, CMake/Ninja, Xvfb/Openbox/software GL; actual 1220×780 and 900×600 client captures, responsive landmarks, native close/exit 0 |
| Windows release build/native lifecycle | PASS, Server 2022/VS 2022, hosted 1600×1200 display at 96 DPI; both client sizes/landmarks and native close/exit 0 |
| Retained capture evidence | PASS; `flutterDesktop/verification/hosted-run.json`, native PNG/JSON and test summary; widget captures explicitly use Ahem |
| Runnable pinned Kotlin Linux reference | PASS; run `43125482870`: shared 78 + sharedUi 85 + desktop 694 = 857 tests, zero failures/errors/skips, plus distributable. Retry `43126572145` also passed Linux |
| Pinned Kotlin Windows reference | BLOCKED; both reference runs fail `:desktopApp:buildAnalysisNative` with CMake exit 1, before a usable reference distributable. Diagnostic standalone configure succeeded with GNU 14.2/JDK 21; Gradle native build still fails. No test/visual pass is inferred |
| Matched visual acceptance | INCOMPLETE; limited local Linux light-theme client comparison captured at both sizes; anonymous Kotlin catalog differs from disconnected Flutter body, so only common shell regions were reviewed. Windows reference pairing is unavailable |
| Native theme/DPI/focus/fullscreen/accessibility matrix | UNVERIFIED beyond hosted 96-DPI lifecycle and widget shortcut/theme tests; no physical-compositor acceptance inferred |
| Reference/data preservation | PASS; no tracked Kotlin/shared/native changes; all 779 recorded reference hashes checked; local reference captures used temporary Java Preferences/user-home and Flutter profiles |
| Performance/full features | NOT MEASURED/NOT IMPLEMENTED; no audio, mock catalog or Phase 2 implementation introduced |

Local Linux capture evidence is in
`flutterDesktop/verification/phase1-local-comparison/`, with app/source/run hashes,
actual raster sizes and limitations in `capture.json`. Both builds were opened
sequentially in the same KDE Wayland/XWayland session. The Kotlin profile was
fresh, welcome-completed and explicitly Light; Flutter Light was selected through
its real settings page. Native root-screen capture was unavailable on XWayland,
so client drawable capture was used. Each saved image's dimensions and SHA-256
were checked. Numerical local DPI was not independently measured. These captures
were cleaned up with SIGTERM and **are not native-close evidence**; native close
is established separately by the passing hosted smokes. Logs, account data,
tokens and profiles were not retained.

Observed P02/P24/P29–P31 gaps: the extra 48-dp Flutter status/navigation row moves
the Home heading down; fallback font metrics and sidebar row spacing differ;
the reference account row/additional rail action are missing; the dock's mark is
larger/darker and several reference controls/volume and feed-under-dock effects
are absent. The 220/72 sidebar and 760×58 dock bounds match structural checks,
but this is not pixel parity. Content differs by implementation scope and blur
cannot be accepted against different backgrounds. SF Pro rights, native icons/
caption/Mica, motion/focus and full DPI/theme/accessibility checks remain open.

**Phase 1 remains INCOMPLETE/BLOCKED for visual acceptance.** Foundation CI and
native lifecycle gates are verified. Next concrete prerequisite: obtain/diagnose
a runnable pinned Windows reference without changing the protected reference,
then execute the remaining matched visual/platform checks and resolve the
recorded shell differences. Phase 2 has not started and is not authorized.

## Phase 1 targeted continuation — 2026-11-12

Resumed from clean `ac30f547`, matching the fetched migration branch. Latest
foundation run `43128710948` passed both platforms and retention. The user
authorized diagnosis of the pinned Windows native failure and correction of the
documented shell differences, still within Phase 1 (P01/P02/P24/P28–P31).
A focused Windows-only diagnostic workflow reproduces the reference's fresh
CMake/Ninja/GNU configure and verbose compilation separately; it leaves all
protected reference source untouched and retains the actual compiler output.
Results are pending. No Phase 2 implementation has begun.

Focused diagnostic [43129985058](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43129985058)
proved that the analyzer DLL itself compiles/links with hosted GNU 14.2. The
failing target is `bitchord_smtc`: the hosted MinGW WinRT headers expose ambiguous
`TimeSpan` and incomplete/missing stream-reference, controls2 and timeline types
when compiling the pinned `smtc_abi.cpp`. CMake's header-presence checks pass,
but do not prove those interfaces compile. No reference source was changed.

The reference already provides an MSVC/C++/WinRT implementation in its CMake
files. A CI-only external Gradle init script now selects that supported compiler
for both native tasks, builds all original targets, and requires analysis, SMTC
and window DLLs. No native target or test is skipped. This is an explicit build
environment adaptation, not a fix to the protected reference or an audio migration.
Reference test/package results for the MSVC route are pending.

MSVC run `43130275448` built the analysis and C++/WinRT SMTC DLLs, then exposed
an independent MSVC-only property-key header/include-order failure in the window
JNI bridge. The adapter is narrowed to `buildAnalysisNative`; the window task
keeps its original GNU toolchain/action. All three native libraries remain
required, with all reference tests enabled. This mixed supported-toolchain
reference build is awaiting execution; no protected source was edited.

Source audit for the Phase 1 shell correction: `DesktopApp.kt` Sidebar/SidebarItem,
MiniPlayer/BottomChrome/Frame and Alt+Left handling; `DesktopAccountUi.kt`;
`DesktopPipUi.kt`; `DesktopThinSlider.kt`; `DesktopTypography.kt`/font mapping;
shared `BitChordTypography.kt` and `HomeScreen.kt` compact heading policy
(P01/P02/P24/P28–P31). The empty dock uses the distinct padded gray
`bitchord_no_music.svg`, not the sidebar's full-size white logo.

Flutter source now removes the default 48-dp navigation row, preserves back via
Alt+Left, matches sidebar 45/51-dp headers and 32/42-dp rows/dividers/footer,
restores the playlist rail action and account avatar/empty menu, and uses the
original gray dock asset, original button dimensions, all dock action slots and
an explicitly unavailable volume track. Backend/auth actions remain unavailable;
no fake playback or sign-in is introduced. The dock/menu use the source's chrome
tint/blur settings; exact Haze noise/renderer equivalence is not claimed.

Typography corrects compact headings to 30 sp, removes inherited Material line
heights from customized roles and maps less-obvious default role weights/metrics.
SF Pro binaries remain unbundled: no redistribution permission was established,
and numeric/typeface parity is still open. Four new tests cover reference layout
bounds, account-menu behavior/rail policy and typography. Execution is pending.
Official standalone Dart 3.13.7 was downloaded to `/tmp`, SHA-256 checked against
the publisher checksum, and used to format/check source at the project's 3.11
language version. Local Python boundary/20-asset/reference checks pass; Flutter
analysis/tests/builds still use hosted runners, given local disk/RAM limits.

Run `43132090172` passed analysis but failed three new Flutter tests: the
account caption wrapped with Ahem instead of honoring Kotlin's single-line
ellipsis policy, and menu route semantics lacked `explicitChildNodes`. Those
implementation issues are corrected; the layout assertions remain unchanged.
Reference run `43131467099` again reached the window native task failure with
GNU. The MSVC adapter now supplies the Windows SDK's `propkeydef.h` via `/FI`
for that bridge, preserving the reference files and all target/test checks.
This header prerequisite corrects include order, not application/audio logic;
its compile/test results are pending.

The corrected shell in run `43132763894` passed analyzer, all 12 tests per OS,
Windows/Linux release builds, both native client sizes, F11/Escape size restore
and native close/exit 0. Its retention job refused to overwrite newer branch work,
so the overall run is red despite both desktop jobs passing. This is the intended
SHA guard, not a source/build failure. The native captures were downloaded and
reviewed: the extra row is gone, corrected sidebar/footer and gray dock are visible.

Windows reference runs `43132763855`/`43132960359` exposed that forced-including
`propkeydef.h` alone is insufficient: its PROPERTYKEY declarations also require
Windows types. The external compiler prelude now includes `windows.h` first with
the reference's existing Windows/NOMINMAX definitions. No protected source changes
or weakened tests are involved. The complete native/reference result is pending.

Native smoke checks now additionally exercise actual account/drawer/settings
input and Light/Dark/Pitch-black selection, retaining six state captures per OS.
Theme checks assert the real rendered page color, followed by fullscreen/restore
and clean native close. These are shell checks, not acceptance of reference
settings inventory, authentication, accessibility or full visual parity.
## Phase 1 targeted acceptance audit — 2026-11-12

This audit supersedes the earlier missing-shell and native-build observations;
it does not accept full visual parity or authorize Phase 2.

| Gate | Latest verified result |
|---|---|
| Flutter foundation Windows + Linux | PASS: run [43134106705](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43134106705), source `3b2a8747`, full matrix and retention succeeded; bot commit `32f8150b` fetched/reviewed |
| Formatting / analyzer / asset boundaries | PASS on both OSs; local official Dart 3.13.7 format check at language version 3.11, Python compilation and 20-asset/palette/reference checks also pass |
| Tests / release builds | PASS: 12 tests per OS, zero failures/skips; official Flutter 3.47.9, Windows VS 2022 and Ubuntu 22.04 release bundles |
| Native shell and lifecycle | PASS in hosted environments: actual 1220×780 and 900×600 painted clients, account/drawer/settings captures, rendered Light/Dark/Pitch-black selection, F11/Escape size restoration and native close/exit 0 on both OSs |
| Retained evidence | PASS: six additional appearance/overlay PNGs per OS, native JSON, two base client PNGs and test summary; Ahem widget images remain layout diagnostics |
| Pinned Windows native targets | PASS: focused [43134106555](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43134106555) builds analysis + SMTC + window DLLs with supported MSVC/C++/WinRT and SDK prerequisites; hashes in `phase1-shell-correction/windows-native.json` |
| Pinned full reference tests/package | PASS on Linux and Windows in [43134106568](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43134106568): 857 tests per OS, zero failed/errors/skipped; Windows original JNI/model packaging audit passed |
| Pinned Windows reference visual capture | INCOMPLETE: tested Windows bundle exists, but the capture helper found no visible main window after 30 seconds; bounded process-tree/window diagnosis and a capture-only retry are pending |
| Corrected local Linux shell comparison | PARTIAL: repeated both apps sequentially in the same KDE/XWayland session, Light and empty test-owned profiles, both client sizes; four new PNGs/source/run/hash provenance in `phase1-shell-correction/capture.json` |
| Kotlin/reference preservation | PASS: byte SHA-256 comparison of all 700 tracked files under desktopApp/shared/sharedUi/native with pinned reference, zero differences; reference branch untouched |
| Visual/platform acceptance | INCOMPLETE: font rights/ink metrics/wrapping, exact Haze/noise/feed material/motion, native caption/Mica/icons, matched Windows pairing and full DPI/focus/accessibility matrix remain open |

The local comparison confirms the extra navigation row is removed and common
sidebar/footer/dock geometry now follows Kotlin. Home ink top/width and expanded
playlist-caption wrapping still differ with fallback fonts. The real Kotlin
catalog and disconnected Flutter body are different states; no full-frame or
blur equivalence is inferred. Numeric local DPI is unmeasured and SIGTERM
capture cleanup is not lifecycle proof. Hosted native smokes establish lifecycle
separately. No actual RAM/CPU benchmark, audio/auth implementation or Phase 2
work is included. Phase 1 remains **INCOMPLETE for visual acceptance**.

## Windows reference capture retry — 2026-11-12

The full pinned reference run [43134106568](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43134106568)
finished. Linux passed the same 857 tests (78 shared, 85 sharedUi, 694 desktop,
zero failed/errors/skipped) and distributable build. Windows also passed all 857
tests with zero failed/errors/skipped, built the distributable, passed protected
source preservation and the packaged native JNI/model audit. Its capture failed
after 30 seconds because .NET `MainWindowHandle` found no visible window for the
launcher. This is a GUI capture failure; test/package/native compile passes are
independent and retained in that run's artifact.

A focused capture workflow reuses only that successful immutable Windows build
artifact. It verifies the producing run, exact pinned reference SHA, passing
Windows test/package/preservation/JNI steps, then checks windows belonging to
the packaged launcher's process tree with a bounded two-minute startup window.
On a repeated startup failure it stores only process/window ownership, Java
exception class names and stack frames. Provider log messages, URLs and profile
data are not retained. The Linux smoke now waits for overlay dismissal and
asserts both the dark drawer scrim and the light Settings page before accepting
those labeled captures. Results are pending; no Windows reference image or new
state-capture pass is inferred.

Focused capture retry [43155307079](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43155307079)
succeeded against the verified Windows package from `43134106568`. It found the
visible `BitChord` window in the launcher's process tree and captured real
1220×780 and 900×600 clients at 96 DPI from the pinned commit, with a fresh
light/100% hosted profile. Those PNGs and run/dimension/hash provenance are in
`flutterDesktop/verification/phase1-shell-correction/`. Capture-only force-stop
is explicitly not lifecycle evidence; native close/fullscreen is verified by the
separate Flutter desktop smokes. Visual review surfaced an 11-pixel Home heading
ink offset on Windows, so the Flutter Home top gutter has been adjusted from 16
to 10 dp to match the Kotlin HomeTitle's 8 dp padding plus platform font inset.
The layout assertion now checks the new measured heading location. Cross-platform
build/test/capture verification of this final spacing adjustment is pending.

The native font fallback ink inset also differs between Windows and Linux. The
final heading therefore uses measured 5 dp and 9 dp top gutters on those systems,
respectively, with platform-metric and Linux widget-geometry assertions. The
Windows pixel comparison and local Linux client captures supply the measurements; the complete
refreshed matrix must pass before this adjustment is verified.

Run `43156122632` reached formatting on both platforms and stopped because the
two just-edited Dart files needed formatter changes; no analyze, widget test,
build or smoke results are inferred from that run. The official standalone
Dart 3.13.7 archive was re-downloaded from storage.googleapis.com, checked against
the publisher SHA-256 `eb99aed266efbcb615c6ae21c12f52cfe935b9f208bccc5fea7f64c21e867229`,
and used to format only `desktop_shell.dart` and `shell_test.dart`. All 15 Dart
files now pass the local language-version-3.11 formatter check. A fresh full
Windows/Linux run with thirteen tests is pending.

That run [43156599265](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43156599265)
passed formatting but failed analysis because the new Windows-position widget
test used the removed `debugDefaultTargetPlatformOverride` test API. That test
now checks both platform-to-gutter measurements directly, while the existing
Linux widget bounds test confirms the gutter in layout. No build/test result is
inferred from the failed analyzer run; a fresh matrix is pending.

## Phase 1 latest verification — 2026-11-12

The fresh Flutter matrix [43156992243](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43156992243)
completed successfully on source `8d607663`. Windows and Linux each passed
formatting, analysis, all **13 tests** (zero failed/skipped), release packaging,
actual shell launch, 1220×780 and 900×600 client captures, six named account,
drawer and appearance states, and F11/Escape restoration. Both native close
smokes exited with code 0. The GitHub retention job passed and its verified
evidence commit `b50c5788` is on this branch. The retained Windows JSON records
the hosted 96-DPI display and the Linux JSON records Xvfb/Openbox at 96 DPI.

The full pinned Kotlin reference workflow [43156276992](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43156276992)
also completed successfully. Linux and Windows each passed all 857 tests with
zero failures/errors/skips and built the distributable. The Windows native
analysis, SMTC, and window DLL tasks, JNI/model packaging audit, protected-source
preservation check, and fresh-profile 1220×780/900×600 shell capture all passed.
The `:desktopApp:buildAnalysisNative` failure was diagnosed as a toolchain/header
configuration issue: MinGW's C++/WinRT path fails on ambiguous/incomplete
Windows Runtime types. The reference build now uses MSVC with the required
Windows SDK headers supplied by external Gradle initialization; no protected
Kotlin or native reference source was changed. The reference tree remains
byte-identical to the pinned commit for all 700 tracked files in the protected
desktopApp/shared/sharedUi/native paths.

The Flutter native captures are retained under `flutterDesktop/verification/`.
Review against the pinned Windows images in
`flutterDesktop/verification/phase1-shell-correction/` at matching client sizes
confirmed that the extra navigation row is gone, sidebar width/dividers/footer
and dock placement align closely, account controls are present in the expanded
sidebar, and the Home title ink now begins at y=16 in both 96-DPI Windows
captures. The title still differs in x/width and glyph shape (fallback font);
matching its top coordinate does not establish typography parity. The 900×600
Kotlin home has live catalog content while Flutter deliberately shows its
disconnected placeholder, so body pixels are not comparable. The earlier local
Linux Kotlin/Flutter pair predates the heading adjustment. A refreshed isolated
Linux pair is now recorded in the following verification entry; the hosted
Linux Flutter capture at y=15 remains a different font/rendering environment.

Remaining visual acceptance gaps are explicit: SF Pro redistribution rights
are not established and fallback glyph widths/wrapping differ; exact Haze/noise,
feed material and motion are not reproduced; native Windows caption/Mica/icon
behavior is not part of the Flutter client capture; and a broader physical DPI,
focus, accessibility and native-theme matrix remains unverified. No full-frame
visual parity is claimed. Foundation and hosted lifecycle gates pass, while
Phase 1 visual acceptance remains **INCOMPLETE** pending these gaps.
Phase 2 remains unstarted.

## Phase 1 final Linux comparison and CI repeat — 2026-11-12

The documentation push also passed the complete Flutter Windows/Linux matrix
in [43158220349](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43158220349),
source `ad0bc173`: 13 tests per OS, analyzer/format, release builds, real native
appearance/overlay captures, F11/Escape restore and native close/exit 0. Its
retention commit `37c51152` was fetched and fast-forwarded into this branch.
Local boundary/20-asset/reference-token checks passed again, and the diff of
desktopApp/shared/sharedUi/native against the pinned reference is empty.

The tested Linux bundles from reference run `43156276992` and Flutter run
`43156992243` were downloaded and launched sequentially in the same isolated
Xvfb display at 96 DPI, with separate temporary XDG directories, Java
Preferences/user.home, and D-Bus sessions. Both used Light/100% scale and empty
queues/accounts. Four actual client images (1220×780 and 900×600 per app),
source/run provenance, hashes, capture method and renderer limits are retained
in `flutterDesktop/verification/phase1-linux-final/capture.json` and its PNGs.
An earlier visible-desktop trial whose playback state changed was rejected.

The repeat confirms the extra header/navigation row is removed; expanded
sidebar/rail, dividers, account footer and dock bounds align closely at both
sizes. The Home title ink begins at y=16 in both applications; Flutter's fallback
glyphs remain 4 pixels wider and 1 pixel taller. The expanded playlist caption
also wraps differently. The Kotlin live anonymous feed differs from Flutter's
disconnected body, so content pixels and dock blur over that content cannot
establish matching materials. Disabled Flutter transport/volume styling is
intentional while its backend is unavailable.

The default Kotlin OpenGL renderer could not create a context in this local
Xvfb setup; this accepted capture explicitly used Skiko SOFTWARE rendering.
Flutter used Mesa llvmpipe software GL. Xvfb was extracted to `/tmp` from the
official Arch Linux package after checking its detached signature with the
installed Arch keyring. No system packages or protected reference sources were
changed. Capture cleanup used SIGTERM and is not lifecycle acceptance; the
separate hosted native-close checks supply that evidence.

The missing final Linux pair is now supplied. Remaining visual acceptance
blockers are the unresolved SF Pro redistribution rights and glyph/wrapping
differences, exact material/motion work, and unavailable physical Windows/Linux
compositor/DPI/focus/accessibility coverage. The current shell is buildable and
its hosted lifecycle passes, but full visual acceptance remains **INCOMPLETE**.
No Phase 2 work has begun.

## Phase 1 handoff correction and user review — 2026-11-12

This entry supersedes earlier *current-status* statements for Phase 1 while preserving the historical CI/audit records above. The user requested documentation reconciliation **before starting Phase 2**, not implementation of Phase 2 and not a premature Phase 1 parity sign-off.

### What is verified

- The earlier Kotlin Windows native toolchain/reference failure was resolved by the verified MSVC + Windows SDK setup without modifying the pinned protected Kotlin reference. Full reference test/package and 96-DPI window capture evidence are recorded above.
- The Kotlin-vs-Flutter shell comparisons at 1220×780 and 900×600 establish useful baseline geometry at matched 100% scale, but not full visual/feature equivalence.
- Flutter workflow [43163359448](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43163359448) passed Windows + Linux desktop jobs and trusted retention with strict Dart formatting, tests, release bundles and native shell smoke evidence.
- Subsequent Phase 1 code changes include a responsive compact dock (small-width test), `NoTransitionPage` navigation to remove Android-style Material page transitions, explicit desktop font fallbacks, and Linux drawer smoke interaction repair. The user has not yet approved the rendered result.
- The most recent font-family change is [`c9cfdb1e`](https://github.com/Bntagaaa/BitChord-Desktop-Revived/commit/c9cfdb1e55ef7e7bc3b058303eb6289968e9dcec). Its workflow [43169153250](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43169153250) was **in progress at this documentation update**; do not infer its final result. A later reviewer must check it explicitly.

### Corrected visual observations

- The user's earlier impression that Kotlin sidebar navigation spacing was larger was caused by comparing **Kotlin UI Scale 110%** to **Flutter 100%**. At a matched 100% scale, the user reports that the spacing is the same. **Do not increase sidebar padding or mark 100% row spacing as a known defect based on the unmatched comparison.** The Kotlin item uses 7 dp vertical padding and the Flutter geometry is intentionally retained.
- Kotlin also supports user-controlled UI Scale (including 110%). Matching the source's scaling behavior and testing 100%/110% and broader DPI values remain future visual-system work, not proof of current Flutter parity.
- The user supplied five static font cuts named `bitchord-sans`: Regular 400, Medium 500, Semibold 600, Bold 700 and Heavy 800. Flutter now prefers that installed system family rather than `SF Pro Display`, but **the font binaries are not bundled** and weight/glyph parity is not validated. Do not ship or redistribute supplied fonts until their license/provenance and redistribution permissions are established. The source Kotlin desktop's physical font-cut mapping remains the reference for typography.
- Android-style route transitions were reported by the user; the Flutter route implementation was changed to `NoTransitionPage`. Passing navigation tests do not replace the user's manual perception check.

### Phase status and next authorization boundary

**Phase 1 foundation: VERIFIED by hosted Windows/Linux CI. Phase 1 visual parity/manual acceptance: OPEN, awaiting user review.** Do not mark the entire phase 100% complete. Outstanding checks include font availability and real glyph/wrapping parity, material/Haze/motion, native chrome/Mica/icons and wider DPI/focus/accessibility tests. Full catalog/music/audio behavior remains out of this phase.

**Phase 2 implementation: NOT STARTED in this handoff.** The requested next action is to use the existing `.codex/phases/phase-2.md` playbook only after the user chooses to start it. Carry unresolved visual evidence into Phase 2 with explicit owners/status rather than treating it as already accepted. Do not automatically move to Phase 3.

## Phase 1 manual acceptance and Phase 2 handoff — 2026-11-12

**User decision:** The user manually reviewed the current Flutter Phase 1 application and explicitly confirmed that everything looked OK. This approval supersedes the earlier *awaiting user manual visual review* gate for the Phase 1 foundation/shell. **Phase 1 is ACCEPTED for its authorized foundation and shell scope.** It is not a claim of pixel-perfect parity across every platform, complete application functionality, or proven RAM improvements.

- Last fully confirmed foundation CI at acceptance time: [43163359448](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43163359448), success on Windows/Linux with tests, release builds, native smoke and retention. The later font-specific run [43169153250](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43169153250) was still in progress when this acceptance was recorded. Check the latest run outcome before relying on it.
- User-confirmed navigation and visual shell are acceptable to proceed. The earlier sidebar spacing concern arose from unmatched Kotlin 110% versus Flutter 100% UI scale; no further 100% padding changes are requested.
- The supplied `bitchord-sans` font family is selected when installed; binaries remain **unbundled** pending provenance and redistribution permission. The user's visual approval does not establish third-party font licensing.
- Carry over platform-specific OS window chrome/Mica/Haze fidelity, broader DPI/accessibility/motion checks, and optional exact typography measurements as tracked **Phase 2/Phase 8 enhancement/verification items**, not Phase 1 completion blockers. New product functionality and audio are still future phases.
- This documentation change does not implement or start Phase 2. **Phase 2 is READY for explicit user-authorized execution** using `.codex/phases/phase-2.md`. Do not initiate Phase 3 automatically.

## Phase 2 execution — 2026-11-12

User explicitly authorized Phase 2 and confirmed Phase 1 accepted. Rechecked
baseline run [43169704236](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43169704236): Windows/Linux and guarded retention succeeded;
font-only run 43169153250 was cancelled, not failed or verified independently.
Fast-forwarded verified retention `c71b8e19` while preserving task edits.

Implemented source-pinned route/settings/default/visibility/semantics extraction,
15 source shell translation catalogues with fallback, reference UI zoom and
backward-compatible shell preference extension, independent display-DPI widget
coverage, drawer Back ordering, and modal background focus/semantics exclusion.
Existing accepted 100% geometry, honest disconnected pages and disabled dock
remain. Full inventory, reproducibility commands, per-surface states and explicit
visual gaps are in `flutterDesktop/verification/phase2/README.md`.

Local: publisher-SHA-256 verified standalone Dart 3.13.7; format at language 3.11,
Python compilation, reference extraction reproducibility, layer/20-asset check,
palette/transport/vector verification and protected Kotlin tree diff passed.
Full Flutter analyze/test/release/native checks use CI (local SDK unavailable,
workspace free disk <1 GiB); results will be appended after the pushed run.

**Phase 2 INCOMPLETE** pending the platform run and remaining matched native
DPI/motion/focus/material/font evidence listed in the ledger. Existing real
Windows/Linux fixtures are retained with original provenance, not renamed as
new captures. No full visual parity, performance result or Phase 3 implementation
is claimed. Phase 3 remains unauthorized.
