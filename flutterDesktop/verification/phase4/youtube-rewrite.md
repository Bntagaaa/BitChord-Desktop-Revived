# YouTube-only rewrite: evidence and limits

The user reports one successful video followed by HTTP 403. This is intermittent
playback, not acceptance. UI, JioSaavn/add-ons and the native engine are unchanged.

## Reproducible checks

```sh
dart format --output=none --set-exit-if-changed lib test
dart run tool/phase4_youtube_contracts.dart
dart run tool/youtube_rewrite_check.dart
python tool/test_youtube_runtime.py <yt-dlp> <plugins-dir> <server-dir>
dart run tool/phase4_bundled_live.dart <release-bundle-dir>
```

The real packaged CLI audit checks separate extractor namespaces, the script
provider and verified-session stdin transport with synthetic data and no network.
Its temporary audit extractor is never shipped. Existing catalog/session fixtures
and 43 rewrite assertions passed locally with Dart 3.13.7. Targeted analyzer and
format passed. The local complete native suite was blocked by libavformat.so.58;
full Windows/Linux regression and actual live playback still require evidence.

## Required live acceptance

Multiple distinct tracks/videos, replay, rapid select/cancel, seek near the end,
long playback and URL expiry, disconnect/reconnect, restart, authorized primary
account playback, and expected refusal of unavailable content. Record only fixed
categories/counts and native output, never signed URLs, tokens or account cookies.
The live CI probe now attempts three selections plus same-rendition renewal; a
hosted bot restriction is a failure, not a fake success or an alternative source.

Primary reference: Kotlin 348b613a StreamResolver, PlayerClient, InnertubeParser,
DesktopYouTubeAuth and DesktopYouTubeSession. Deployment configuration verified
against upstream:
- https://github.com/Brainicism/bgutil-ytdlp-pot-provider#usage
- https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide
- https://github.com/yt-dlp/yt-dlp#developing-plugins

Phase 4 remains INCOMPLETE. No error-free guarantee. Live/HLS/MPD/SABR,
delegated-account media, browser sign-in and persistent account protection are
not claimed by this direct-file transport rewrite.
