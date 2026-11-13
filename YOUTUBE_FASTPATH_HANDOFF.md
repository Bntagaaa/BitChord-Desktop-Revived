# YouTube fast-start integration: verified handoff

Date: 2026-11-13. Authorized scope: implement the earlier Flutter direct-playback approach in the clean `2.0.0-flutter` rewrite, without redesigning UI or replacing other providers/audio engines.

## Exact source and delivery

- Task baseline: `14f1df8cdb61ab755d2e6a9f94519b7e6493ed4e`.
- Main implementation: `eea76e79c13366baac626d794c108f51b09d832c`.
- Final tested application source: `78715c9c0efa6bfabea29e6453678bd9f3440654`.
- [Final Windows/Linux workflow 43358786449](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43358786449): Linux, Windows and trusted retention all completed SUCCESS.
- Retention commit: `81360f47686f8ea5380deaa306e26d4c8e845c16`; `flutterDesktop/verification/hosted-run.json` records the exact tested source/run and both platform successes.
- This root-document handoff changes no application source. It supersedes pending-validation wording in the initial fast-start sections of the migration documents, not their remaining acceptance gates.
- **Fast-path implementation and controlled/platform validation delivered. Actual audible startup improvement and general live reliability remain UNVERIFIED. Phase 4 INCOMPLETE; Phase 5 NOT STARTED.**

## Implemented behavior

Anonymous YouTube playback now attempts an independent direct `/youtubei/v1/player` request with the VISIONOS profile first, following the lightweight protocol observed in `1.9.2-flutter-reworked` at `d05c9a62822fb706aa9656ecdbf22bea67bf9f4b`. No old branch was merged. A current anonymous catalog visitor snapshot is reused when available; playback does not fetch another catalog shell solely to obtain it. Direct success starts no yt-dlp subprocess. Unsupported direct formats and transient failures can use the existing maintained extractor fallback. The direct path has an eight-second cancellable budget inside the existing overall resolution deadline.

The VISIONOS path is guest-only. A signed-in session continues through the account-bound extractor; it is not silently changed to an anonymous request. Explicit login, content/rights and rate-limit refusals remain explicit rather than causing unrestricted client cycling. The existing verification, cancellation and same-rendition renewal rules still apply. Cipher-only formats are not pretended to be deciphered.

The previous large preflight is replaced by an initial 16 KiB range plus at most 16 KiB beyond the first MiB for a large file. Each candidate is still checked for real audio bytes, exact ranges, length and validators. A successful candidate carries its immutable, exact-source-bound initial bytes to the existing IO broker. The broker can serve that prefix without another upstream request and reads the remainder of the first span starting after the prefix. This is at most 32 KiB of preflight per large candidate, not a promise that all fallback attempts together consume only 32 KiB.

Only the YouTube source opts into source-owned HTTP keep-alive. Other providers retain their default per-request transport mode. The production pool uses one connection per host for sequential bounded spans, discards failed connections and closes on source shutdown. Default range integrity, expiry, cancellation and cache bounds are retained. The warmup prefix is transient memory, not durable signed-URL or account storage.

Redacted diagnostics separate direct request, external extraction, media probes, broker readiness and native command timings. `first_audible_audio` remains `NOT_MEASURED`; command completion is not reported as sound from speakers.

## Scope and changed paths

New files: `flutterDesktop/lib/features/online/data/youtube/direct_player.dart`, `youtube/media_probe.dart`, and `flutterDesktop/tool/youtube_fastpath_check.dart`.

Integration edits: YouTube resolver/extraction request/catalog context/provider, `online_runtime.dart`, `catalog.dart`, `provider_http.dart`, `range_source.dart`, `source_broker.dart`, and `phase4_check.dart`. The four migration/architecture/performance documents received implementation and measurement notes. Shared transport/broker interfaces changed to support the optional prefix and connection owner, but JioSaavn/add-on implementations, native C++/FFmpeg, Kotlin reference code and all UI files were not changed. Existing other-provider regressions pass. Temporary staging workflow files are absent from the final application tree.

## Checks actually observed

All executable validation for this task ran in GitHub Actions with Flutter 3.47.9 / Dart 3.13.7. The authoring container did not execute the SDK or inspect downloaded artifact bytes. Results below were checked through workflow jobs, decoded logs and artifact metadata.

The isolated staging workflow `43357847760` passed fatal analysis, formatting, 49 new fast-path assertions, 43 existing rewrite assertions and 38 Flutter tests before the application change was adopted. Both assertion suites are called by `phase4_check.dart`, which the main Windows/Linux matrix executes through `phase4_platform.py`.

