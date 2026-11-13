# Flutter migration architecture

## Decision and boundaries

Pinned Kotlin reference: `1.9.2` at `348b613a0c3db1a54de56483ea6f8748d6465211`. Target: Flutter Desktop for Windows x64 and Linux x64; keep the current Linux packaging compatibility baseline under review (`DESKTOP.md` documents Ubuntu 22.04). No mobile/macOS expansion. All new application code and native bridges belong under `flutterDesktop/`. Do not modify Kotlin, overwrite `flutterApp/`, merge previous Flutter rewrites or introduce a JVM sidecar as the final application architecture. Use reference source as a specification; independently implement Dart orchestration and UI. Existing native-library reuse is a separate documented provenance decision, never previous Flutter implementation reuse.

Phase 0.5 finalizes the hybrid boundary below, extending the completed Phase 0 audit. Architecture decisions are final for planning; implementations and third-party capabilities remain unverified. Phase 1 is precisely bounded in MIGRATION_PROGRESS. Pin a current Flutter stable SDK, Dart constraint and dependency versions only after checking Windows/Linux compatibility in that phase; this audit does not invent a tested Flutter version or lockfile.

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

FFmpeg decoding/resampling preserves source format, probe/reconnect and media-header behavior. Use WASAPI shared-mode output on Windows, derived from the reference native behavior through a new engine-owned adapter, and libpulse output on Linux (including PipeWire Pulse compatibility), with ALSA fallback for systems without a Pulse server. Preserve negotiated rate, channel layout, float/integer precision, endpoint switching and played-frame timing. Miniaudio is not the selected output architecture; adopting it later requires evidence that these contracts and fallback behavior remain equivalent. Expose the actual signal chain, not a nominal “lossless” flag. Stereo widening and E-AC-3 decode are not a promise of object-based Atmos rendering.

Port EQ/filter/silence/speed rules against reference PCM fixtures. Silence removal must feed position accounting. Crossfade and Automix share an authoritative transport timeline; incoming elapsed overlap becomes current position at handoff. Automix analysis, transition planning, scheduling and execution all belong to the C++ backend. Dart supplies user settings, queue intent and lyric exclusion spans, and displays native plans; it does not calculate or schedule audible transitions. Preserve repeat, party, sleep-after-track and ordinary-prefetch suppression rules. Buffer expansion at handoff must preserve underrun resilience. No feature may be disabled merely to improve a memory result.

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

## Phase 0.5 decision record — C++ native audio backend

Status: accepted architecture, documentation only. Reference remains Kotlin 1.9.2 at `348b613a0c3db1a54de56483ea6f8748d6465211`; Phase 0 commit `28b6ac40` and its inventory remain preserved. No old Flutter code is input. All UI, including player, waveform, lyrics, quality/output sheets, PiP and diagnostic panels, is Flutter. Dart owns navigation, presentation state and UI-related application logic. **C++ owns playback, decode/fetch buffering, audible DSP, Automix analysis/planning/rendering and audio output.** There is one native audio engine per process, independent of Flutter window/engine count. FFI is the audio control/data boundary; OS platform channels are reserved for host-window/texture/media integration when needed. No UI is rendered by the audio backend.

### Why C++ rather than Rust

