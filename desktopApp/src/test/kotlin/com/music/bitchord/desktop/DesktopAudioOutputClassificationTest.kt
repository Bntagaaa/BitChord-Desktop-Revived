package com.music.bitchord.desktop

import com.music.bitchord.ui.player.AudioOutputKind
import com.music.bitchord.ui.player.BluetoothForm
import org.junit.Test
import kotlin.test.assertEquals

class DesktopAudioOutputClassificationTest {
    @Test
    fun systemDefaultUsesLocalRouteRegardlessOfLocalizedLabel() {
        assertEquals(AudioOutputKind.PHONE to BluetoothForm.UNKNOWN, classifyDesktopAudioOutput("", "Default"))
    }

    @Test
    fun classifiesExplicitEndpointsUsingIdAndName() {
        assertEquals(AudioOutputKind.BLUETOOTH to BluetoothForm.BUDS, classifyDesktopAudioOutput("pulse-sink:bluez_output.123", "Galaxy Buds2"))
        assertEquals(AudioOutputKind.BLUETOOTH to BluetoothForm.HEADPHONES, classifyDesktopAudioOutput("endpoint", "AirPods Max"))
        assertEquals(AudioOutputKind.BLUETOOTH to BluetoothForm.SPEAKER, classifyDesktopAudioOutput("pulse-sink:bluez_output.123", "Bluetooth Speaker"))
        assertEquals(AudioOutputKind.BLUETOOTH to BluetoothForm.CAR, classifyDesktopAudioOutput("bluetooth", "Car audio"))
        assertEquals(AudioOutputKind.BLUETOOTH to BluetoothForm.UNKNOWN, classifyDesktopAudioOutput("pulse-sink:bluez_output.123", "Unknown device"))
        assertEquals(AudioOutputKind.USB to BluetoothForm.UNKNOWN, classifyDesktopAudioOutput("endpoint", "USB DAC"))
        assertEquals(AudioOutputKind.HDMI to BluetoothForm.UNKNOWN, classifyDesktopAudioOutput("pulse-sink:alsa_output.pci.hdmi-stereo", "Monitor"))
        assertEquals(AudioOutputKind.WIRED to BluetoothForm.UNKNOWN, classifyDesktopAudioOutput("endpoint", "Wired headphones"))
        assertEquals(AudioOutputKind.OTHER to BluetoothForm.UNKNOWN, classifyDesktopAudioOutput("endpoint", "Speakers (Realtek)"))
    }
}