Final exact-source main matrix `43358786449`:

| Check | Linux | Windows |
| --- | --- | --- |
| Formatting | 55 files, 0 changed | 55 files, 0 changed |
| Fatal analyzer | No issues | No issues |
| Fast-path/rewrite and provider HTTP suites | PASS | PASS |
| Flutter tests | 38 passed, 0 failed/skipped | 38 passed, 0 failed/skipped |
| Native CTests | 2/2 PASS | 2/2 PASS |
| Read-only Kotlin export tests | 3 PASS | 3 PASS |
| FFI, SQLite/import, pinned queue corpus | PASS, 450 transitions | PASS, 450 transitions |
| Controlled HTTP to native decoder | 384000 exact PCM frames, 0 live decoders after cleanup | 384000 exact PCM frames, 0 live decoders after cleanup |
| Actual bundled yt-dlp namespace/plugin/session audit | PASS, offline fixtures | PASS, offline fixtures |
| Release build, runtime packaging, native GUI launch/close | PASS | PASS |
| Physical audible YouTube startup | NOT VERIFIED | NOT VERIFIED |

New tests exercise zero extractor calls on direct success, real loopback player requests, warm catalog reuse/invalidation, guest/session isolation, bounded fallback, cancellation, pinned rendition, small probes beyond 1 MiB, immutable prefix binding, zero upstream requests at seeded broker startup, no duplicate initial byte download, exact remaining bytes, keep-alive, redirect rejection and timeout recovery.

The first main run `43358214980` passed Linux but failed the new Windows connection-reuse assertion. The production pool was changed from two connections per host to one for sequential spans in `78715c9c`. The unchanged assertion and full Windows matrix then passed. No test was disabled, expectation weakened or source validation bypassed. The Windows hosted audio endpoint remains unavailable (native probe error 105); GUI launch and decoder fixtures are not physical output evidence.

## Live result and performance limits

The final Linux anonymous bundled probe fetched 20 catalog entries and attempted three real selections. Its report, `flutterDesktop/verification/youtube-bundle-live.json` in the Linux artifact, records `media: REFUSED_OR_PARTIAL` and `native_audibility: NOT_TESTED`.

| Selection | Observed route | Final result | Media probes |
| --- | --- | --- | --- |
| 0 | One direct VISIONOS request, no extractor | loginRequired | 0 |
| 1 | Direct formatUnavailable, then one mweb extraction | loginRequired | 0 |
| 2 | One direct VISIONOS request, no extractor | loginRequired | 0 |

The direct request durations were 75, 85 and 105 ms; the second selection spent another 1956 ms in extraction. These are refused/unsupported request timings, NOT first-audible latency or proof of faster playback. The non-gating observation step exits successfully when it records results, not when all songs play. No Windows live YouTube streaming trial was performed by this matrix. Hosted-network refusal does not establish the same outcome on the user's network.

No full-song soak, physical first-audible A/B benchmark, authenticated live playback, or real-song network/expiry recovery was verified here. The original public-video success reported by the user remains separate baseline evidence. No universal playback, zero-error, memory-saving or speedup-factor claim is made.

## Exact build artifacts

Use artifacts from run `43358786449`, not earlier failed runs or the staging workflow:

- [Linux: flutter-foundation-linux, artifact 14007566230](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43358786449/artifacts/14007566230), 127693209 bytes. GitHub-reported SHA-256: `a4f7bb93c51ab79ecb87d7c756fe0db96fb56ffb457843f16749b8c75e70e998`.
- [Windows: flutter-foundation-windows, artifact 14007875877](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43358786449/artifacts/14007875877), 116870239 bytes. GitHub-reported SHA-256: `029bf81c5b9287328f9887e9020e41bd5f45af7d229639c3af495889093920d5`.

Extract the complete release directory. Linux application directory is `flutterDesktop/build/linux/x64/release/bundle/`; Windows is `flutterDesktop/build/windows/x64/runner/Release/`. Preserve all adjacent libraries/data and `youtube_runtime`; do not copy only the executable or mix old and new runtime folders. No manual extractor/Node/token-provider installation is required for these complete bundles.

The next acceptance check is repeated playback of the same previously working public videos on the user's device/network, with click-to-first-audible timing, replay, track switching and seek, followed by a dedicated authorized session test. Keep account mode and quality fixed for comparisons. Record only redacted categories and timings, never cookies or signed media URLs.
