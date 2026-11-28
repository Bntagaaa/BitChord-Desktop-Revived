package com.music.bitchord.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as platformFont
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Frame
import java.awt.Window
import java.io.File
import kotlin.math.roundToInt

internal object DesktopWindowsCaptionMetrics {
    val BUTTON_WIDTH: Dp = 46.dp
    const val BUTTON_COUNT: Int = 3
    val TOTAL_WIDTH: Dp = BUTTON_WIDTH * BUTTON_COUNT // 138.dp
    val INTEGRATED_TITLE_BAR_HEIGHT: Dp = 32.dp
    val TOP_PLAYER_HEIGHT: Dp = 64.dp

    fun isIntegratedTitleBarActive(isWindows: Boolean, isFullscreen: Boolean): Boolean =
        isWindows && !isFullscreen

    fun integratedTitleBarHeight(uiScalePercent: Int = 100): Dp =
        INTEGRATED_TITLE_BAR_HEIGHT * (100f / uiScalePercent.coerceAtLeast(1))

    fun captionInsetEnd(
        isWindows: Boolean,
        isFullscreen: Boolean,
        uiScalePercent: Int = 100,
    ): Dp =
        if (isIntegratedTitleBarActive(isWindows, isFullscreen)) {
            TOTAL_WIDTH * (100f / uiScalePercent.coerceAtLeast(1))
        } else {
            0.dp
        }
}

internal class DesktopTitleBarHitTestRegistry {
    val interactiveRects = mutableStateMapOf<String, Rect>()
    var minRect by mutableStateOf(Rect.Zero)
    var maxRect by mutableStateOf(Rect.Zero)
    var closeRect by mutableStateOf(Rect.Zero)
    var overlayBlocked by mutableStateOf(false)
}

internal data class DesktopWindowActions(
    val onMinimize: () -> Unit,
    val onToggleMaximize: () -> Unit,
    val onClose: () -> Unit,
)

internal val LocalDesktopWindowActions = compositionLocalOf<DesktopWindowActions?> { null }

internal val LocalDesktopSystemDensity = compositionLocalOf<Density?> { null }

internal val LocalDesktopTitleBarHitTestRegistry =
    compositionLocalOf<DesktopTitleBarHitTestRegistry?> { null }

@Composable
internal fun Modifier.registerTitleBarInteractive(key: String): Modifier {
    val registry = LocalDesktopTitleBarHitTestRegistry.current ?: return this
    DisposableEffect(registry, key) {
        onDispose { registry.interactiveRects.remove(key) }
    }
    return this.onGloballyPositioned { coords ->
        registry.interactiveRects[key] = coords.boundsInWindow()
    }
}

/**
 * Windows-only native caption bridge backed by `bitchord_window.dll` (`native_caption.cpp`).
 * Subclasses the decorated HWND with `WM_NCCALCSIZE` and `WM_NCHITTEST` so the Compose client
 * extends into the title bar while preserving native resize borders, Aero Snap, and Snap Layouts.
 */
internal object DesktopWindowsNativeCaption {
    private val available: Boolean by lazy {
        DesktopPlatform.isWindows &&
            runCatching { DesktopAnalysisRuntime.loadNative(LIBRARY) }
                .onFailure { DesktopTrackLog.log("native Windows caption unavailable: ${it.message}") }
                .isSuccess
    }

    private var attachedWindow: Window? = null
    private var hwndPtr: Long = 0L
    var attachedRevision by mutableStateOf(0)
        private set

    fun attach(window: Window, titleBarHeightPx: Int): Boolean {
        if (!available || window !is Frame || window.isUndecorated || !window.isDisplayable) return false
        val pointer = runCatching { Native.getWindowPointer(window) }.getOrNull()
            ?.let { Pointer.nativeValue(it) }
            ?.takeIf { it != 0L } ?: return false
        val ok = runCatching { nativeAttach(pointer, titleBarHeightPx.coerceAtLeast(0)) }.getOrDefault(false)
        if (ok) {
            attachedWindow = window
            hwndPtr = pointer
            attachedRevision++
        }
        return ok
    }

    fun detach(window: Window) {
        if (attachedWindow === window && hwndPtr != 0L) {
            runCatching { nativeDetach(hwndPtr) }
            attachedWindow = null
            hwndPtr = 0L
        }
    }

    fun setTitleBarHeight(titleBarHeightPx: Int) {
        val ptr = hwndPtr
        if (available && ptr != 0L) {
            runCatching { nativeSetTitleBarHeight(ptr, titleBarHeightPx.coerceAtLeast(0)) }
        }
    }

    fun setFullscreen(enabled: Boolean) {
        val ptr = hwndPtr
        if (available && ptr != 0L) {
            runCatching { nativeSetFullscreen(ptr, enabled) }
        }
    }

    fun setOverlayBlocked(blocked: Boolean) {
        val ptr = hwndPtr
        if (available && ptr != 0L) {
            runCatching { nativeSetOverlayBlocked(ptr, blocked) }
        }
    }

