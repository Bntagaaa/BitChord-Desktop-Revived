package com.music.bitchord.desktop.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lazily created native resource per key, with serialized use.
 * Invalidation retires busy resources until their call finishes; it never
 * closes a handle while another thread is executing through it.
 */
class KeyedResourceRegistry<K : Any, V : AutoCloseable>(
    private val onCloseFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private class Slot<V> {
        val mutex = Mutex()
        var resource: V? = null
        var busy = false
        var retired = false
    }
    private val lock = Any()
    private val slots = mutableMapOf<K, Slot<V>>()
    private var closed = false

    suspend fun <R> withResource(key: K, create: suspend () -> V, use: suspend (V) -> R): R {
        val slot = synchronized(lock) {
            check(!closed) { "Resource registry is closed" }
            slots.getOrPut(key) { Slot() }
        }
        return slot.mutex.withLock {
            synchronized(lock) {
                if (slot.retired) throw CancellationException("Resource was invalidated")
                slot.busy = true
            }
            try {
                val resource = slot.resource ?: create().let { created ->
                    var published = false
                    try {
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) {
                            if (slot.retired) throw CancellationException("Resource was invalidated during creation")
                            slot.resource = created
                            published = true
                        }
                        created
                    } finally {
                        if (!published) closeResource(created)
                    }
                }
                use(resource)
            } finally {
                val retired = synchronized(lock) {
                    slot.busy = false
                    if (slot.retired) slot.resource.also { slot.resource = null } else null
                }
                retired?.let(::closeResource)
            }
        }
    }

    /** Reload is reversible; calls started after invalidation acquire new resources. */
    fun clear() = retire(permanent = false)

    override fun close() = retire(permanent = true)

    private fun retire(permanent: Boolean) {
        val idle = synchronized(lock) {
            if (closed) return
            if (permanent) closed = true
            slots.values.mapNotNull { slot ->
                slot.retired = true
                if (!slot.busy) slot.resource.also { slot.resource = null } else null
            }.also { slots.clear() }
        }
        idle.forEach(::closeResource)
    }

    private fun closeResource(resource: V) {
        try { resource.close() } catch (failure: Exception) {
            runCatching { onCloseFailure(failure) }
        }
    }
}
