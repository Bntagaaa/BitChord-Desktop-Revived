# Flutter migration architecture

## Decision and boundaries

Pinned Kotlin reference: `1.9.2` at `348b613a0c3db1a54de56483ea6f8748d6465211`. Target: Flutter Desktop for Windows x64 and Linux x64; keep the current Linux packaging compatibility baseline under review (`DESKTOP.md` documents Ubuntu 22.04). No mobile/macOS expansion. All new application code and native bridges belong under `flutterDesktop/`. Do not modify Kotlin, overwrite `flutterApp/`, merge previous Flutter rewrites or introduce a JVM sidecar as the final application architecture. Use reference source as a specification; independently implement Dart orchestration and UI. Existing native-library reuse is a separate documented provenance decision, never previous Flutter implementation reuse.

Phase 0 selects boundaries and acceptance contracts, not a claim that dependencies are ready. Phase 1 is precisely bounded in MIGRATION_PROGRESS. Pin a current Flutter stable SDK, Dart constraint and dependency versions only after checking Windows/Linux compatibility in that phase; this audit does not invent a tested Flutter version or lockfile.

## Layers and ownership

```text
Flutter widgets / custom painters / window views
  -> application controllers and use cases
    -> pure Dart domain contracts and state machines
      <- data repositories / platform adapters implement ports
        -> SQLite, HTTP/WebSocket, secure OS storage
        -> C ABI audio / DSP / analysis / video libraries
        -> Windows and Linux native window/media/update APIs
```

Presentation cannot call HTTP clients, SQL, filesystem or raw FFI. Domain has no Flutter/plugin imports. Application owns cancellation, operation generations, state transitions and error mapping. Repositories own serialization, persistence and external protocol conversion. Platform adapters own handles, native threads and event translation. Composition in `lib/app/bootstrap/` injects real adapters; tests inject deterministic fakes. Do not ship fakes or empty adapters as implemented functionality.

Proposed layout (created later, not placeholder files now):

```text
flutterDesktop/
  lib/app/                 bootstrap, router, lifecycle, dependencies
  lib/core/domain/         IDs, failures, clock, cancellation contracts
  lib/core/data/           HTTP, database, serialization, migrations
  lib/core/platform/       desktop, storage, diagnostics, native bindings
  lib/design_system/       measured tokens, typography, surfaces, motion
  lib/features/<feature>/
    domain/                immutable entities, rules, repository ports
    application/           use cases and lifecycle controllers
    data/                  repository implementations and DTO mappers
    presentation/          views and view models
  native/                  audio, analysis, video, platform C ABI bridges
  windows/ linux/          Flutter host and integration registration
  assets/                  reviewed fonts, artwork, shaders and models
  test/ integration_test/  behavioral, visual and platform regression suites
```

Feature boundaries follow P01–P38: onboarding, discovery/catalog, search, library, local music, downloads, history/replay, queue/playback, lyrics, artwork/video, accounts, party, integrations, settings and updates/about. Share pure models and service contracts, not widget state. A source ID, catalog song ID, rendition ID and queue entry ID are distinct. Never key duplicated queue rows solely by video ID.

## State and navigation decisions

Use `flutter_riverpod` as the preferred dependency and reactive-state layer, immutable snapshots and explicit controller commands. Use scoped/selective subscriptions; high-frequency playhead updates must not rebuild the app or feed lists. Request generations belong to account/query/track/route contexts; cancellation does not alone prevent a late native/network completion. Validate generations both before enqueue and when consuming commands. Avoid broad keepAlive scopes and unbounded provider families; document retained state and dispose reasons.

Use `go_router` with typed route models for reference destination IDs: listen-now, new, explore, library, spotify, search, history, downloads, local-music, settings. Detail routes include collection, artist, shelf and Replay. Preserve navigation/back state and per-route scroll/sort/filter selection through lightweight restoration records. Retaining a scroll offset must not retain all decoded artwork. Overlays (search, provider picker, menus, dialogs) use an explicit modal/focus stack, not ad hoc route pushes. Shell player is independent of page lifetimes. Main and PiP views observe the same logical transport.

