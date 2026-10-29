package com.music.bitchord.desktop

internal const val DESKTOP_BACK_RESTARTS_AFTER_MS = 10_000L

/** Clock samples belong to scrubbers/lyrics/video, not the root of every desktop page. */
internal fun samePlaybackUiState(before: DesktopPlaybackState, after: DesktopPlaybackState): Boolean =
    (before.positionMs > DESKTOP_BACK_RESTARTS_AFTER_MS) == (after.positionMs > DESKTOP_BACK_RESTARTS_AFTER_MS) &&
        before.copy(positionMs = after.positionMs, positionSampledAtNanos = after.positionSampledAtNanos) == after
