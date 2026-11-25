package com.music.bitchord.desktop

import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.music.bitchord.ui.player.CurrentTrackArtwork
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Schedules the desktop's remote Coil loads, including those from shared Compose surfaces. */
internal class DesktopArtworkRequests(
    maxConcurrent: Int = 5,
    private val clock: Clock = Clock.systemUTC(),
    private val jitterMs: () -> Long = { Random.nextLong(150) },
    private val trace: ((String) -> Unit)? = null,
) : Interceptor {
    private class RequestGroup(val gate: Mutex = Mutex(), var readers: Int = 0)
    private val lock = Any()
    private val groups = mutableMapOf<String, RequestGroup>()
    private val total = Semaphore(maxConcurrent)
    // FIFO permits keep thumbnails moving while reserving one lane for the active player.
    private val thumbnails = Semaphore(maxConcurrent - 1)
    private val requestIds = AtomicLong()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            return chain.proceed()
        }
        val group = synchronized(lock) {
            groups.getOrPut(url, ::RequestGroup).also { it.readers++ }
        }
        val prefix = trace?.let {
            "artwork image=${url.hashCode().toUInt().toString(16)} request=${requestIds.incrementAndGet()}"
        }
        trace?.invoke("$prefix event=start")
        val currentTrack = chain.request.getExtra(CurrentTrackArtwork)
        try {
            if (group.gate.isLocked) trace?.invoke("$prefix event=waiting_for_image")
            // Recheck Coil's memory and disk cache once an exact-URL request ahead of this one
            // finishes. Coil still validates the requested dimensions, policies and cache keys.
            return group.gate.withLock {
                for (attempt in 0..MAX_RETRIES) {
                    val result = if (currentTrack) {
                        total.withPermit { chain.proceed() }
                    } else {
                        thumbnails.withPermit { total.withPermit { chain.proceed() } }
                    }
                    if (result is SuccessResult) {
                        trace?.invoke("$prefix event=delivered source=${result.dataSource}")
                        return@withLock result
                    }
                    val error = (result as ErrorResult).throwable
                    val wait = retryDelay(error, attempt)
                    trace?.invoke("$prefix event=failed type=${error.javaClass.simpleName} " +
                        "status=${(error as? HttpException)?.response?.code} retry_ms=$wait")
                    if (wait == null) return@withLock result
                    delay(wait)
                }
                error("Artwork retry loop exhausted")
            }
        } catch (cancelled: CancellationException) {
            trace?.invoke("$prefix event=cancelled")
            throw cancelled
        } finally {
            synchronized(lock) {
                if (--group.readers == 0) groups.remove(url)
            }
        }
    }

    internal private fun retryDelay(error: Throwable, attempt: Int): Long? {
        if (attempt >= MAX_RETRIES) return null
        val response = (error as? HttpException)?.response
        if (response != null) {
            if (response.code !in RETRYABLE_HTTP) return null
            val retryAfter = response.headers["Retry-After"]?.trim()
            if (retryAfter != null) {
                val seconds = retryAfter.toLongOrNull()
                if (seconds != null) {
                    return seconds.coerceAtLeast(0).takeIf { it <= MAX_RETRY_WAIT_MS / 1000 }?.times(1000)
                }
                val deadline = runCatching {
                    ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                }.getOrNull()
                if (deadline != null) {
                    return (deadline.toEpochMilli() - clock.millis()).coerceAtLeast(0)
                        .takeIf { it <= MAX_RETRY_WAIT_MS }
                }
            }
            // A rate-limited CDN with no retry time gets a longer cooldown.
            if (response.code == 429) return (1_000L shl attempt) + jitterMs()
        } else if (error !is InterruptedIOException && error !is SocketException &&
            error !is UnknownHostException) {
            return null
        }
        return (300L shl attempt) + jitterMs()
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val MAX_RETRY_WAIT_MS = 4_000L
        val RETRYABLE_HTTP = setOf(408, 429, 500, 502, 503, 504)
    }
}
