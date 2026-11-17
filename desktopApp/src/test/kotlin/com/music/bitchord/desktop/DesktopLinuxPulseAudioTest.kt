package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLinuxPulseAudioTest {
    @Test
    fun `Pulse device list exposes stable ids for Bluetooth and USB headsets`() {
        val listing = """
            Sink #2
                State: RUNNING
                Name: bluez_output.11_22_33.a2dp-sink
                Description: Galaxy Buds2
            Sink #4
                State: IDLE
                Name: alsa_output.usb-Apple_EarPods-00.analog-stereo
                Description: EarPods USB-C
        """.trimIndent()
        val sinks = DesktopLinuxPulseAudio.parseSinks(listing)
        assertEquals(2, sinks.size)
        assertEquals("pulse-sink:bluez_output.11_22_33.a2dp-sink", sinks[0].id)
        assertEquals("Galaxy Buds2", sinks[0].name)
        assertEquals("EarPods USB-C", sinks[1].name)
    }

    @Test
    fun `selected Pulse sink id maps to a native per-stream target`() {
        val bt = "pulse-sink:bluez_output.11_22_33.a2dp-sink"
        val usb = "pulse-sink:alsa_output.usb-Apple_EarPods-00.analog-stereo"
        assertEquals("bluez_output.11_22_33.a2dp-sink", DesktopLinuxPulseSink.targetName(bt))
        assertEquals("alsa_output.usb-Apple_EarPods-00.analog-stereo", DesktopLinuxPulseSink.targetName(usb))
        assertNull(DesktopLinuxPulseSink.targetName(""))
        assertNull(DesktopLinuxPulseSink.targetName("plughw:2,0"))
        assertNull(DesktopLinuxPulseSink.targetName("pulse-sink:"))
    }

    @Test
    fun `Pulse sample formats match the desktop PCM pipeline`() {
        assertEquals(5, DesktopLinuxPulseSink.sampleFormatFor(DesktopPcmFormat(44_100, 2, 4, isFloat = true)))
        assertEquals(3, DesktopLinuxPulseSink.sampleFormatFor(DesktopPcmFormat(48_000, 2, 2)))
        assertEquals(7, DesktopLinuxPulseSink.sampleFormatFor(DesktopPcmFormat(96_000, 2, 4)))
        assertNull(DesktopLinuxPulseSink.sampleFormatFor(DesktopPcmFormat(48_000, 2, 3)))
    }
}
