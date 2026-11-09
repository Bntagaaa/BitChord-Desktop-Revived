package com.music.bitchord.desktop.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Application lifetime, independent of window visibility and presentation scopes. */
class DesktopResourceOwner(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onCloseFailure: (String, Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val lock = Any()
    private val resources = LinkedHashMap<String, AutoCloseable>()
    private var closed = false
    val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("BitChord desktop services"))

    fun <T : AutoCloseable> own(name: String, create: () -> T): T = synchronized(lock) {
        check(!closed) { "Desktop resource owner is closed" }
        check(name !in resources) { "Resource already registered: $name" }
        create().also { resources[name] = it }
    }

    override fun close() {
        val closing = synchronized(lock) {
            if (closed) return
            closed = true
            resources.entries.toList().asReversed().also { resources.clear() }
        }
        scope.cancel()
        for ((name, resource) in closing) {
            try { resource.close() } catch (failure: Exception) {
                runCatching { onCloseFailure(name, failure) }
            }
        }
    }
}
