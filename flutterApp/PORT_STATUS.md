# Flutter port — implementation in progress

Source of truth: BitChord `1.9.2`, immutable commit `348b613a0c3db1a54de56483ea6f8748d6465211`.
Implementation branch: `1.9.2-flutter-reworked`.
No source from another BitChord Kotlin or Flutter experiment is used.

This branch is **not a finished 1:1 port**. Do not label it production-ready or merge it as a complete replacement.

## Implemented source mappings

| Dart | Original 1.9.2 source | Scope |
| --- | --- | --- |
| `lib/domain/music.dart` | shared Models.kt | Track/card/shelf/page/filter data |
| `lib/data/innertube.dart` | Innertube.kt | WEB_REMIX requests, shell client context, account-derived SAPISID signing, browse/search/continuations/rating |
| `lib/data/renderer_parser.dart` | InnertubeParser.kt | Responsive rows, fixed-column runtime, MPED watch identities, two-row cards and shelves |
| `lib/domain/queue.dart` | QueueTimeline.kt | Context/manual queue preservation, stable entry identities, sectioned shuffle |
| `lib/data/local_music.dart`, `lib/data/local_artwork.dart` | DesktopLocalMusic.kt, DesktopLocalArtwork.kt | File identities/name credits, four-level scan, opt-in non-music filtering, FLAC PICTURE/ID3 APIC/M4A covr and case-insensitive folder covers, bounded worker reads and fingerprinted image cache |
| `lib/data/lyrics.dart`, `lib/data/lyric_formats.dart`, `lib/domain/lyrics.dart`, `lib/domain/lyrics_session.dart` | LyricsQuery.kt, LrcLib.kt, EnhancedLrc.kt, TtmlLyrics.kt, ProviderLyrics.kt, LyricLine.kt, SyllableRuns.kt, LyricGaps.kt, LyricAlignments.kt, BackgroundVocals.kt, DesktopLyricsSession.kt | LRCLIB requests; source word/syllable timing, held-word animation mathematics, duet/backing voices, TTML/A2/QRC/YRC/document envelopes; selected-track duration and stale-result guards. The richer model is not yet fully rendered or connected to the full provider suite. |
| `lib/domain/lyric_clock.dart` | LyricClock.kt, LyricFocus.kt | Original dated-reading slew/glide/rate-learning arithmetic and overlapping-vocal focus; behavioral tests. Frame-clock integration in the Flutter panel remains required. |
| `lib/data/artwork_blur.dart`, `lib/ui/artwork_backdrop.dart` | ArtworkMeshBackdrop.kt / NowPlayingScreen.kt | Landscape full-cover background: 128px decode, original three clamped box passes on a worker, eight-entry LRU, owned image references, 320ms fade and original legibility scrim. Portrait seam mesh/Legacy mesh/PiP remain required. |
| `lib/data/stream_resolver.dart` | Original InnerTubeX v0.7.0 VISIONOS_0_1 profile and StreamResolver.kt | Direct anonymous audio formats, real audio-byte probe beyond 1 MiB; no invented URL or media IDs |
| `lib/ui/theme.dart`, pubspec fonts | DesktopTheme.kt, BitChordTypography.kt, DesktopFontSettings.kt | Original color tokens, logical-to-physical font cuts, Default/Legacy and Pitch Black rules |
| `tool/font_metadata.py`, derived Bold asset | DesktopFontMetadata.kt | Correct the bundled Bold cut's OS/2 classification from 600 to 700, preserve every outline/metric table and recompute SFNT checksums. Raw upstream assets remain unchanged. |
| `lib/ui/shell.dart` | DesktopApp.kt frame/sidebar/dock | Initial Flutter presentation using the 72/220 dp rail, 980 dp breakpoint, 58 dp dock and original assets |
| `lib/ui/player.dart` | LandscapePlayer.kt/Columns.kt/LyricsFrame.kt | Initial cover bounds, bounded queue, 2-second metadata reveal, original transition durations and lyric edge fades |

`tool/source_manifest.json` records the 420 Kotlin files inventoried from the source and hashes of 25 copied original assets. An inventory is not a claim that all those files have already been ported. Production has no seeded catalog, fabricated lyric fallback, fake track identifiers, simulated playback ticker or demo account. Downloads and Spotify explicitly report unavailability; they do not pretend a real library fetch returned an empty result.

## Major work still required

