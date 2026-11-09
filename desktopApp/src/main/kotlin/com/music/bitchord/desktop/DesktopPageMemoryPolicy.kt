package com.music.bitchord.desktop

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import java.nio.file.Files
import java.nio.file.Path

/** Process resident memory, including native audio, images, and renderer allocations. */
internal object DesktopPageMemoryPolicy {
    const val TRIM_THRESHOLD_BYTES = 650L * 1_024L * 1_024L

    fun <T> oldestInactive(usageOldestFirst: List<T>, active: T, evictable: Set<T>): T? =
        usageOldestFirst.firstOrNull { it != active && it in evictable }

    fun oldestEvictable(usageOldestFirst: List<DesktopDestination>, active: DesktopDestination) =
        oldestInactive(usageOldestFirst, active,
            setOf(DesktopDestination.LISTEN_NOW, DesktopDestination.NEW,
                  DesktopDestination.SEARCH, DesktopDestination.EXPLORE))

    fun trimProcessMemory() {
        DesktopArtworkCache.clear()
        DesktopImageLoader.trimMemory()
        System.gc()
        if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) {
            runCatching { LinuxLibC.instance?.malloc_trim(0L) }
        }
    }

    // Do not substitute JVM heap size for resident memory: native allocations are substantial.
    fun processResidentBytes(): Long? = runCatching {
        when {
            System.getProperty("os.name").contains("Linux", ignoreCase = true) -> {
                Files.readAllLines(Path.of("/proc/self/status"))
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter(':')?.trim()
                    ?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.times(1024L)
            }
            System.getProperty("os.name").contains("Windows", ignoreCase = true) -> {
                val counters = ProcessCounters()
                counters.cb = counters.size()
                if (WindowsProcessMemory.instance.GetProcessMemoryInfo(
                        Kernel32.INSTANCE.GetCurrentProcess(), counters, counters.size()
                    )) counters.WorkingSetSize else null
            }
            else -> null
        }
    }.getOrNull()

    // The shipped Windows desktop is x86_64, whose SIZE_T is eight bytes.
    @Structure.FieldOrder(
        "cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize",
        "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
        "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage",
        "PagefileUsage", "PeakPagefileUsage"
    )
    class ProcessCounters : Structure() {
        @JvmField var cb: Int = 0
        @JvmField var PageFaultCount: Int = 0
        @JvmField var PeakWorkingSetSize: Long = 0
        @JvmField var WorkingSetSize: Long = 0
        @JvmField var QuotaPeakPagedPoolUsage: Long = 0
        @JvmField var QuotaPagedPoolUsage: Long = 0
        @JvmField var QuotaPeakNonPagedPoolUsage: Long = 0
        @JvmField var QuotaNonPagedPoolUsage: Long = 0
        @JvmField var PagefileUsage: Long = 0
        @JvmField var PeakPagefileUsage: Long = 0
    }

    private interface WindowsProcessMemory : StdCallLibrary {
        fun GetProcessMemoryInfo(process: WinNT.HANDLE, counters: ProcessCounters, size: Int): Boolean
        companion object {
            val instance: WindowsProcessMemory by lazy {
                Native.load("psapi", WindowsProcessMemory::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }
        }
    }

    private interface LinuxLibC : com.sun.jna.Library {
        fun malloc_trim(pad: Long): Int
        companion object {
            val instance: LinuxLibC? by lazy {
                runCatching { Native.load("c", LinuxLibC::class.java) }.getOrNull()
            }
        }
    }
}