Settings are typed, versioned values, with defaults and visibility predicates extracted from the pinned source. Do not infer UI availability from enums alone. Preserve reference preferences such as the sole effective global blur switch while retaining import compatibility for legacy keys. Flutter's default Material components must not redefine the visual design.

## Audio service and DSP

Prefer established native libraries for decoding/output/ML over reimplementing codecs or real-time primitives in Dart. A generic Flutter audio plugin is insufficient unless it exposes every needed PCM, overlap, sink-time and device-control capability. `media_kit`/libmpv is an evaluation candidate for independent media surfaces, not the default music engine: enabling its audio path beside the custom engine would risk double output and incompatible transitions.

A dedicated native engine owns decode threads, ring buffers, processing graph, output device and sample clock. Dart sends coarse commands and receives bounded snapshots/events; no Dart allocation, JSON serialization, file access, HTTP callback or FFI call back into Dart from the real-time audio callback. Use a wakeable command queue and preallocated interleaved buffers. Decode/fetch workers apply backpressure; cancellation interrupts native opens/reads. One current decoder and at most one normal upcoming decoder; mixing and video have explicit separately budgeted resources.

C ABI design: opaque handles, fixed-width sizes, version/capability negotiation, explicit create/start/pause/seek/select/reconfigure/dispose, asynchronous completion IDs, bounded poll/drain events, error codes and explicit buffer-release functions. Ownership is stated for every pointer and texture. Native finalizers are last-resort cleanup, not primary lifecycle. FFI generated with ffigen; keep ABI definitions stable and test allocation/free, cancellation and double-dispose on both OSs. Snapshot fields include song/entry/rendition IDs, position and sample timestamp, discontinuity count, awaitingAudio, duration, source/format, loading/error, mixing and analysis progress.

FFmpeg decoding/resampling preserves source format, probe/reconnect and media-header behavior. WASAPI output on Windows and an evaluated Linux native output layer preserve negotiated rate, channel layout, float/integer precision, endpoint switching and played-frame timing. Evaluate miniaudio for common output/device lifecycle before writing OS-specific sink code; retain native adapters when required semantics are absent. Expose the actual signal chain, not a nominal “lossless” flag. Stereo widening and E-AC-3 decode are not a promise of object-based Atmos rendering.

Port EQ/filter/silence/speed rules against reference PCM fixtures. Silence removal must feed position accounting. Crossfade and Automix share an authoritative transport timeline; incoming elapsed overlap becomes current position at handoff. Planner logic can be pure Dart if not in the callback; native processing applies the resulting immutable transition plan. Preserve repeat, party, sleep-after-track and ordinary-prefetch suppression rules. Buffer expansion at handoff must preserve underrun resilience. No feature may be disabled merely to improve a memory result.

Use ONNX Runtime native C API for the existing quantized beat/vocal models, lazy sessions and bounded jobs. Preserve model bytes, mel/resampler preprocessing, feature schema, confidence/fallback and analysis cache compatibility. Existing `native/analyzer`, `native/loopmix`, Signalsmith libraries and Android JNI wrappers establish algorithms; JNI itself is not the Flutter ABI. Before any native reuse, document allowed source provenance/license and obtain byte/feature comparison tests. A clean implementation may wrap repository C++ through a new narrow C ABI; no prior Flutter bridge is permitted.

## Data and protocol adapters

Use `dio` as preferred HTTP transport for cancellation, streaming and interceptors; choose retry policy per provider, not a global retry loop. WebSocket sessions use Dart networking with explicit ping/report/backoff and generation fences. Redact cookies/tokens/signed URLs. Preserve client headers and credential scope; add-on transport is not arbitrary JS execution. Range fetches use bounded spans, verified content and cache expiry. Stream resolution returns source ID, rendition identity, format, headers and cancellation-aware upgrade channel.

