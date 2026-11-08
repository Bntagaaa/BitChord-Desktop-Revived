package com.music.bitchord.data.listentogether

import com.music.bitchord.reworked.DesktopStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** New JVM storage adapter for the original mobile transport's small preference contract. */
internal class PartyPreferences {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val values = DesktopStore.readText("party-state.json")?.let {
        runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
    }.orEmpty().toMutableMap()

    fun getString(key: String, default: String?): String? = synchronized(lock) { values[key] ?: default }
    fun edit() = Editor()

    inner class Editor {
        private val changes = linkedMapOf<String, String?>()
        fun putString(key: String, value: String) = apply { changes[key] = value }
        fun remove(key: String) = apply { changes[key] = null }
        fun apply() = synchronized(lock) {
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            DesktopStore.writeText("party-state.json", json.encodeToString(values.toMap()))
        }
    }
}
