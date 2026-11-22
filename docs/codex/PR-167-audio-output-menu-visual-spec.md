# PR #167 — Audio Output Context Menu Visual Specification

**Target branch:** `feat/1.9.3-phase1-compact-audio-output-menu`  
**PR:** [#167](https://github.com/Bntagaaa/BitChord-Desktop-Revived/pull/167) → `1.9.3-phase-1`  
**Task:** Codex must fix both desktop Audio Output entry paths and update this existing PR. Do not merge.

## Goal

Make Audio Output look and behave like BitChord's existing song context menu/account picker:

- compact, dark, rounded context-menu card;
- real frosted backdrop blur (not just a translucent black background);
- smooth hover highlight animation matching the song context menu;
- anchored to the control that opened it, with edge-aware placement;
- bounded height and wheel-scrollable device list;
- identical appearance/behavior from the expanded/fullscreen player and normal floating/top player.

Keep Android/mobile's existing AudioOutputSheet/PlayerDrawer behavior unchanged.

## Screenshot evidence supplied by the user

1. **Expanded/fullscreen output menu:** compact and anchored, but no convincing backdrop blur and no animated hover treatment.
2. **Song context menu reference:** desired frosted glass, rounded card, row density, icon/text alignment, and animated hover background.
3. **Normal player output menu:** still displays the old large centered modal. This route must be corrected too.

The screenshots are attached in the conversation that requested this spec. Use them as the visual acceptance references; do not substitute a generic Material dropdown.

## Source diagnosis to verify

There are two independent desktop paths in the current branch:

### Expanded/fullscreen player

- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/AudioOutputSheet.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/DesktopCompactAudioOutputMenu.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt`

The desktop branch of `AudioOutputSheet` currently renders `DesktopCompactAudioOutputMenu`, which uses Compose `Popup` and a hard-coded dark background. That is not the desktop main-window menu portal/Haze path, so the background behind the separate popup cannot be sampled in the same way as the in-window context menu. Its row currently uses a plain clickable/background and lacks the context menu's animated hover state.

### Normal floating/top player

- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioOutputUi.kt`

The floating/top player sets `overlays.audioOutput = true`, then mounts `DesktopAudioOutputDialog`. The branch has been edited to call `DesktopMenuSurface`, but the supplied runtime screenshot still shows the old centered modal. Verify the exact binary/commit used and prove the actual normal-player route reaches the portal. If the latest branch build still shows a modal, trace and fix the live call site/provider rather than assuming the source edit is active.

## Required architecture

### One desktop menu host for both desktop modes

Unify expanded/fullscreen and normal floating/top player paths so both render the same desktop menu surface.

A safe approach is to add a desktop menu hook to the shared `PlayerHost` interface (with a harmless default for non-desktop implementations) and override it in `DesktopPlayerHost` to render the desktop-app menu. The shared UI must not import desktopApp classes. Another clean approach is to lift expanded-player output overlay state to the desktop root. Choose the least invasive architecture that ensures both triggers reach the same desktop component.

Do not keep one path on a standalone `Popup` and the other on a portal/modal.

### Main-window portal and anchoring

Use the established `DesktopMenuSurface` → `LocalDesktopMenuPortal` → `DesktopMenuPortal` flow. The portal is rendered in the main Compose window and can sample the Haze source beneath it.

- Anchor to the actual clicked output control or its bounds.
- Support floating player, legacy top player, and expanded-player output pill/caption.
- Flip above/below when necessary and clamp to window edges.
- Preserve outside-click and Escape dismissal.
- Keyboard activation should have a sensible bounds-based fallback when there is no recent pointer press.

### Real frosted blur

Reuse `Modifier.desktopMenuGlass(shape)` / the exact material used by `MenuCard` in `DesktopSongMenuUi.kt`.

That helper routes through `desktopFrosted`, the existing `MENUS_POPUPS` blur preference, the established tint/border, and the 35 dp menu blur radius. Ensure the menu is actually composed under the `menuHaze` provider/source in `DesktopFrame`. A transparent or semi-transparent `background(Color(...))` alone is not blur.

Do not render the desktop menu in a separate native Compose Popup window if it prevents the main window's Haze source from being sampled.

### Hover animation

Match `DesktopMenuRow` in `DesktopSongMenuUi.kt`:

- remembered `MutableInteractionSource`;
- `collectIsHoveredAsState()`;
- `animateColorAsState` for a subtle hover fill;
- approximately 170 ms tween;
- use 0 ms when `DesktopAppearanceSettings.reduceAnimation` is enabled.

Apply this to every enabled output row and the advanced-device action. Keep selected state visually distinct after hover ends. Respect existing project conventions for pressed/disabled states.

## Compact layout and scrolling

- Width target: approximately 280–316 dp, matching the account/song menu.
- Height target: capped around 380–420 dp, adjusted to available window size.
- Keep the header compact.
- Device rows should be approximately 44–48 dp high, with a 28–32 dp circular icon, concise text, ellipsis for long names, and a trailing checkmark for the selected device.
- The device list must scroll with the mouse wheel; it must never expand into a huge modal.
- Preserve System default and the advanced-device disclosure.

**Important Compose constraint:** `DesktopMenuSurface` currently wraps its content in a capped `verticalScroll`. Do not place a `LazyColumn` inside that vertically scrollable parent without changing the shell: nested vertical scroll containers can receive unbounded constraints or compete for scroll input. Either use compact rows inside the existing single scroll container, or change the menu shell to support one LazyColumn as the sole scroll owner. Add a test/verification for long Linux ALSA lists.

## Preserve existing functionality

- System default, PipeWire/PulseAudio, Java Sound, ALSA, Bluetooth, USB, HDMI and advanced devices remain selectable where currently supported.
- Selecting an output keeps the existing immediate routing/persistence path; do not rewrite `DesktopAudioDevices` or the audio engine.
- Selected device remains highlighted with a checkmark and current/playing subtitle where appropriate.
- Preserve Cast, volume, and Audio Pipeline actions in the expanded-player path wherever they are currently supported.
- Preserve advanced-device reveal/hide behavior.
- Keep Escape/outside-click dismissal and avoid stale registered flyouts.
- Android remains on its existing PlayerDrawer sheet.

## Acceptance criteria

### Visual

- Both normal and expanded/fullscreen desktop player open the same compact context-menu design.
- No large centered Audio Output modal remains on any desktop player entry point.
- Real backdrop blur is clearly visible over the current page/player, with the same visual treatment as the supplied song context menu.
- Hovering a row animates a subtle highlight smoothly; Reduce Animation is respected.
- Menu position follows the clicked control and remains inside the window.
- The page/player is not covered by a modal dimming scrim.

### Functional

- All output routes still switch immediately.
- Advanced Linux devices remain accessible.
- Long lists scroll inside the bounded menu.
- Long names do not push the checkmark out of view.
- Outside click and Escape dismiss correctly.
- Cast/volume/pipeline actions continue to work where supported.
- Android behavior is unchanged.

### Required test matrix

| Scenario | Expected |
|---|---|
| Normal floating player | Compact anchored portal menu, blur visible, no center modal |
| Legacy top player | Same compact menu and dismissal |
| Expanded/fullscreen player | Same glass and hover treatment; no separate Popup surface |
| Linux with many ALSA devices | Advanced disclosure and bounded wheel scrolling |
| Windows output devices | Switching and selected marker work |
| Reduce Animation / Reduce Blur | Existing preferences are respected |
| Android | Original bottom sheet unchanged |

## Build, PR and delivery

1. Inspect current branch and PR #167 before editing.
2. Implement the unified desktop menu path and tests.
3. Run the relevant Gradle compile/tests and CI for Linux, Windows, Android and Go.
4. Build/run the app and visually verify both normal and expanded player modes. Do not claim visual parity from compilation alone.
5. Push commits to this branch and update the existing PR #167 description with the actual implementation, verification results and any known limitations.
6. Keep the PR targeting `1.9.3-phase-1`. Do not merge.

## Files to inspect first

- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/AudioOutputSheet.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/DesktopCompactAudioOutputMenu.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioOutputUi.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSongMenuUi.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopMenuPortal.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
