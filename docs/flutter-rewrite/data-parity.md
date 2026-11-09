# Flutter data and domain parity

Source baseline: branch `1.9.2`, commit `348b613a0c3db1a54de56483ea6f8748d6465211`. This document describes implemented data behavior and remaining differences. It does **not** claim that the full Flutter rewrite has achieved complete source parity.

## Implementation map

| Source responsibility | Flutter implementation | Implemented behavior |
| --- | --- | --- |
| `shared/.../data/model/Models.kt` | `flutterApp/lib/domain/models.dart` | Immutable catalogue/queue models, separate queue occurrence and recording IDs, playlist set-video IDs, local metadata, search filters, typed pages, lyric words and timing. |
| `shared/.../innertube/Innertube.kt` | `data/innertube_client.dart` | Real WEB_REMIX requests; live client-version/visitor/session metadata; SAPISIDHASH; delegated account selection; anonymous typeahead; bounded responses/timeouts; read retries; no mutation retries. |
| `shared/.../innertube/InnertubeParser.kt` | `data/innertube_parser.dart` | Renderer parsing for search, shelves, albums, playlists, artist pages, watch queues, account menus and continuation tokens. Browse targets take precedence over play overlays. |
| `shared/.../data/YtMusicRepository.kt` | `data/music_repository.dart` | Home, Explore, new releases, filtered search, suggestions, detail/continuation, radio, library/history, account/channel enumeration, likes, library actions, subscriptions and playlist CRUD. |
| `desktopApp/.../DesktopPersistence.kt`, `DesktopPreferenceChunks.kt` | `data/local_store.dart`, `data/legacy_profile.dart` | Separate versioned JSON profile; atomic replacement and backup; serialized writes; explicit read-only legacy XML migration; real persisted library, local playlists, queue, history, settings and downloaded metadata. |
| Desktop authentication/cookie import | `data/auth_store.dart`, `data/cookie_input.dart` | Explicit Cookie-header/Netscape import, live account validation, secure OS vault, restore/sign-out, strict origin handling for exported cookies. |
| Shared lyrics clients/parsers | `data/lyrics_service.dart` | Nine real provider adapters; LRC, enhanced LRC, TTML, QRC/YRC and structured LyricsPlus timing; provider order/enabled selection; translation and romanization. |
| `shared/src/jvmMain/.../spotify/SpotifyImporter.kt` | `data/spotify_importer.dart`, `data/song_matcher.dart` | Public Spotify playlist extraction, continuation beyond embed preview, bounded parallel catalogue matching, ordered results, explicit unmatched entries/progress/cancellation. |
| `desktopApp/.../DesktopAddon.kt` | `data/addon_client.dart`, `data/addon_registry.dart` | Real HTTP manifest/search/stream protocol; secure URL storage; enable/remove/reorder; default settings/quality negotiation; source matching; manifest download policy; 429 quiet period; bounded caches. |
| `desktopApp/.../DesktopJioSaavn.kt` | `data/jiosaavn_client.dart` | Real search/song-details endpoints, recording matching, source metadata, legacy DES CDN-URL decoding, advertised 320 kbps selection and explicit-version ordering. |

Paths abbreviated with `...` refer to the matching package under the source tree. Flutter paths beginning `data/` are relative to `flutterApp/lib/`.

## API and integration contracts

`MusicRepository` returns real source data. It exposes `home`, `explore`, `newReleases`, `search`, `searchSuggestions`, `browse`, `songPages`, `allSongs`, `radio`, `radioForSong`, `watchQueue`, `library`, `history`, `account`, `accountChannels`, `moodAndGenres`, `lyrics`, and `translateLyrics`. Pagination uses real continuation tokens with repeated-token guards. Library collection and playlist-picker feeds continue beyond the first response. Liked-song pagination is exposed separately through `LibraryPage.likedContinuation`. `radioForSong` preserves a YouTube recording's exact identity and uses one bounded, recording-matched catalogue lookup for a local/addon/JioSaavn track before starting radio; unmatched tracks return a real failure.

`Song.videoId` identifies a recording. `queueEntryId` identifies an occurrence in a queue. `setVideoId` identifies a particular entry in a YouTube playlist; removing a duplicate track must use that entry ID, not merely the recording ID. `Song.isYouTube` requires an 11-character YouTube identifier and no local file reference. Addon and JioSaavn IDs remain namespaced and must not be sent as YouTube IDs. Responsive shelf items retain their complete `Song` metadata.

Cloud playlist mutations mirror the Kotlin wire contract:

