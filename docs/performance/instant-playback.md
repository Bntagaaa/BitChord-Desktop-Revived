# Desktop instant playback

Branch: `feature/instant-playback-optimization`, based directly on the fetched
`upstream-1109-port` at `3fd06fd5ad1bf5e6289c21da3d0a72c0068b44e7`.
No updater, memory, smoothness, or unrelated upstream branch was merged.

## Confirmed bottlenecks

- `DesktopApp.playCurrent()` serialized and synchronously flushed queue, cursor,
  and history before `engine.load()`. Large queues make this disk work especially
  expensive. Playback now starts first; one serial background writer coalesces
  snapshots. Queue and cursor travel together. Older writes cannot complete after
  newer writes. Tray quit drains the writer off the UI thread; normal JVM exit has
  a drain hook. Forced termination/power loss can lose the latest pending snapshot.
- `prepareNext()` returned immediately with Crossfade and Automix disabled.
  Ordinary playback now prepares one next track, after current audio submission
  plus a one-second grace period. Add-ons retain their existing late arming policy.
  A normal speculative decoder holds two seconds of PCM; mixing keeps the original
  eight seconds. Selection/handoff expands the speculative buffer back to the normal
  eight-second budget, preserving underrun resilience for the now-current song. Selecting the prepared song consumes that decoder on the audio
  thread. Automatic handoff preserves queued audio and correctly renegotiates
  differing source sample rates. Sleep-after-track, repeat-one and Listen Together
  suppress ordinary prefetch.
- Desktop cached stream URLs, but not the audio ranges that FFmpeg consumes.
  The Android `AudioCache`/Media3 implementation already combines bounded LRU spans
  and next-track warming; shared `StreamResolver` already coalesces URL resolution.
  Desktop now uses the same span-cache idea at its existing windowed transport
  boundary. It does not add a second resolver or Media3 dependency.
- Generation checks before queuing a Start were insufficient when a newer click
  arrived before the audio pump consumed it. Start/Select/Upcoming commands now
  validate their generation when consumed. Native opens are serialized separately
  for foreground and prefetch, are interruptible, and have an FFmpeg interrupt
  callback. The custom range reader also stops canceled requests rather than
  retrying them from another FFmpeg probe. Idle pump dispatch uses a wakeable park.
- Decode-ahead already returns the first block without waiting for its eight-second
  buffer. This was preserved. FFmpeg probing/discovery parameters, codec selection,
  resampling quality, reconnect policy, and WASAPI/Linux output negotiation remain
  in use. Network initialization is now synchronized once; decoder contexts are not
  reused across unrelated sources. Unconsumed queued decoders are closed on shutdown.

## Cache rules and cost

`DesktopAudioCache` stores checksummed, atomically replaced spans of at most 512 KiB
in `bitchord/audio-ranges-v1` under XDG cache home (Linux) or LOCALAPPDATA (Windows).
Spans have a separate 256 MiB LRU budget, including their metadata and orphaned
partial writes. A 1,024-file cap also bounds index/filesystem overhead. Startup
and writes trim the cache, including partial files left by abrupt termination.
Temporary writes can briefly exceed the budget by one span per concurrent writer;
cache I/O failure is a miss and never a playback requirement. Clear song cache
also clears these spans off the UI thread. The existing media-cache slider controls
the separate artwork/motion cache; its description identifies the audio budget.

The key hashes the **entire exact URL**, request headers, window size, song,
configured source and rendition metadata. URLs/headers are never stored as text.
Different signed URLs intentionally do not share spans. This sacrifices reuse when
YouTube resolves a new signature, rather than risking wrong-rendition bytes.
Playback still resolves its source before using cached audio; an expired URL is
not resurrected from disk. Expiry is at most 24 hours and never exceeds the signed
`expire` or origin `max-age` deadline. Authorization/Cookie requests, private,
no-store and Set-Cookie responses bypass cache writes. Unknown mutable origins
must explicitly allow freshness with positive `max-age`; signed googlevideo
renditions require `itag`, `lmt`, `clen` and `expire`. Corruption/partial writes are
misses. Local downloads/offline paths and native HLS/DASH/add-on transports bypass
this cache. Servers ignoring large Range requests fall back to native streaming,
not whole-file JVM buffering.

