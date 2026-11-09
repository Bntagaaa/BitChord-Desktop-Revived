package com.music.bitchord.desktop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Metadata/transport changes may invalidate the application presentation.
 * Clock samples belong to PlaybackPosition and the video clock instead.
 * Never pass this clock-free projection to playback or platform services.
 */
internal fun Flow<DesktopPlaybackState>.presentationStates(): Flow<DesktopPlaybackState> =
    map { it.copy(positionMs = 0L, positionSampledAtNanos = 0L) }.distinctUntilChanged()
