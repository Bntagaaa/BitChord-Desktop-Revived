package com.music.bitchord.desktop

import java.awt.Desktop
import java.awt.EventQueue
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.SystemSleepEvent
import java.awt.desktop.SystemSleepListener
import java.nio.file.Path

/** macOS owns the app lifecycle independently of its last visible window. */
internal object DesktopMacApplication {
    private val reopened = AppReopenedListener { EventQueue.invokeLater { DesktopWindowVisibility.raise() } }

    private val sleep = object : SystemSleepListener {
        override fun systemAboutToSleep(event: SystemSleepEvent) = Unit
        override fun systemAwoke(event: SystemSleepEvent) { DesktopAudioDevices.systemChanged() }
    }

    fun configure() {
        if (!DesktopPlatform.isMac) return
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "BitChord")
        System.setProperty("apple.awt.application.appearance", "system")
    }

    fun install(quit: () -> Unit) {
        if (!DesktopPlatform.isMac || !Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) desktop.addAppEventListener(reopened)
        if (desktop.isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP)) desktop.addAppEventListener(sleep)
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler { _, response ->
            // Existing Compose disposal shuts down playback and integrations.
            response.cancelQuit()
            EventQueue.invokeLater(quit)
        }
    }

    fun uninstall() {
        if (!DesktopPlatform.isMac || !Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        desktop.removeAppEventListener(reopened)
        desktop.removeAppEventListener(sleep)
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler(null)
    }
}

internal object DesktopMacPaths {
    private val library: Path get() = Path.of(System.getProperty("user.home"), "Library")
    val applicationSupport: Path get() = library.resolve("Application Support")
    val caches: Path get() = library.resolve("Caches")
    val logs: Path get() = library.resolve("Logs")
}
