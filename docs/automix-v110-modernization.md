# Desktop AutoMix and upstream v1.10

## Baseline audit

Desktop 1.10 at `acf101ac9390f42c4a219e2d0e96e2b54400f67a` was compared with upstream v1.9.1, v1.10 (`7448b92e032af7e5666c93b0e04c6b9fa3abf898`) and its Web Player. Artwork behavior comes from upstream PR #1870 (`541adcdadbb0bc98dc808445c3a69250a166f14d`).

| Area | Upstream change or Web behavior | Desktop baseline and disposition |
| --- | --- | --- |
| Planner and loops | Phrase-aware planning, instrumental loop selection, vocal protection, bass and filter gestures | `TransitionPlanner.kt`, `LoopMix.kt` and the native loop renderer have the same Git blobs in all three Kotlin trees. Preserve shared algorithms and desktop PCM handoff. |
| Transition clock | Android smooths coarse ExoPlayer position reports and supplies an artwork feed. Web uses clocked decks and a media-element position servo. | `DesktopAudioEngine` mixes PCM by frame and `DesktopAudioSink.framesPlayed()` reports played frames for WASAPI, PulseAudio and Java Sound. Feed artwork from those frames. Retain desktop tempo stretch and device routing. |
| Runtime | Android uses a reduced 1.28.0 runtime with its official JNI component, a config including optimized operators, and a native parity harness. | Desktop ships ONNX Runtime 1.29.0. The upstream AAR is Android-specific. A desktop reduced runtime requires same-version JNI and operator/output checks on both targets. |
| Inference | Android tries ALL_OPT then BASIC_OPT. | Both desktop trackers only try ALL_OPT. Their tensor outputs and tensors already close per run. Sessions are reused across windows; options need deterministic disposal. |
| Delivery | Android requests weights on enable and downloads a pair of release assets. | Desktop extracts weights from the app JAR and its legacy analysis directory can contain valid copies. Use a pinned verified source and retain the existing analysis cache. |
| Beat model | Web's historical broken-model note refers to `beat_this_int8.onnx`. | Desktop, upstream Android and Web already ship the same final0 model (`7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd`) and vocal model (`a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb`). |
| Beat front end | Web ports the phone's 22,050 Hz, 1024-point FFT, 441-hop, 128-bin filterbank. Both use reflect padding, periodic Hann with square-root FFT normalization, peak-normalized Slaney mel filters and log compression. | The native desktop front end is unchanged across tags. Both paths handle separate beat and downbeat logits and confidence from regularity and peak strength. No model/preprocessing change calls for a cache invalidation. |
| Scheduling | v1.10 Android changes model loading and keeps the previous model-window algorithm. Web workers measure only current and upcoming tracks, with a separate media download and IndexedDB cache. | Desktop already has a single worker, two pending slots, duplicate suppression, relevance cancellation, persistent cache, bounded negative-cache hints and CPU-aware inference thread caps. Profile transport, FFmpeg and inference before changing musical inputs. |
| Artwork | `AutoMixVisualState`, `AutoMixVisualFeed`, artwork preferences, reveal clock, cover and ambient helpers, and Android-specific shader/Canvas integration are new. | Desktop has existing still art, player and motion-video surfaces. Adapt the reveal timing and shader to Compose/Skia and retain existing resource limits. |
| Web experiments | Phase-coherence grids, beat-accurate loop deck, Signalsmith Stretch, master tempo ramps, fallback strategies and browser timing corrections. | Web AutoMix implementation is unchanged between the tags. Desktop already uses the Beat This! outputs, native loop phase/tempo control, filter fallback and shared phrase policy. The reported Web timings do not establish a native improvement. Keep a stretch replacement or grid-policy change independent of parity work. |
| Diagnostics | The Web player reports source, analysis and fallback details. | Existing desktop current/next states and loop summary can carry measured timing and planner evidence. |

The existing canonical hash verifier flagged `AnalysisStore.kt` in the baseline. The desktop file intentionally bounds the negative disk lookup cache. That behavior is useful on long sessions and will be retained.

## Desktop implementation