| Criterion | C++17 choice | Rust alternative | Decision consequence |
|---|---|---|---|
| Existing native algorithms | Directly uses repository analyzer/loopmix and Signalsmith C++ types | Calls the same C++ through wrappers or rewrites tested algorithms | C++ minimizes algorithm and interop changes |
| Windows output | Reference WASAPI code already uses COM and C++ | Windows bindings are viable, but reference logic must be ported and C++ DSP still bridged | Keep one native implementation language and isolate OS ownership |
| FFmpeg and ONNX | Native C APIs usable behind RAII; no JavaCPP/JNI | C APIs usable through unsafe FFI wrappers too | Neither language removes native lifetime obligations |
| Memory safety | Manual hazards; require RAII, strict ownership, sanitizers, bounded queues and no exceptions across ABI | Stronger safe-language guarantees for new orchestration; unsafe remains at codec/OS/C++ boundaries | Rust advantage acknowledged; C++ safety work is mandatory |
| Real-time behavior | Can avoid allocation/locks/destruction in render path | Can also do this; neither language guarantees real-time scheduling | Decide by tests and architecture, not language slogans |
| Build/maintenance | CMake integrates Flutter Windows/Linux hosts and existing C++17 libraries | Adds Cargo toolchains, link/runtime integration and another language boundary | Avoid additional toolchain and mixed Rust/C++ ownership initially |
| RAM/performance | No comparative measurements | No comparative measurements | Neither is presumed faster or smaller |

Select **C++17**, CMake, a narrow versioned C ABI, Dart `dart:ffi` bindings generated with ffigen, and explicit lifecycle ownership. Use MSVC on Windows and Clang/GCC on Linux with the supported Flutter host toolchains. Do not expose STL, COM, exceptions or compiler-specific C++ ABI to Dart. Rust is not prohibited in a future deliberate redesign, but is not a second backend or a Phase 1 dependency. A switch would require a new decision with evidence and updated parity contracts.

### Responsibilities and single sources of truth

| Responsibility | Owner | Boundary payload |
|---|---|---|
| Catalog/auth/source preference, account, semantic queue/shuffle/repeat, history/persistence | Dart application/data layer | Versioned queue slice (active and nominated next entry), repeat/sleep/party intent, source capabilities; explicit source descriptor replies |
| Select/seek/pause/rendition swap; decoder current/upcoming lifetime; automatic nominated handoff | Native control coordinator | Accepted command IDs, terminal completion and authoritative entry-adoption event |
| Model preprocessing/inference, transition strategy/timing/loop knots, DSP configuration and execution | Native worker + render owner | Feature/settings input, bounded lyric exclusion spans, native analysis/plan summary events |
| Source URL/client/token acquisition and reauthentication | Dart resolver broker | Asynchronous source-request ID; copied URL/headers/expiry/cache identity/transport reply |
| Open/read/decode/range transport, compressed cache and PCM rings | Native IO/decode owners | Native errors/recovery requests and counters; PCM stays native |
| Played-frame/source-time mapping | Native output/render owner | Timestamped position snapshot plus device/clock epoch and discontinuity |
| Visual lyric clock/interpolation, animation and waveform painting | Dart/Flutter | Native clock samples and bounded decimated waveform/mix metadata; no PCM to widgets |
| OS media buttons, tray and party commands | Dart application entry point | Same command protocol as UI; no second audio-control path |

The native queue slice is execution authorization, not a duplicate library or independent shuffle algorithm. Dart nominates the next queueEntryId and supplies a queue revision. Native may execute that prepared handoff without a Dart round trip; it reports adoption exactly once. Dart consumes the event idempotently to update history/semantic queue and supply a later nomination. During a midpoint display switch, the former upcoming deck remains audible until the blend ends; a new nomination is deferred, not allowed to close it. An account/queue revision change invalidates unresolved nominations and transition plans. Party state and sleep-after-track suppress prefetch/overlap exactly as the reference; repeat-one cannot speculate an unrelated next track. If no next source is ready, report buffering or end according to policy; never promise gapless playback for unprepared network media.

### Transport and source IO

Use an in-process dynamic library (`bitchord_audio.dll` / `libbitchord_audio.so`) loaded by one long-lived Dart audio-service isolate. Widgets and PiP send intents to that isolate and subscribe to its projected state. Additional Flutter engines connect to the existing service through an explicit host broker; they never create a second engine. No per-window audio owner and no process-global mutable singleton state copied from the JNI bridge.

