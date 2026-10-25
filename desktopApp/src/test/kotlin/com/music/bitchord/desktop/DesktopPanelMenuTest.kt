package com.music.bitchord.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPanelMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Home keeps its feed mounted and moves the shelf arrow outside the panel`() {
        val home = mutableStateOf(true)
        val open = mutableStateOf(false)
        val shelfState = LazyListState()
        var disposed = 0
        compose.setContent {
            Box(Modifier.size(800.dp, 500.dp)) {
                DesktopPageWithSidePanel(
                    overlayPanel = home.value,
                    modifier = Modifier.fillMaxSize(),
                    panel = {
                        DesktopSidePanelColumn(
                            panel = if (open.value) DesktopSidePanel.QUEUE else null,
                            onClose = {},
                        ) { Box(Modifier.fillMaxSize().testTag("panel")) }
                    },
                ) {
                    DisposableEffect(Unit) { onDispose { disposed++ } }
                    Column(Modifier.fillMaxSize().testTag("feed")) {
                        DesktopShelfRowChrome.Wrap(shelfState) {
                            LazyRow(state = shelfState, modifier = Modifier.fillMaxWidth().height(100.dp)) {
                                items(20) { Box(Modifier.size(150.dp, 100.dp)) }
                            }
                        }
                    }
                }
            }
        }
        val initialWidth = compose.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open.value = true }
        compose.mainClock.advanceTimeBy(130)
        val entering = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        assertTrue(entering.left > initialWidth - 360f && entering.left < initialWidth, "Panel must slide through an intermediate position: $entering")
        assertEquals(initialWidth, compose.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot.width)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("feed").performMouseInput { moveTo(Offset(100f, 40f)) }
        val panel = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        val arrow = compose.onNodeWithContentDescription(DesktopStrings["d_scroll_right", "Scroll right"])
            .fetchSemanticsNode().boundsInRoot
        assertEquals(initialWidth, compose.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot.width)
        assertTrue(arrow.right <= panel.left, "$arrow must stay to the left of $panel")
        compose.onNodeWithContentDescription(DesktopStrings["d_scroll_right", "Scroll right"])
            .performMouseInput { click(center) }
        compose.runOnIdle { assertTrue(shelfState.firstVisibleItemIndex > 0, "The uncovered arrow must still page the shelf") }
        compose.runOnIdle { home.value = false }
        val narrowWidth = compose.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot.width
        assertTrue(narrowWidth < initialWidth)
        compose.runOnIdle { home.value = true }
        assertEquals(initialWidth, compose.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot.width)
        assertEquals(0, disposed, "Changing panel placement must not remount the feed")
    }

    @Test fun `menu preserves the page and blurs the expanded player at the clicked position`() = menuScene(1f)

    @Test fun `menu anchor and blur follow desktop scaling`() = menuScene(1.5f)

    @Test fun `menu stays inside the window at the bottom right and Escape restores focus`() = menuScene(1f, edge = true)

    @Test fun `Home panel blurs its live feed while other pages keep a solid panel`() {
        val home = mutableStateOf(true)
        val swapped = mutableStateOf(false)
        val oldReduce = DesktopAppearanceSettings.reduceDynamicBlur.value
        DesktopAppearanceSettings.setReduceDynamicBlur(false)
        try {
            compose.setContent {
                Box(Modifier.size(800.dp, 500.dp)) {
                    Frame(
                        onMenuClosed = {},
                        home = home.value,
                        panel = {
                            DesktopSidePanelColumn(DesktopSidePanel.QUEUE, {}, blurBackground = home.value) {
                                Box(Modifier.fillMaxSize().testTag("glass"))
                            }
                        },
                        overlay = {},
                        content = {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(if (swapped.value) Color.Blue else Color.Red)
                                drawRect(if (swapped.value) Color.Red else Color.Blue,
                                    Offset(size.width * 0.75f, 0f), Size(size.width * 0.25f, size.height))
                            }
                        },
                    )
                }
            }
            val glass = compose.onNodeWithTag("glass")
            val pixels = glass.captureToImage().toPixelMap()
            val left = pixels[20, pixels.height / 2]
            val right = pixels[pixels.width - 20, pixels.height / 2]
            assertTrue(left.red > left.blue + 0.1f, "Home red must be visible through glass: $left")
            assertTrue(right.blue > right.red + 0.1f, "Home blue must be visible through glass: $right")
            compose.runOnIdle { swapped.value = true }
            val updated = glass.captureToImage().toPixelMap()
            val live = updated[20, updated.height / 2]
            assertTrue(live.blue > live.red + 0.1f, "Glass must update with the live feed instead of retaining a screenshot: $live")
            compose.runOnIdle { home.value = false }
            val solid = glass.captureToImage().toPixelMap()
            val a = solid[20, solid.height / 2]
            val b = solid[solid.width - 20, solid.height / 2]
            assertTrue(abs(a.red - b.red) < 0.01f && abs(a.blue - b.blue) < 0.01f, "Other pages must use a solid panel: $a vs $b")
        } finally {
            DesktopAppearanceSettings.setReduceDynamicBlur(oldReduce)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun menuScene(scale: Float, edge: Boolean = false) {
        val open = mutableStateOf(false)
        var actions = 0
        var restored = 0
        val oldReduce = DesktopAppearanceSettings.reduceDynamicBlur.value
        DesktopAppearanceSettings.setReduceDynamicBlur(false)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                    Box(Modifier.size(600.dp, 400.dp)) {
                        Frame(
                            onMenuClosed = { restored++ },
                            overlay = {
                                Box(Modifier.fillMaxSize().testTag("player")) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawRect(Color.Red)
                                        drawRect(Color.Blue, Offset(size.width * 0.6f, 0f), Size(size.width * 0.4f, size.height))
                                    }
                                    Box(Modifier.offset(if (edge) 540.dp else 240.dp, if (edge) 340.dp else 140.dp)
                                        .size(32.dp).testTag("anchor").clickable { open.value = true }) {
                                        if (open.value) DesktopMenuSurface(
                                            onDismiss = { open.value = false },
                                            modifier = Modifier.testTag("menu"),
                                        ) {
                                            Box(Modifier.height(120.dp).fillMaxWidth().clickable { actions++; open.value = false }) {
                                                Text("Action")
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            val before = compose.onNodeWithTag("player").fetchSemanticsNode().boundsInRoot
            val anchor = compose.onNodeWithTag("anchor").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("anchor").performMouseInput { click(center) }
            val after = compose.onNodeWithTag("player").fetchSemanticsNode().boundsInRoot
            val menu = compose.onNodeWithTag("menu").fetchSemanticsNode().boundsInRoot
            assertEquals(before, after, "Opening the menu must not consume the player's layout space")
            if (edge) {
                assertTrue(menu.bottom < anchor.center.y, "The menu must flip above an anchor near the bottom: $menu vs $anchor")
            } else assertTrue(abs(menu.left - anchor.center.x) < 2f, "Menu $menu must open by the clicked anchor $anchor")
            assertTrue(menu.bottom <= after.bottom && menu.right <= after.right)

            val pixels = compose.onNodeWithTag("menu").captureToImage().toPixelMap()
            val split = (after.width * 0.6f - menu.left).toInt()
            val redSide = pixels[20, pixels.height / 2]
            val blueSide = pixels[pixels.width - 20, pixels.height / 2]
            assertTrue(redSide.red > redSide.blue + 0.1f, "Player red must contribute to the menu: $redSide")
            assertTrue(blueSide.blue > blueSide.red + 0.1f, "Player blue must contribute to the menu: $blueSide")
            // A translucent unblurred menu has a hard red/blue edge. Blur mixes both sides
            // several pixels away from that edge, independently of the dark menu tint.
            for (x in listOf(split - (5 * scale).toInt(), split + (5 * scale).toInt())) {
                val mixed = pixels[x, pixels.height / 2]
                assertTrue(mixed.red > 0.04f && mixed.blue > 0.04f, "Expected real blur at $x: $mixed")
            }
            if (edge) compose.onNodeWithTag("menu").performKeyInput { pressKey(Key.Escape) }
            else compose.onNodeWithText("Action").performMouseInput { click(center) }
            compose.onNodeWithTag("menu").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(if (edge) 0 else 1, actions)
                assertEquals(1, restored)
            }
        } finally {
            DesktopAppearanceSettings.setReduceDynamicBlur(oldReduce)
        }
    }

    @Composable
    private fun Frame(
        onMenuClosed: () -> Unit,
        overlay: @Composable () -> Unit,
        home: Boolean = false,
        panel: @Composable () -> Unit = {},
        content: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color.Green)) },
    ) {
        DesktopFrame(
            containerColor = Color.Black,
            hasFloatingPlayer = false,
            homePanelOverlay = home,
            menuPortal = remember { DesktopMenuPortalState() },
            onMenuClosed = onMenuClosed,
            sidebarCollapsed = true,
            sidebarDrawerOpen = false,
            onSidebarCollapsedChange = {},
            onSidebarDrawerOpenChange = {},
            backdrop = { Box(Modifier.fillMaxSize().background(Color.Green)) },
            sidebar = { _, _, _ -> },
            bottomBar = {},
            topBar = {},
            overlay = overlay,
            trailing = panel,
        ) { content() }
    }
}
