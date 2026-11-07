package com.music.bitchord.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Psapi
import java.nio.file.Files
import java.nio.file.Path

/** Resident process memory includes native image, audio and renderer allocations. */
internal object DesktopPageMemoryPolicy {
    const val TRIM_THRESHOLD_BYTES = 1_024L * 1_024L * 1_024L

    fun <T> oldestInactive(usageOldestFirst: List<T>, active: T, evictable: Set<T>): T? =
        usageOldestFirst.firstOrNull { it != active && it in evictable }

    fun oldestEvictable(usageOldestFirst: List<DesktopDestination>, active: DesktopDestination) =
        oldestInactive(usageOldestFirst, active,
            setOf(DesktopDestination.LISTEN_NOW, DesktopDestination.NEW,
                  DesktopDestination.SEARCH, DesktopDestination.EXPLORE))

    // Do not use heap size as a fallback: that undercounts native memory.
    fun processResidentBytes(): Long? = runCatching {
        when {
            System.getProperty("os.name").contains("Linux", ignoreCase = true) -> {
                Files.readAllLines(Path.of("/proc/self/status"))
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter(':')?.trim()
                    ?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.times(1024L)
            }
            System.getProperty("os.name").contains("Windows", ignoreCase = true) -> {
                val counters = Psapi.PROCESS_MEMORY_COUNTERS()
                if (Psapi.INSTANCE.GetProcessMemoryInfo(Kernel32.INSTANCE.GetCurrentProcess(),
                        counters, counters.size())) counters.WorkingSetSize.longValue() else null
            }
            else -> null
        }
    }.getOrNull()
}