Dart catalog HTTP may use dio, but native FFmpeg IO must not synchronously call Dart. Native workers open local files or resolved URLs, using FFmpeg native transport and a custom AVIO range reader with libcurl for bounded range/cache semantics. Preserve TLS verification, client headers, proxy/CA configuration, timeouts, range behavior, HLS/DASH rendition choice and redirect/credential scope. Review native transport build features on both platforms. A worker posts `SOURCE_REQUIRED`/`SOURCE_REFRESH_REQUIRED` and waits interruptibly off the render path for a matching Dart reply; it never blocks an audio callback. Descriptor replies include request, session/selection/queue generation, entry and rendition IDs, local path or URL, headers, expiry, transport hint, quality/format hints and original-version preference. Native copies and validates all bytes at admission; URL hints do not replace measured format. Late replies close/discard their own resources, not the current track. Cancel, expiry, broker failure and shutdown have finite deadlines and explicit outcomes.

For native analysis, preserve reference recording selection: analysis can use local files or the YouTube Opus rendition rather than the add-on rendition actually playing. Source requests therefore carry purpose (foreground, upcoming, upgrade, recovery or analysis) and recording identity. Analysis results are not reused for a different recording merely because a title matches. Do not perform extra provider/byte-range resolution on the real-time path.

### C ABI v1 protocol specification (not implemented headers)

All exported calls return fixed-width status codes and use `struct_size` plus ABI major/minor on extensible structs. Little-endian x64 is the initial target, but use fixed-width integer fields, explicit alignment and offsets, UTF-8 pointer+byte-length strings, `uint32_t` booleans/enums, and integer microseconds/nanoseconds; no Dart `int` layout assumptions. Capabilities advertise supported commands/formats, maxima and build/codec/model versions. Unknown required fields/capabilities fail explicitly; optional trailing fields can be ignored by size/version rules. ABI layout/offset/static assertions and Dart codec roundtrips must be tested per platform before audio implementation.

Use generation-tagged `uint64_t` engine/session handles managed in a registry, not freely dereferenceable caller pointers. Command IDs increase within a session; response identity includes session epoch, command ID, selection generation, queue revision and entry/rendition ID. A handle retained after destroy returns INVALID_HANDLE; guard ABA/reused slots and concurrent acquisition. Application IDs use copied UTF-8 bytes or an explicitly registered token table, never hash-only identity with unchecked collisions.

| Planned operation | Calling context and payload | Completion/ownership rule |
|---|---|---|
| Query ABI/capabilities | Service isolate; caller-owned fixed struct and sizes | Synchronous bounded copy, no worker/model/device initialization |
| Create | Service isolate → native non-render setup | Returns handle and clock epoch or structured setup error; no playback implied |
| Submit | Service isolate, typed command header and payload | Bounded validation/copy/enqueue; returns ACCEPTED, BUSY/QUEUE_FULL, INVALID_ARGUMENT, STALE or CLOSED. ACCEPTED means queued, not applied |
| Read snapshot | Service isolate, caller-owned fixed buffer | Nonblocking consistent snapshot with sequence and event watermark; if no safe published slot, return RETRY and retain previous snapshot |
| Drain events | Service isolate, caller-owned bounded buffer, max count/bytes | Copies records atomically; insufficient space returns required size, no truncated record; acknowledgment is explicit |
| Ack events | Service isolate, contiguous processed sequence | Releases native journal credits only after Dart applies/idempotently stores effects as needed |
| Begin shutdown / query shutdown | Service isolate; drain-or-stop policy | Rejects new commands, cancels workers, disposes resources on owners; emits terminal status when quiescent |
| Destroy | Service isolate only after shutdown complete | Invalidate registry handle; no outstanding calls/callbacks/buffers; unload library only after all owners and engines are gone |

