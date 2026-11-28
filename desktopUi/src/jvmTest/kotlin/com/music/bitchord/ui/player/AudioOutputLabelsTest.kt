package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AudioOutputLabelsTest {
    @Test
    fun desktopUsesPersonalDefaultLabelAndPreservesOtherRoutes() {
        runDesktopComposeUiTest(width = 500, height = 300) {
            setContent {
                MaterialTheme {
                    Column {
                        val local = AudioOutputDevice(0, "System default", AudioOutputKind.PHONE, true)
                        val buds = AudioOutputDevice(1, "Galaxy Buds2", AudioOutputKind.BLUETOOTH, true)
                        val hdmi = AudioOutputDevice(2, "", AudioOutputKind.HDMI, true)
                        Text(outputLabel(local, "Alex Smith", desktop = true))
                        Text(outputLabel(null, "Taylor Smith", desktop = true))
                        Text(outputLabel(local, null, desktop = true))
                        Text(outputLabel(buds, "Alex", desktop = true))
                        Text(outputLabel(hdmi, "Alex", desktop = true))
                        Text(outputLabel(local.copy(name = ""), "Alex Smith", desktop = false))
                    }
                }
            }
            onNodeWithText("Alex’s Desktop").assertIsDisplayed()
            onNodeWithText("Taylor’s Desktop").assertIsDisplayed()
            onNodeWithText("This Desktop").assertIsDisplayed()
            onNodeWithText("Galaxy Buds2").assertIsDisplayed()
            onNodeWithText("HDMI").assertIsDisplayed()
            onNodeWithText("Alex’s Phone").assertIsDisplayed()
        }
    }
}
