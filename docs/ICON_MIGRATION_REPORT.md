# BitChord Desktop Revived — Icon Migration Report

- **Base branch**: `1.10-fix`
- **Implementation branch**: `agy/1.10-unified-icon-system`
- **Upstream reference**: `kushagrasinghx/BitChord` @ `21356206de5594ca0bdaf4680ca2db68a2287729` (2026-11-21)

## 1. Migration Statistics

| Metric | Before | After | Change |
|---|---:|---:|---:|
| Direct Material Icon usages (`desktopApp/src/main`, `sharedUi/src/jvmSharedMain`, `desktopUi/src`, `shared/src`) | 199 | 0 | -100.0% |
| Unique Material Icon symbols | 91 | 0 | -100.0% |
| Files with Material Icon usages | 33 | 0 | -100.0% |
| Unused Material Icon imports cleaned up | 36 files | 0 | -100.0% |
| Approved Material Icon fallbacks | — | 0 | — |
| Centralized `DesktopIcons` semantic tokens | 0 | 114 | +114 |

## 2. Key Architectural & Visual Changes

- Created [`SfStyleIcons.kt`](../sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/icons/SfStyleIcons.kt) in `sharedUi/src/jvmSharedMain` with 56 lazy-cached, 24×24 SF-style & Lucide-derived Compose `ImageVector`s (`StrokeCap.Round`, `StrokeJoin.Round`, `2.0f` stroke, RTL `autoMirror` support).
- Updated [`BitChordIcons.kt`](../sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/icons/BitChordIcons.kt) with upstream `AirPlay` and consistent `RepeatOne` matching `BitChordIcons.Repeat`.
- Created [`DesktopIcons.kt`](../desktopApp/src/main/kotlin/com/music/bitchord/desktop/icons/DesktopIcons.kt) with semantic namespaces (`Navigation`, `Player`, `Actions`, `Audio`, `Window`, `Settings`, `Status`), optical `Size` tokens, and `DesktopIcon` composables.
- Unified `Shuffle`, `Repeat`, `RepeatOne`, `Lyrics`, and `Queue` across `DesktopApp.kt`, `DesktopMiniPlayer.kt`, `PlayerControls.kt`, `NowPlayingScreen.kt`, `LandscapePlayer.kt`, and `DesktopSongMenuUi.kt` on canonical `BitChordIcons` geometry, while preserving the Apple Music–style solid `player_transport_play/pause/previous/next` SVG buttons.
- Added CI enforcement via [`.github/scripts/check-icon-system.py`](../.github/scripts/check-icon-system.py) wired into [`.github/workflows/check.yml`](../.github/workflows/check.yml), plus unit tests in [`DesktopIconsTest.kt`](../desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopIconsTest.kt).

## 3. Screens & Components Migrated

- **Shared UI (`sharedUi/src/jvmSharedMain`)** — 14 files: `Common.kt`, `SearchField.kt`, `HomeScreen.kt`, `SearchScreen.kt`, `PlayerControls.kt`, `NowPlayingScreen.kt`, `LandscapePlayer.kt`, `AudioOutputSheet.kt`, `PlayerLyrics.kt`, `LyricsProviderSheet.kt`, `PlayerQueue.kt`, `ListenTogetherMembersSheet.kt`, `MusicVideoLayout.kt`, `LyricsShare.kt`.
- **Desktop App (`desktopApp/src/main`)** — 22 files (19 with Material icon usages + 3 unused Material imports): `DesktopApp.kt`, `DesktopSongMenuUi.kt`, `DesktopAudioOutputUi.kt`, `DesktopListenTogetherUi.kt`, `DesktopContentPreferences.kt`, `DesktopMiniPlayer.kt`, `DesktopPipUi.kt`, `DesktopPlaylistUi.kt`, `DesktopAccountUi.kt`, `DesktopAppleArtistUi.kt`, `DesktopDownloadManagerUi.kt`, `DesktopNowPlayingDetails.kt`, `DesktopPlayerSheet.kt`, `DesktopSharedPages.kt`, `DesktopIntegrationsUi.kt`, `DesktopKeyboardShortcutsModal.kt`, `DesktopLyricsSourcesUi.kt`, `DesktopReplayUi.kt`, `DesktopSidePanel.kt`, `DesktopTranslationLanguageUi.kt`, `DesktopIntegrationsDialog.kt`, `DesktopEqualizerUi.kt`.
- **Not migrated (out of desktop scope)**: Android-only `app/` module.

## 4. Contact Sheet & Verification

- Contact sheet: [`docs/icon-contact-sheet.html`](icon-contact-sheet.html) (114 semantic tokens + 27 integrated drawables, dual light/dark swatches).
- Static icon enforcement: Passed (`python3 .github/scripts/check-icon-system.py` -> 0 violations).
- CI build, unit tests, and visual verification: Pending GitHub Actions run on PR targeting `1.10-fix`.
