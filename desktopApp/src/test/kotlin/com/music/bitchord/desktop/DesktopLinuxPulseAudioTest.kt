package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLinuxPulseAudioTest {
    @Test
    fun `Pulse device list exposes stable sink ids`() {
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
    fun `only own application streams can be moved`() {
        val listing = """
            Sink Input #35
                Sink: 3
                Properties:
                    application.process.id = "54321"
                    application.name = "java"
            Sink Input #36
                Sink: 1
                Properties:
                    application.process.id = "98765"
                    application.name = "java"
            Sink Input #37
                Sink: 2
                Properties:
                    application.process.id = 54321
        """.trimIndent()
        assertEquals(
            listOf(DesktopLinuxPulseAudio.SinkInput("35", "3"),
                DesktopLinuxPulseAudio.SinkInput("37", "2")),
            DesktopLinuxPulseAudio.parseOwnedInputs(listing, "54321"),
        )
    }

    @Test
    fun `Pulse changes keep the current PCM line while raw ALSA changes reopen`() {
        val pulse = "pulse-sink:bluez_output.buds2.a2dp-sink"
        val usb = "pulse-sink:alsa_output.usb-earpods"
        assertTrue(DesktopLinuxPulseAudio.sameOutputLine("", pulse, true))
        assertTrue(DesktopLinuxPulseAudio.sameOutputLine(pulse, usb, true))
        assertTrue(DesktopLinuxPulseAudio.sameOutputLine(usb, "", true))
        assertFalse(DesktopLinuxPulseAudio.sameOutputLine(pulse, "plughw:2,0", true))
        assertFalse(DesktopLinuxPulseAudio.sameOutputLine("plughw:2,0", usb, true))
        assertFalse(DesktopLinuxPulseAudio.sameOutputLine(pulse, usb, false))
        assertFalse(DesktopLinuxPulseAudio.sameOutputLine(null, pulse, true))
    }
}
