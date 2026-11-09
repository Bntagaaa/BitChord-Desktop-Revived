# Playback implementation and parity evidence

Source: branch `1.9.2`, commit
`348b613a0c3db1a54de56483ea6f8748d6465211`. The Flutter work is an independent
desktop target under `flutterApp`, with source queue and musical-policy behavior
ported where specified below.

## Executable playback path

`PlaybackController` is a `ChangeNotifier` and owns the complete decoder
lifecycle. `MediaKitBackend` uses `media_kit`/libmpv for actual local or remote
audio and video. The UI does not create an audio player when a sheet, lyric
view or video surface opens. The controller accepts an `AudioBackendFactory`
for deterministic tests; the application default constructs real native players.

| Source behavior | Dart implementation | Status and limits |
| --- | --- | --- |
| Queue entry identity independent of recording ID | `queue_timeline.dart` | Duplicate recordings have different immutable entry IDs. |
| User queue, context and autoplay tiers | `queue_timeline.dart` | Play next, append, forward jump, consumed manual entries, shuffle/undo and active-row-safe edits ported from `DesktopQueue.kt`/`QueueTimeline.kt`. History retains 25 preceding entries. |
| Play/pause, seek, next/previous, repeat | `playback_controller.dart` | Native commands; natural completion respects repeat-one while manual Next advances. Three-second previous/restart behavior and generation guards included. |
| Restore state | `playback_snapshot.dart` | Queue metadata, position, volume, repeat, shuffle, rate, EQ and video preference are serializable. Restore is paused and allocates no decoder until play. Signed URLs/headers are not persisted. |
| Radio/autoplay | `playback_controller.dart` | Asynchronous repository callback, deduplication by recording ID, 30 candidates per radio result, stale response guard and completion waiting. |
| Music-only preference | `songNormalizer` callback | Looks up active/next entries lazily, preserving entry ID, tier, source and `isVideoOrigin`. Normalization cannot replace a newer selection. |
| Stream lookup | `stream_resolver.dart` | Local file existence checks, configured alternate-provider callback, authenticated Innertube direct HTTPS formats, then `youtube_explode_dart` extraction. Encrypted signatures are delegated to the extractor. |
| Stream quality and download metadata | `ResolvedStream` | Codec facts, provider quality, container, headers, URL expiry, content length and provider download permission remain distinct. Container controls download extension. Dynamic audio bitrate cap invalidates cached requests. |
| Bounded remote transport | `range_media_proxy.dart` | Googlevideo streams use token-protected loopback transport with 512 KiB upstream ranges, 2 MiB maximum configurable window, backpressure, exact range validation and cancellation on deck disposal. |
| Audio route, volume and playback rate | `MediaKitBackend` | Actual native device stream/selection, volume and rate commands. Hardware-specific device and route behavior still needs native acceptance testing. |
| Equalizer | `equalizer.dart` | Source seven band centers, shelf endpoints, tone-pad curve, presets and calculated preamp headroom; native FFmpeg filter graph through libmpv. Tone-pad mode is not yet persisted as a distinct session setting. |
| Ordinary crossfade | Controller staged deck | Equal-power gain envelope, muted preparation, commit-after-handoff, pause/seek/queue-edit cancellation and outgoing volume restoration. This is labeled **crossfade**, not AutoMix. |
| Audio/video separation | Independent silent native video deck | Audio remains the clock; video follows pause/rate/seek and corrects drift. Toggling/replacing the video decoder does not recreate audio. This is not a claim of zero drift on every GPU. |
| Sleep timer | Controller | Countdown and end-of-track pause stop the audible session before queue advancement. |
| Resource ownership | Controller/backend | Active, staged, opening and video players, subscriptions, timers and loopback transports close explicitly. New requests invalidate old logical generations. |

There is one staged audio deck at most. URL and manifest caches each retain at
most eight entries by default, and expired URLs are refreshed. Current and next
stream lookup is separate from loading the complete queue's metadata; the
controller never downloads a whole track into a Dart byte array to play it.
Pending extractor HTTP work cannot be individually aborted through the package's
public API: late results are rejected by logical generations and requests have
timeouts; closing an owned resolver closes its client. This distinction matters
when measuring rapid skip/network cancellation behavior.

## AutoMix: policy port and remaining renderer gap