Provider adapters remain separate for YouTube/InnerTubeX/NewPipe-equivalent resolution, JioSaavn, HTTP add-ons and custom module indexes. InnerTubeX and NewPipe are JVM dependencies with no demonstrated drop-in Dart equivalents. Phase 4 must prove cipher/client/PoToken compatibility using current protocols or a compatible independently implemented adapter; carrying names forward is not completion. Isolate module JS execution with explicit host APIs, memory/time limits and cancellation. QuickJS is a candidate, but ECMAScript/HTTP bridge compatibility with `DesktopModules.kt` is an unresolved spike.

Lyrics retain native-independent domain models for lines, words/syllables, vocal/background/duet groups and offsets. Adapters preserve all offered sources, source order, word-priority selection, hidden-provider exclusion, shared documents, lazy Genius fallback and status reporting. A single application session survives main-window hide while PiP can display it. Parse enhanced LRC/TTML/provider structures from saved fixtures. The frame-driven clock consumes monotonic sampled native readings with discontinuities; it never extrapolates through buffering or reverses without an explicit jump. Translation and sharing are independent use cases. No network access from the paint loop.

Authentication requires a browser capability interface, not an assumed cross-platform webview package. Windows WebView2 and Linux WebKitGTK/other supported host must be evaluated for cookie access, PoToken execution, OAuth/session flows and lifecycle/memory. Preserve browser-import support with explicit user action and OS crypto permissions. Do not ship an Android-only webview API on desktop. Lazy browser runtimes release when no longer needed, subject to documented session needs. Spotify client-token/refresh and Google profile/cookie persistence remain separate brokers.

Keep the existing Go Listen Together backend and wire protocol; no server rewrite. Server-clock estimator, queue reconciliation and local-queue restore are Dart domain/application services. Reconnect and stale socket events must not mutate a different account/room. Party mode turns off Automix as the reference does.

## Persistence and migration

Prefer SQLite via `drift` plus supported native SQLite distribution; isolate database work and use transactions for queue/cursor, playlists, download records, settings and listening statistics. `shared_preferences` may store truly small host flags but is not the queue/database replacement. Use `path_provider` for OS directories and preserve portable installation semantics through an explicit storage-root resolver. Separate downloaded media, disposable cache, durable user data and credentials.

The Kotlin reference uses Java Preferences with chunked serialized data, platform protections and account-specific state. Plan a read-only importer: capture backup, parse reference formats, validate versions/counts, import transactionally into a separate destination, record checkpoint and verify. Never mutate the reference's Preferences, downloads or library. Define how paths, missing files, older enums, duplicates, account IDs, history, EQ/settings, queue position and statistics migrate. Secret migration may require OS APIs/user reauthentication; do not promise portable DPAPI secrets. Portable/read-only install and failed import must leave reference usable. Destructive replacement is outside migration defaults.

Use `flutter_secure_storage` only after validating Linux Secret Service availability/failure and Windows storage guarantees. DPAPI/browser-cookie and custom-source credential compatibility may need direct native bridges. Store no secrets in logs, fixture files, plaintext fallback or SQL by convenience. Explain unavailable keyring behavior in the product consistently with the reference.

## Visual identity and native integration

Measure tokens from D DesktopTheme/Typography/Surfaces and shared player UI. Flutter visual implementation uses explicit colors, metrics, font cuts, icons, focus/hover states, curves, constraints and responsive breakpoints. `flutter_svg`, gen_l10n, CustomPainter and fragment shaders are preferred building blocks, with package capability checks. Blur bounds must be small and invalidated only when necessary; global reduction overrides every surface. Preserve Home's feed under the side panel, menu overlay z-order, solid non-Home panels, empty dock, old-style toggles and lyrics tracking. Text scaling, DPI and OS caption are separate inputs.

