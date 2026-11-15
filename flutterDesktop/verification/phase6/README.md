# Phase 6 CI evidence

Status: **INCOMPLETE**. GitHub Actions is the primary build/test environment.
No full local Kotlin or Flutter production builds are authorized for this work.
Delivery branch: `codex/phase6-player-lyrics`; PR base: `2.0.0-flutter`;
never auto-merge. Automix/ONNX/Loop & Mix remain deferred.

## Pipeline

`.github/workflows/flutter-desktop.yml` runs:

- `desktop` on Ubuntu 22.04 and Windows 2022: native contracts/sanitizers where
  supported, Dart format/analyze, Flutter tests, release bundles, native GUI
  smoke/captures. Screenshot-tool/coverage contracts run on Linux.
- `phase6-reference`: clean checkout of Kotlin
  `348b613a0c3db1a54de56483ea6f8748d6465211`, Java 21, original JVM suites,
  distributable, immutable SHA and source-diff check. No reference source edits.
- `phase6-visual`: downloads **this run's** successful release artifacts,
  restores launcher/runtime execute permissions, creates disposable signed-out
  profiles, then sequentially drives both actual clients on one Xvfb/Openbox
  display at 96 DPI with software GL. No local builds or private accounts.

`phase6-visual-linux` includes per-client action logs, PNGs/metadata/OCR,
1220×780 and 900×600 clients, side-by-side sheets, overlays, heatmaps,
unmasked pixel metrics, `coverage.json`, and `summary.md`. Launch/capture/close
failures fail the check and preserve partial reports. Kotlin's default
close-to-tray must hide the window; its remaining process is then terminated
as harness cleanup, not accepted as verified native quit or physical tray
behavior. Flutter must exit zero through its existing native close path.
Numeric mismatch is
diagnostic, since signed-out live feeds can return different content and
known migration gaps are not golden regressions.

## Coverage and limitations

The bounded script clicks all eight main sidebar destinations plus Settings,
scrolls Settings, opens signed-out Accounts and the narrow drawer, and captures
post-input states. Inputs/capture/OCR are evidence for review, **not automatic
proof that a route/state was reached or matched**. The source inventory is a
lower bound on runtime discovery, not an exhaustive exploratory audit.

All source-inventoried routes, overlays, Settings sections/pages and required
interactive scenarios remain in the matrix. Unvisited states remain
NOT VERIFIED. Scrolling Settings does not prove every row/subpage/control;
missing Flutter hierarchy remains an implementation gap. The script does not
play a track or establish Fullscreen Player, lyrics sync, signed-in account
switching/persistence, video/Canvas/PiP, dark/pitch-black, keyboard/hover or
physical audibility. Paired Windows exploration remains open; Windows release
build/tests/native smoke are separate checks. No screenshot proves full
interactive parity, audio correctness, memory savings or Phase 6 completion.

Prefer extending hosted input/observation coverage before requesting local
work. Physical audibility and private-account persistence need minimal manual
verification later, using downloaded CI bundles rather than local builds.
Missing player/Settings implementations need code work, not a user smoke test.

## Run record

Baseline desktop: [43671240501](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43671240501),
merged `bb60cc6c`, Windows/Linux success. Historical reference:
[43599294154](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/43599294154),
Windows/Linux success. Neither establishes results for this increment.
Current-source run and PR will be recorded after publication and inspection.