Command families: select/open, nominate/clear upcoming, play, pause, stop/flush, seek, set volume, atomic DSP/settings batch, output selection/precision, quality/source-policy update, replace rendition/upgrade, source reply/failure, party/sleep policy, lyric-span update, bounded analysis request/cancel, shutdown. Native owns derived DSP coefficients and Automix plans. Updates during an audible overlap preserve reference chosen-plan semantics; changes affect later cues unless the reference explicitly aborts/rebases.

Each accepted command has exactly one terminal outcome (APPLIED, SUPERSEDED, CANCELLED, FAILED) correlated by ID. APPLIED means a specified engine boundary adopted it, not that sound has reached the speaker. Seek completion reports landed/failed and a new discontinuity; first-output and estimated/actual-presented milestones are separate. A later seek may explicitly supersede an earlier pending seek, preserving both terminal outcomes. Select/replace/upcoming results are validated again on consumption, not only when their worker starts. Never replay an old selection or toggle after a newer command. Use idempotent play/pause, not blind toggle retries.

Initial negotiated bounds for v1: 128 queued commands, 64 KiB maximum payload per command, 256 unacknowledged ordered event records and 1 MiB total journal bytes; admit only when completion space is reserved. These are design bounds, not measured capacity or fixed product limitations; enlarge through capabilities if real fixtures require it. Query/snapshot settings can be coalesced; entry adoption, discontinuities, terminal commands and errors cannot be silently dropped. Source-request and autonomous-transition events reserve credits before scheduling their work. On saturation, continue current prepared audio, reject new mutations with BUSY and stop scheduling further automatic handoffs at a safe boundary until events are acknowledged/resynchronized. Never block the render owner waiting for Dart or grow an unbounded journal. Expose pressure/overflow diagnostic state in every snapshot; Dart resynchronizes from snapshot+watermark and retained events, never invents lost history.

### State, events and errors

Snapshot contains sequence/watermark, session/clock/device epochs, active and display entry IDs (they may differ during overlap), rendition/source IDs, current/nominated generations, transport phase (idle/resolving/buffering/playing/paused/ended/error/closing), play intent, awaitingAudio, source position and sample timestamp, duration/unknown flag, effective user speed/deck rate, discontinuity counter, source/decoded/output format and channel layouts, output endpoint/buffer/latency, mixing/plan window, search-upgrade/analysis state, underrun/recovery counters and last structured error. No signed URL, cookie or secret header is echoed into UI state.

Native time is an explicitly queried monotonic nanosecond domain (Windows QPC converted to ns; Linux CLOCK_MONOTONIC). Do not subtract Dart Stopwatch's unrelated epoch directly. The service calibrates an offset/uncertainty using paired native clock queries bracketed by Dart timestamps; timestamp receipt alone is not sample time. Recalibrate after engine recreation/system suspend, attach epoch and age, and test drift. Position uses source microseconds plus presented frame segments, not wall-clock duration since play. During pause/buffering/awaitingAudio or invalid clock, lyric interpolation holds. Include timing quality: device-derived/latency-estimated/no-physical-output. The reference WASAPI path advances frames in real time when no endpoint exists; preserve the documented transport behavior while labeling it non-audible, not as proof speakers played samples.

Initial delivery policy: latest snapshots at up to 20 Hz while playing, up to 2 Hz while paused/idle; immediate control/event wakeups are permitted through a non-render dispatcher/host notification, with periodic draining as fallback. Decorative Flutter frame interpolation can run at display rate; it does not change audio scheduling. The event journal is ordered and includes command completion, entry/display adoption, overlap finish/abort, seek/discontinuity, EOS, source-needed/recovery, negotiated format/device change, analysis readiness/failure and terminal engine lifecycle. Native midpoint display adoption is emitted once even when a later explicit Next races it. Scrobbling/history act on IDs and acknowledged event sequence, not repeated snapshots.

