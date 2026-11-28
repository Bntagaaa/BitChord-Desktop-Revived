package com.music.bitchord.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import com.music.bitchord.data.settings.PlayerLayout
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class DesktopPlayerLayoutPickerTest {
    @Test
    fun hoveringCyclesBothPreviewsAndLeavingRestoresPlayer() = runDesktopComposeUiTest(width = 800, height = 400) {
        val layout = mutableStateOf(PlayerLayout.CENTERED)
        setContent {
            DesktopPlayerLayoutPicker(layout.value, "Preview", onSelect = { layout.value = it })
        }
        mainClock.autoAdvance = false
        val header = onNodeWithText("Player layout")
        val centered = onNodeWithText("Centered")
        fun pixels(): IntArray {
            val image = centered.captureToImage()
            val buffer = IntArray(image.width * image.height)
            image.readPixels(buffer)
            return buffer
        }

        val player = pixels()
        header.performMouseInput { enter(center) }
        mainClock.advanceTimeBy(2700)
        val lyrics = pixels()
        assertFalse(player.contentEquals(lyrics))
        mainClock.advanceTimeBy(2200)
        val queue = pixels()
        assertFalse(lyrics.contentEquals(queue))
        mainClock.advanceTimeBy(2200)
        assertContentEquals(player, pixels())
        mainClock.advanceTimeBy(2200)
        header.performMouseInput { exit() }
        mainClock.advanceTimeBy(500)
        assertContentEquals(player, pixels())
        centered.assertIsSelected()
        onNodeWithText("Classic").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Classic").assertIsSelected()
    }
}