- The model manager fetches both weights from the pinned upstream v1.10 release. It verifies size and SHA-256 before an atomic install, resumes partial transfers when the server supports ranges, and reuses verified legacy files. Manual controls cover download, retry, and deletion. A verified cached analysis remains usable offline. A pair with no analysis follows the existing fallback while the models are unavailable.
- Both trackers try `ALL_OPT` and retry ORT initialization errors with `BASIC_OPT`. Session options and tensors close after use. The analysis worker reuses sessions across windows with arena and memory patterns disabled and retains the existing CPU-aware thread caps. Session-close RSS measurements showed little immediate return of memory to the OS. The desktop keeps the existing session lifetime.
- FFmpeg playback, structural analysis, model windows, and loop preparation use the same cache key when they have the same URL, headers, source, and rendition. Add-ons and mismatched recordings still use the canonical analysis source. Cache restoration and initial JNI loading run on the bounded analysis worker. Cached head and tail grids retain their own timestamps. The combined list deduplicates overlapping downbeats on analysis and on cache load.
- Artwork uses a played-frame feed and a compiled Skia organic mask. Expanded, mini, and floating players have independent main and mini settings, both off by default. Incoming artwork and Canvas metadata can prepare before handoff. The outgoing Canvas contributes one bounded snapshot from its existing decoder. Once the incoming Canvas has a frame, the mask reveals that frame source. The organic mask uses a 96 × 96 alpha texture generated once per active reveal. A missing frame or short transition can settle on ordinary artwork. Player themes and transport layout retain their existing controls and styles.
- Stats for Nerds reports current and next analysis, cache state, BPM and grid confidence, planned mix and cue, tempo ratio, loop readiness, vocal overlap, and model status. Developer logs measure resolve, HTTP retrieval, decoding, DSP, resampling, model windows, planning, loop preparation, and queued audio at handoff. An HTTP retrieval measurement is a subset of its enclosing decode time. Beats and downbeats share the inference pass.

## Runtime and platform results

| Measurement | Desktop 1.10 | Modernized |
| --- | ---: | ---: |
| Linux x86_64 app image, bytes | 499,331,449 | 433,353,714 |
| ONNX Runtime JAR on Linux, bytes | 54,400,660 | 11,026,323 |
| Linux CPU runtime binary, bytes | 28,497,752 | 28,497,752 |
| First viewable X11 window, median of three fresh profiles | 4.31 s | 3.32 s |
| Windows MSI workflow artifact, bytes | 340,311,614 | 269,651,959 |
| Windows setup EXE workflow artifact, bytes | 340,355,929 | 269,697,012 |
| Windows portable workflow artifact, bytes | 338,675,389 | 268,049,476 |

The Windows values are the GitHub Actions archive sizes from the baseline and the packaged 018c7057 revision. [Baseline Windows packages](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/45338994376) · [Modernized Windows packages](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/45341322488)

The two Linux CPU and JNI binary hashes matched between the full and target-only JARs. On the standalone synthetic-tensor probe both model output signatures matched. The retained-memory columns are process RSS. They include the JVM, runtime allocator, and other native allocations.

| Two threads, `ALL_OPT` | Official full JAR | Official target-only JAR |
| --- | ---: | ---: |
| Beat session cold / warm open | 692 / 373 ms | 360 / 487 ms |
| Beat warm inference median | 1,551 ms | 1,406 ms |
| Vocal session cold / warm open | 25 / 10 ms | 14 / 10 ms |
| Vocal warm inference median | 71 ms | 77 ms |
| Process peak RSS | 826 MiB | 811 MiB |
| Process RSS after closing both measured sessions | 367 MiB | 471 MiB |
| Beat / vocal inference process CPU, one core = 100% | 171% / 212% | 199% / 220% |

These are smoke measurements from individual JVM processes on a shared Linux host. The CPU and JNI binaries are identical, so latency and RSS fluctuations are not evidence of faster kernels. The supported size reduction comes from packaging and model delivery. The first-window timings include the packaged launcher and software X11 rendering.

