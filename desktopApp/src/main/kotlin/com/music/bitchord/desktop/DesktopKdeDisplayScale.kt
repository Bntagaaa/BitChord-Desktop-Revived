package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible

internal fun isKdeDesktop(
    currentDesktop: String? = System.getenv("XDG_CURRENT_DESKTOP"),
    fullSession: String? = System.getenv("KDE_FULL_SESSION"),
): Boolean = currentDesktop.orEmpty().split(':').any { it.trim().equals("KDE", ignoreCase = true) } ||
    fullSession?.trim()?.lowercase() in setOf("true", "1")

/** Only call with enabled=true for the Linux X11 AWT toolkit, including XWayland. */
@Composable
internal fun rememberKdeX11Scale(enabled: Boolean): State<Float?> {
    val source = remember(enabled) {
        if (!enabled || !isKdeDesktop()) null else {
            val directory = kdeScaleConfigDirectory(System.getenv("XDG_CONFIG_HOME"), System.getProperty("user.home"))
            val wayland = when (System.getenv("XDG_SESSION_TYPE")?.trim()?.lowercase()) {
                "wayland" -> true
                "x11" -> false
                else -> !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
            }
            directory?.let { KdeScaleSource(it, wayland) }
        }
    }
    return produceState<Float?>(null, source) {
        value = null
        if (source != null) {
            kdeX11ScaleUpdates(source.directory, source.wayland).collect { value = it }
        }
    }
}

private data class KdeScaleSource(val directory: Path, val wayland: Boolean)

internal fun kdeScaleConfigDirectory(configHome: String?, userHome: String?): Path? {
    fun absolutePath(value: String?): Path? = try {
        value?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.takeIf { it.isAbsolute }
    } catch (_: IllegalArgumentException) {
        null
    }
    // XDG requires an absolute path; ignore an invalid override instead of resolving it in cwd.
    return absolutePath(configHome) ?: absolutePath(userHome)?.resolve(".config")
}

internal fun parseKdeX11Scale(wayland: Boolean, kdeglobals: String?, kwinrc: String?): Float? {
    if (wayland) {
        // KWin deletes its client scale when the compositor scales legacy applications.
        // An explicit preference also wins during the brief interval before that deletion.
        val clientsScale = kdeConfigEntry(kdeglobals, "KScreen", "XwaylandClientsScale")?.lowercase()
        if (clientsScale in setOf("false", "0", "no", "off")) return 1f
    }
    val raw = if (wayland) kdeConfigEntry(kwinrc, "Xwayland", "Scale")
        else kdeConfigEntry(kdeglobals, "KScreen", "ScaleFactor")
    return raw?.toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..5f }
}

private fun kdeConfigEntry(contents: String?, group: String, key: String): String? {
    var inGroup = false
    var result: String? = null
    contents?.lineSequence()?.forEach { raw ->
        val line = raw.trim().removePrefix("\uFEFF")
        if (line.startsWith('[')) {
            inGroup = line == "[$group]" || line == "[$group][\$i]"
        } else if (inGroup && !line.startsWith('#') && !line.startsWith(';')) {
            val separator = line.indexOf('=')
            if (separator >= 0 && line.substring(0, separator).trim().removeSuffix("[\$i]") == key) {
                result = line.substring(separator + 1).trim()
            }
        }
    }
    return result
}

/**
 * Plasma publishes only the integer part in Gdk/WindowScalingFactor. Read its actual
 * client coordinate scale, which also avoids confusing old forced font DPI with UI scale.
 * Plasma 6.8 source: KDE/kde-gtk-config@20c6849a4ed188d87b40a01e269ffdae59336cba,
 * kded/configvalueprovider.cpp (x11GlobalScaleFactor) and kded/gtkconfig.cpp (setTextScale).
 * KWin's Application::applyXwaylandScale writes/deletes kwinrc [Xwayland] Scale to match
 * the chosen legacy-application scaling mode; physical monitor scale is not substituted.
 */
internal fun kdeX11ScaleUpdates(directory: Path, wayland: Boolean): Flow<Float?> = flow {
    fun readScale(): Float? = parseKdeX11Scale(
        wayland,
        readKdeScaleFile(directory.resolve("kdeglobals")),
        if (wayland) readKdeScaleFile(directory.resolve("kwinrc")) else null,
    )

    // Watch the directory, not the file inode: KConfig saves using atomic replacement.
    // Register before reading so a change during the initial read is not missed.
    val watcher = openKdeScaleWatcher(directory)
    if (watcher == null) {
        emit(readScale())
        return@flow
    }
    watcher.use {
        emit(readScale())
        while (currentCoroutineContext().isActive) {
            val key = try {
                runInterruptible { watcher.take() }
            } catch (_: ClosedWatchServiceException) {
                break
            }
            val changed = key.pollEvents().any { event ->
                event.kind() == OVERFLOW || (event.context() as? Path)?.fileName?.toString() in
                    setOf("kdeglobals", "kwinrc")
            }
            val valid = key.reset()
            if (changed || !valid) {
                // Coalesce truncate/write and related KConfig notifications without polling.
                delay(60)
                emit(readScale())
            }
            if (!valid) break
        }
    }
}.distinctUntilChanged().flowOn(Dispatchers.IO)

private fun readKdeScaleFile(path: Path): String? = try {
    Files.readString(path)
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private fun openKdeScaleWatcher(directory: Path): WatchService? {
    var watcher: WatchService? = null
    return try {
        directory.fileSystem.newWatchService().also {
            watcher = it
            directory.register(it, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        }
    } catch (_: IOException) {
        closeKdeScaleWatcher(watcher)
        null
    } catch (_: UnsupportedOperationException) {
        closeKdeScaleWatcher(watcher)
        null
    } catch (_: SecurityException) {
        closeKdeScaleWatcher(watcher)
        null
    }
}

private fun closeKdeScaleWatcher(watcher: WatchService?) {
    try {
        watcher?.close()
    } catch (_: IOException) {
        // An unavailable optional scale source must not prevent opening the app.
    }
}
