package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopSettingsScrollLayoutTest {
    @Test
    fun overviewFooterAndAppearanceLastRowClearTheFloatingPlayer() {
        for (scale in listOf(1f, 1.25f)) {
            for (overview in listOf(true, false)) {
                runDesktopComposeUiTest(width = (640 * scale).toInt(), height = (480 * scale).toInt()) {
                    val state = LazyListState()
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(scale)) {
                            Box(Modifier.fillMaxSize()) {
                                DesktopSettingsList(
                                    state = state,
                                    modifier = Modifier.fillMaxSize().testTag("settings-list"),
                                    topPadding = if (overview) 10.dp else 0.dp,
                                    bottomScrollInset = 132.dp,
                                ) {
                                    if (overview) {
                                        item { Box(Modifier.fillMaxWidth().height(760.dp)) }
                                        item { Box(Modifier.fillMaxWidth().height(80.dp).testTag("last-setting")) }
                                    } else {
                                        item {
                                            Column {
                                                Box(Modifier.fillMaxWidth().height(760.dp))
                                                Box(Modifier.fillMaxWidth().height(80.dp).testTag("last-setting"))
                                            }
                                        }
                                    }
                                }
                                Box(
                                    Modifier.align(Alignment.BottomCenter)
                                        .fillMaxWidth().height(90.dp).testTag("floating-player"),
                                )
                            }
                        }
                    }

                    val list = onNodeWithTag("settings-list").fetchSemanticsNode().boundsInRoot
                    val player = onNodeWithTag("floating-player").fetchSemanticsNode().boundsInRoot
                    assertTrue(list.bottom > player.top, "The viewport must extend behind the player")

                    runOnIdle {
                        if (overview) state.requestScrollToItem(1)
                        else state.requestScrollToItem(0, 2000)
                    }
                    waitForIdle()
                    runOnIdle { assertFalse(state.canScrollForward, "The list should reach its end") }
                    val last = onNodeWithTag("last-setting").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue(last.bottom < player.top, "Final content must clear the player at ${scale}x")
                }
            }
        }
    }
}
