# YouTube Music backend rewrite: verified handoff

Date: 2026-11-13. Authorized scope: YouTube Music backend only, not a UI redesign or Phase 5.

## Exact source and outcome

- Main rewrite: `17fef96c2b7692f6f95f21184eab577830b89ae3`.
- Final application/test source: `b52cd56486bb6f2af5ae59e9b0354d8ee3b797bc`.
- [Final Windows/Linux CI run 43348184273](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43348184273): Linux, Windows and trusted retention all completed SUCCESS.
- Verified retention commit: `4e94bf7ddde7c7e9bb420906e63bbaaf3b67723c`; `flutterDesktop/verification/hosted-run.json` records the exact source and run.
- This document records later verification than the pending-CI statements in the initial rewrite sections of MIGRATION_PROGRESS.md, MIGRATION_PARITY.md and flutterDesktop/verification/phase4/youtube-rewrite.md. Those statements are historical, not the final build status.
- **Backend refactor and controlled/package validation delivered. Live reliability acceptance NOT COMPLETE. Phase 4 INCOMPLETE; Phase 5 NOT STARTED.**

## What changed

The old combined provider/resolver logic is separated into `youtube/accounts.dart`, `catalog_client.dart`, `catalog_parser.dart`, `extractor.dart`, `formats.dart` and `runtime.dart`, with `youtube_music.dart` and `youtube_resolver.dart` as coordinators. yt-dlp remains the maintained extraction engine; C++/FFmpeg remains the single audio engine. This is a rewrite of the application's integration, not a newly implemented YouTube cipher engine.

An actual configuration defect was corrected: `youtube:player_client=mweb` and `youtubepot-bgutilscript:server_home=...` are now passed as separate `--extractor-args` values. Previously the token-provider namespace was concatenated into YouTube's option string. Actual packaged yt-dlp, not only string assertions, validates the routing and plugin loading. This defect is not proven to be the sole cause of the user's HTTP 403.

Each extraction owns an isolated temporary process/cache context, bounded output, cancellation and deadline. Recovery is bounded; media 403/token failures can trigger a fresh extraction context instead of endlessly retrying stale signed URLs. Session/account generations cancel obsolete work. Renewal preserves video and rendition identity instead of joining bytes from different encodings.

A verified primary YouTube Music session can now serve catalog and playback through an in-memory stdin pipe to the explicitly bundled session plugin. Cookie values are not command-line arguments, environment values or saved cookie files. Raw diagnostics and signed media URLs are not written to logs. Browser login, protected persistent sessions and delegated-account media are not claimed complete.

Catalog bootstrapping keeps version and visitor context together, retries only appropriate read requests within bounds, and rejects malformed entries. Rate limits and account/rights refusals are distinguished from a generic unavailable provider. Candidate streams remain restricted to validated direct HTTPS audio-only media.

Automatic YouTube-to-JioSaavn substitution was removed: a selected YouTube track must really resolve through YouTube. JioSaavn and add-on provider implementations, native audio, generic broker/download storage and Kotlin reference sources were not rewritten. Their existing controlled regressions still pass. No screen redesign was made. The single changed Accounts description accurately discloses that a submitted verified session is now used for catalog AND playback, rather than incorrectly promising catalog-only use.

## Checks actually observed

Local pinned Dart 3.13.7: formatter, targeted analyzer, existing YouTube contracts and 43 new synthetic/offline rewrite assertions pass. The actual Linux yt-dlp binary passes the offline namespace/script-plugin/session-pipe audit. New scenarios include fresh-context 403 recovery, cancellation/timeout, account generation fencing, format isolation, same-rendition renewal, rate-limit handling and subprocess output boundaries.

Final exact-SHA CI artifacts were downloaded, SHA-256 checked and inspected for both operating systems:

| Evidence | Linux | Windows |
| --- | --- | --- |
| Formatting, fatal analysis, native/FFI/provider checks | PASS | PASS |
| Flutter tests, non-hidden completed tests | 38/38 PASS | 38/38 PASS |
| Controlled HTTP to native decoder | 384000 exact PCM frames, zero live decoders after cleanup | 384000 exact PCM frames, zero live decoders after cleanup |
| Actual packaged yt-dlp namespace/plugin/in-memory-session offline audit | PASS | PASS |
| Release bundle, bundled runtime, native GUI launch/close | PASS, exit 0 | PASS, exit 0 |
| Physical audible YouTube playback | NOT VERIFIED | NOT VERIFIED |

The new rewrite assertions are called by `phase4_check.dart`; they are separate from the count of 38 Flutter tests. Fixture success is not live provider or physical speaker proof.

Two CI-discovered portability errors were fixed without disabling checks: absolute fixture paths now use the native platform; the Python Windows runtime audit preserves SYSTEMROOT using case-insensitive environment matching. Its previous WinError 10106 happened before extraction. The production Dart environment lookup was already case-insensitive. Temporary bootstrap/diagnostic workflows were removed from the final tree.

## Live result: still an open acceptance gate

The final Linux anonymous bundled live probe fetched **20 catalog entries**, then attempted **three real selections**. All three returned `loginRequired` on the hosted runner, each after one `mweb` extraction and with **zero media probes**. The JSON explicitly records `media: REFUSED_OR_PARTIAL` and `native_audibility: NOT_TESTED`. The observation step's exit 0 means results were recorded, NOT that playback succeeded. No live Windows streaming trial is provided by this matrix.

The user's earlier report that one video played successfully is preserved as a separate user-observed baseline result. Hosted runner refusal does not prove the identical failure mechanism on the user's network, and the successful build does not establish that every track now plays. No authorized live account trial, full-song soak, physical Windows audio or actual expiry/network recovery during a live song was verified here. Manifest/HLS/MPD/SABR/live-media support is not supplied by the direct-file transport. No anti-error guarantee or full YouTube feature-parity claim is made.

## Artifacts for the exact verified code

- [Linux artifact 14002224698](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43348184273/artifacts/14002224698), `flutter-foundation-linux`, 126268062 bytes; SHA-256 `5e0c72e93e0ddd3078f1824acb3765a7cee8856b91e349af2b33533f462c0ed7`.
- [Windows artifact 14003400482](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43348184273/artifacts/14003400482), `flutter-foundation-windows`, 117083999 bytes; SHA-256 `279485d86a37dfb959d2372c6f7c53841e095453174a50207f2164b23feda6b7`.

Extract the complete release directory, preserving `youtube_runtime` beside the executable; do not mix an older runtime with the rewritten app. The pinned resolver/Node/token runtime is included, not a manual installation requirement.

The next acceptance evidence must come from repeated real YouTube playback (including the earlier successful video), seek/replay/track switches, a dedicated authorized primary session where required, and real failure recovery. Record only redacted error categories and source/commit identity, never cookies or signed URLs.
