package com.music.bitchord.desktop

/** Bound queued work to the playing track and the next track. */
internal class DesktopAutomixWorkQueue<T> {
    @Volatile private var focusIds: List<String>? = null
    private val pending = LinkedHashMap<String, T>()

    @Synchronized
    fun focus(currentId: String?, nextId: String?) {
        val ids = listOfNotNull(
            currentId?.takeIf { it.isNotBlank() },
            nextId?.takeIf { it.isNotBlank() },
        ).distinct().take(2)
        focusIds = ids
        pending.keys.retainAll(ids.toSet())
    }

    fun isRelevant(id: String): Boolean = focusIds?.contains(id) ?: true

    @Synchronized
    fun offer(id: String, task: T): Boolean {
        if (!isRelevant(id) || id in pending || pending.size >= 2) return false
        pending[id] = task
        return true
    }

    @Synchronized
    fun poll(): Pair<String, T>? {
        val id = focusIds?.firstOrNull { it in pending } ?: pending.keys.firstOrNull()
        return id?.let { key -> key to pending.remove(key)!! }
    }

    @Synchronized
    fun pendingIds(): List<String> = pending.keys.toList()

    @Synchronized
    fun clear() {
        pending.clear()
        focusIds = emptyList()
    }
}