Error record fields: category, stable code, severity, recoverability/recommended action, command/event identity, phase/source-purpose, optional native FFmpeg/HRESULT/errno code and bounded redacted diagnostic text. Categories include ABI/admission, resolver/auth/expiry, transport, demux/codec, device/format, DSP/analysis/model, cancellation/stale, resource-limit and internal-fault. Distinguish EOF, temporary starvation, worker failure and intentional cancel. Dart localizes public messages and resolves auth/provider fallback policy; native reports evidence and requests replacements rather than silently changing catalog policy. Recovery budgets are bounded and reset only after healthy PCM, as in the reference. Catch all exceptions before crossing C; return allocation failure/status safely. No language unwind/exception crosses the ABI; public state contains no native internal pointer. Temporary caller-owned FFI buffers remain an implementation detail of the service adapter. Optional analysis failure falls back to the reference transition strategy, not false success.

### Threading, ownership and shutdown

| Owner/thread | Exclusive resources and allowed work | Forbidden work |
|---|---|---|
| Flutter UI isolate | Widgets, navigation, animation and focus; submits intents asynchronously | Native waits, decode, DSP, DB/network blocking work |
| Dart audio-service isolate | Sole ABI client, command/event projection, resolver coordination | PCM processing, transition deadlines dependent on polling, long native join on UI |
| Native control coordinator | Lifecycle, command admission/order, queue generations, owner handoff and published snapshots | Device callback blocking, filesystem work in critical render sections |
| Per-deck decode/IO owner | FFmpeg contexts/packets/frames, AVIO/curl request, resampler and PCM producer ring; one thread at a time touches a decoder | Closing/seeking a decoder concurrently from control/render threads |
| Native render/output owner | Current/upcoming deck graph, DSP/filter history, accepted/presented frames, sample-accurate seam decisions | Dart callbacks, IO, model inference, unbounded locks, allocation or final resource destruction in deadline sections |
| Analysis/plan/loop preparation worker | ONNX sessions/tensors, feature extraction, region decode and prepared immutable plans/render assets | Competing unbounded inference, creating work for obsolete current/next IDs |
| OS notifications / event dispatcher | Endpoint flags and bounded mailbox notifications | JNI/Dart/UI callbacks from audio callback; COM pointer teardown on arbitrary thread |

Use preallocated SPSC rings for each decode→render connection and fixed-size command/event staging; no vector growth, debug formatting or reference-count last-release in an audio callback. Reference pump uses blocking sink writes and timed decoder polls; the new design uses a non-real-time preparation/coordinator path plus a bounded deadline-critical render path. Callback sinks only copy prepared frames; push sinks run on a dedicated render/output thread. Do not copy reference mutex/allocating JNI globals into a callback. Prepare maximum block scratch and DSP configuration off the critical path, swap at a block boundary, defer destruction to its owner. Benchmark and audit Signalsmith/other libraries for render-time allocation; “already C++” is not a real-time guarantee.

Buffer rules: float32 interleaved PCM internally, explicit frame count, channel count/layout, sample rate and capacity; frame != sample != byte. Pooled buffers have one owner, with queue transfer and generation tags. Seek clears old-generation queued blocks, resets time-stretch/filter/silence state and flushes device data before new audio. No borrowed FFmpeg sample array outlives its frame/reuse interval. No PCM, ONNX tensors or full-rate waveform arrays cross FFI. Diagnostic waveform payloads are bounded/decimated and copied; any later texture/native buffer API must specify retain/release and cannot free on Flutter GC alone. Submitted payload pointers are borrowed only until submit returns; caller frees with the allocator that allocated them. Library-internal allocations are freed only by native owners. OS handles, AV*, Swr*, curl and ONNX resources use RAII with correct owning-thread disposal; COM initialization/uninitialization and endpoint observer registration are paired.