Window integration candidates: `window_manager`, `screen_retriever`, `tray_manager`; evaluate each against native fullscreen, caption color/system menu/Snap, visibility, min size, theme, monitor DPI and Linux compositor behavior. PiP multi-window may need a native host or additional Flutter engine; no selection until memory and shared-engine ownership are demonstrated. Native callbacks marshal onto the application event loop.

MPRIS/SNI/DBusMenu on Linux and SMTC/WinRT on Windows require platform-specific adapters if proven packages fall short. Optional services (Discord IPC/gateway, scrobblers, public presence) fail independently without blocking playback. Updater is a state machine with installation-specific helpers, verified downloads, staged operations, readiness marker after a rendered window and rollback/recovery. Generic auto-update plugins are candidates only after packaging/recovery tests pass; never reuse a Kotlin-specific launcher blindly.

## Memory design and budgets

Separate steady-state UI, decoded images/textures, media/network buffers, native ML sessions, web runtime and durable disk cache accounting. All numbers below are **initial design budgets**, not measurements or a memory-reduction claim:

| Owner | Initial budget / bound | Release policy |
|---|---|---|
| Decoded artwork | 64 MiB byte-accounted shared cache; size-aware decode | Evict cold items; reject oversized single decode or use thumbnail; flush inactive views on pressure |
| Artwork fetch/decode | At most 4 fetches / 2 decodes concurrently | Cancel invisible/stale requests; deduplicate by URL+size+revision |
| Ordinary PCM prefetch | One upcoming track, 2 seconds initially; active/mixing up to reference 8-second window | Expand on adoption; cancel on queue generation/party/repeat/sleep change |
| Audio disk spans | Reference-compatible 256 MiB / 1024-file cap, 512 KiB spans | Checksums, expiry, atomic writes, startup orphan trim |
| Media disk cache | Reference default 512 MiB, configurable semantics retained | LRU trim; downloads never counted as disposable media |
| Video textures | At most current displayed frame plus producer buffers per active session | Bound queue; drop obsolete frames, detach/dispose hidden decorative playback |
| Page data | Active route plus small bounded recent metadata working set | Evict inactive heavy payloads; retain scroll/restoration only |
| Analysis | One default active job; modes tune measured bounded concurrency | Lazy ONNX sessions; cancel obsolete work; close on shutdown/pressure where safe |
| Network/logs | Stream bodies to disk, capped responses and rotating logs | No unbounded response/exception histories or debug audio copies |

Native PCM cost is `seconds × rate × channels × bytesPerSample × decoderCount`, plus decoder/resampler/processing overhead. Native/GPU allocations and browser subprocesses count toward product memory. Dart ImageCache's entry limit alone does not enforce total byte ownership; account for widget-held images, GPU textures and precache references. Do not rely on forced GC as a resource policy. Use explicit dispose, subscription cancellation, bounded LRU, isolate termination, FFI frees and texture deregistration. Main-window hidden state pauses decorative frame tickers and trims caches while audio/party/lyrics needed by PiP remain correct. Budgets must be tuned by PERFORMANCE_BASELINE, without changing reference feature/default behavior during comparisons.

## Validation and decision gates

Use reference regression suites as behavioral oracles, independently authored Dart/native tests, recorded provider fixtures, differential action traces, PCM/feature comparisons and native OS integration suites. Visual gates include frame sequences, hit testing and focus/accessibility. Integration tests must exercise real adapters, not only mocks. Platform CI builds/test packages on Windows and Linux; test full installations and update recovery in disposable systems.

Open gates: package compatibility/version/license checks; native audio output negotiation; InnerTubeX/NewPipe parity and PoToken web runtime; custom module JS host; PiP engine/ownership/RAM; font redistribution and text metrics; read-only data import; authenticated provider access; Windows/Linux visual and physical audio evidence; updater signing/channel/recovery. These block their dependent phases and final parity, not document completion. See MIGRATION_PROGRESS for ordering and PERFORMANCE_BASELINE for objective memory/CPU acceptance.
