package com.music.bitchord.reworked

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.music.bitchord.reworked.resources.Res as DesktopRes
import com.music.bitchord.reworked.resources.mobile_app_icon
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.ic_logo
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.painterResource
import java.awt.Window
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

internal object DesktopAppearanceExtras {
    enum class Icon { MOBILE, MONOCHROME, CUSTOM }
    enum class Backdrop(val nativeValue: Int) { AUTO(0), SOLID(1), MICA(2), ACRYLIC(3), TABBED(4) }
    private val prefs get() = DesktopStore.preferences
    var icon by mutableStateOf(runCatching { Icon.valueOf(prefs.get("app_icon", "MOBILE")) }.getOrDefault(Icon.MOBILE)); private set
    var backdrop by mutableStateOf(runCatching { Backdrop.valueOf(prefs.get("window_backdrop", "AUTO")) }.getOrDefault(Backdrop.AUTO)); private set
    var sidebarTint by mutableStateOf(prefs.getBoolean("wallpaper_sidebar", false)); private set
    var wallpaperColor by mutableStateOf<Color?>(null); private set
    var classicExplore by mutableStateOf(prefs.getBoolean("classic_explore_cards", false)); private set
    var iconRevision by mutableIntStateOf(0); private set
    val customIconFile get() = DesktopStore.root.resolve("custom-app-icon.png")
    val isWindows get() = System.getProperty("os.name").startsWith("Windows")
    @JvmName("updateIcon")
    fun setIcon(value: Icon) { icon = value; prefs.put("app_icon", value.name) }
    @JvmName("updateBackdrop")
    fun setBackdrop(value: Backdrop) { backdrop = value; prefs.put("window_backdrop", value.name) }
    @JvmName("updateSidebarTint")
    fun setSidebarTint(value: Boolean) { sidebarTint = value; prefs.putBoolean("wallpaper_sidebar", value) }
    @JvmName("updateClassicExplore")
    fun setClassicExplore(value: Boolean) { classicExplore = value; prefs.putBoolean("classic_explore_cards", value) }
    fun chooseIcon(onError: (String) -> Unit) {
        SwingUtilities.invokeLater {
            val picker = JFileChooser().apply { dialogTitle = "Choose an application icon"; fileFilter = FileNameExtensionFilter("PNG image", "png") }
            if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) runCatching {
                val input = picker.selectedFile.toPath()
                require(Files.size(input) <= 4 * 1024 * 1024) { "Choose a PNG under 4 MiB" }
                ImageIO.createImageInputStream(input.toFile()).use { stream ->
                    val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: error("The image could not be read")
                    try { reader.input = stream; require(reader.formatName.equals("png", true) && reader.getWidth(0) in 16..2048 && reader.getHeight(0) in 16..2048) { "Choose a PNG between 16 and 2048 pixels" } }
                    finally { reader.dispose() }
                }
                val temp = Files.createTempFile(DesktopStore.root, "app-icon-", ".part")
                try { Files.copy(input, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING); atomicMove(temp, customIconFile) } finally { Files.deleteIfExists(temp) }
                iconRevision++; setIcon(Icon.CUSTOM)
            }.onFailure { onError(it.message ?: "Could not read this icon") }
        }
    }
    suspend fun readWallpaper() {
        if (!isWindows || !sidebarTint || ReworkedAppearance.black) { wallpaperColor = null; return }
        val color = withContext(Dispatchers.IO) { runCatching {
            WindowsMedia.load()
            val path = WindowsMediaNative.wallpaper().takeIf(String::isNotBlank) ?: return@runCatching null
            val image = ImageIO.read(java.io.File(path)) ?: return@runCatching null
            var red = 0L; var green = 0L; var blue = 0L
            for (y in 0 until 16) for (x in 0 until 16) {
                val pixel = image.getRGB(x * image.width / 16, y * image.height / 16)
                red += pixel shr 16 and 255; green += pixel shr 8 and 255; blue += pixel and 255
            }
            Color((red/256).toInt(), (green/256).toInt(), (blue/256).toInt())
        }.getOrNull() }
        wallpaperColor = color
    }
}

@Composable
internal fun reworkedAppIcon(): Painter {
    val icon = DesktopAppearanceExtras.icon
    val custom = remember(icon, DesktopAppearanceExtras.iconRevision) {
        if (icon == DesktopAppearanceExtras.Icon.CUSTOM) runCatching { ImageIO.read(DesktopAppearanceExtras.customIconFile.toFile())?.toComposeImageBitmap() }.getOrNull() else null
    }
    return if (custom != null) BitmapPainter(custom) else if (icon == DesktopAppearanceExtras.Icon.MONOCHROME) painterResource(Res.drawable.ic_logo) else painterResource(DesktopRes.drawable.mobile_app_icon)
}

@Composable
internal fun ApplyWindowAppearance(window: Window) {
    val backdrop = DesktopAppearanceExtras.backdrop
    val black = ReworkedAppearance.black
    val dark = ReworkedAppearance.dark
    val tint = DesktopAppearanceExtras.sidebarTint
    LaunchedEffect(window, backdrop, black, dark) {
        if (DesktopAppearanceExtras.isWindows) runCatching {
            withTimeout(10_000) { while (!window.isDisplayable) delay(50) }
            withContext(Dispatchers.IO) { WindowsMedia.load() }
            WindowsMediaNative.backdrop(Pointer.nativeValue(Native.getComponentPointer(window)), if (black) 1 else backdrop.nativeValue, dark)
        }
    }
    LaunchedEffect(tint, black) { DesktopAppearanceExtras.readWallpaper() }
}

@Composable
internal fun DesktopExtraAppearanceSettings(onError: (String) -> Unit) {
    Text("Application icon", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(DesktopAppearanceExtras.Icon.MOBILE, DesktopAppearanceExtras.Icon.MONOCHROME).forEach { icon ->
            FilterChip(DesktopAppearanceExtras.icon == icon, onClick = { DesktopAppearanceExtras.setIcon(icon) }, label = { Text(icon.name.lowercase().replaceFirstChar(Char::titlecase)) })
        }
        OutlinedButton(onClick = { DesktopAppearanceExtras.chooseIcon(onError) }) { Text("Choose PNG…") }
    }
    ReworkedToggle("Classic Explore cards", DesktopAppearanceExtras.classicExplore, DesktopAppearanceExtras::setClassicExplore)
    if (DesktopAppearanceExtras.isWindows) {
        Text("Windows caption backdrop", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DesktopAppearanceExtras.Backdrop.entries.forEach { backdrop ->
            FilterChip(DesktopAppearanceExtras.backdrop == backdrop, enabled = !ReworkedAppearance.black, onClick = { DesktopAppearanceExtras.setBackdrop(backdrop) }, label = { Text(backdrop.name.lowercase().replaceFirstChar(Char::titlecase)) })
        } }
        Text("Mica and Acrylic require a supported Windows 11 build.", style = MaterialTheme.typography.bodySmall)
        if (!ReworkedAppearance.black) ReworkedToggle("Tint sidebar from wallpaper", DesktopAppearanceExtras.sidebarTint, DesktopAppearanceExtras::setSidebarTint)
    }
}