Shutdown sequence: detach UI/OS command producers → reject new admissions → cancel resolver requests and IO with atomic interrupt flags → stop/flush or drain per explicit quit policy → retire render and join decode/preparation workers → release ONNX only after inference ends → unregister notifications and close endpoint resources on their owner → publish final events/snapshot and acknowledge required persistent effects → mark quiescent → destroy handle → release Dart subscriptions/ports → unload library at process shutdown if safe. Stop/tray-hide is not engine destroy. Repeated shutdown requests are idempotent. Deadline expiry returns SHUTDOWN_PENDING with live ownership retained; never free under a blocked worker or call a timed join “disposed.” Native finalizers are a leak alarm/fallback only, never the primary asynchronous close mechanism. The existing reference two-second audio join and sixty-second analysis wait are observed bounds, not evidence the new backend may free live contexts at timeout.

### Windows/Linux implementation commitments and remaining tests

Windows: WASAPI shared mode with source-rate request and system conversion flags as in `desktopApp/native/window/window_jni.cpp`; COM/MMDevice notification handling in an engine-owned adapter, no JNI or window-decoration dependency. Retain route selection, unplug/default changes, pause/flush/drain, precision conversion and continuous played-frame accounting. Reference Java Sound fallback is replaced by a native compatibility path with the same negotiated ladder where possible; if no native route works, surface the same failure category rather than require a JVM. Linux: libpulse stream timing/latency and endpoint APIs for PulseAudio/PipeWire compatibility, ALSA fallback for devices/systems lacking Pulse. Keep server default and explicit endpoint semantics distinct, and move only this app's streams. No unrelated application's route changes. Validate timing latency estimates against loopback; Linux reference uses Java Sound/ALSA plus best-effort `pactl`, not an existing direct libpulse engine.

Preserve the reference negotiation preference: source rate/shape first, float→S16 fallback, 48 kHz/44.1 kHz alternatives, then stereo fallback when needed; expose the actual accepted format and conversion. No exclusive WASAPI, ASIO, native PipeWire-only requirement or fixed 48 kHz pipeline is silently introduced. A later backend-specific negotiated path must pass the same format/quality corpus. Keep native runtime/codec DLL/SO loading, portable roots, Linux minimum glibc/system dependencies and Windows runtime linkage explicit in release CI. Align FFmpeg codec/build configuration and ONNX/model versions to reference fixtures before changing versions. Device capability support and library packaging remain tests to run, not verified facts.

## Phase 3 implementation decisions

The first local persistence adapter uses SQLite through the same narrow native
library rather than adding Drift/code generation to the runtime dependency graph.
SQL stays in `native/audio/src/store.cpp`; data adapters call it from short-lived
storage isolates. A WAL/FULL, revision-checked transaction commits a versioned
local document containing queue/cursor/settings/import checkpoint together. This
is a local foundation; relational catalog/history query tables remain future
work. Small shell appearance flags keep their existing separate namespace.

The actual ABI 1 header is `flutterDesktop/native/audio/include/bitchord_audio.h`.
Fixed x64 layouts are verified by native static assertions and generated Dart
`sizeOf` negotiation. Commands are copied UTF-8 bounded records; native owns all
PCM, decoder and sink resources. A single audio-service isolate owns one engine;
no callback enters Dart. Dedicated push output uses prepared PCM, not an OS
real-time callback. Adapters negotiate source float/S16, alternate 48k/44.1k rates and stereo
fallback with an FFmpeg-derived channel matrix; snapshots report actual output
rate/channels/precision. Unsupported devices fail explicitly. Physical precision
and device-matrix acceptance remains a Phase 3 gap. Native resampling rate is distinguished from
Kotlin's pitch-preserving speed (Phase 5).

Phase 3's explicit user instruction prohibits simulated audio. It supersedes
the earlier planning note about preserving the Kotlin no-endpoint virtual clock:
this engine returns `BC_ERROR_NO_DEVICE` and holds time/frames at the last valid
position when no route exists. Hosted Windows confirms error 105 and zero output,
not audible playback. WASAPI/Pulse notifications trigger bounded native-owner
reconfiguration from the last presented position; physical acceptance remains
open. Final code/platform evidence is in MIGRATION_PROGRESS's Phase 3 handoff.

