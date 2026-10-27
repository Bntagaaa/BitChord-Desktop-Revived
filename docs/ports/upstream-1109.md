# Upstream #1109 desktop port

Upstream: https://github.com/kushagrasinghx/BitChord/pull/1109
Pinned head: `1769e349f1bd9fd8786f28d19e4c2a7cf8b27fa2` (the PR is open and can change).
Desktop base: `1.8.3-Perfboost-16085863910554356210`, initially `cfd782a8`, rebased onto `9552d4a4` to retain the newly added raster window icon.
Working branch: `upstream-1109-port`.

## Status

The desktop port includes the native instrumental loop-mix path. It runs through the existing audio pump and output sink; ordinary Automix/crossfade remains the fallback when a safe loop cannot be planned or prepared. This PR stays Draft for real-device and authenticated validation, which the cloud cannot perform.

## Integrated changes

| Upstream feature | Desktop integration |
| --- | --- |
| Top Picks for You | Shared station builder wired to Home, account/session invalidation, release navigation and cancellable station loading. Uses the existing queue and source metadata. |
| Find Your Mood | Home tiles and all-moods grid use the same station/queue path. |
| Home animation lifecycle | Scroll gates from upstream; window focus, expanded player and Reduce Animation gate desktop artwork motion. |
| Music video | One shared silent FFmpeg video deck for visible surfaces. Existing audio engine remains authoritative for playing, pause, seeks, buffering and speed. Quality choices come from the extracted stream. Cleanup is owned by cancellation. |
| Player visual changes | Upstream video layout, fades, two-note instrumental glyph, lyric styles, title overflow, mix popup and artwork tint. Existing desktop hover, Recents list/grid preference, See All routes and centered-lyrics composition local retained. |
| Motion artwork on Top Picks | Desktop artwork providers implement release and artist motion hooks, falling back to still artwork when absent or disabled. |
| Mix visualization | Existing desktop transition plan publishes the actual gain curves and timing. Popup requests bounded, streamed three-band waveform extraction; closing cancels it. Native loop playback publishes a repeated virtual waveform on the renderer clock; it does not advance a source waveform through bars that are repeating. |
| Instrumental loop mix | Shared bar/tempo planner, two native Signalsmith decks inside the existing analysis library, exact-frame takeover and 25 ms handover into the existing live incoming decoder. One audio sink owns output and progress. Pause holds the sink; seek, skip, output changes, mode changes and shutdown cancel/settle ownership. Newly available synced words reject unsafe loop bounds. |
| Beat model and analysis format | New final0 model, opening/ending beat grids, raw vocal-model windows and 10 fps level curves. Native analysis PCM is explicitly resampled to each model's rate; old analyses remain intact and are re-earned in a `loop-v1` cache namespace. |
| Tempo range and AutoPlay | Incoming beatmatch supports 0.90–1.25x with gradual return to listener speed. The existing AutoPlay request path retries every 15 seconds while playing at the queue tail, using earlier seeds when a radio is exhausted. |
| Direct Source Stream | Opt-in setting reuses ranked source resolution and the existing engine, bounded preferred-source wait, YouTube fallback, no later upgrade in that mode, 25-second next-track arming. Inactive in parties or with YouTube disabled. |
| Source matching | Upstream shared Portuguese-credit normalization and matching changes; addon call/read ceilings increased. |
| Custom sleep timer | Existing timer accepts menu-entered 5–720 minutes, with input validation and existing preset/end-of-track controls. |
| Offline playlists | Local songs use the existing playlist picker and existing persisted playlist records; no second playlist store. |
| Remove from history | History-only menu action; account deletion requires the parser's explicit DELETE feedback token. Local history changes only after account success; failed deletion leaves rows intact. |
| Replay album grouping | Stable album ID first, album + artist fallback; existing single Unknown Album row retained. |
| Korean | Upstream catalogue, desktop language entry and translated desktop-only catalogue. |
| Simplified Chinese region | Shared locale mapping uses SG for the YouTube region. |
| Blur compatibility | Newly touched mesh blur observes the existing master override. Native Windows title-bar material is not replaced. |

Android-only Bluetooth routing, Wear output, Media3 sink integration, system-bar and Android Liquid Glass APIs are not desktop drivers. Platform-neutral/shared visual changes are integrated; existing Android host implementations retain compatible defaults for newly added optional host state. Android compatibility is covered by the PR CI jobs.

## Validation

Local command (environment launcher selects JDK 21 and proxy/trust-store configuration):

```sh
DISPLAY=:99 XDG_CACHE_HOME=/workspace/.cache \
python3 /workspace/.cloud-setup/gradle1109.py \
  -I /workspace/.cloud-setup/test-runtime.gradle \
  :shared:jvmTest :sharedUi:jvmTest :desktopApp:test \
  :desktopApp:createDistributable -Pbitchord.version=test-visua --continue
```

The final local compatibility run passed 70 shared, 34 shared UI and 481 desktop tests (585 total), with no failures/skips, plus Linux packaging. CI status is recorded in the PR. Tests were retained: a Replay regression and missing Korean resources were fixed in production data/code, not hidden by altering assertions.

New desktop regression coverage exercises timer limits and malformed input, album identity across changed credits, local album separation/unknown albums, video clock extrapolation at different playback speeds and buffering, and native recorded-image lifetime. Desktop native tests additionally cover requested-rate region decoding, cancellation/reopening, raw vocal-model inference, incomplete region rejection, repeated renderer creation/disposal, buffer bounds, finite synthetic output and final incoming track alignment. Tempo tests cover both ends of the 0.90–1.25x range. Shared upstream loop-planner tests also run. These tests do not certify device audio.

## Playback compatibility

Native loops require stereo, listener speed 1x, skip silence off, measured beat/vocal windows and source durations within 0.5 seconds of the analysed recording. Video-origin tracks and Listen Together parties do not enter this path. A missing model, unsuitable instrumental bars, stale preparation, lyric conflict or preparation failure preserves the existing transition path. This keeps the existing source resolver, queue, EQ, spatial processing, silence skipping and output selection authoritative rather than creating a competing player. The renderer owns decoded regions only; it never seeks the outgoing live decoder during preparation. Publication/cancellation is synchronized, and only the audio thread closes an active renderer.

## Manual validation still required

- Exercise native loop takeover and handoff with real music and output devices on Windows/Linux, including pause, seek before/after midpoint, skip, source upgrades, output changes and shutdown. Synthetic tests cannot establish audible quality, seamlessness or real hardware clock behavior.
- Verify authenticated Top Picks, station loading/account changes and history deletion against a real account.
- Exercise video quality changes, seek, pause, source upgrades, long tracks and A/V sync on Linux and Windows; test cancellation while network/native reads are pending.
- Verify visible player changes, RTL, centered lyrics, artist navigation, motion-artwork lifecycle, all master-blur states and native Mica on target desktops.
- Check real playback, gapless, crossfade, Automix, EQ and parties. The cloud has Xvfb/software rendering, no real audio device or account; headless tests cannot certify these behaviors.

An isolated packaged Xvfb smoke run rendered the welcome screen, accepted Skip, opened Settings, found the Direct source stream toggle through search, scanned a generated local WAV, and opened its song menu and custom timer dialog. It does not establish real device playback or video synchronization. Android CI exposed a removed public PlayingAccent constant; its original API/value has been restored. The shared player now observes the existing desktop master blur/animation flows directly, with regression coverage.