- Complete the original InnerTubeX automatic catalog, content eligibility, EJS/cipher/n-transform, PoToken minting, authenticated extraction, SABR/UMP and NewPipe fallback. The direct profile above is not an equivalent substitute. Many tracks may fail until these are ported.
- Port the original native FFmpeg/DSP playback behavior, Automix/ONNX, crossfade/Loopmix, output precision/devices, EQ, silence/speed processing and stream recovery. The current native media_kit adapter provides real local/direct-stream audio, **not** parity with the original engine.
- Complete every renderer/continuation/header/search promotion, playlist edit/library feeds, account and brand-profile switching, login, history telemetry, local playlists, local date/sort metadata and download-artwork reuse, filesystem watchers, persistence migration and download management.
- Connect the remaining lyrics providers/order, word/syllable renderer, translation/romanization, embedded lyrics and lyrics settings. The pure timing model and document parsers are ported; the complete network/provider and visual pipeline is not.
- Complete Spotify/import/matching/Canvas, Listen Together, replay statistics, scrobbling, Discord/addons, system media controls, notifications, tray, PiP/video, updater and all remaining settings.
- Match every screen, hover/context-menu/dialog state, responsive layout, typography, dynamic artwork mesh/Haze layering and animation to native-source screenshots. Current Flutter screenshots have not established 1:1 visual parity. Remaining generic Material controls must be replaced with the source presentation.
- Complete party/autoplay queue orchestration and persistence. Context/manual jump ordering, identity-preserving promotion and sectioned shuffle are ported, but the party service and autoplay generation are not.

Settings currently use a separate Flutter namespace, leaving original Java Preferences/accounts untouched. Original-user-data import is not implemented; this is not yet data compatibility.

## Build and validation

Pinned Flutter 3.35.7; run from `flutterApp/`:

```
flutter pub get
dart format --output=none --set-exit-if-changed lib test integration_test
flutter analyze --fatal-infos --fatal-warnings
flutter test
flutter build linux --release
# On Windows:
flutter build windows --release
```

Standard Linux/Windows runners generated by that exact SDK are checked in, with the original Windows icon. CI builds the committed runners directly. Native dependencies on Ubuntu: clang, cmake, ninja-build, pkg-config, libgtk-3-dev, libasound2-dev and libmpv-dev; the GUI/audio checks additionally use Xvfb, xdotool, ImageMagick, Tesseract and a PulseAudio null sink. Windows builds use Visual Studio 2022 desktop C++ tools.

The branch-only workflow runs analysis/tests and native Linux/Windows builds. Results must be read before claiming they pass. Native sound on a physical output and live account/stream integration remain unverified. Test fixtures are confined to `test/` and never supply app data.

The current cloud executor has no Flutter SDK and explicitly denies its official storage.googleapis.com download endpoint. The required domain has been saved to the environment draft, which does not apply itself. CI can independently validate on GitHub-hosted runners while that access is pending.

## Verified progress

CI run [42605375803](https://github.com/Bntagaaa/BitChord-Desktop-Revived/actions/runs/42605375803), commit 63dd1750, passed strict committed-source checks (no unapplied format/fix changes), zero analyzer errors/warnings/infos, 35 unit/widget tests, Linux/Windows native release builds and native smoke/audio checks. It covers the original lyric-clock arithmetic, word/syllable model, TTML/A2/QRC/YRC parsers, track-scoped lookup, local scanner/artwork, Bold metadata correction, backdrop worker and real-image cache lifecycle tests. Subsequent changes must pass the branch workflow independently.

Linux rendered Settings/Theme/Font were verified through an actual X11 window; Windows showed a native window. The native PCM integration test checks decoded duration, advancing playhead, seek and pause. A subsequent CI monitor check also requires actual 440 Hz PCM output from the test fixture, rejecting silence and a wrong frequency; its result is stored as native-audio-output.json. Linux audio uses a PulseAudio virtual sink; no physical-speaker audibility claim is made. The WAV fixture exists only in integration_test and is never offered as application music. These checks do not establish live YouTube extraction or full visual/feature parity.

The source manifest validator confirms original Kotlin/assets remain unchanged, and reviewed formatting/package-lock output from the real CI Dart SDK has been applied. The font validator independently checks all SFNT checksums and outline/metric preservation. The local cloud SDK and live network tests remain blocked until the environment network draft is applied.
