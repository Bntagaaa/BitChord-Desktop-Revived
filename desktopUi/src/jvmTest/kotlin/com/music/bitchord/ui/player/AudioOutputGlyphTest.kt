package com.music.bitchord.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioOutputGlyphTest {
    private fun glyph(kind: AudioOutputKind, form: BluetoothForm = BluetoothForm.UNKNOWN) =
        audioOutputGlyphKind(AudioOutputDevice(1, "Device", kind, true, form), false, false)

    @Test
    fun localOutputAndAllDeviceForms() {
        assertEquals(AudioOutputGlyphKind.AIRPLAY, audioOutputGlyphKind(null, false, false))
        assertEquals(AudioOutputGlyphKind.AIRPLAY, glyph(AudioOutputKind.PHONE))
        assertEquals(AudioOutputGlyphKind.BUDS, glyph(AudioOutputKind.BLUETOOTH, BluetoothForm.BUDS))
        assertEquals(AudioOutputGlyphKind.HEADPHONES, glyph(AudioOutputKind.BLUETOOTH, BluetoothForm.HEADPHONES))
        assertEquals(AudioOutputGlyphKind.HEADPHONES, glyph(AudioOutputKind.BLUETOOTH))
        assertEquals(AudioOutputGlyphKind.SPEAKER, glyph(AudioOutputKind.BLUETOOTH, BluetoothForm.SPEAKER))
        assertEquals(AudioOutputGlyphKind.CAR, glyph(AudioOutputKind.BLUETOOTH, BluetoothForm.CAR))
        assertEquals(AudioOutputGlyphKind.HEADPHONES, glyph(AudioOutputKind.WIRED))
        assertEquals(AudioOutputGlyphKind.HEADPHONES, glyph(AudioOutputKind.USB))
        assertEquals(AudioOutputGlyphKind.TV, glyph(AudioOutputKind.HDMI))
        assertEquals(AudioOutputGlyphKind.SPEAKER, glyph(AudioOutputKind.OTHER))
    }

    @Test
    fun remoteOutputWinsOverLocalRoute() {
        val buds = AudioOutputDevice(1, "Buds", AudioOutputKind.BLUETOOTH, true, BluetoothForm.BUDS)
        assertEquals(AudioOutputGlyphKind.CAST, audioOutputGlyphKind(buds, true, true))
        assertEquals(AudioOutputGlyphKind.WATCH, audioOutputGlyphKind(buds, false, true))
    }

    @Test
    fun opticalSizingAndCompactInsetsMatchUpstream() {
        assertEquals(19.5.dp, AudioOutputGlyphKind.AIRPLAY.pillSize)
        assertEquals(22.043478f, AudioOutputGlyphKind.AIRPLAY.standaloneSize.value, 0.001f)
        assertEquals(29.dp, AudioOutputGlyphKind.BUDS.pillSize)
        assertEquals(21.dp, AudioOutputGlyphKind.CAST.pillSize)
        assertEquals(17.dp, AudioOutputGlyphKind.WATCH.pillSize)
        val airPlay = AudioOutputGlyph(com.music.bitchord.ui.icons.BitChordIcons.AirPlay, 19.5.dp)
        assertEquals(19f, airPlay.sizeForAirPlay(19.dp).value, 0.001f)
        assertTrue(actionRowInset(440.dp, 2) > actionRowInset(440.dp, 3))
        assertEquals(0.dp, actionRowInset(100.dp, 3))
    }
}
