package com.music.bitchord.desktop

/** One revision at a time; bounded to the caller's fixed set of period keys. */
internal class DesktopRevisionCache<K, V>(private val limit: Int) {
    private var version: Any? = null
    private val values = LinkedHashMap<K, V>()
    @Synchronized
    fun get(key: K, revision: Any, load: () -> V): V {
        if (version != revision) { values.clear(); version = revision }
        if (values.containsKey(key)) return values.getValue(key)
        val value = load()
        if (values.size >= limit) values.remove(values.keys.first())
        values[key] = value
        return value
    }
}