    fun updateHitTestRects(
        minRect: Rect,
        maxRect: Rect,
        closeRect: Rect,
        interactiveRects: Collection<Rect>,
    ) {
        val ptr = hwndPtr
        if (!available || ptr == 0L) return
        val flatInteractive = IntArray(interactiveRects.size * 4)
        var idx = 0
        for (rect in interactiveRects) {
            flatInteractive[idx++] = rect.left.roundToInt()
            flatInteractive[idx++] = rect.top.roundToInt()
            flatInteractive[idx++] = rect.right.roundToInt()
            flatInteractive[idx++] = rect.bottom.roundToInt()
        }
        runCatching {
            nativeSetHitTestRects(
                ptr,
                encodeRect(minRect),
                encodeRect(maxRect),
                encodeRect(closeRect),
                flatInteractive,
            )
        }
    }

    private fun encodeRect(rect: Rect): IntArray = intArrayOf(
        rect.left.roundToInt(),
        rect.top.roundToInt(),
        rect.right.roundToInt(),
        rect.bottom.roundToInt(),
    )

    @JvmStatic private external fun nativeAttach(hwndPtr: Long, titleBarHeightPx: Int): Boolean
    @JvmStatic private external fun nativeDetach(hwndPtr: Long)
    @JvmStatic private external fun nativeSetTitleBarHeight(hwndPtr: Long, titleBarHeightPx: Int)
    @JvmStatic private external fun nativeSetFullscreen(hwndPtr: Long, fullscreen: Boolean)
    @JvmStatic private external fun nativeSetOverlayBlocked(hwndPtr: Long, blocked: Boolean)
    @JvmStatic private external fun nativeSetHitTestRects(
        hwndPtr: Long,
        minRect: IntArray,
        maxRect: IntArray,
        closeRect: IntArray,
        interactiveRects: IntArray,
    )

    @JvmStatic
    fun onCaptionButtonState(hovered: Int, pressed: Int) {
        if (java.awt.EventQueue.isDispatchThread()) {
            hoveredHitTest = hovered
            pressedHitTest = pressed
        } else {
            java.awt.EventQueue.invokeLater {
                hoveredHitTest = hovered
                pressedHitTest = pressed
            }
        }
    }

    var hoveredHitTest by mutableStateOf(0)
        private set
    var pressedHitTest by mutableStateOf(0)
        private set

    private const val LIBRARY = "bitchord_window"
}

private const val HT_MIN_BUTTON = 8
private const val HT_MAX_BUTTON = 9
private const val HT_CLOSE = 20

private object WindowsCaptionIconFont {
    const val GLYPH_MINIMIZE = "\uE921"
    const val GLYPH_MAXIMIZE = "\uE922"
    const val GLYPH_RESTORE = "\uE923"
    const val GLYPH_CLOSE = "\uE8BB"

    val family: FontFamily? by lazy { loadSystemCaptionFontFamily() }

    private fun loadSystemCaptionFontFamily(): FontFamily? {
        if (!DesktopPlatform.isWindows) return null
        val windir = System.getenv("WINDIR")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
        val fontsDir = File(windir, "Fonts")
        val candidates = listOf("SegoeIcons.ttf", "segmdl2.ttf")
        for (fileName in candidates) {
            val file = File(fontsDir, fileName)
            if (!file.isFile) continue
            val loaded = runCatching {
                FontFamily(platformFont("windows-caption-$fileName", file.readBytes(), FontWeight.Normal))
            }.getOrNull()
            if (loaded != null) return loaded
        }
        return null
    }
}