The reduced-operator candidate uses the v1.10 operator list as a starting point and builds against the current desktop ORT version. Source endpoints for the official v1.29.0 Git checkout returned HTTP 404 in the validation environment. The reduced runtime therefore has no parity or performance measurement. The production default is the official target-specific runtime. Passing `-Pbitchord.ort.fullJar=true` reproduces the full upstream JAR packaging. [ONNX Runtime releases](https://github.com/microsoft/onnxruntime/releases)

The canonical-file verifier checks 22 files against the project’s original pin and four intentional adaptations against their recorded hashes. The desktop `AnalysisStore` retains its bounded negative cache. The separate native, Android, and Web comparisons above use the v1.10 tag.

## Analysis benchmark

The comparison used a Linux x86_64 AMD EPYC VM with the test processes restricted to the same four logical cores, JDK 21, the Balanced analysis profile, identical weights and identical local input files. Each track used a fresh cache key. Warm timings measure a new analyser reading the saved result. The benchmark has a separate 120/124 BPM case for time to the first pair plan.

| Audio fixture | Cold baseline | Cold modernized | Tempo, baseline / modernized |
| --- | ---: | ---: | ---: |
| 120 BPM synthetic | 6.86 s | 6.37 s | 120.01 / 120.01 |
| 124 BPM synthetic | 4.79 s | 5.03 s | 124.08 / 124.08 |
| 172 BPM synthetic | 5.27 s | 5.09 s | 171.42 / 171.42 |
| Vibe Ace, Ogg | 4.35 s | 4.25 s | 130.29 / 130.29 |
| Let’s Go Fishin’, Ogg | 6.50 s | 6.84 s | 88.38 / 88.38 |
| Hungarian Dance No. 5, Ogg | 3.64 s | 4.34 s | 149.71 / 149.71 |
| Vibe Ace, FLAC encoding of the decoded fixture | 4.05 s | 4.29 s | 130.34 / 130.34 |
| First pair plan | 11.66 s | 11.42 s | DJ filter / DJ filter |
| Median saved-analysis lookup | 2.58 ms | 2.96 ms | — |

The modernized 120 BPM run recorded 239 ms to structurally decode, 106 ms to extract DSP features, 122 ms to decode model regions, and 220 ms for beat resampling. Beat windows took 4,503 ms and vocal windows took 1,096 ms including model front ends. These local inputs required zero HTTP retrieval. The loopback HTTP regression fetched one range for playback, reused it for AutoMix, and fetched again only for a different source key. A fresh packaged app with AutoMix enabled installed both pinned release weights in 5.3 s from process launch, verified their hashes, and reopened with an unreachable proxy while keeping both file timestamps unchanged.

The recordings exercise sung vocals, steady instrumental rhythm, tempo variation, and two decoder formats. They have no annotated downbeat ground truth. The native analyser probe found 120.003 BPM with ordered downbeats and valid resampled PCM. Neural head and tail accuracy on real material requires annotated input. The measured CPU bottleneck remains Beat This! inference. Smaller input windows, a different inference profile, or different grid alignment would change planner evidence and need a separate parity test.

## Playback and artwork verification

The accelerated collecting-sink test processed 30 minutes of PCM through FFmpeg, the native desktop mixer and the overlap handoff. Both revisions produced 49 ordered handoffs, with the same 0.252 peak and 355,250 near-zero equivalent frames across approximately 80 million frames submitted. Baseline final RSS was 218,256 KiB and modernized final RSS was 217,728 KiB. Process CPU usage was 11.6% on each run with one core as 100%. This exercises real decoding and mixing with a synthetic source and injected beat evidence.

The same test at nominal playback speed processed 1,800.1 seconds of PCM in 1,795.4 wall-clock seconds. It completed 49 ordered handoffs at 1.45% process CPU on a one-core scale. Final RSS was 204,140 KiB and peak RSS was 205,344 KiB. JVM Native Memory Tracking reported 179,117 KiB committed after warm-up and 178,933 KiB late in the run, each including 66,560 KiB of committed Java heap. This evidence is scoped to the PCM test and does not isolate third-party native allocations.

Other checks exercise rapid skipping, obsolete queue and network opens, pause/resume, seek, speed and device reconfiguration, six consecutive handoffs, native loop handover position within 2 ms of its model, digest mismatch, interrupted downloads, cache restoration and Skia masking. The artwork integration test renders the old Canvas snapshot until the audio reveal window, verifies both incoming and outgoing colors during the mask, then checks cancellation restores the live surface.

The software Skia probe rendered 49 organic dissolves, 156 frames each, at 320 × 320. Evaluating the noise field in the fragment shader took 31.82 ms median and 36.60 ms at p95. Sampling the same field from a small texture reduced those to 3.23 ms and 3.87 ms. The probe measures draw time on a software canvas. GPU display presentation and full-size video composition have separate costs.

The Web Player’s browser servo addresses media-element seek latency. The desktop uses a PCM sample axis and its native LoopMix renderer already uses Signalsmith Stretch and a shared master-tempo ramp. Changing stretch engines or adding explicit 4-bar and 32-bar selections would be a separate experiment. The current comparison has no measurements that justify those changes.

Hardware speaker output, driver-specific frame pacing, and GPU residency are outside the collecting-sink and software-rendering tests. Live streaming, actual Windows installer upgrades, and Canvas-to-Canvas playback with external video providers also require direct device testing.

## Reproduce

Build the baseline from `acf101ac` and the branch with the same JDK and Gradle configuration. To run the new benchmark methods on the baseline, take `DesktopAutomixTest.kt` and `DesktopInstantPlaybackEngineTest.kt` from commit `b529d26f`. Its benchmark methods use the baseline APIs.

```bash
python tools/automix-validation/verify-upstream.py
./gradlew -p tools/automix-validation test
./gradlew :shared:jvmTest :desktopRuntime:test :desktopUi:jvmTest :desktopApp:test
./gradlew :desktopApp:createDistributable
bash desktopApp/packaging/tests/linux-automix-check.sh

BITCHORD_AUTOMIX_BENCHMARK=1 ./gradlew :desktopApp:test --rerun --tests '*DesktopAutomixTest.*profile cold*'
BITCHORD_AUTOMIX_SOAK_SECONDS=1800 ./gradlew :desktopApp:test --rerun --tests '*DesktopInstantPlaybackEngineTest.*sustained*'
BITCHORD_AUTOMIX_ARTWORK_ROUNDS=49 ./gradlew :desktopApp:test --rerun --tests '*DesktopAutoMixVisualTest.measureSoftwareMaskFrameTimesAndResidency'

java -cp /path/to/onnxruntime-1.29.0.jar tools/automix-validation/AutoMixOrtBenchmark.java \
  app/src/main/assets /path/to/onnxruntime-1.29.0.jar 2 8 ALL_OPT
xvfb-run python tools/automix-validation/measure-startup.py --runs 3 \
  /path/to/baseline/BitChord/bin/BitChord /path/to/modernized/BitChord/bin/BitChord
```

Set `BITCHORD_AUTOMIX_AUDIO_FIXTURES` to the platform’s path-separated list of Ogg or FLAC files. The recorded run used `Kevin_MacLeod_-_Vibe_Ace.ogg`, `Karissa_Hobbs_-_Lets_Go_Fishin.ogg`, and `Hungarian_Dance_number_5_-_Allegro_in_F_sharp_minor_(string_orchestra).ogg`. Decode the first file to FLAC with `ffmpeg -i INPUT.ogg OUTPUT.flac` for the fourth input. The unaccented synthetic click tracks are built into the benchmark. [Fixture catalogue and content hashes](https://github.com/librosa/librosa/blob/main/librosa/util/example_data/registry.txt)

`BITCHORD_AUTOMIX_SOAK_SPEED=20` selects the accelerated collecting-sink run. Leave it unset for real-time playback. The tests record timings and process readings in Gradle’s test XML. On Windows use the repository’s existing package workflow or `gradlew.bat :desktopApp:packageMsi :desktopApp:createDistributable`, then run `desktopApp/packaging/tests/windows-automix-check.ps1`. Linux AppImage, DEB and RPM are built by the AppImage workflow with its native package audit enabled.

For the reduced-operator experiment, run `python tools/automix-validation/build-reduced-ort.py ORT_CHECKOUT ONNXRUNTIME_JAR BUILD_DIR` from a native x86_64 shell. The source must be the official `v1.29.0` tag. Use a Visual Studio developer shell on Windows. Use the supplied Java benchmark on the experimental and standard JARs at both optimization levels. Graph optimization can add new operator fusions, so validate output parity on both models before using the experiment in a build.
