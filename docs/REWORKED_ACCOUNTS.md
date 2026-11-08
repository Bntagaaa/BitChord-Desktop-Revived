# YouTube Music accounts and profiles

Desktop Reworked implements the account and channel behavior of original BitChord mobile v1.9.1. The desktop boundary is new Kotlin/JVM and JavaFX code. No historical desktop or web account component is used.

## User behavior

- **Accounts and profiles** is available in Settings and the app bar. Several independently saved Google logins can each contain personal and brand profiles.
- **Add account** opens the original mobile Google sign-in URL. **Choose profile in YouTube Music** first installs that saved account's cookie snapshot into the local browser, then opens YouTube Music's own account picker.
- Reaching a signed-in page never saves automatically. The user presses **Use this YouTube Music profile** after selecting the intended identity. The app reads that exact page's `ytcfg`, verifies it with YouTube Music's original `account_menu` contract, and saves only an accepted response.
- Refreshing channels uses the original shared `accountChannels()` parser and its fallback endpoint. A temporary empty response does not erase the captured profile. The app's selected profile is distinct from the server's default channel.
- Removing a saved account removes that Google session and chooses another saved account when available. It clears local Google/YouTube browser cookies without invoking Google's logout endpoint. Spotify and Discord browser logins remain separate.

## Source provenance

All referenced upstream paths are from original BitChord `v1.9.1`, commit `7f45c768a461fd7e68bf4613f71e8a767e643d4c`.

| Original mobile/shared contract | Fresh desktop implementation |
| --- | --- |
| `app/.../auth/AccountSessions.kt` | `MobileYouTubeAccountModels.kt`: independent account records, personal/brand profile records, stable account/profile order and IDs |
| `app/.../auth/AuthStore.kt` | `DesktopYouTubeAccountStore.kt`: dedicated Reworked registry, atomic persistence and platform-specific credential protection |
| `app/.../auth/WebSession.kt` and `YtMusicLoginScreen.kt` | `DesktopYouTubeBrowser.kt`, `DesktopBrowserAuth.kt`: explicit page capture, account-specific browser cookie installation and scoped removal |
| `app/.../ui/MainViewModel.kt` account/profile selection and listener reset | `MobileYouTubeAccounts.kt`, `MobileSession.kt`: restored scope, account-channel selection, listener revision, job cancellation and separate listener caches |
| `app/.../ui/components/AccountProfileSelector.kt` and `AccountChannelDialog.kt` | `MobileYouTubeAccountSettings.kt`: saved accounts, profile selector, discoverable channels and browser fallback |
| `shared/.../innertube/Innertube.kt`, `InnertubeParser.kt`, `YtMusicRepository.kt` | `YouTubeCapturedSessionValidator.kt` plus direct calls to the original shared repository/parser |

The isolated validator uses the original `WEB_REMIX` context, `SAPISIDHASH`, `X-Goog-AuthUser`, `X-Goog-PageId`, visitor identity and account-header parser. Candidate credentials are not installed in the process-wide Innertube singleton during validation. Persistence must succeed before activation.

The live page supplies `DELEGATED_SESSION_ID`, `DATASYNC_ID`, `SESSION_INDEX`, `VISITOR_DATA`, `INNERTUBE_CLIENT_VERSION` and `LOGGED_IN`. For a brand channel, its page ID takes precedence as the active data-sync identity. The original `normalizeDataSyncId` helper handles personal identities. A personal viewer can have no page/data-sync ID; its captured Google account index is still retained on restart. Profile IDs are never guessed from a later default account page.

## Listener isolation

`MobileYouTubeAccounts.state` publishes account summaries without cookie values. A revision changes whenever the active request scope changes. Its `listenerKey` is a hash of the saved Google account and profile IDs, or `guest` when signed out.

`MobileSession` cancels the departing listener's home, library, search, browse and liked-pagination jobs. It clears private navigation and search results, reloads the new listener's caches and refreshes the visible data. Likes and playback history use `liked-<listenerKey>.json` and `history-<listenerKey>.json`; an in-memory snapshot preserves recent changes during quick switches. Local playlists, downloaded files and the playback queue remain device data.

Repository calls whose results belong to a listener run through a revision gate. The gate also covers original shared library methods that mutate the process-wide `LikeState` map while parsing. A profile switch cannot interleave with those mutations. Server writes require the exact starting revision and a signed-in profile, so an old playlist dialog cannot write to a newly selected account.

Downloaded playlist synchronization records an independent owner key and uses it before reading the remote playlist or resolving newly queued downloads. A collection with no recorded owner requires an explicit download under the intended profile before automatic synchronization. Changing profiles leaves a Listen Together room through the normal personal-queue restoration path. Refreshing the cookie for the same listener retains membership.

## Storage and lifecycle

`youtube-accounts.json` belongs to the separate BitChord Reworked profile. Windows encrypts the registry with user-scoped DPAPI. Linux stores an explicitly unencrypted private file with owner read/write permissions; it is not described as encrypted. Both paths refuse symbolic-link destinations and replace complete files atomically where supported. An unreadable registry is preserved and reported, rather than silently overwritten with an empty registry.

The earlier Reworked-only `youtube-cookie.txt` can be held as a provisional in-memory account. It is removed after the new registry has been successfully saved. This does not import the Revived application's profile.

`Main` restores the registry before creating `MobileSession` or listening integrations. `--offline-smoke` instead initializes an empty guest registry without reading an account file or invoking account validation. All online account actions retain the offline network guard. Spotify, Discord, application shutdown and player/native-media lifecycles keep their existing desktop behavior.

## Verification and remaining runtime checks

The focused account fixtures exercise exact cookie-name recognition, origin restrictions, live-page identity parsing, the signed request envelope, original response parsing, failed/canceled validation, save-before-activation ordering, storage failure, account deduplication, profile switching, partial channel responses, stale writes/reads, scoped browser-cookie clearing, personal viewers without channel IDs and guest-only startup. Temporary-store fixtures cover registry round trips, private Linux permissions, corrupt-file preservation and symbolic-link refusal. They use synthetic credentials and mocked responses.

The standalone pre-integration run passed **26 focused account tests** and compiled the new Compose/JavaFX surfaces. The repository's complete gate is the authoritative final build result. Windows DPAPI execution belongs to the Windows test run; Linux does not verify that native API. No live Google sign-in, real brand-account session or protected user library was used during implementation. Service acceptance of the embedded browser and signed-in provider behavior remain live-account checks.
