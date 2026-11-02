package com.music.bitchord.desktop

/** Freshness of lightweight page metadata; decoded artwork is managed separately. */
internal class DesktopTabCachePolicy {
    private val freshAtNanos = HashMap<String, Long>()

    fun isStale(key: String, ttlNanos: Long, nowNanos: Long = System.nanoTime()): Boolean {
        require(ttlNanos >= 0L)
        val last = freshAtNanos[key] ?: return true
        return nowNanos < last || nowNanos - last >= ttlNanos
    }

    fun markFresh(key: String, nowNanos: Long = System.nanoTime()) {
        freshAtNanos[key] = nowNanos
    }

    fun invalidate(key: String) { freshAtNanos.remove(key) }
}
