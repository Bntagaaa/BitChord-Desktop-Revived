package com.music.bitchord.desktop

/** Only nonessential, reloadable page data belongs in this budget. */
internal enum class DesktopCachedPage {
    HOME, SEARCH, NEW, SPOTIFY, LOCAL_MUSIC,
}

/**
 * Evicts one least-recently-used inactive page when the *process* reaches the RSS budget.
 * UI composition is already disposed by navigation; callers clear the page's retained data.
 * Neither navigation history nor playback state belongs here.
 */
internal class DesktopPageCacheEvictor(
    private val thresholdBytes: Long = LIMIT_BYTES,
) {
    private val recentlyUsed = LinkedHashSet<DesktopCachedPage>()

    fun visited(page: DesktopCachedPage?) {
        if (page == null) return
        recentlyUsed.remove(page)
        recentlyUsed.add(page)
    }

    fun oldestInactiveUnderPressure(
        residentBytes: Long?,
        active: DesktopCachedPage?,
    ): DesktopCachedPage? {
        if (residentBytes == null || residentBytes < thresholdBytes) return null
        val oldest = recentlyUsed.firstOrNull { it != active } ?: return null
        recentlyUsed.remove(oldest)
        return oldest
    }

    internal fun retainedPagesOldestFirst(): List<DesktopCachedPage> = recentlyUsed.toList()

    companion object {
        const val LIMIT_BYTES = 1_024L * 1_024 * 1_024
        const val SAMPLE_INTERVAL_MS = 8_000L
    }
}
