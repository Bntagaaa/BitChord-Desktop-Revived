# BitChord Desktop Revived: competitive engineering audit

Audit date: **9 November 2026 (Asia/Jakarta)**. This is a source and release audit, with explicitly bounded local verification. It is not a claim that either application passed a live comparative playback benchmark.

## Decision

Keep Revived's Kotlin/Compose architecture. Prioritize reliable updates, playback, cross-platform packaging, measurable resource budgets, and clear installation before adding more visual effects. Position Revived as **a customizable, immersive desktop music experience for Windows and Linux, with predictable everyday playback and resource use**. “Premium” should describe the experience; it must not imply paid-service audio entitlements or a measured performance lead.

Continued 2.0 has a demonstrated distribution-size advantage and a simpler native audio/UI dependency stack. Its Rust implementation also has useful explicit texture and streaming budgets. These are real architectural properties, but they do not establish a RAM, CPU, startup, or audio-latency victory. Revived already offers source-level differentiators including local folder scanning, audio equalization, music-video rendering, a separate PiP window, ListenBrainz, and shared mobile-derived player UI. These need acceptance testing before being advertised as universally reliable.

## 1. Snapshot and scope

| Repository / ref | Inspected commit | Meaning |
| --- | --- | --- |
| Revived `1.9.2` | [`348b613a0c3db1a54de56483ea6f8748d6465211`](https://github.com/Bntagaaa/BitChord-Desktop-Revived/commit/348b613a0c3db1a54de56483ea6f8748d6465211) | Development target; README improvement after released code |
| Revived `1.9.1` | [`6fd92645a8ce9b4564e89f9d425799dbf8c4c415`](https://github.com/Bntagaaa/BitChord-Desktop-Revived/commit/6fd92645a8ce9b4564e89f9d425799dbf8c4c415) | Repository default, not the newest release line |
| Revived `v1.9.2` | [`37d54fe0c70f127ae0c4f3ad95cf9638299afb50`](https://github.com/Bntagaaa/BitChord-Desktop-Revived/commit/37d54fe0c70f127ae0c4f3ad95cf9638299afb50) | Published 8 November 2026, 20:28 WIB |
| Revived `main` | `b267e60a676d0456730d1d9a80da540dbc71df9c` | Exists; older branch, not current development |
| Revived `bitchord-desktop-reworked` | `b69e26c32bdd52bac23e98047e35499f1ae95fc2` | Confirmed experimental branch; no edits or merges made |
| Continued `main` | [`5b49c884ec459886e26817f768c64cf670d22aac`](https://github.com/jackychan4891/BitChord-PC-Continued/commit/5b49c884ec459886e26817f768c64cf670d22aac) | Rust 2.0 implementation plus README change |
| Continued `v2.0.0` | [`68ee4212130cb0992364dbad63c550eca2d60c6e`](https://github.com/jackychan4891/BitChord-PC-Continued/commit/68ee4212130cb0992364dbad63c550eca2d60c6e) | Published 8 November 2026, 06:09 WIB |
| Original upstream `main` | [`acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc`](https://github.com/kushagrasinghx/BitChord/commit/acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc) | Mobile/shared-code reference; desktopApp absent from current settings |

Repository metadata, branch lists, recent commits, source modules, build files, release assets, workflows, recent runs, open issues and PR summaries were inspected. Inspection was targeted rather than an exhaustive line-by-line security or correctness review. Recent history is shallow locally (Revived 30 commits, Continued 20, upstream 5); individual PR/API references supplement it. No competitor or upstream changes were made.

The first branch API page was incomplete: `main` only appeared after checking the full list. It must not be described as missing. Both projects have empty topics, blank homepage fields and Discussions disabled at the snapshot. Revived is publicly identified as a fork. Default-branch presentation and release-triggered workflow selection therefore require deliberate branch maintenance.

## 2. Architecture assessment

| Area | Revived | Continued 2.0 | Engineering implication |
| --- | --- | --- | --- |
| Languages/framework | Kotlin 2.3.20, Compose plugin 1.10.3, JVM 21, shared/sharedUi, C++ JNI [R1][R2] | Rust 2024, egui/eframe 0.37, native libraries [C1] | Different runtimes and feature scope; language alone is not a benchmark |
| Rendering | Compose/Skiko; Haze; shared player; Windows vs Linux dependency selections [R2][R3] | Immediate-mode egui; default OpenGL, optional wgpu/Vulkan/Metal; no MSAA/depth/stencil in window config [C1][C2] | Continued exposes frame/texture budgets explicitly. Revived needs resolved dependency parity checks, not a speculative renderer migration |
| Audio | FFmpeg/JavaCPP decode, dedicated PCM pump, decode-ahead worker, Java/platform output [R4][R5][R6] | rodio/CPAL, Symphonia plus format-specific code, audio command thread [C3][C4] | Revived has codec/video breadth but greater native packaging and lifecycle surface |
| Concurrency | Coroutines/IO scopes, generation fencing, mutexes, daemon decode/audio threads [R4][R5] | Tokio runtime, crossbeam commands, atomics for playhead, blocking workers [C3][C5] | Both move work off the UI. Rust channels here are often unbounded; ownership does not automatically bound queues |
| Memory | GC heap plus native decoder, Skia, image and ONNX memory; byte LRU; RSS-based feed eviction [R7][R8][R9] | Ownership/RAII plus textures, buffers and caches; 96 MiB texture budget, disk-image budget [C4][C6] | Measure process/private memory, not JVM heap alone. Both can retain memory through logical caches |
| Startup | Platform/auth hooks before window; download verification/background home load; local scan lazy; analyzer initialization launched in background [R10][R11] | Runtime/backend/audio initialized around window setup; webview flows run in child processes [C2][C5][C7] | Profile first usable frame and feed-ready separately. Revived already deferred several tasks |
| Networking/cache | Shared OkHttp/resolver plus CIO, JDK HTTP and native FFmpeg; 512 KiB ranges; 256 MiB/1,024-entry disk audio cache [R6][R12] | Shared reqwest client; 4 workers × 256 KiB blocks, retries/URL refresh, 2-block speculative prefetch; windowing above 40 MiB [C4][C5] | Continued's parallel range scheduling is worth studying. Revived's bounded disk reuse is also valuable; do not replace buffering without impaired-network tests |
| Audio buffering | Normally eight seconds decoded; speculative path can start with two seconds [R4][R5] | Seek-aware compressed block buffers; rodio output and generation checks [C3][C4] | Revived stereo float PCM at 48 kHz × 8 s is about 3.07 MB payload per deck; 192 kHz is about 12.29 MB. This arithmetic is not measured total RAM |
| Hidden/background | Visibility/minimize hooks, trim/GC request, lyric animation policy, video surface-user suspension [R10][R13][R14] | Hidden/minimized bookkeeping and 250 ms repaint scheduling; animated view paths use shorter intervals [C8] | Preserve audio and PiP while gating unused visual work; compare visible/unfocused/minimized separately |
| Platform integration | MPRIS, WinRT SMTC bridge, Windows audio/frame helpers, Linux tray/status notifier [R15] | souvlaki, tray-icon, platform keyring, Windows helpers and platform packaging scripts [C1][C9] | OS-specific functionality must be tested on its actual platform |
| Distribution | JDK/runtime + FFmpeg + JavaFX + ONNX + Graal JS and app/shared UI dependencies [R2] | Native executable, QuickJS, codec crates, platform webview integration; LTO/strip [C1] | Smaller distribution has concrete origins beyond “Rust is faster” |
| Original upstream | Current Android app uses Compose/Media3; shared resolver/lyrics/party models and analyzer are reference implementations [U1][U2] | Independent implementation and relay protocol | Port by feature contract and licensing; current upstream has no desktopApp module |

**Rust decision:** Native compilation and not bundling a JVM explain part of Continued's package advantage. RAII can simplify decoder/texture release, but neither GC nor Rust ownership prevents application-level retention or expensive redraws. Revived can remove retention, defer initialization, reduce duplicate decoding and measure frame costs incrementally. No evidence in this audit justifies a rewrite. A rewrite RFC would require measured persistent failures against agreed user budgets, an incremental-optimization comparison, platform/feature parity costs, and maintenance capacity.

### Dependency and native risks

Revived's sharedUi selects Compose desktop 1.10.3 on Windows and 1.12.0 elsewhere. Haze also varies by target. Existing comments describe ShaderBrush/Skia ABI mismatches; these are source evidence of compatibility constraints, not proof of a currently failing build [R2][R3]. Use dependencyInsight snapshots on both runner OSes and a packaged blur/lyrics smoke test. Do not “upgrade everything” as a quick performance fix.

Continued's current checkout still contains Kotlin/Gradle-oriented Check and Release YAML, although its current source is Rust and Gradle files are absent [C10]. The Actions API returned no runs in the sampled query. This does not prove the distributed executable was never tested; it does mean this audit could not establish a working public Rust CI/release trail. Its README's three-platform badge is also broader than the published v2.0.0 assets, which are Windows-only.

## 3. Performance evidence

Full methodology and remaining measurements: [benchmark protocol](benchmark-protocol.md).

| Metric | Revived | Continued | Evidence / limit |
| --- | --- | --- | --- |
| Cold startup | Unmeasured | Unmeasured | Need same OS/hardware and cold-cache definition |
| Warm startup | Unmeasured | Unmeasured | External launch-to-frame measurement required |
| Idle RAM | Unmeasured | Unmeasured | Capture RSS/private bytes including child processes |
| Playback RAM | Unmeasured | Unmeasured | Match source/codec, visuals, prefetch and duration |
| Playback CPU | Unmeasured | Unmeasured | Same output device and power plan |
| GPU utilization | Unmeasured | Unmeasured | Hardware renderer and frame-time capture required |
| Memory growth | Unmeasured at app level | Unmeasured | 2-hour controlled navigation/playback soak |
| Playback startup latency | Unmeasured | Unmeasured | Submission diagnostics are not acoustic first audio |
| Track transitions | Unmeasured | Unmeasured | Capture loopback gap/overlap with matched settings |
| UI responsiveness | Unmeasured | Unmeasured | Input-to-present p50/p95 and frame times |
| Background use | Unmeasured | Unmeasured | Unfocused, minimized, tray and PiP cases |
| Windows setup asset | **341,431,860 bytes** (341.43 MB) | **13,549,747 bytes** (13.55 MB) | GitHub release metadata, not runtime measurement [A1][A2] |
| Windows portable ZIP | **338,515,710 bytes** | **16,685,812 bytes** | Different feature/dependency sets; not installed footprint [A1][A2] |
| Executable alone / installed footprint | Unmeasured | Unmeasured | Extract/install and enumerate files for each platform |

Continued's setup download is about **25.2× smaller**. Do not generalize that ratio to RAM, CPU or speed. Revived has Linux assets (AppImage 345,401,848 bytes, DEB 328,680,540, RPM 351,761,561) while Continued 2.0 has no published Linux asset in the inspected release.

A local Kotlin source harness confirmed one narrow retention defect: 10,000 distinct media URL lookups left 10,000 entries in the old URL/path map; removing the redundant map left zero entries while preserving golden paths, file reuse, clearing and disk-LRU behavior. No whole-application before/after RAM number is claimed. PR #135 contains that change.

## 4. Feature gap matrix

**Status rules:** “Fully implemented and verified” requires completed acceptance testing in the tested release/environment; no broad live feature in this audit meets that bar. “Unverified — implementation present” means source wiring exists but live behavior was not demonstrated. “Implemented but unreliable” below denotes an open reproducible-user report, not an independently reproduced diagnosis. “Missing” means no path was found in the inspected source surface, not a claim about future branches. Experimental features stay labeled as such even with tests.

| Feature | Revived 1.9.2 | Continued 2.0 | Evidence / gap |
| --- | --- | --- | --- |
| YouTube Music | Unverified — implementation present | Unverified — implementation present | Shared resolver/search vs innertube/ytm/backend [R16][C5][C11] |
| Google authentication | Unverified — implementation present | Unverified — implementation present | JavaFX/cookie flow vs system webview child + keyring [R17][C7] |
| Spotify playlist import | Unverified — implementation present | Unverified — implementation present | Public embed + matching/library flow vs transfer playlists/liked music [R18][C12]; test >100 items and unmatched tracks |
| Local music folders | Unverified — implementation present | Missing from inspected local-library UI | Revived scan/watcher/artwork; Continued has Downloads and cloud Uploads, which are distinct [R19][C8] |
| Offline downloads | Unverified — implementation present | Unverified — implementation present | Both persist/play files [R20][C13] |
| Music video rendering | Experimental | Missing from inspected music-video playback UI | Revived FFmpeg video session behind video-beta; Continued handles music-video catalog IDs as audio/song versions. Animated artwork is not music-video playback [R14][C14] |
| Audio/video switching | Experimental | Missing as video rendering | Revived media-version/session paths; validate clock continuity and failures [R14][R21] |
| Automix | Experimental | Unverified — implementation present | Revived native analyzer/ONNX/loopmix; Continued BPM-aware phrase/fade envelopes [R4][R22][C3]. Names do not establish equivalent beat analysis |
| Crossfade | Unverified — implementation present | Unverified — implementation present | Both have two-track/fade logic [R4][C3] |
| Audio equalizer | Unverified — implementation present | Missing from inspected audio DSP/settings | Revived DesktopEqualizer; Continued's `widgets::equalizer` draws activity bars, not an audio EQ [R23][C15] |
| Lossless/hi-res addons | Unverified — implementation present | Unverified — implementation present | Both have HTTP sources/quality logic; actual source availability remains external [R24][C16] |
| Synced lyrics | Unverified — implementation present | Unverified — implementation present | Source/provider and playback-clock paths in both [R25][C17] |
| Lyrics translation | Implemented but unreliable — open report #133 | Unverified — implementation present | Revived shared translation adapter exists; investigate provider/language/session before diagnosis [R26][I1][C18] |
| Lyrics romanization | Unverified — implementation present | Unverified — implementation present | Both delegate/transcode provider output [R26][C18] |
| Fullscreen player | Implemented but unreliable — open HiDPI report #128 | Unverified — implementation present | Windows scale >100% reported; native fullscreen paths in both [R27][I2][C19] |
| Picture-in-Picture | Unverified — implementation present | Implemented but incomplete relative to separate PiP | Revived independent window/host; Continued mini mode reuses the main viewport [R28][C20] |
| Listen Together | Implemented but incomplete as default-service delivery | Unverified — implementation + default relay present | Revived server URL may be injected/custom; release workflow does not write LISTEN_TOGETHER_SERVER. Continued ships default relay URL; live uptime not checked [R29][C21] |
| Discord Rich Presence | Unverified — implementation present | Unverified — implementation present | IPC/client and player callbacks [R30][C22] |
| Last.fm | Unverified — implementation present | Unverified — implementation present | Both need configured credentials/session [R31][C23] |
| ListenBrainz | Unverified — implementation present | Missing from inspected integration surface | Revived scrobbling settings and client [R31] |
| Playback quality controls | Unverified — implementation present | Unverified — implementation present | Revived measured stream-format badges; Continued addon quality Info/menu [R6][C16][C14]. No bit-perfect claim |
| Queue management | Unverified — implementation present | Unverified — implementation present | Generation/queue models and UI actions [R32][C14] |
| Custom themes | Unverified — implementation present | Unverified — implementation present | Revived theme/player-theme settings; Continued preset schema [R33][C24] |
| Fonts/appearance | Unverified — implementation present | Unverified — implementation present | SF Pro default/legacy and theme choices vs selectable font/presets [R33][C24] |
| Keyboard shortcuts | Unverified — implementation present | Unverified — implementation present | DesktopShortcuts vs UI shortcut handling [R34][C8] |
| In-app updates | Unverified — implementation + regression infrastructure present | Unverified — implementation present | Revived size/hash/readiness/rollback helpers; Continued update module [R35][C25] |
| Windows | Published release + recent successful release job | Published release; runtime not independently verified | Assets establish availability, not all-functionality validation [A1][A2][W1] |
| Linux | Published release + recent successful release job | Experimental/source support; no v2 Linux asset | Revived AppImage/DEB/RPM/Arch; Continued install scripts and Linux dependencies [A1][A2][C1] |
| macOS | Missing in supported build target | Experimental/source support; no v2 macOS asset | Revived build explicitly restricts targets; Continued bundle script/conditional dependencies [R2][C1] |

The strongest confirmed competitive gap is **download footprint**. A second source-supported gap is **default Listen Together service delivery**: Continued provides an endpoint; Revived's code alone is insufficient. Continued's bounded textures, seek-aware transport and wider Spotify transfer surface are design references, not demonstrated reliability wins. Revived should defend music video, local music, audio EQ, separate PiP, Linux distribution and mobile-derived UI consistency with tested user journeys.

## 5. Optimization opportunities

Expected benefits are hypotheses until measured. Existing good mechanisms must be retained: artwork byte-LRU, audio range limits, two-second speculative decode, cancellation/generation fencing, reduced blur, hidden-video suspension, lazy local scans and feed eviction.

| Initiative / root cause | Source evidence | Proposed solution | Expected benefit | Regression risk | Measurement | Complexity |
| --- | --- | --- | --- | --- | --- | --- |
| Retained URL/path metadata outlives disk entries | `DesktopMediaCache.known` never evicted except Clear [R36] | Remove redundant memoization; preserve digest/path format (PR #135) | Bound this metadata to live call lifetime | Low: repeat hashing cost | Before/after 10k lookups; app browsing soak + CPU | Low |
| Multiple artwork loads can miss cache concurrently; response bytes are read without a size cap | ArtworkCache load uses get → blocking readBytes → decode → put [R7] | Single-flight by sized URL, bounded input and decode dimensions; cancellation-aware subscribers | Fewer duplicate requests/transient allocations | Medium: local/remote image formats, cancel semantics | Count network/decode per URL; burst search, corrupt/oversized art tests; peak RSS | Medium |
| Two image paths can retain overlapping artwork | Custom decoded LRU and shared Coil player [R2][R7] | Instrument both first; consolidate keys/sizes or ownership only where traces show duplication | Less CPU/native image duplication | Medium: player/placeholders/fallbacks | JFR/native allocations + image cache counters + warm navigation | Medium |
| Broad root UI observes playback and contains many effects | DesktopApp playback collection/timer/effects [R11] | Compose tracing, then move high-frequency position reads into narrow player scopes/derived state | Lower avoidable recomposition/layout | Medium: queue, party and lyrics must still update | Frame p95, recomposition counts, input latency; no root refactor without evidence | Medium–High |
| Explicit GC and filesystem trim on minimize/hidden event | Main LaunchedEffect calls MemoryOptimizer; System.gc [R10][R13] | Measure hide/restore pauses, debounce repeated events, do IO trim off UI dispatcher; avoid forced GC only if data supports it | Fewer restore/hide stalls | Medium: delayed reclamation, PiP lifetime | GC pause/JFR, repeated hide/restore, RSS and audio underruns | Medium |
| System theme polled every 2.5 seconds | Main theme loop [R10] | Measure platform command cost; prefer OS notifications or skip work when explicit theme chosen | Less periodic wake/IO | Low–Medium: theme changes must propagate | Idle CPU/wakeup rate with System vs explicit Light/Dark | Medium |
| Video frame copy and image ownership at playback frame rate | pixels.copyOf per frame in DesktopVideoSession [R14] | Profile first; safely reuse bounded buffers with explicit painter ownership only if allocation dominates | Lower GC/native churn during video | High: tearing, stale frames, decoder lifetimes | Video CPU/alloc rate/frame p95; switch/fullscreen/PiP stress | High |
| Heavy libraries in startup/runtime distribution | JavaFX, Graal JS, ONNX, FFmpeg; startup hooks [R2][R10] | Dependency/size inventory; delay optional runtime initialization; lazy analyzer after settings/need if profiling confirms eager cost | Smaller startup work; possible package reduction later | Medium–High: auth, addons, codec availability | Cold/warm startup phases, jlink/dependency report and codec parity | Medium; dependency removal needs RFC |
| Resolver/network stalls and speculative work compete | Decoder/read timeout, open/prefetch gates, range fetch [R4][R5][R12] | Trace stage p95; deduplicate resolves and prefetch only next eligible track; consider bounded parallel ranges via RFC after trace | Faster starts/transitions and fewer stalls | High if buffering changed blindly | 30 clicks, 30 transitions, impaired network and rapid-skip tests | Medium–High |
| Existing feed eviction threshold is reactive | 1 GiB process RSS / 5-second policy [R8][R11] | Log retained feeds/images/native memory; add account-aware bounded cache policies if needed; preserve Back state | Predictable long sessions | Medium: refetch and navigation state loss | 2-hour history/scroll soak, network count and RSS slope | Medium |
| Startup/RSS diagnostics have measurement blind spots | startNano initialized in diagnostics object; Linux statm ×4096; Windows RSS absent there [R37] | External launch clock; reuse processResidentBytes for diagnostics; name first-frame proxy accurately | Trustworthy baselines, not a direct speed win | Low: logging only | Compare to OS sampler at identical timestamps | Low–Medium |

Prioritize the first two memory items and measurement foundation before changing decoded-buffer duration or visual design. GPU work should target measured redraw/blur cost: retain preblurred Simple player theme and Reduce Blur, stop unused animation clocks, keep PiP visuals alive only when visible, and test on an integrated GPU. No new animated layers are proposed.

## 6. Reliability and release engineering

Recent evidence is more nuanced than “Windows CI is broken”:

- PR #107 final head `3a9d0e6...` had successful Check and Updater reliability runs (42369959390 and 42369959343).
- PR #113 final head `4727257...` had successful Check and Updater reliability runs (42371482037 and 42371482069).
- Release run [42479332482](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/42479332482) passed Windows but failed Linux AppImage packaging. PR #132 documents an HTML/CDN response saved as appimagetool; pinned download/runtime verification was merged in `37d54fe...` [W2].
- Release [42485140845](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/42485140845) succeeded for v1.9.2. This is release-workflow evidence, not a new independent installed-app test.
- AUR job [128745278291](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/42485141027/job/128745278291) failed when create-pull-request tried Git operations after checkout had downloaded an archive. Its log explicitly says Git was unavailable at checkout and later shows `fatal: --local can only be used inside a git repository`.

The audit does not attribute every earlier Windows failure to the same root cause. Current build files already contain WiX discovery, installer version normalization, native-library checks and Windows Compose/Haze accommodations. Preserve those checks. Add a small failure taxonomy (dependency resolution, Kotlin/Compose ABI, native compile, test isolation, WiX/packaging, download integrity, installed-app runtime) and record the actual failed step rather than changing tests at random.

PR #134 adds active-branch push coverage, installs Git before AUR checkout, verifies the worktree, and aligns the generated AUR PR with 1.9.2. **Release-event workflows come from the default branch:** merging only into 1.9.2 will not update that release handler while 1.9.1 stays default. A separate deliberate backport/default-branch decision is still required; none was performed here.

Updater code already checks release URL association, published SHA-256, size/free space, isolated partial files, cancellation, relaunch identity/version, and GUI readiness [R35]. Improve its test matrix and actionable stage errors; do not replace it wholesale. Validate real upgrades with an old installed version, stale installer cache, portable paths with spaces, interruption, low disk space, and failed relaunch. Package version monotonicity and displayed release version are separate contracts.

Build reproducibility remains incomplete: action major tags, moving runner images, network-fetched tools, and platform-dependent dependency graphs need inventory. Keep Gradle wrapper, establish dependency verification/lockfiles where supported after reviewing native/Android graphs, pin external binaries/checksums, save dependencyInsight output, and use cached dependencies without hiding corruption. Distinguish fast PR compile/tests from installed-package smoke/release gates. Never remove tests to obtain green checks.

## 7. Discoverability and sustainable growth

### Evidence

The current repository description clearly identifies Windows/Linux and YouTube Music. Its README includes the project name as an image alt plus body text, so the image title alone cannot establish why it ranks lower. The pre-change README has broken internal navigation targets, duplicate `download` IDs, generic screenshot alt text and Windows/Linux buttons both labeled “Download for Android”. Asset links point to the older main branch, creating avoidable branch coupling. These are verifiable presentation/accessibility defects [R38].

Public searches during this audit returned Revived's **release page**, and third-party repository listings mention Revived. Therefore “Revived has no searchable web presence” is incorrect. This tool's search results are **not an authenticated Google SERP or Search Console report**: neither the repository home page's Google index status nor its exact Indonesia/mobile ranking was established. Continued also appears in third-party listings. No comparable backlink inventory or causal explanation for Google's ranking was established.

Google documents that prominent text, headings, descriptive links and alt text help interpretation, and that ranking depends on many factors. It does not promise a rank increase after a README edit [S1][S2][S3]. A GitHub maintainer does not control github.com's robots, canonical tags or its domain Search Console. A project website would provide that control for its own domain only.

### Actions

1. **Immediate:** retain banner/wordmark, add a readable text H1, correct alt labels and anchors, use relative repository assets, expose releases/latest plus package choices, identify 1.9.2 development and experimental rework, link contribution/build docs. This PR implements those changes.
2. **Metadata:** propose topics `bitchord`, `desktop`, `music-player`, `youtube-music`, `windows`, `linux`, `kotlin`, `compose-multiplatform`, `open-source`. No topic mutation tool is available here; these are recommendations, not completed changes. Keep a concise description rather than stuffing synonyms. Both repos currently have no topics, so this does not explain Continued's relative lead by itself.
3. **Default-branch consistency:** decide separately whether stable/default should follow released 1.9.2, or backport only presentation/workflow fixes into 1.9.1. Do not merge unrelated development to change the homepage.
4. **Installation docs:** show package selection, desktop/auth prerequisites, updater limitations and troubleshooting by OS. Add one real screenshot each for normal playback, local music, lyrics and low-effects mode, version-labeled and with alt text. Do not imply macOS support.
5. **Release presentation:** consistent `BitChord Desktop Revived vX.Y.Z` titles, concise English feature/fix notes, tested platforms, hashes and known limitations. Asset download counts are requests per asset, not unique installs or active users.
6. **Project website decision (30 days):** a small public landing page can centralize screenshots, real feature claims, platform download buttons, changelog and troubleshooting. Use owned-domain canonical URLs, title/description, sitemap and Search Console. Keep GitHub Releases as binary authority. Build it only if someone will maintain it; no site was deployed in this audit.
7. **Community:** maintain a weekly issue-triage window; request version/OS/DPI/logs/reproduction; use P0/P1/P2 and regression labels. Add small scoped good-first-issue tasks with acceptance criteria. Fix source-build instructions and platform smoke commands before asking for contributors.
8. **Announcements:** publish one clear release post per meaningful release in permitted channels with screenshots, supported OSes, install link, limitations and credits. Link contextually from an allowed upstream community/fork comparison or genuine package listing. No unsolicited community messages were sent and no artificial engagement is proposed.
9. **Feedback:** consider enabling Discussions for Q&A/release feedback. Begin with a maintainer-owned known-issues page if moderation capacity is limited. Use bug reports and opt-in surveys, not default telemetry, to learn why installs fail or users leave.
10. **Measurement:** weekly snapshot of release asset downloads, failed-upgrade reports, recurring playback/HiDPI incidents, time to first useful issue response, first-contribution completion and search-query observations. Define a pre-change baseline; compare 4-week trends with release age. No guaranteed ranking/star/download target.

## 8. Execution and boundaries

See the [7/30/90-day roadmap](roadmap-2026-11.md). Each initiative includes evidence, impact, priority, complexity, risk, dependencies, a success metric and approach.

Completed implementation so far:

| PR | Branch | Commit | Scope |
| --- | --- | --- | --- |
| [#134](https://github.com/Bntagaaa/BitChord-Desktop-Revived/pull/134) | `fix/1.9.2-ci-branch-and-aur-checkout` | `95329dfd8c9b054a39621e4ab54ab7720ba98fc4` | Three workflow files; no runtime or tests disabled |
| [#135](https://github.com/Bntagaaa/BitChord-Desktop-Revived/pull/135) | `perf/1.9.2-release-media-path-metadata` | `6066ea0d4edc4c7f14af669546b05c99b7ae997e` | Remove one unbounded metadata map |
| Documentation PR containing this audit | `docs/1.9.2-product-discovery-and-audit` | See PR head | README, audit, protocol and roadmap |

All target 1.9.2 and remain for human review. No automatic merge, force push, protected-branch mutation, dependency migration or experimental-branch edit. The competitor and upstream checkouts were read-only.

Local validation: 9 existing AUR tests passed; YAML/branch/prerequisite-order/base-alignment checks passed; source harness passed before/after cache behavior and retention exercise; diff whitespace checks passed. The Kotlin harness used compiler 2.0.20 from downloaded Gradle, JDK 17 and a configuration stub; it is not a substitute for project Kotlin 2.3.20/JDK21 builds.

Full Gradle test attempt failed during resolution of Android Gradle plugin 8.10.1 before tests executed. The environment initially has JDK17 rather than JDK21; system installation failed due setgroups/setuid restrictions. Rust/cargo, Xvfb and a usable comparative desktop/audio setup are absent. Gradle was obtained through the existing proxy, but project dependency resolution remained blocked. No escalation or configuration bypass was attempted. Application startup/playback/rendering, real Google sign-in, live party service, addon source uptime and cross-platform runtime acceptance remain unverified.

CI results are time-sensitive: use the PR checks and the final implementation-results artifact for the latest observed state. A green run proves only the checks it actually ran. The proposed optimization and discovery work must not be described as already merged or released.

## Source index

All code links below are pinned to the audited commit. Release/run/issue metadata is a dated snapshot and can change.

[R1]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/build.gradle.kts
- [R1] Bntagaaa/BitChord-Desktop-Revived: `build.gradle.kts`
[R2]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/build.gradle.kts
- [R2] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/build.gradle.kts`
[R3]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/sharedUi/build.gradle.kts
- [R3] Bntagaaa/BitChord-Desktop-Revived: `sharedUi/build.gradle.kts`
[R4]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt
- [R4] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt`
[R5]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDecodeAhead.kt
- [R5] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDecodeAhead.kt`
[R6]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayback.kt
- [R6] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayback.kt`
[R7]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopArtwork.kt
- [R7] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopArtwork.kt`
[R8]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPageMemoryPolicy.kt
- [R8] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPageMemoryPolicy.kt`
[R9]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopByteBudgetLru.kt
- [R9] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopByteBudgetLru.kt`
[R10]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/Main.kt
- [R10] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/Main.kt`
[R11]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt
- [R11] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
[R12]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopRangeStream.kt
- [R12] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopRangeStream.kt`
[R13]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMemoryOptimizer.kt
- [R13] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMemoryOptimizer.kt`
[R14]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMusicVideo.kt
- [R14] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMusicVideo.kt`
[R15]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMpris.kt
- [R15] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMpris.kt`
[R16]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSearchClient.kt
- [R16] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSearchClient.kt`
[R17]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWebSignIn.kt
- [R17] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWebSignIn.kt`
[R18]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/shared/src/jvmMain/kotlin/com/music/bitchord/data/spotify/SpotifyImporter.kt
- [R18] Bntagaaa/BitChord-Desktop-Revived: `shared/src/jvmMain/kotlin/com/music/bitchord/data/spotify/SpotifyImporter.kt`
[R19]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLocalMusic.kt
- [R19] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLocalMusic.kt`
[R20]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDownloadManager.kt
- [R20] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDownloadManager.kt`
[R21]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt
- [R21] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
[R22]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAnalysisRuntime.kt
- [R22] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAnalysisRuntime.kt`
[R23]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEqualizer.kt
- [R23] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEqualizer.kt`
[R24]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAddon.kt
- [R24] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAddon.kt`
[R25]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLyricsClient.kt
- [R25] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLyricsClient.kt`
[R26]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayerHost.kt
- [R26] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayerHost.kt`
[R27]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsFullscreen.kt
- [R27] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsFullscreen.kt`
[R28]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPipHost.kt
- [R28] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPipHost.kt`
[R29]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopListenTogether.kt
- [R29] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopListenTogether.kt`
[R30]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDiscordRpc.kt
- [R30] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDiscordRpc.kt`
[R31]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopScrobbling.kt
- [R31] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopScrobbling.kt`
[R32]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopQueue.kt
- [R32] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopQueue.kt`
[R33]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTheme.kt
- [R33] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTheme.kt`
[R34]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopShortcuts.kt
- [R34] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopShortcuts.kt`
[R35]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateDownloader.kt
- [R35] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopUpdateDownloader.kt`
[R36]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMediaCache.kt
- [R36] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMediaCache.kt`
[R37]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopStartupDiagnostics.kt
- [R37] Bntagaaa/BitChord-Desktop-Revived: `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopStartupDiagnostics.kt`
[R38]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/blob/348b613a0c3db1a54de56483ea6f8748d6465211/README.md
- [R38] Bntagaaa/BitChord-Desktop-Revived: `README.md`
[C1]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/Cargo.toml
- [C1] jackychan4891/BitChord-PC-Continued: `Cargo.toml`
[C2]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/main.rs
- [C2] jackychan4891/BitChord-PC-Continued: `src/main.rs`
[C3]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/audio.rs
- [C3] jackychan4891/BitChord-PC-Continued: `src/audio.rs`
[C4]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/stream.rs
- [C4] jackychan4891/BitChord-PC-Continued: `src/stream.rs`
[C5]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/backend.rs
- [C5] jackychan4891/BitChord-PC-Continued: `src/backend.rs`
[C6]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/images.rs
- [C6] jackychan4891/BitChord-PC-Continued: `src/images.rs`
[C7]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/login.rs
- [C7] jackychan4891/BitChord-PC-Continued: `src/login.rs`
[C8]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/ui/mod.rs
- [C8] jackychan4891/BitChord-PC-Continued: `src/ui/mod.rs`
[C9]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/media.rs
- [C9] jackychan4891/BitChord-PC-Continued: `src/media.rs`
[C10]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/.github/workflows/check.yml
- [C10] jackychan4891/BitChord-PC-Continued: `.github/workflows/check.yml`
[C11]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/ytm.rs
- [C11] jackychan4891/BitChord-PC-Continued: `src/ytm.rs`
[C12]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/transfer/spotify.rs
- [C12] jackychan4891/BitChord-PC-Continued: `src/transfer/spotify.rs`
[C13]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/downloads.rs
- [C13] jackychan4891/BitChord-PC-Continued: `src/downloads.rs`
[C14]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/ui/playback.rs
- [C14] jackychan4891/BitChord-PC-Continued: `src/ui/playback.rs`
[C15]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/ui/widgets.rs
- [C15] jackychan4891/BitChord-PC-Continued: `src/ui/widgets.rs`
[C16]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/hires.rs
- [C16] jackychan4891/BitChord-PC-Continued: `src/hires.rs`
[C17]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/lyrics.rs
- [C17] jackychan4891/BitChord-PC-Continued: `src/lyrics.rs`
[C18]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/translate.rs
- [C18] jackychan4891/BitChord-PC-Continued: `src/translate.rs`
[C19]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/fullscreen.rs
- [C19] jackychan4891/BitChord-PC-Continued: `src/fullscreen.rs`
[C20]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/ui/mini.rs
- [C20] jackychan4891/BitChord-PC-Continued: `src/ui/mini.rs`
[C21]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/jam.rs
- [C21] jackychan4891/BitChord-PC-Continued: `src/jam.rs`
[C22]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/discord.rs
- [C22] jackychan4891/BitChord-PC-Continued: `src/discord.rs`
[C23]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/lastfm.rs
- [C23] jackychan4891/BitChord-PC-Continued: `src/lastfm.rs`
[C24]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/preset.rs
- [C24] jackychan4891/BitChord-PC-Continued: `src/preset.rs`
[C25]: https://github.com/jackychan4891/BitChord-PC-Continued/blob/5b49c884ec459886e26817f768c64cf670d22aac/src/update.rs
- [C25] jackychan4891/BitChord-PC-Continued: `src/update.rs`
[U1]: https://github.com/kushagrasinghx/BitChord/blob/acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc/app/build.gradle.kts
- [U1] kushagrasinghx/BitChord: `app/build.gradle.kts`
[U2]: https://github.com/kushagrasinghx/BitChord/blob/acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc/settings.gradle.kts
- [U2] kushagrasinghx/BitChord: `settings.gradle.kts`
[A1]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/releases/tag/v1.9.2
- [A1] Revived v1.9.2 release assets
[A2]: https://github.com/jackychan4891/BitChord-PC-Continued/releases/tag/v2.0.0
- [A2] Continued v2.0.0 release assets
[I1]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/issues/133
- [I1] Revived #133, translation report
[I2]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/issues/128
- [I2] Revived #128, Windows HiDPI fullscreen report
[W1]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/42485140845
- [W1] Successful v1.9.2 release run
[W2]: https://github.com/Bntagaaa/BitChord-Desktop-Revived/pull/132
- [W2] Merged #132, AppImage tool/runtime verification
[S1]: https://developers.google.com/search/docs/appearance/title-link
- [S1] Google Search Central, title links
[S2]: https://developers.google.com/search/docs/appearance/google-images
- [S2] Google Search Central, image alt text
[S3]: https://developers.google.com/search/docs/fundamentals/how-search-works
- [S3] Google Search Central, how Search works
