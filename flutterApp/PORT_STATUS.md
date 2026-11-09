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
| `lib/data/lyrics.dart` | LyricsQuery.kt, LrcLib.kt, LyricGaps.kt | Recording-title cleanup, exact LRCLIB/fallback duration match, LRC fractional timing and four-second gap threshold |
| `lib/data/stream_resolver.dart` | Original InnerTubeX v0.7.0 VISIONOS_0_1 profile and StreamResolver.kt | Direct anonymous audio formats, real audio-byte probe beyond 1 MiB; no invented URL or media IDs |
| `lib/ui/theme.dart`, pubspec fonts | DesktopTheme.kt, BitChordTypography.kt, DesktopFontSettings.kt | Original color tokens, logical-to-physical font cuts, Default/Legacy and Pitch Black rules |
| `lib/ui/shell.dart` | DesktopApp.kt frame/sidebar/dock | Initial Flutter presentation using the 72/220 dp rail, 980 dp breakpoint, 58 dp dock and original assets |
| `lib/ui/player.dart` | LandscapePlayer.kt/Columns.kt/LyricsFrame.kt | Initial cover bounds, bounded queue, 2-second metadata reveal, original transition durations and lyric edge fades |

`tool/source_manifest.json` records the 420 Kotlin files inventoried from the source and hashes of copied original assets. An inventory is not a claim that all those files have already been ported. Production has no seeded catalog, fabricated lyric fallback, fake track identifiers, simulated playback ticker or demo account.

## Major work still required

- Complete the original InnerTubeX automatic catalog, content eligibility, EJS/cipher/n-transform, PoToken minting, authenticated extraction, SABR/UMP and NewPipe fallback. The direct profile above is not an equivalent substitute. Many tracks may fail until these are ported.
- Port the original native FFmpeg/DSP playback behavior, Automix/ONNX, crossfade/Loopmix, output precision/devices, EQ, silence/speed processing and stream recovery. The current native media_kit adapter provides real local/direct-stream audio, **not** parity with the original engine.
- Complete every renderer/continuation/header/search promotion, playlist edit/library feeds, account and brand-profile switching, login, history telemetry, local playlists, local metadata/artwork, scanner/watchers, persistence migration and download management.
- Port remaining lyrics providers/order, enhanced word timing, translation/romanization and lyrics settings.
- Complete Spotify/import/matching/Canvas, Listen Together, replay statistics, scrobbling, Discord/addons, system media controls, notifications, tray, PiP/video, updater and all remaining settings.
- Match every screen, hover/context-menu/dialog state, responsive layout, typography, dynamic artwork mesh/Haze layering and animation to native-source screenshots. Current Flutter screenshots have not established 1:1 visual parity. Remaining generic Material controls must be replaced with the source presentation.
- Restore original three-tier jump/autoplay/party queue semantics in all actions; current sequential playback and queue selection are an initial subset.

Settings currently use a separate Flutter namespace, leaving original Java Preferences/accounts untouched. Original-user-data import is not implemented; this is not yet data compatibility.

## Build and validation

Pinned Flutter 3.35.7; run from `flutterApp/`:

```
flutter create --platforms=linux,windows --org=com.music --project-name=bitchord --no-pub .
flutter pub get
dart format lib test
flutter analyze --fatal-infos --fatal-warnings
flutter test
flutter build linux --release
# On Windows:
flutter build windows --release
```

The branch-only workflow runs analysis/tests and native Linux/Windows builds. Results must be read before claiming they pass. Native sound on a physical output and live account/stream integration remain unverified. Test fixtures are confined to `test/` and never supply app data.

The current cloud executor has no Flutter SDK and explicitly denies its official storage.googleapis.com download endpoint. The required domain has been saved to the environment draft, which does not apply itself. CI can independently validate on GitHub-hosted runners while that access is pending.