## Phase 4 progressive HTTP broker decision — interim, 2026-11-12

The current ABI-1 increment gives FFmpeg an opaque loopback HTTP grant. A
separate Dart **IO isolate** owns provider HTTP requests, capped 512 KiB spans,
validators, 4 MiB in-memory span cache and one expiry re-resolution. Native owns
FFmpeg's interruptible AVIO/socket, decoder/PCM backpressure, DSP, sink and clock;
no Dart/FFI callback occurs on its decode/render threads. Cancellation revokes
the grant and interrupts native IO; shutdown closes native audio before broker
teardown. There is one audio engine. Signed upstream URLs/headers stay outside
ABI structs and durable queue state. Dart's standard HttpClient is used here
instead of adding dio; per-request clients supply concrete cancellation owners,
body bounds, scoped redirects and total deadlines.

This is a **progressive-file transport increment**, not completion of the planned
native libcurl/disk-span-cache or full source-purpose protocol. Manifest demux,
source-purpose/request IDs, native-owned durable span-cache equivalence and
UI/service-stall/recovery acceptance remain Phase 4 work. The two-second native
PCM bounds and all output/clock ownership remain unchanged. Do not treat this
interim broker as evidence that the complete Phase 0.5 protocol was delivered.

## Phase 4 optional extractor and ephemeral catalog session

A configured absolute yt-dlp executable (optional absolute Node JS runtime) may
perform progressive YouTube URL extraction as a cancellable data adapter. It is
not an audio player; only a media-byte-verified result reaches the existing
bounded broker/C++ engine. The app does not ship/download executables, load user
extractor config/plugins/remote components, pass account cookies to extraction,
or persist signed results. Process environment/output/deadline and candidate
scheme/CDN/format/headers are restricted. This opt-in path is not a completed
PoToken/browser architecture or verified working media integration; actual live
media HTTP 403 remains open.

Existing YouTube Music cookie verification follows the pinned reference's
signed-in-shell and SAPISIDHASH/session/channel/dataSync scope. This initial
catalog session lives only in memory. Protected persistent accounts, browser
import/sign-in and authenticated media are separate unfinished capabilities;
there is no plaintext storage fallback. Artwork fetches use a credential-free
port with four active transfers, 2 MiB body cap and scoped cancellation; Flutter
uses size-aware decodes and a 64 MiB ImageCache budget, without any measurement
claim about total process/GPU/retained-image memory.


## YouTube backend rewrite boundaries (2026-11-13)

This supersedes earlier optional-extractor notes for the delivered YouTube path. The public YouTubeMusic facade delegates to `youtube/catalog_client.dart`, `catalog_parser.dart`, `accounts.dart`, `runtime.dart`, `extractor.dart` and `formats.dart`; YouTubeResolver coordinates a bounded extraction/preflight state machine. The native C++ engine and shared source broker remain sole audio owners. Explicit runtime deployment packages pinned yt-dlp/Node plus script-only BgUtils and the reviewed `bitchord_session.py` override. Per-operation temporary directories isolate BgUtils' independent token cache; raw subprocess output never becomes logs, persistence or UI. A verified primary account cookie crosses only the stdin pipe and the extractor's in-memory YouTube-domain cookie jar; no browser scraping, cookie file or argv/environment secret.

A rejected URL triggers bounded fresh-context recovery, not an unbounded client loop. Renewal is same video/rendition/account generation, preserving ranged-file identity. Account/rights/rate-limit errors are terminal; declared session mode never silently downgrades to guest. The prior automatic JioSaavn substitute is not part of YouTube success. Diagnostics expose only fixed categories/counts/timing, not identities or signed URLs. UI, JioSaavn/add-on adapters, native decoder/sinks and generic storage are unchanged. Live/HLS/MPD/SABR and delegated media accounts remain explicit unsupported contracts; external service refusals remain possible and must be reported honestly.
