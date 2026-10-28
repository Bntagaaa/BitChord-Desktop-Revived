# Desktop music video completion

Based on `upstream-1109-port` at `dc36047d` (including its latest artist page work).

Open Now Playing, choose its `…` menu, then **Watch music video**. The existing YouTube repository finds a matching video; the existing desktop audio engine plays its sound and the silent FFmpeg video surface follows that engine's clock. Video quality controls remain in the shared video player. Fullscreen now uses the existing desktop window placement controller instead of the phone-only orientation API; exiting fullscreen returns to the windowed player. **Switch to audio** finds the catalogue rendition again.

Version selection preserves the current queue slot, entry ID, tier, source and neighboring songs. A version change replaces the local history entry rather than adding a second entry. Lookup failures leave playback alone; obsolete lookups are canceled on song/account/profile changes and rejected if the current queue entry changes. Local files and Listen Together parties do not offer the action. Pause/play state is retained.

Each version starts at its own beginning: music videos can contain additional intros and outros, so this change does not claim measured audio-to-video timestamp alignment across different recordings. Video frames within the selected recording follow pause, speed and seek from the existing audio clock. Late frames from before a pause/seek are discarded, and the video decoder remains available for repeat/seek after EOF.

Regression coverage: queue version round trips, retained source/entry identity and neighbors, stale result rejection, unchanged/wrong-type result rejection. Existing video clock/native frame lifetime tests remain applicable. Live YouTube extraction and device A/V synchronization require network/device validation; local automated tests do not certify them.
