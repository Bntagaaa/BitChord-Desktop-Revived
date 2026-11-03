package com.music.bitchord.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.util.concurrent.TimeUnit

/** Best-effort native OS appearance; no credentials, installation or network dependency. */
internal object DesktopSystemTheme {
    fun isDark(): Boolean = when {
        DesktopPlatform.isWindows -> windowsDark()
        DesktopPlatform.isLinux -> linuxDark()
        DesktopPlatform.isMac -> command("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle")?.trim().equals("Dark", true)
        else -> false
    }

    private fun windowsDark(): Boolean = runCatching {
        val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
        // AppsUseLightTheme is the Windows Apps theme, distinct from the taskbar theme.
        Advapi32Util.registryGetIntValue(
            WinReg.HKEY_CURRENT_USER, key, "AppsUseLightTheme",
        ) == 0
    }.getOrDefault(false)

    private fun linuxDark(): Boolean {
        // GTK 4 / GNOME: 'prefer-dark'; KDE, GTK 3 and other desktops may instead
        // expose a dark gtk-theme name. All probes are bounded and fail open to light.
        val scheme = command("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
        if (scheme?.contains("prefer-dark", ignoreCase = true) == true) return true
        if (scheme?.contains("prefer-light", ignoreCase = true) == true) return false
        val gtk = command("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")
        if (gtk?.contains("dark", ignoreCase = true) == true) return true
        return System.getenv("GTK_THEME")?.contains("dark", ignoreCase = true) == true
    }

    private fun command(vararg args: String): String? = runCatching {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!p.waitFor(1200, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            null
        } else if (p.exitValue() != 0) null else p.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
}