Extra normal prefetch PCM is roughly 0.67 MiB at 44.1 kHz stereo float, or 2.93 MiB
at 192 kHz, plus one codec/container context and bounded HTTP windows. Cache writes
and hash checks add disk/CPU work when reading a new range. There is no full-song
prefetch, background whole-library worker, decoder pool, or busy-wait. One lazy
persistence thread sleeps between writes. Diagnostic reporting has one lazy daemon
with a bounded queue and is disabled by default. Neither visual effects nor audio
processing features were disabled to obtain these changes.

## Instrumentation

Enable `-Dbitchord.playback.diagnostics=true` in the **application/test JVM**.
Monotonic trace points record click/engine entry, source resolution, native input
opening, stream probing, codec initialization, first PCM and first positive sink
write. Reports include submission proxy latency, queued-output estimate, cache
hits/misses, underruns, JVM heap, process CPU load/time. Output formatting and log
I/O happen on the bounded diagnostics worker. No song, URL, headers or account
values are included. Cached prepared selection has no current-request source/open
stages; those fields report unavailable instead of fabricated zero-duration work.

The UI timestamp is taken at `playCurrent()` entry, not at the operating system's
mouse event. Output timing is measured after a sink write accepts bytes. **This is
not acoustic TTFAA**, nor proof of speaker/Bluetooth/driver latency. Queued output
is only an estimate; OS and hardware latency require loopback/microphone measurement.
Heap metrics are not native/process RSS. The synthetic tests use a collecting sink.

## Recorded results

Final validation: **639 tests passed** (desktop 535, shared 70, sharedUi 34),
zero failures/errors/skips, with both opt-in fixture groups enabled. Seven real
codec/container fixtures decoded and sought successfully. Linux `createDistributable`
and Windows `windowsPortable -Pbitchord.target=windows` both passed. The latter
is a Linux cross-build: `bitchord_analysis.dll` is absent, so it is not an Automix-
capable Windows release or evidence of actual Windows runtime playback.

| Repeated source open → first PCM (9 warm trials) | Baseline | Optimized |
| --- | ---: | ---: |
| Median | 118.9 ms | 31.0 ms |
| p95 (nearest rank) | 127.7 ms | 54.0 ms |
| Origin requests per replay | 2 | 0 |

Median improved 73.9% and p95 57.7% **in this synthetic cached-replay scenario**.
The single first-native-open observations (808.8 / 668.1 ms) are raw samples,
not a cold-start distribution or a justified cold-start improvement claim.
There is no measured end-to-end acoustic or live-source 30–50% claim.

## Reproduction and evidence

`instant-playback/results.json` contains raw trials, nearest-rank p95, suite counts,
platform/build outcomes and unverified scenarios. First native/JVM initialization
is listed separately; it is not a comparable repeated cold-start distribution.

Run the deterministic loopback benchmark (40 ms per HTTP range response, real
FFmpeg, twelve seconds of stereo 44.1 kHz WAV):

```sh
BITCHORD_PLAYBACK_BENCHMARK=1 ./gradlew :desktopApp:test --tests '*DesktopPlaybackBenchmarkTest'
```

The benchmark prints open and first-PCM latency and origin request counts for ten
opens. Trial 0 is the first request; trials 1–9 compare an uncached baseline with a
warm disk-range cache on this branch. Baseline was recorded before implementation
against the base decoder/range reader using the same benchmark source. No cache
lookup or next-track prefetch exists in that baseline. Artificial network latency
is deliberate; this benchmark does not represent live YouTube, all sources,
end-to-end song clicks, or a promised percentage gain on a user's laptop.

Optional codec fixtures (requires FFmpeg executable only to generate the files;
application decoding uses bundled FFmpeg):

