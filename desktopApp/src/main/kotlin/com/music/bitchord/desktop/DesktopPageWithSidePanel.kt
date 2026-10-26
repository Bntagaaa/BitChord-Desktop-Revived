package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints

/** Both slots keep their composition identity when navigation changes the panel placement. */
@Composable
internal fun DesktopPageWithSidePanel(
    overlayPanel: Boolean,
    modifier: Modifier = Modifier,
    panel: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val panelWidth = remember { mutableIntStateOf(0) }
    val overlay = rememberUpdatedState(overlayPanel)
    val shelfInset = remember { { if (overlay.value) panelWidth.intValue else 0 } }
    Layout(
        modifier = modifier,
        content = {
            Box {
                CompositionLocalProvider(LocalDesktopShelfEndInset provides shelfInset) { content() }
            }
            Box(Modifier.fillMaxHeight().onSizeChanged { panelWidth.intValue = it.width }) { panel() }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val trailing = children[1].measure(Constraints(0, width, height, height))
        val pageWidth = if (overlayPanel) width else (width - trailing.width).coerceAtLeast(0)
        val page = children[0].measure(Constraints.fixed(pageWidth, height))
        layout(width, height) {
            page.place(0, 0)
            trailing.place(width - trailing.width, 0)
        }
    }
}
