# Desktop music video completion

Based on `upstream-1109-port` at `dc36047d` (including its latest artist page work).

Open Now Playing, choose its `…` menu, then **Watch music video**. The existing YouTube repository finds a matching video; the existing desktop audio engine plays its sound and the silent FFmpeg video surface follows that engine's clock. Video quality controls remain in the shared video player. Fullscreen now uses the existing desktop window placement controller instead of the phone-only orientation API; exiting fullscreen returns to the windowed player. **Switch to audio** finds the catalogue rendition again.

Version selection preserves the current queue slot, entry ID, tier, source and neighboring songs. A version change replaces the local history entry rather than adding a second entry. Lookup failures leave playback alone; obsolete lookups are canceled on song/account/profile changes and rejected if the current queue entry changes. Local files and Listen Together parties do not offer the action. Pause/play state is retained.

Each version starts at its own beginning: music videos can contain additional intros and outros, so this change does not claim measured audio-to-video timestamp alignment across different recordings. Video frames within the selected recording follow pause, speed and seek from the existing audio clock. Late frames from before a pause/seek are discarded, and the video decoder remains available for repeat/seek after EOF.

Regression coverage: queue version round trips, retained source/entry identity and neighbors, stale result rejection, unchanged/wrong-type result rejection. Existing video clock/native frame lifetime tests remain applicable. Live YouTube extraction and device A/V synchronization require network/device validation; local automated tests do not certify them.

## Desktop watch layout

Windows at least 900 dp wide and 540 dp high use a dedicated two-column watch view. The video fills the available left stage, with credits, scrubber, compact desktop transport and volume below it. A 300/360 dp sidebar holds the existing editable queue or the existing synchronized lyrics pane, including its provider/translation/share controls. The background uses BitChord's artwork treatment with a darker veil, rounded video corners and subtle panel borders. Fullscreen remains a separate edge-to-edge mode; smaller windows retain the compact player.

The video sheet no longer uses drag-to-dismiss. Its minimize button and Escape route remain available. Queue operations, output selection, shuffle, repeat and AutoPlay use their existing callbacks; this is a presentation change with no new playback controller.

Compose viewport tests exercise 1440×900 and 960×600: the video stays larger than the sidebar, both remain within bounds without overlap, transport is visible, and queue/lyrics tab selection updates the panel. A structural preview with placeholder video/control slots is exported to `desktopApp/build/reports/desktop-video-layout.png`; it is not a live playback screenshot.
