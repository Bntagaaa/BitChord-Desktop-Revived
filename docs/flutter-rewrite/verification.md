# Verification record

The evidence below applies to this rewrite checkpoint. It deliberately separates
pure Dart assertions, Flutter/engine tests, native builds and live acceptance.
A green row in one category does not establish another category.

| Check | Observed result |
| --- | --- |
| Dependency resolution with committed lock | Passed with Flutter 3.47.9 / Dart 3.13.7; offline enforced-lock resolution also passed |
| Whole-project Dart analysis | **No issues found** |
| Dart formatting | **65 files checked; 0 changes** |
| Pure data/domain/migration/local-scan assertions | **54 passed, 0 failed** under Dart AOT, including rejection of a missing first legacy chunk |
| Pure playback/transport/planner assertions | **44 passed, 0 failed** under Dart AOT |
| Native runner callback API inspection | Matches installed plugin declarations on Linux, Windows and macOS |
| Workflow YAML / macOS XML and entitlement parsing | Passed |
| Normal local Flutter tests | **Blocked before tests executed** by `objective_c` native-asset hook: Dart JIT child exits 255 with no output |
| Local native release build | Not established: managed Linux session lacks required native development packages; elevated installation was unavailable |
| GitHub Actions full Flutter and platform matrix | The [PR description](https://github.com/Bntagaaa/BitChord-Desktop-Revived/pull/137) records actual run/job results and the exact tested SHA; results are not inferred for later untested commits |
| Signed-in/live-stream/native playback smoke tests | Not established |
| Source-versus-Flutter screenshot comparison | Not established |
| Comparative performance measurements | Not established; see performance.md |

## Reproduce the pure assertion evidence

From `flutterApp`, using the Dart binary from the pinned Flutter SDK:

```sh
python test/data/run_pure_aot.py --dart /path/to/flutter/bin/cache/dart-sdk/bin/dart
python test/playback/run_pure_aot.py --dart /path/to/flutter/bin/cache/dart-sdk/bin/dart
```

The helpers create temporary harnesses and adapt only the tests' Flutter test
re-export imports to the underlying `matcher` and `test_api` APIs. They use the
actual repository production code and unchanged assertions. They do not replace
native/Flutter production services with stand-in implementations to manufacture
a pass. The normal Flutter engine suite remains the authoritative integration
check and runs unchanged in the workflow.

The 98 passing pure tests cover queue identities/tiers/shuffle/restore, equalizer
curves/headroom, stream containers, source-ID guards, HTTP range validation,
source AutoMix planning decisions, models/renderers, session-scoped requests,
cloud playlist wire contracts, local scan/tag fallbacks, legacy chunk/Unicode/
quality/volume/provider migration, lyrics parsing/translation alignment, public
Spotify matching/cancellation, addons and JioSaavn decoding/matching.

## Engine-dependent regressions committed for CI

The normal test tree additionally exercises native-boundary playback controller
state, atomic profile persistence/recovery, download publication/resume/range/
refresh-policy/cancel/shutdown behavior, auth-vault interleavings, application
startup/session fences and widget layout/interaction fixtures. The normal engine
suite passed all 141 tests at checkpoint `6907c4a`. The subsequent missing-first-
chunk regression passed the pure harness; its normal-engine result belongs to
the next commit's CI record on the PR. Pure assertions do not establish native
playback, live service or OS credential-store acceptance.

In particular, the integration review added regressions for a snapshot's volume
winning over stale legacy preferences, preventing late initialization from
replacing new playback, discarding old-account Home/Library/detail responses,
and ordering vault writes so logout cannot be undone by a late sign-in.
PiP creation is serialized and uses a child-ready handshake and window identity;
that lifecycle still requires a real multi-window native test.

## CI evidence

The pull request description is the live CI evidence record: it records actual
run/job links, the tested commit SHA, results and unresolved runner limitations.
Keeping that record on the PR preserves the exact tested Git commit instead of
creating a new commit solely to record its own result. Preserve failures when a
fix or rerun does not establish a pass. A compiled bundle is not launch-tested
until the native application is actually exercised.
