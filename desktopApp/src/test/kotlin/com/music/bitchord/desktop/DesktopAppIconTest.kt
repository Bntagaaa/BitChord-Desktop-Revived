package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import bitchord.desktopapp.generated.resources.Res
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopAppIconTest {
    @Test fun selectionSavesAcrossReadsAndUpdatesThePickerWithoutChangingTheme() = runDesktopComposeUiTest(width = 640, height = 340) {
        val previous = DesktopAppIconSettings.selected.value
        val theme = DesktopTheme.mode
        try {
            DesktopAppIconSettings.select(DesktopAppIcon.DARK)
            setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().background(DesktopTheme.palette.background).padding(24.dp)) {
                        Text("App icon", color = DesktopTheme.palette.foreground)
                        DesktopAppIconPicker()
                    }
                }
            }
            onNodeWithTag("app-icon-dark").assertIsSelected()
            onNodeWithTag("app-icon-light").performClick().assertIsSelected()
            onNodeWithTag("app-icon-dark").assertIsNotSelected()
            runOnIdle {
                assertEquals(DesktopAppIcon.LIGHT, DesktopAppIconSettings.read(DesktopPersistence().preferences))
                assertEquals(theme, DesktopTheme.mode)
            }
            // Capture actual asset previews and selected-state styling for review.
            val bitmap = onRoot().captureToImage()
            val pixels = bitmap.toPixelMap()
            val output = java.awt.image.BufferedImage(bitmap.width, bitmap.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
            val target = java.io.File("build/reports/desktop-app-icons.png")
            target.parentFile.mkdirs()
            javax.imageio.ImageIO.write(output, "png", target)

            onNodeWithTag("app-icon-dark").performClick().assertIsSelected()
            runOnIdle { assertEquals(DesktopAppIcon.DARK, DesktopAppIconSettings.read(DesktopPersistence().preferences)) }
        } finally {
            DesktopAppIconSettings.select(previous)
        }
    }

    @Test fun missingOrUnknownPreferenceUsesDark() {
        val preferences = Preferences.userRoot().node("com.music.bitchord.desktop.test.icons_${UUID.randomUUID()}")
        try {
            assertEquals(DesktopAppIcon.DARK, DesktopAppIconSettings.read(preferences))
            preferences.put(DesktopAppIconSettings.KEY, "removed-option")
            assertEquals(DesktopAppIcon.DARK, DesktopAppIconSettings.read(preferences))
        } finally { preferences.removeNode() }
    }

    @Test fun bothUploadedIconsRenderDistinctTrayPixelsAtEverySupportedSize() = runBlocking {
        for (size in listOf(22, 32, 48)) {
            val dark = assertNotNull(renderTraySvg(Res.readBytes(DesktopAppIcon.DARK.svgPath), size))
            val light = assertNotNull(renderTraySvg(Res.readBytes(DesktopAppIcon.LIGHT.svgPath), size))
            val at = ((size / 5) * size + size / 2) * 4
            fun channel(icon: TrayPixmap, offset: Int) = icon.data[at + offset].toInt() and 0xff
            assertTrue(channel(dark, 0) > 240, "Dark tray background must be visible")
            assertTrue(channel(light, 0) > 240, "Light tray background must be visible")
            assertTrue(channel(dark, 1) < 90, "Dark tray pixels must stay dark")
            assertTrue(channel(light, 1) > 220, "Light tray pixels must stay light")
        }
    }
}