`playback/smart/track_analysis.dart`, `transition_policy.dart`, and
`transition_planner.dart` port the corresponding `1.9.2` shared Kotlin sources.
The original Orchard/BitChord AGPL notices remain on these files. The pure
planner preserves confidence tiers, octave-aligned tempo bounds, the asymmetric
0.9–1.25 incoming rate window, audible-content skip budgets, vocal overlap
checks, low-frequency structure, 8/16-bar phrase lengths, mix-window bounds,
spoken/live exclusions, same-album policy and explicit fallback reasons.
`beat_aware_transition.dart` ports render-clock seam placement and the late
decoder downgrade to ordinary crossfade.

These policies are executable and tested against fixtures ported from
`AdvancedAutomixPlannerTest`, `PhraseBarAutomixTest`, `SpokenOrLiveTest` and
`BeatAwareTransitionTest`. They are **not** connected to a fabricated analysis
provider. The Flutter native path does not yet port the source native beat/key/
vocal analysis, source PCM transition renderer, Signalsmith/WSOLA time stretching,
LoopMix renderer, vocal separation models, or their native cache. Full AutoMix
parity therefore remains incomplete, and the active crossfade control does not
advertise those capabilities.

Other source playback features still requiring separate implementation and
native verification include bit-perfect/exclusive output policy, source-specific
spatial/Atmos rendering, replay-gain/normalization parity, silence skipping,
transactional manual audio/video-version replacement during an already audible
track, and hardware media/route acceptance across every target OS. Codec metadata
alone is not proof of an Atmos or bit-perfect output path.

## Verification performed

With package dependencies resolved, the scoped Dart analyzer type-checked the
real native package APIs. The pure playback suites executed through a native
Dart AOT test runner with **44 passing tests and zero failures** in the
implementation environment. The suites cover:

- Queue duplicates, tiers, context replacement, forward jumps, manual-entry
  consumption, shuffle/undo, removal/reordering and bounded history.
- Equalizer band geometry, filter values, clipping headroom and equal-power gains.
- Direct player response selection, container-based extensions, video caps,
  cipher rejection, snapshot control bounds, and refusing to send unavailable
  external-source identifiers to the YouTube account/extractor.
- Actual local HTTP sockets for byte-exact bounded range responses and rejection
  of upstream servers that ignore the range limit.
- The source AutoMix policy fixtures and late-decoder fallback listed above.

`playback_controller_test.dart` additionally contains injected-backend regression
tests for paused restoration, late stream resolution, disposal during native
open, pause/seek while resolving, automatic/manual repeat semantics, queue edits,
sleep-after-track, persistence ordering, error redaction and lazy music-only
normalization. Those Flutter-dependent cases must be run by normal Flutter CI;
the isolated pure result does not claim they ran.

### Reproduce normal validation

From `flutterApp`:

```sh
flutter pub get --enforce-lockfile
flutter analyze --no-pub
flutter test test/playback --no-pub --reporter expanded
```

### Managed-environment AOT fallback used in this session

Normal Flutter test startup in the managed implementation environment failed
**before executing a test**: the `objective_c-9.6.3` native-asset `build.dart`
hook invoked its compiled `hook.dill` in Dart JIT, exited 255, and produced empty
stderr/stdout. The AOT Flutter command itself was usable. Production dependencies
and hooks were not patched or bypassed in the committed application.

The checked-in helper is a narrower, explicit fallback:

```sh
python3 test/playback/run_pure_aot.py \
  --dart /path/to/flutter/bin/cache/dart-sdk/bin/dart
```

It copies the five pure suites to a temporary directory outside the package,
changes only their `flutter_test` import to the same `matcher/expect.dart` and
`test_api/scaffolding.dart` exports, and retains every assertion. It resolves
the existing lockfile's package paths to absolute URIs, creates `Declarer`/
`Suite`/`LiveTest` runners from `test_api`, and runs:

```sh
dart compile exe --packages=package_config.json main.dart -o playback_tests
./playback_tests
```

No mock matcher or alternative implementation under test is substituted.
The helper deliberately cannot validate Flutter widgets, audio hardware,
multi-window engines, native video, or the full controller suite. The ordinary
Flutter workflow remains the required test gate. Authenticated YouTube stream
availability and real audio/video output have not been established by the
pure-AOT result.

See `platform-support.md` for native dependencies, per-platform build outputs,
secondary-engine registration, macOS entitlement choices and the separate CI
workflow.