| Method | Endpoint and required semantics |
| --- | --- |
| `createPlaylist(title, privacy:, videoIds:)` | `playlist/create`; sends privacy and optional initial recordings in one request; returns the real created ID. |
| `renamePlaylist(id, title)` | `browse/edit_playlist`, `ACTION_SET_PLAYLIST_NAME`. |
| `addToPlaylist(id, videoIds)` | `ACTION_ADD_VIDEO`; returns the minted video-ID-to-set-video-ID mapping when supplied by the service. |
| `removeFromPlaylist(id, songs)` | `ACTION_REMOVE_VIDEO`; requires each entry's `setVideoId` and recording ID. Missing entry IDs fail before sending. |
| `movePlaylistEntry(id, setVideoId, beforeSetVideoId:)` | `ACTION_MOVE_VIDEO_BEFORE`; absent successor moves the entry to the end. |
| `deletePlaylist(id)` | `playlist/delete`. |

Edit/delete calls strip the `VL` browse prefix. HTTP 200 bodies containing errors or a failed edit status are rejected. Mutations are not retried automatically because a failed response can occur after the server commits an edit. Cache generations prevent completed pre-invalidation requests from repopulating current results.

Ownership is read from the playlist's header: the editable-header wrapper, owner edit/delete icons, and the presence of the source's bookmark toggle. A song-row delete icon is never evidence that the playlist belongs to the account. Saved library playlists are not automatically classified as owned. Continuations without a header do not establish ownership.

`AddonRegistry.resolve(song, quality:, forDownload:)` and `JioSaavnClient.resolve(song, quality:)` return `AddonStream` transport metadata. The caller converts this into the playback resolver's type, preserving container, codec and download permission. `AddonRegistry.reorder(from, to)` uses **final-index** semantics, persists the order in the vault and clears source caches. Only enabled sources participate. JioSaavn matching for a YouTube-origin recording is used at HIGH/LOSSLESS quality; LOW/MEDIUM skip that source.

HTTP addon manifests with `allowDownloads: false`, `0`, or equivalent false strings are honored before a download stream request. Encrypted addon responses are rejected. A stream endpoint returning 404 may fall back to the same search row's explicit direct stream URL. The registry does not download or execute addon code.

## Persistence and migration

The Flutter profile lives in a `flutter-profile-v1` directory under the platform application-support directory. It does not reuse or overwrite the JVM Preferences store. Existing malformed/unsupported profile JSON causes an error rather than being replaced with defaults. Writes produce a flushed pending file, preserve a last-known backup and rename into place. There is no cross-process locking; multiple main application instances writing one profile are not a supported persistence mode.

Explicit migration accepts a Java Preferences XML export or the corresponding Linux `prefs.xml`. It decodes the source's Base64 URL fields and chunked logical values, preserves Unicode, duplicate playlist entries, video origin, local file paths, explicit flags and quality metadata. Missing first or interior chunks cause an explicit import error; a genuine unchunked legacy value remains supported when no numbered chunks exist. An export explicitly naming another application's node is rejected. Before import the Flutter profile gets a `profile.before-migration.json` checkpoint; the selected legacy file is only read.

History retains the source's 100-item limit. Queue snapshots retain at most 200 entries while keeping the playing entry and cursor in range. Liked recording IDs are persisted together with available song metadata so the local liked library remains useful offline. Migration recovers this metadata from existing history, queue, downloads and playlists; IDs with no old metadata cannot be reconstructed without a later catalogue lookup.

Credentials are excluded from ordinary settings and migration, including cookies, tokens, API keys, session keys, protected source configurations and module-index URLs. This includes the source's Last.fm, ListenBrainz, Discord and Spotify credential keys. Sensitive addon URLs use the OS vault because paths/query parameters can contain access tokens. On macOS both vault adapters use `MacOsOptions(usesDataProtectionKeychain: false)`, following the installed secure-storage package's guidance for standalone applications without Keychain Sharing/provisioning.

Authentication operations carry generations and vault writes are serialized. A late startup read cannot overwrite a manual login/logout. Logout clears the active client immediately and deletes after any already-started older vault write. Superseded sign-ins cannot publish an account or restore an old cookie. A failed current sign-in restores the last committed session rather than another unverified attempt; failed vault operations do not block later logout attempts.

Legacy lyric source order/enabled selections are mapped into `lyrics_providers`, including an explicit empty selection. Hidden/unsupported providers are not silently enabled. The source's pre-four-rung HIGH setting migrates to LOSSLESS, while a current HIGH remains HIGH and legacy STANDARD becomes MEDIUM. Legacy volume fractions are multiplied by 100 and clamped to the Flutter/media_kit 0–100 percentage range, so 0.75 becomes 75. Other settings retain their original keys; a preserved key only affects behavior when the Flutter application consumes it.

## Lyrics behavior and remaining differences

Implemented adapters are **lrc.red, BiniLyrics, BetterLyrics, BetterLyrics Portato, LyricsPlus, Unison, YouTube captions, YouTube Music and LRCLIB**. Selection order is caller-controlled; disabled providers are not contacted. Lookups have bounded timeouts, finite in-memory caching and real empty/error results. No demo lyrics are substituted.

LRC supports repeated timestamps, document offset and enhanced per-word timestamps. TTML reads namespace-independent paragraph/span timing and voice alignment. QRC/YRC accept suffix and prefix word-timing forms. LyricsPlus preserves its structured syllable times. Google translation/romanization uses explicit line markers, preserves source timing/text, and rejects responses that lose alignment. The provider-envelope parser stops at missing fields, avoiding an exponential null-tree traversal.

