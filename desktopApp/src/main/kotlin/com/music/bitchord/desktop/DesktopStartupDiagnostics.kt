package com.music.bitchord.desktop

import androidx.compose.ui.draw.drawWithContent
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap

/** Opt-in, one-shot milestones. Labels must be fixed phase names, never user data. */
internal object DesktopStartupDiagnostics {
    val enabled = java.lang.Boolean.getBoolean("bitchord.startupDiagnostics")
    private val origin = System.nanoTime()
    private val seen = if (enabled) ConcurrentHashMap.newKeySet<String>() else null

    fun mark(phase: String) {
        if (seen?.add(phase) != true) return
        val runtime = Runtime.getRuntime()
        System.err.println("startup phase=$phase elapsedMs=${(System.nanoTime() - origin) / 1_000_000} " +
            "processUptimeMs=${ManagementFactory.getRuntimeMXBean().uptime} " +
            "heapUsedBytes=${runtime.totalMemory() - runtime.freeMemory()} " +
            "heapCommittedBytes=${runtime.totalMemory()}")
        // Heap is not RSS: native graphics, audio and model allocations are measured externally.
    }
}

@androidx.compose.runtime.Composable
internal fun androidx.compose.ui.Modifier.startupDrawMilestone(phase: String): androidx.compose.ui.Modifier {
    if (!DesktopStartupDiagnostics.enabled) return this
    val drawn = androidx.compose.runtime.remember(phase) { booleanArrayOf(false) }
    return this.drawWithContent {
        drawContent()
        if (!drawn[0]) { drawn[0] = true; DesktopStartupDiagnostics.mark(phase) }
    }
}
