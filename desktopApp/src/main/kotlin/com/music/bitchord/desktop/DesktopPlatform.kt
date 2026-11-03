package com.music.bitchord.desktop

/** Which desktop this is running on. */
internal object DesktopPlatform {
    private val name: String = System.getProperty("os.name").orEmpty()
    val isWindows: Boolean = name.startsWith("Windows", ignoreCase = true)
    val isMac: Boolean = name.startsWith("Mac", ignoreCase = true)
    val isLinux: Boolean = name.contains("linux", ignoreCase = true)

}