/** Default Windows 10/11 caption buttons (Minimize, Maximize/Restore, Close). */
@Composable
internal fun DesktopWindowsCaptionButtons(
    isMaximized: Boolean,
    height: Dp = DesktopWindowsCaptionMetrics.INTEGRATED_TITLE_BAR_HEIGHT,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val registry = LocalDesktopTitleBarHitTestRegistry.current
    val foreground = DesktopForeground
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val defaultIconTint = if (windowFocused) foreground else foreground.copy(alpha = 0.45f)
    val captionDensity = LocalDesktopSystemDensity.current ?: LocalDensity.current
    val captionFontFamily = WindowsCaptionIconFont.family

    LaunchedEffect(
        DesktopWindowsNativeCaption.attachedRevision,
        registry?.minRect,
        registry?.maxRect,
        registry?.closeRect,
        registry?.interactiveRects?.toMap(),
    ) {
        val reg = registry ?: return@LaunchedEffect
        DesktopWindowsNativeCaption.updateHitTestRects(
            minRect = reg.minRect,
            maxRect = reg.maxRect,
            closeRect = reg.closeRect,
            interactiveRects = reg.interactiveRects.values,
        )
    }

    LaunchedEffect(DesktopWindowsNativeCaption.attachedRevision, registry?.overlayBlocked) {
        val reg = registry ?: return@LaunchedEffect
        DesktopWindowsNativeCaption.setOverlayBlocked(reg.overlayBlocked)
    }

    val hoveredHitTest = DesktopWindowsNativeCaption.hoveredHitTest
    val pressedHitTest = DesktopWindowsNativeCaption.pressedHitTest

    CompositionLocalProvider(LocalDensity provides captionDensity) {
        Row(modifier = modifier.height(height), verticalAlignment = Alignment.CenterVertically) {
            CaptionButton(
                width = DesktopWindowsCaptionMetrics.BUTTON_WIDTH,
                height = height,
                nativeHovered = hoveredHitTest == HT_MIN_BUTTON,
                nativePressed = pressedHitTest == HT_MIN_BUTTON,
                defaultIconColor = defaultIconTint,
                hoverColor = foreground.copy(alpha = 0.09f),
                pressedColor = foreground.copy(alpha = 0.13f),
                onPositioned = { registry?.minRect = it },
                onClick = onMinimize,
            ) { iconColor ->
                if (captionFontFamily != null) {
                    CaptionGlyphText(WindowsCaptionIconFont.GLYPH_MINIMIZE, captionFontFamily, iconColor)
                } else {
                    Canvas(Modifier.size(10.dp)) {
                        val y = size.height / 2f
                        drawLine(
                            color = iconColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
            }
            CaptionButton(
                width = DesktopWindowsCaptionMetrics.BUTTON_WIDTH,
                height = height,
                nativeHovered = hoveredHitTest == HT_MAX_BUTTON,
                nativePressed = pressedHitTest == HT_MAX_BUTTON,
                defaultIconColor = defaultIconTint,
                hoverColor = foreground.copy(alpha = 0.09f),
                pressedColor = foreground.copy(alpha = 0.13f),
                onPositioned = { registry?.maxRect = it },
                onClick = onToggleMaximize,
            ) { iconColor ->
                if (captionFontFamily != null) {
                    CaptionGlyphText(
                        glyph = if (isMaximized) WindowsCaptionIconFont.GLYPH_RESTORE else WindowsCaptionIconFont.GLYPH_MAXIMIZE,
                        fontFamily = captionFontFamily,
                        color = iconColor,
                    )
                } else {
                    Canvas(Modifier.size(10.dp)) {
                        val stroke = 1.dp.toPx()
                        val half = stroke / 2f
                        if (isMaximized) {
                            val backOffset = 2.dp.toPx()
                            val boxSize = size.width - backOffset - stroke
                            drawRect(
                                color = iconColor,
                                topLeft = Offset(half, backOffset + half),
                                size = Size(boxSize, boxSize),
                                style = Stroke(width = stroke),
                            )
                            drawLine(
                                color = iconColor,
                                start = Offset(backOffset, half),
                                end = Offset(size.width - half, half),
                                strokeWidth = stroke,
                            )
                            drawLine(
                                color = iconColor,
                                start = Offset(size.width - half, half),
                                end = Offset(size.width - half, backOffset + boxSize),
                                strokeWidth = stroke,
                            )
                        } else {
                            drawRect(
                                color = iconColor,
                                topLeft = Offset(half, half),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(width = stroke),
                            )
                        }
                    }
                }
            }
            CaptionButton(
                width = DesktopWindowsCaptionMetrics.BUTTON_WIDTH,
                height = height,
                nativeHovered = hoveredHitTest == HT_CLOSE,
                nativePressed = pressedHitTest == HT_CLOSE,
                defaultIconColor = defaultIconTint,
                hoverColor = Color(0xFFC42B1C),
                pressedColor = Color(0xFFB22719),
                hoverIconColor = Color.White,
                onPositioned = { registry?.closeRect = it },
                onClick = onClose,
            ) { iconColor ->
                if (captionFontFamily != null) {
                    CaptionGlyphText(WindowsCaptionIconFont.GLYPH_CLOSE, captionFontFamily, iconColor)
                } else {
                    Canvas(Modifier.size(10.dp)) {
                        val stroke = 1.dp.toPx()
                        drawLine(
                            color = iconColor,
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                            strokeWidth = stroke,
                            cap = StrokeCap.Butt,
                        )
                        drawLine(
                            color = iconColor,
                            start = Offset(size.width, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = stroke,
                            cap = StrokeCap.Butt,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptionGlyphText(
    glyph: String,
    fontFamily: FontFamily,
    color: Color,
) {
    Text(
        text = glyph,
        color = color,
        style = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 10.sp,
            lineHeight = 10.sp,
        ),
        maxLines = 1,
    )
}

@Composable
private fun CaptionButton(
    width: Dp,
    height: Dp,
    nativeHovered: Boolean,
    nativePressed: Boolean,
    defaultIconColor: Color,
    hoverColor: Color,
    pressedColor: Color,
    hoverIconColor: Color? = null,
    onPositioned: (Rect) -> Unit,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pointerHovered by interactionSource.collectIsHoveredAsState()
    val pointerPressed by interactionSource.collectIsPressedAsState()
    val hovered = pointerHovered || nativeHovered
    val pressed = pointerPressed || nativePressed
    val background = when {
        pressed -> pressedColor
        hovered -> hoverColor
        else -> Color.Transparent
    }
    val iconTint = if ((hovered || pressed) && hoverIconColor != null) hoverIconColor else defaultIconColor
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(background)
            .onGloballyPositioned { onPositioned(it.boundsInWindow()) }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        icon(iconTint)
    }
}
