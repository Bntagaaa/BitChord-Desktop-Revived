package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.ic_output_buds
import com.music.bitchord.sharedui.resources.ic_output_speaker
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.icons.SfStyleIcons
import org.jetbrains.compose.resources.vectorResource

/**
 * Optical sizes from BitChord mobile v1.10. A glyph's visible bounds are not
 * the same as its nominal viewport: AirPlay fills its viewport and earbuds
 * are unusually wide. Keep the SF-style vectors used throughout Revived.
 */
internal enum class AudioOutputGlyphKind(val pillSize: Dp) {
    AIRPLAY(19.5.dp),
    BUDS(29.dp),
    HEADPHONES(23.dp),
    SPEAKER(23.dp),
    CAR(23.dp),
    CAST(21.dp),
    WATCH(17.dp),
    TV(21.dp),
    ;

    val standaloneSize: Dp get() = pillSize * (26f / 23f)
}

internal fun audioOutputGlyphKind(
    active: AudioOutputDevice?,
    castConnected: Boolean,
    watchActive: Boolean,
): AudioOutputGlyphKind = when {
    castConnected -> AudioOutputGlyphKind.CAST
    watchActive -> AudioOutputGlyphKind.WATCH
    else -> when (active?.kind) {
        null, AudioOutputKind.PHONE -> AudioOutputGlyphKind.AIRPLAY
        AudioOutputKind.BLUETOOTH -> when (active.form) {
            BluetoothForm.BUDS -> AudioOutputGlyphKind.BUDS
            BluetoothForm.SPEAKER -> AudioOutputGlyphKind.SPEAKER
            BluetoothForm.CAR -> AudioOutputGlyphKind.CAR
            BluetoothForm.HEADPHONES, BluetoothForm.UNKNOWN -> AudioOutputGlyphKind.HEADPHONES
        }
        AudioOutputKind.WIRED, AudioOutputKind.USB -> AudioOutputGlyphKind.HEADPHONES
        AudioOutputKind.HDMI -> AudioOutputGlyphKind.TV
        AudioOutputKind.OTHER -> AudioOutputGlyphKind.SPEAKER
    }
}

/** Shared by the fullscreen and desktop floating output controls. */
data class AudioOutputGlyph(val icon: ImageVector, val pillSize: Dp) {
    val standaloneSize: Dp get() = pillSize * (26f / 23f)

    /** Keep AirPlay at the caller's established size and scale other glyphs optically. */
    fun sizeForAirPlay(airPlaySize: Dp): Dp = pillSize * (airPlaySize.value / 19.5f)
}

@Composable
fun rememberAudioOutputGlyph(host: PlayerHost = PlayerPlatform.host): AudioOutputGlyph {
    val cast by host.castState.collectAsStateWithLifecycle()
    val watch by host.watchOutput.collectAsStateWithLifecycle()
    val active = host.rememberAudioOutputs().firstOrNull { it.isActive }
    val kind = audioOutputGlyphKind(active, cast.connectedName != null, watch.active)
    val icon = when (kind) {
        AudioOutputGlyphKind.AIRPLAY -> BitChordIcons.AirPlay
        AudioOutputGlyphKind.BUDS -> vectorResource(Res.drawable.ic_output_buds)
        AudioOutputGlyphKind.HEADPHONES -> SfStyleIcons.Headphones
        AudioOutputGlyphKind.SPEAKER -> vectorResource(Res.drawable.ic_output_speaker)
        AudioOutputGlyphKind.CAR -> SfStyleIcons.Car
        AudioOutputGlyphKind.CAST -> SfStyleIcons.CastConnected
        AudioOutputGlyphKind.WATCH -> SfStyleIcons.Watch
        AudioOutputGlyphKind.TV -> SfStyleIcons.Tv
    }
    return AudioOutputGlyph(icon, kind.pillSize)
}
