# Desktop music video beta

Based on `upstream-1109-port` at `dc36047d` (including its latest artist page work).

Enable **Settings → Playback → Music video playback (Beta)** first, then open Now Playing, choose its `…` menu, and select **Watch music video**. The existing YouTube repository finds a matching video; the existing desktop audio engine plays its sound and the silent FFmpeg video surface follows that engine's clock. Video quality controls remain in the shared video player. Fullscreen now uses the existing desktop window placement controller instead of the phone-only orientation API; exiting fullscreen returns to the windowed player. **Switch to audio** finds the catalogue rendition again.

The beta defaults off and is persisted separately from artwork/layout settings. Off keeps the classic audio/cover player and does not resolve or open a picture stream. Disabling it cancels a pending rendition switch and releases the current video decoder without replacing the audio track. Thumbnail dimensions and video metadata remain correct in either mode.

Home and artist playback retain `ShelfItem.isVideo` when converting a card into `Song`. The parser prefers YouTube's OMV/UGC/ATV watch metadata over a square thumbnail crop, and explicit video shelves provide a fallback when watch metadata is absent. Desktop artist cards, shared Home cards, Recents, queue and track-row thumbnails use widescreen video sizing while album/audio art stays square. Mixed shelves use each card's media type rather than treating every item as a video.

Manual rendition matching requires the same title/version and artist but allows the music video's extra intro/outro. A failed lookup stays retryable instead of trying to put null into a ConcurrentHashMap. The queue can also repair an old audio classification when the matched video has the same ID.

Version selection preserves the current queue slot, entry ID, tier, source and neighboring songs. A version change replaces the local history entry rather than adding a second entry. Lookup failures leave playback alone; obsolete lookups are canceled on song/account/profile changes and rejected if the current queue entry changes. Local files and Listen Together parties do not offer the action. Pause/play state is retained.

Each version starts at its own beginning: music videos can contain additional intros and outros, so this change does not claim measured audio-to-video timestamp alignment across different recordings. Video frames within the selected recording follow pause, speed and seek from the existing audio clock. Late frames from before a pause/seek are discarded, and the video decoder remains available for repeat/seek after EOF.

Regression coverage: queue version round trips, retained source/entry identity and neighbors, stale result rejection, unchanged/wrong-type result rejection. Existing video clock/native frame lifetime tests remain applicable. Live YouTube extraction and device A/V synchronization require network/device validation; local automated tests do not certify them.

## Desktop watch layout

The user's two-page PDF is a layout reference only. Windows at least 900 dp wide and 540 dp high use a two-column watch view: a large video stage on the left and a queue-only sidebar on the right. The caption is centered above them. Credits, transport, volume and track actions share the strip below the video; the timeline follows the entire strip. On narrower desktop widths that strip uses two rows. The sidebar scales from 300 to 450 dp, with inner padding so drag handles, titles and remove actions stay inside its border.

With the beta enabled, lyrics are disabled for desktop music videos: no tab, bottom glyph, restored lyrics pane or lyric strip, and the desktop lyric request is skipped. Audio-only tracks retain their existing lyrics. Unrelated Canvas lookups are also skipped while a music video is playing. Queue thumbnails use the existing 16:9 sizing helper for video and remain square for audio.

The window chrome owns the single fullscreen button. Video surfaces only offer quality selection. In fullscreen the desktop transport sits at the bottom left above the timeline; moving the mouse reveals controls. The video sheet does not use drag-to-dismiss or replay the opening animation on restore. The minimize button and Escape route remain available.

## Retained video session

The session is keyed by the playing video ID and requested quality, never by surface count. It resolves and opens ahead of showing the player, retains one decoder and the last frame across fullscreen/minimize/restore, and suspends decoding while hidden or paused. Restore displays that frame immediately and reanchors the existing decoder to the audio clock. A new track, changed quality or stop releases the old decoder on its owning worker. This does not keep decoding invisible video or allocate a second audio player.

An unsuccessful resolution or an initially empty video shows an explicit retry state rather than an endless loading backdrop. Attaching another view does not silently retry a failed network request. The initial fetch, a requested quality change, an explicit retry and reads needed to catch up with audio can still depend on the network; this change does not promise zero network latency.

## Validation

Session regression tests count resolution/open/close operations across repeated view transfers, hidden playback and restore, track changes, quality changes, stop, explicit retry and seeks after EOF. Decode failures close resources and expose a failure state. Compose tests exercise 1440×900 and 960×600, check panel bounds, timeline position, square audio versus 16:9 video thumbnails, absence of lyrics and a single fullscreen toggle. An additional test renders the actual NowPlayingScreen and exercises fullscreen → windowed → minimized → restored without starting a Canvas lookup.

`desktopApp/build/reports/desktop-video-player.png` captures the actual player components with a placeholder video; `desktop-video-layout.png` is the structural viewport test. Neither is a live YouTube playback capture. Live extraction and device A/V synchronization still require network/device validation.
