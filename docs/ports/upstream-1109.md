# Upstream #1109 desktop port

Upstream: https://github.com/kushagrasinghx/BitChord/pull/1109
Pinned head: `1769e349f1bd9fd8786f28d19e4c2a7cf8b27fa2` (the PR is open and can change).
Desktop base: `1.8.3-Perfboost-16085863910554356210`, initially `cfd782a8`, rebased onto `9552d4a4` to retain the newly added raster window icon.
Working branch: `upstream-1109-port`.

## Status

**Work in progress. Do not merge yet.** The native instrumental loop-mix renderer is not yet connected to the desktop audio pump. The shared loop planner, regression tests and upstream native sources are present; those alone do not enable loop playback. Existing desktop Automix continues to run its established transitions. No claim of a completed full port is made.

## Integrated changes

| Upstream feature | Desktop integration |
| --- | --- |
| Top Picks for You | Shared station builder wired to Home, account/session invalidation, release navigation and cancellable station loading. Uses the existing queue and source metadata. |
| Find Your Mood | Home tiles and all-moods grid use the same station/queue path. |
| Home animation lifecycle | Scroll gates from upstream; window focus, expanded player and Reduce Animation gate desktop artwork motion. |
| Music video | One shared silent FFmpeg video deck for visible surfaces. Existing audio engine remains authoritative for playing, pause, seeks, buffering and speed. Quality choices come from the extracted stream. Cleanup is owned by cancellation. |
| Player visual changes | Upstream video layout, fades, two-note instrumental glyph, lyric styles, title overflow, mix popup and artwork tint. Existing desktop hover, Recents list/grid preference, See All routes and centered-lyrics composition local retained. |
| Motion artwork on Top Picks | Desktop artwork providers implement release and artist motion hooks, falling back to still artwork when absent or disabled. |
| Mix visualization | Existing desktop transition plan publishes the actual gain curves and timing. Popup requests bounded, streamed three-band waveform extraction; closing cancels it. This is separate from unfinished native loop playback. |
| Beat model and analysis format | New final0 model asset and shared analysis schema/planner updates. |
| Direct Source Stream | Opt-in setting reuses ranked source resolution and the existing engine, bounded preferred-source wait, YouTube fallback, no later upgrade in that mode, 25-second next-track arming. Inactive in parties or with YouTube disabled. |
| Source matching | Upstream shared Portuguese-credit normalization and matching changes; addon call/read ceilings increased. |
| Custom sleep timer | Existing timer accepts menu-entered 5–720 minutes, with input validation and existing preset/end-of-track controls. |
| Offline playlists | Local songs use the existing playlist picker and existing persisted playlist records; no second playlist store. |
| Remove from history | History-only menu action; account deletion requires the parser's explicit DELETE feedback token. Local history changes only after account success; failed deletion leaves rows intact. |
| Replay album grouping | Stable album ID first, album + artist fallback; existing single Unknown Album row retained. |
| Korean | Upstream catalogue, desktop language entry and translated desktop-only catalogue. |
| Simplified Chinese region | Shared locale mapping uses SG for the YouTube region. |
| Blur compatibility | Newly touched mesh blur observes the existing master override. Native Windows title-bar material is not replaced. |

Android-only Bluetooth routing, Wear output, Media3 sink integration, system-bar and Android Liquid Glass APIs are not desktop drivers. Platform-neutral/shared visual changes are integrated; existing Android host implementations retain compatible defaults for newly added optional host state. Android build validation remains required.

## Validation

Local command (environment launcher selects JDK 21 and proxy/trust-store configuration):

```sh
DISPLAY=:99 XDG_CACHE_HOME=/workspace/.cache \
python3 /workspace/.cloud-setup/gradle1109.py \
  -I /workspace/.cloud-setup/test-runtime.gradle \
  :shared:jvmTest :sharedUi:jvmTest :desktopApp:test \
  :desktopApp:createDistributable -Pbitchord.version=test-visua --continue
```

The final local compatibility run passed 70 shared, 34 shared UI and 476 desktop tests (580 total), with no failures/skips, plus Linux packaging. CI status is recorded in the PR. Tests were retained: a Replay regression and missing Korean resources were fixed in production data/code, not hidden by altering assertions.

New desktop regression coverage exercises timer limits and malformed input, album identity across changed credits, local album separation/unknown albums, video clock extrapolation at different playback speeds and buffering, and native recorded-image lifetime. Shared upstream loop-planner tests run, but do not test desktop loop rendering or device audio.

## Remaining before a complete port

- Connect native loop rendering to the desktop's single audio sink, with sample-accurate takeover, handoff, pause/seek/skip cancellation and source ownership; complete native tests. The current PR must remain Draft until this is implemented and reviewed.
- Verify authenticated Top Picks, station loading/account changes and history deletion against a real account.
- Exercise video quality changes, seek, pause, source upgrades, long tracks and A/V sync on Linux and Windows; test cancellation while network/native reads are pending.
- Verify visible player changes, RTL, centered lyrics, artist navigation, motion-artwork lifecycle, all master-blur states and native Mica on target desktops.
- Check real playback, gapless, crossfade, Automix, EQ and parties. The cloud has Xvfb/software rendering, no real audio device or account; headless tests cannot certify these behaviors.

An isolated packaged Xvfb smoke run rendered the welcome screen, accepted Skip, opened Settings, found the Direct source stream toggle through search, scanned a generated local WAV, and opened its song menu and custom timer dialog. It does not establish real device playback or video synchronization. Android CI exposed a removed public PlayingAccent constant; its original API/value has been restored. The shared player now observes the existing desktop master blur/animation flows directly, with regression coverage.
