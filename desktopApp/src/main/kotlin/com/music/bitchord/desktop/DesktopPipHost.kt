package com.music.bitchord.desktop

import androidx.compose.runtime.*
import com.music.bitchord.playback.PlaybackPosition

/** Reads the app's live snapshot state, rather than a frame captured before main was hidden. */
internal class DesktopPipSession(
    val presentation: () -> DesktopPipState,
    val position: PlaybackPosition,
    val onPlayPause: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSeek: (Long) -> Unit,
)

internal class DesktopPipHost(val controller: DesktopPipController = DesktopPipController()) {
    var session by mutableStateOf<DesktopPipSession?>(null)
}

/** Must be a sibling of the main Window: a hidden Window pauses its parent composition. */
@Composable
internal fun DesktopPipHostWindow(host: DesktopPipHost) {
    val session = host.session ?: return
    val open by host.controller.open.collectAsState()
    if (!open) return
    // Engine position samples do not belong in the window presentation.
    val presentation by remember(session) { derivedStateOf { session.presentation() } }
    DesktopPipWindow(host.controller, presentation, session.position,
        session.onPlayPause, session.onPrevious, session.onNext, session.onSeek)
}
