package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Read the wallpaper location without changing the Windows desktop or window state. */
private interface DesktopWallpaperUser32 : StdCallLibrary {
    fun SystemParametersInfoW(action: Int, parameter: Int, output: Pointer, flags: Int): Boolean
}

/**
 * A lightweight, wallpaper-tinted Mica-style surface for the Compose navigation rail.
 *
 * Native DWM Mica targets an HWND, not a single child composable. BitChord deliberately keeps
 * its decorated Compose window opaque for correct Windows caption, Snap, and resize behavior.
 * This approximation samples the Windows wallpaper only when it changes, and renders an opaque
 * flat tint rather than introducing a live blur, transparency, or reflective Haze effect.
 */
internal object DesktopSidebarMica {
    private const val SPI_GETDESKWALLPAPER = 0x0073
    private const val WALLPAPER_BUFFER_CHARS = 4096
    private const val MAX_IMAGE_BYTES = 64L * 1024L * 1024L

    private val user32 by lazy {
        if (!DesktopPlatform.isWindows) null else runCatching {
            Native.load("user32", DesktopWallpaperUser32::class.java)
        }.getOrNull()
    }

    /** Win11-only. Old Windows, Linux and inaccessible registry values stay on flat surfaces. */
    val isSupported: Boolean by lazy {
        DesktopPlatform.isWindows && runCatching {
            Advapi32Util.registryGetStringValue(
                WinReg.HKEY_LOCAL_MACHINE,
                "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",
                "CurrentBuildNumber",
            ).toInt() >= 22000
        }.getOrDefault(false)
    }

    private val _wallpaperTone = MutableStateFlow<Color?>(null)
    val wallpaperTone: StateFlow<Color?> = _wallpaperTone
    private val cacheLock = Any()
    private var cachedSignature: String? = null
    private var cachedTone: Color? = null

    /** No bitmap reads, native calls, or image decoding on the Compose render thread. */
    suspend fun refresh() {
        if (!isSupported) {
            _wallpaperTone.value = null
            return
        }
        val next = withContext(Dispatchers.IO) {
            if (!transparencyAllowed()) null else readWallpaperTone()
        }
        _wallpaperTone.value = next
    }

    /** An opaque color with restrained wallpaper influence and legible foreground contrast. */
    fun surface(wallpaper: Color?, dark: Boolean, fallback: Color): Color {
        if (wallpaper == null) return fallback
        val neutral = if (dark) Color(0xFF171719) else Color(0xFFE9ECF2)
        // Never copy the wallpaper itself: a strong tint would reduce text contrast.
        return lerp(neutral, wallpaper, if (dark) 0.065f else 0.060f).copy(alpha = 1f)
    }

    private fun transparencyAllowed(): Boolean {
        // Match Windows' own accessibility and personalization choices.
        val highContrast = runCatching {
            Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on") as? Boolean
        }.getOrNull() ?: false
        if (highContrast) return false
        return runCatching {
            Advapi32Util.registryGetIntValue(
                WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "EnableTransparency",
            ) != 0
        }.getOrDefault(true)
    }

    private fun readWallpaperTone(): Color? {
        val wallpaper = wallpaperPath()?.let(::File)?.takeIf(File::isFile)
            ?: System.getenv("APPDATA")?.let {
                File(it, "Microsoft\\Windows\\Themes\\TranscodedWallpaper")
            }?.takeIf(File::isFile)
            ?: return null

        if (wallpaper.length() !in 1L..MAX_IMAGE_BYTES) return null
        val signature = wallpaper.absolutePath + ":" + wallpaper.lastModified() + ":" + wallpaper.length()
        synchronized(cacheLock) {
            if (signature == cachedSignature) return cachedTone
        }
        val tone = sampleImage(wallpaper)
        synchronized(cacheLock) {
            cachedSignature = signature
            cachedTone = tone
        }
        return tone
    }

    private fun wallpaperPath(): String? {
        val native = user32 ?: return null
        val output = Memory(WALLPAPER_BUFFER_CHARS * 2L).apply { clear() }
        val ok = runCatching {
            native.SystemParametersInfoW(SPI_GETDESKWALLPAPER, WALLPAPER_BUFFER_CHARS, output, 0)
        }.getOrDefault(false)
        if (!ok) return null
        return runCatching { output.getWideString(0L).trim() }
            .getOrNull()?.takeIf(String::isNotEmpty)
    }

    private fun sampleImage(file: File): Color? = runCatching {
        ImageIO.createImageInputStream(file)?.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width > 32_768 || height > 32_768) {
                    return@use null
                }
                val step = maxOf(1, maxOf(width, height) / 96)
                val parameters = reader.defaultReadParam.apply {
                    setSourceSubsampling(step, step, 0, 0)
                }
                val preview = reader.read(0, parameters)
                val xStep = maxOf(1, preview.width / 48)
                val yStep = maxOf(1, preview.height / 48)
                var red = 0L
                var green = 0L
                var blue = 0L
                var pixels = 0L
                for (y in 0 until preview.height step yStep) {
                    for (x in 0 until preview.width step xStep) {
                        val rgb = preview.getRGB(x, y)
                        if ((rgb ushr 24) < 128) continue
                        red += (rgb ushr 16) and 0xFF
                        green += (rgb ushr 8) and 0xFF
                        blue += rgb and 0xFF
                        pixels++
                    }
                }
                if (pixels == 0L) null else Color(
                    (red.toFloat() / pixels / 255f).coerceIn(0f, 1f),
                    (green.toFloat() / pixels / 255f).coerceIn(0f, 1f),
                    (blue.toFloat() / pixels / 255f).coerceIn(0f, 1f),
                    1f,
                )
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()
}
