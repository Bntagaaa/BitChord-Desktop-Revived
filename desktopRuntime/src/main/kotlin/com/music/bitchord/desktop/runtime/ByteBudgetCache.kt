package com.music.bitchord.desktop.runtime

/** Cache ownership is separate from a caller's live reference to the same value. */
class ByteBudgetCache<K : Any, V : Any>(val budgetBytes: Long, private val sizeOf: (V) -> Long) {
    init { require(budgetBytes >= 0) }
    private data class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(64, .75f, true)
    private var bytes = 0L
    val heldBytes: Long get() = synchronized(this) { bytes }
    val size: Int get() = synchronized(this) { entries.size }
    @Synchronized operator fun get(key: K): V? = entries[key]?.value
    @Synchronized fun contains(key: K): Boolean = entries.containsKey(key)

    @Synchronized fun put(key: K, value: V): Boolean {
        val weight = sizeOf(value)
        require(weight >= 0) { "A cache weight must be non-negative" }
        entries.remove(key)?.let { bytes -= it.bytes }
        // The caller still receives its value. Keeping a single oversized entry
        // would defeat the budget and needlessly retain it after the screen closes.
        if (weight > budgetBytes || budgetBytes == 0L) return false
        val iterator = entries.entries.iterator()
        while (bytes > budgetBytes - weight && iterator.hasNext()) {
            bytes -= iterator.next().value.bytes
            iterator.remove()
        }
        entries[key] = Entry(value, weight)
        bytes += weight
        return true
    }

    @Synchronized fun clear() { entries.clear(); bytes = 0 }
}
