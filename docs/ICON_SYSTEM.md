# BitChord Desktop — Unified Icon System

## 1. Design Philosophy

BitChord Desktop Revived uses a single, cohesive iconography language aligned with the latest upstream BitChord application (`kushagrasinghx/BitChord`, pinned at commit `21356206de5594ca0bdaf4680ca2db68a2287729`) and its Apple Music–inspired visual direction:

- **Unified stroke & geometry**: Utility and menu glyphs use a 24×24 nominal viewport, `2.0f–2.2f` primary stroke weight, `StrokeCap.Round`, and `StrokeJoin.Round` so all icons read as one intentional family.
- **Intentional filled states**: Transport controls (`Play`, `Pause`, `Previous`, `Next`), active favorites/likes (`HeartFilled`, `StarFilled`, `BrokenHeartFilled`), and primary navigation anchors (`Home`, `TabExplore`, `TabSearch`, `TabRadio`, `TabLibrary`) use filled silhouettes where visual hierarchy requires it.
- **Theme-aware tinting**: Monochrome UI icons use `SolidColor(Color.Black)` internally and receive runtime tinting via `Icon` / `LocalContentColor.current`. Brand and provider marks (`ic_logo`, `ic_google_g`, `spotify_logo`, `ic_lossless`, `ic_dolby_atmos`) preserve their intended branding.

## 2. Icon Selection Hierarchy

1. **Tier 1 — SF-style iconography (`SfStyleIcons`)**: Settings, context menus, account management, utility actions, toolbar actions, audio output devices, window controls, and status indicators.
2. **Tier 2 — Original BitChord icons (`BitChordIcons`, `DesktopPhoneMenuIcons`, `shared-ui`)**: Primary navigation tabs, `Shuffle`, `Repeat`, `RepeatOne`, `Infinity`, `Queue`, `Lyrics`, `Heart`, `Star`, `AirPlay`, `Download`, `Pin`, `GridView`, `ListView`, and music indicators.
3. **Tier 3 — Permitted non-Material libraries (`web-lucide`)**: ISC-licensed Lucide geometry adapted into `SfStyleIcons` for desktop-exclusive and utility controls.
4. **Tier 4 — Original custom Compose vectors**: Desktop-native 24×24 path drawings in `SfStyleIcons` and `DesktopPhoneMenuIcons` matching the exact stroke/cap/join specification.
5. **Tier 5 — Material Icons fallback**: Strictly controlled last-resort fallback. **Current count: 0.**

## 3. Centralized Semantic Architecture

- **Shared layer** (`sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/icons/`):
  - `BitChordIcons.kt`: Upstream BitChord vector family (including `AirPlay` synced from upstream and `RepeatOne` matching `Repeat`).
  - `SfStyleIcons.kt`: Shared SF-style & Lucide-derived Compose `ImageVector` library, lazily built once and cached.
- **Desktop layer** (`desktopApp/src/main/kotlin/com/music/bitchord/desktop/icons/DesktopIcons.kt`):
  - `DesktopIcons.Navigation`
  - `DesktopIcons.Player`
  - `DesktopIcons.Actions`
  - `DesktopIcons.Audio`
  - `DesktopIcons.Window`
  - `DesktopIcons.Settings`
  - `DesktopIcons.Status`
  - `DesktopIcons.Size` optical size tokens (`Status = 14.dp`, `Compact = 16.dp`, `Menu = 18.dp`, `Toolbar = 20.dp`, `Navigation = 22.dp`, `Standard = 24.dp`)
  - `DesktopIcon(...)` reusable composable handling size, tint, and disabled alpha.

## 4. Asset Licensing

- **Apple SF Symbols (`sf-symbols/`)**: **Excluded from redistribution.** Apple's SF Symbols terms prohibit redistributing SF Symbols assets in non-Apple Windows/Linux applications. All SF-style icons in `SfStyleIcons.kt` and `DesktopPhoneMenuIcons.kt` are original Compose vector drawings or adapted from ISC-licensed Lucide geometry.
- **Lucide (`web-lucide/`)**: ISC License (permissive, compatible with Windows/Linux distribution).
- **BitChord original icons (`BitChordIcons.kt`, `shared-ui/`, `player_transport_*.svg`)**: Project-native assets.

## 5. Adding or Updating Icons

1. Check if a semantic token already exists in `DesktopIcons`. Reuse existing tokens so identical actions share identical geometry across all screens.
2. If a new glyph is needed, add a `by lazy` `ImageVector` to `SfStyleIcons.kt` (or `BitChordIcons.kt` if porting from upstream) using a 24×24 viewport, `2.0f` stroke, `StrokeCap.Round`, and `StrokeJoin.Round`. Set `autoMirror = true` for directional glyphs.
3. Expose the semantic property on `DesktopIcons.<Category>`.
4. Update `tools/generate_icon_contact_sheet.py` and `DesktopIconsTest.kt`.

## 6. Automated Enforcement in CI

Run the static Material Icons enforcement check (executed automatically in `.github/workflows/check.yml`):

```bash
python3 .github/scripts/check-icon-system.py
```

Any unapproved `androidx.compose.material.icons` import or `Icons.*` usage in `desktopApp/src/main`, `desktopUi/src`, `sharedUi/src/jvmSharedMain`, `sharedUi/src/commonMain`, or `shared/src` will fail the CI check.
