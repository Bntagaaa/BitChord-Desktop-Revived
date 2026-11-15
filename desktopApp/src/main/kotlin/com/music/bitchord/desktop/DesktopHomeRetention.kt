package com.music.bitchord.desktop

/**
 * Home is a disposable feed, not a background cache. Any different full-page surface (including
 * a detail page reached from Home) releases its network results and scroll state immediately.
 * Expanded/fullscreen Now Playing is an overlay, not a new page: it retains Home and its scroll.
 * Playback and account history are independent application state and must not be discarded.
 */
internal fun isHomeFeedVisible(
    destination: DesktopDestination,
    nestedPageOpen: Boolean = false,
    settingsOpen: Boolean = false,
    replayOpen: Boolean = false,
    moodGridOpen: Boolean = false,
    @Suppress("UNUSED_PARAMETER") nowPlayingOpen: Boolean = false,
): Boolean = destination == DesktopDestination.LISTEN_NOW &&
    !nestedPageOpen && !settingsOpen && !replayOpen && !moodGridOpen
