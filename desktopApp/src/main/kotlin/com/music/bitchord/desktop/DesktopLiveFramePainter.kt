package com.music.bitchord.desktop

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/** Frame changes invalidate drawing. Geometry invalidates layout only when dimensions change. */
internal class DesktopLiveFramePainter(private val frame: State<Painter?>) : Painter() {
    private val dimensions = derivedStateOf { frame.value?.intrinsicSize ?: Size.Unspecified }
    override val intrinsicSize: Size get() = dimensions.value

    override fun DrawScope.onDraw() {
        frame.value?.let { with(it) { draw(size) } }
    }
}