The following source behavior is **not yet at parity**:

- Six offered source adapters remain unported: PaxSenix, PaxSenix: Spotify, PaxSenix: Musixmatch, KuGou, Musixmatch and Genius. PaxSenix key entry/storage is therefore not exposed as a working Flutter provider.
- SimpMusic and Megalobiz are hidden by the source itself; leaving them unoffered and unqueried is intentional.
- The source's full background-vocal grouping, instrumental-gap synthesis, syllable-run merging and prioritization logic are not completely represented by the simpler Flutter lyric model.
- Parsing, synchronized text, local embedded-lyric reading and manual translation are implemented; this is not a claim that every original lyric export/embedded-tag-writing or provider-specific interaction exists.

## Other explicit parity gaps

- **Authentication:** browser-based JCEF sign-in, automatic installed-browser cookie extraction, multiple persistent Google account/profile management and proof-of-origin WebView flows are not ported. The implemented path is explicit session import into secure storage. Account-channel enumeration/selection exists in the data client, but it does not itself constitute the source's complete account-management UI.
- **Custom module execution:** the source removes the withdrawn build-seeded `MODULE` index, but still supports user-added `CUSTOM_MODULE` indexes through its JavaScript module engine. Flutter implements HTTP addons and JioSaavn, not that custom JavaScript engine. The two source kinds must not be conflated.
- **Spotify breadth:** public playlist import is implemented. The source's signed-in Spotify library, Chromium token exchange, Canvas sources and related account flows are separate features and are not provided by this importer.
- **Source selection:** source HTTP clients and ordered fallback are present; source racing, late stream upgrades, every original-version pinning rule and all recording-transliteration heuristics require integration work beyond these clients.
- **Other services:** Listen Together, Last.fm/ListenBrainz scrobbling, Discord credentials/RPC and YouTube watch-time/reporting protocols are not implemented in this data package.
- **Provider evolution:** Innertube, Spotify embed/partner and third-party lyric/addon response shapes are external contracts. Fixtures and parser tests establish the behavior against represented contracts; they do not establish that every live account, locale or service variant works today.

## Verification

The scoped Dart analyzer completed with **No issues found** for data/domain and their tests. The initial local `flutter test test/data test/domain` attempt stopped before running tests because the `objective_c` native-assets hook invoked that environment's failing JIT runtime and exited with code 255. This historical local limitation did not apply to the normal CI runner: the engine unit/widget suite subsequently passed all **141 tests at checkpoint `6907c4a`**, including profile/download tests and the seven authentication interleaving tests. See [PR #137 checks](https://github.com/Bntagaaa/BitChord-Desktop-Revived/pull/137/checks) for commit-specific evidence and the latest native gates. That checkpoint result is not a claim that a later commit has passed before its own CI run completes.

An isolated AOT harness subsequently ran the unchanged assertions from ten pure Dart test files against the actual repository implementation: **54 tests passed, 0 failed**. Only the test-library imports were adapted from Flutter's test re-export to `matcher`/`test_api`; production code was not copied or stubbed. This verified domain metadata/YouTube identity, renderer distinctions, cloud mutations/pagination/account changes, source-aware radio, lyric parser foundations, translation alignment, Spotify ordering/cancellation, addon policy/URL handling, the independently generated OpenSSL JioSaavn DES vector, legacy migration parsing/missing-chunk rejection/disabled-provider choices/volume units, and filesystem scanning. Native keychain, path-provider, widget rendering and playback were outside that harness.

Normal-engine profile/download regressions cover atomic publication, interrupted transfer resume, a server ignoring Range, invalid Content-Range, refresh-time download policy, queued cancellation, shutdown, HTML rejection, filename normalization, read-only migration, backups, serialized writes, current-queue preservation and refusal to overwrite damaged JSON. Their passing checkpoint evidence is described above. These tests do not establish authenticated live mutations or real user-library behavior.

Seven `AuthStore` concurrency tests use a deferred fake secure-storage adapter to verify restore/sign-in/sign-out/write interleavings, committed-session rollback and recovery after a vault failure. They passed in the normal engine suite at `6907c4a`; they are not included in the pure-AOT count and do not exercise a real OS keychain.

Normal SDK verification commands from `flutterApp`:

```sh
flutter pub get
flutter analyze
flutter test test/data test/domain test/application
```

The limited AOT verification is reproducible after dependency resolution:

```sh
python test/data/run_pure_aot.py --dart /path/to/flutter/bin/cache/dart-sdk/bin/dart
```

The helper uses a temporary directory and the resolved package configuration. It does not replace Flutter integration/widget tests and does not alter committed test assertions.

Native desktop smoke checks and signed-in service verification remain separate release gates; source parity should be assessed against the explicit gaps above and the rewrite's main status report.