```sh
mkdir -p /tmp/bitchord-codecs
ffmpeg -v error -y -f lavfi -i sine=frequency=440:sample_rate=48000 -t 4 -ac 2 /tmp/bitchord-codecs/tone.wav
ffmpeg -v error -y -i /tmp/bitchord-codecs/tone.wav /tmp/bitchord-codecs/tone.flac
ffmpeg -v error -y -i /tmp/bitchord-codecs/tone.wav -c:a libopus /tmp/bitchord-codecs/tone.opus
ffmpeg -v error -y -i /tmp/bitchord-codecs/tone.wav -c:a libmp3lame /tmp/bitchord-codecs/tone.mp3
ffmpeg -v error -y -i /tmp/bitchord-codecs/tone.wav -c:a aac /tmp/bitchord-codecs/tone.m4a
ffmpeg -v error -y -i /tmp/bitchord-codecs/tone.wav -c:a aac -f hls /tmp/bitchord-codecs/tone.m3u8
ffmpeg -v error -y -i /tmp/bitchord-codecs/tone.wav -c:a aac -f dash /tmp/bitchord-codecs/tone.mpd
BITCHORD_PLAYBACK_CODEC_FIXTURES=/tmp/bitchord-codecs ./gradlew :desktopApp:test --tests '*DesktopPlaybackCodecMatrixTest'
```

Without the opt-in environment variables, benchmark/fixture methods return without
running those scenarios. The full local validation explicitly enabled both.
`DesktopInstantPlaybackEngineTest` always runs, decodes real PCM, and injects only
source selection/output collection: rapid skips, obsolete resolutions, native
network-open cancellation, prefetch consumption/cancellation, differing sample
rates, Crossfade, Sleep Timer, seek, pause/resume, speed and output precision.
Cache tests exercise expiry, corruption, LRU, concurrent replacement, range reuse,
source/rendition changes and restart of the disk-cache reader. Persistence tests
hold an old write blocked while submitting a thousand newer snapshots.

## Limits

No physical audio device or Windows runtime is present in this cloud environment.
Linux unit/native decoder results and Windows archive assembly cannot certify
actual WASAPI playback, Bluetooth, device hotplug, live authenticated YouTube/add-on
network recovery, live Automix/beatmatch sound quality, UI click-to-speaker p95,
RSS/CPU equivalence to baseline, or a complete app restart with a new signed URL.
Automix planning/processing regressions are covered by the existing suite; there
is no new live audible Automix benchmark. No regressions were observed in the
executed tests; the unexecuted device/network scenarios remain release validation.

## Files changed

| Files under desktopApp | Purpose |
| --- | --- |
| `DesktopApp.kt` | Playback-first dispatch, queue/cursor snapshot, prefetch guards, off-thread quit/cache clear |
| `DesktopPersistence.kt`, `DesktopPreferenceChunks.kt`, `DesktopPlaybackWrites.kt` | Coalesced serialized persistence, immediate session reads, shutdown barrier |
| `DesktopAudioEngine.kt` | Ordinary prefetch, cancellation/generation ownership, prepared manual/automatic handoffs |
| `DesktopAudioDecoder.kt` | Cancellation-aware native open, one-time network init, cleanup and trace stages |
| `DesktopDecodeAhead.kt` | Small speculative budget expanding on promotion, underrun diagnostics |
| `DesktopAudioSink.kt` | Collecting-output test boundary; native negotiation/write logic retained |
| `DesktopRangeStream.kt`, `DesktopAudioCache.kt` | Bounded HTTP bodies and cache spans, safe native fallback |
| `DesktopPlaybackDiagnostics.kt` | Optional bounded asynchronous performance reports |
| `DesktopInstantPlaybackEngineTest.kt`, `DesktopRangeStreamTest.kt`, `DesktopAudioCacheTest.kt`, `DesktopPlaybackWritesTest.kt` | Engine/transport/persistence regressions |
| `DesktopPlaybackBenchmarkTest.kt`, `DesktopPlaybackCodecMatrixTest.kt` | Reproducible latency/codec measurements |

The companion results JSON records the actual validation rather than implying
that every scenario in the acceptance checklist could run in this environment.
