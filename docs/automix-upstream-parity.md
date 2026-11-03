# AutoMix upstream parity and consecutive-track recovery

Upstream reference: [kushagrasinghx/BitChord b690dc146009d4815825fa4a20815ef2e4ee8898](https://github.com/kushagrasinghx/BitChord/commit/b690dc146009d4815825fa4a20815ef2e4ee8898), the upstream HEAD inspected on 2026-11-02.

This update supersedes the initial branch's custom musical-planner changes. It
uses the upstream planner as-is, including its tempo/style decisions, duration
rules, cue ranking, fallback, phrase-bar support and optional mix-window policy.
It removes the extra desktop downbeat-cue rewrite. The frame scheduler and output
device handling remain desktop adapters: Android uses Media3/AudioTrack, whereas
Windows/Linux decode and mix PCM for WASAPI/Java Sound.

## What is identical, and how it is verified

`tools/automix-validation/upstream-manifest.json` pins SHA-256 checksums for **25
canonical files**: both ONNX model weights, shared musical planner/policy/analysis
and loop logic, JVM model inference and analysis storage, native analysis and
native loop-renderer sources. These match the pinned upstream source; textual
line endings are normalized for Windows checkout compatibility. Model weights are
compared byte for byte. This includes upstream's optimized polyphase resampler,
which was missing from the fork.

```bash
python tools/automix-validation/verify-upstream.py
./gradlew -p tools/automix-validation --no-daemon test
```

The unmodified upstream advanced-planner and phrase-bar regression suites are
included. Desktop faders now follow upstream `Render`, `mixRise`, `mixFall` and
`headroomFor`: filter gestures share the same smooth faders, the outgoing blend
begins falling no later than the midpoint, vocal collision reduces the held-fader
gesture, and grid snapping uses actual overlap duration/beat duration rather than
the nominal planned beat count. Golden points test these adaptations.

Desktop model orchestration now ports upstream's two overlapping head windows
and three overlapping tail windows, seam stitching, tail-governs-outgoing tempo,
head beat origin, raw vocal-window masks, heuristic/model mask fusion and RMS
window calculation. Both models read bounded decoded regions rather than a
second whole-track beat-model decode. Whole-track structural PCM becomes
unreachable before model inference. A new analysis cache namespace prevents
old results computed with the previous pipeline from silently surviving the update.

Loop preparation now obtains synced lyrics for both tracks and distinguishes
"lookup pending" from "lookup completed without synced lyrics", as upstream does.
It uses the same four-timed-lines threshold, sung-line end/gap rules and 15-second
setup lead. Third-party masters analysed from another recording are not spliced
into a native loop. New lyric/analysis evidence invalidates a previous refusal.

This is not a claim that platform-specific transport, Media3 and desktop WSOLA,
or physical audio output are byte-identical. It is an auditable shared musical
core with the upstream decisions and corresponding desktop execution rules.

## Why AutoMix could disappear during a longer queue

The desktop queue effect also runs for metadata/autoplay updates. Previously,
`prepareNext` unconditionally cancelled the pending resolve, cleared the decoded
incoming track and cancelled its overlap even when the selected next song had not
changed. Reapplying the same AutoMix/Crossfade settings also invalidated a prepared
cue. A delayed command delivering an already-opened decoder created another small
window in which a repeat request could reopen the same song.

The engine now preserves the same healthy prepared/resolving/command-pending
next track at the same quality. Settings setters are idempotent. A genuinely new
selection or quality still replaces it. Midpoint handoffs retain and execute the
deferred request for the following track.

Analysis now always releases its running marker in `finally`, publishes usable
in-memory evidence independently of disk writes, tolerates persistence/observer
failures and clears the marker if executor submission races with shutdown.
Unusable results may retry after the existing cooldown instead of poisoning an
ID for the entire session. Windowed inference and the upstream resampler also
reduce back-to-back analysis load. These address identifiable failure paths;
they do not establish that every reported intermittent case has the same cause.

Regression coverage includes repeated unchanged queue updates during an active
overlap, **six consecutive tracks with midpoint/deferred queue handoffs**, and
three real DSP analyses despite injected disk and observer failures.

## Windows and Linux validation

Both platforms receive the same planner, model-window orchestration, faders and
queue/analyser lifecycle fixes. Windows' static MinGW runtime linking and DLL
import/package/JNI checks remain intact. Linux retains its `.so`, AppImage and
packaged JNI checks. Ordinary Crossfade remains a separate setting.

Observed locally: all 25 canonical-file hashes match, **71 focused tests passed**,
and the rebuilt native JNI probe passed (120 BPM input measured 120.003 BPM,
ordered downbeats, valid resampled PCM).

The full [CI run 41447022620](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/41447022620)
for implementation commit `d77475ba` passed all four jobs:

| Platform | Focused/core and JNI | Full application regressions | Packaging |
| --- | --- | --- | --- |
| Windows | Passed, including source/model hash identity and MinGW dependency audit | Passed, including unchanged queue updates, six consecutive tracks, and persistence/observer failures | EXE/MSI and portable built; packaged DLL/model/JNI and metadata/helper audits passed |
| Linux | Passed, including source/model hash identity and real JNI analysis | Passed, including the same new multi-track and failure regressions | AppImage built; packaged/extracted `.so` JNI and relaunch/recovery checks passed |

The [Windows package job](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/41447022620/job/125282572667)
succeeded in 10m51s and the
[Linux package job](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/41447022620/job/125282572505)
in 6m35s. These are CI durations, not comparative performance benchmarks.
Windows uploads its portable ZIP; EXE/MSI are built and audited by that workflow
but not uploaded. Linux uploads the AppImage. No release was published.

The six-track regression uses real FFmpeg/decode workers and a collecting PCM
sink with accelerated timing; it validates repeated engine handoffs, not physical
speakers or arbitrary remote playlists. Device/listening checks and actual
Windows installer upgrades remain unverified. The existing native-load fix is
preserved on Windows and Linux.

Local JitPack and GitHub API requests still return HTTP 403. Full-build validation
above comes from the actual OS CI runners, not an unexecuted local command.
The branch is pushed, but PR creation targeting `integration/v1.8.4` remains
blocked by access to `api.github.com`; its updated title/body are prepared.
