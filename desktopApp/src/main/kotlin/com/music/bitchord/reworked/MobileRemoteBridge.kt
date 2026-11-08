package com.music.bitchord.reworked

import com.music.bitchord.data.Http
import com.music.bitchord.data.smb.SmbClient
import com.music.bitchord.data.webdav.WebDavConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fresh native transport bridge. FFmpeg can seek a mobile SMBJ File through
 * byte ranges, while WebDAV credentials stay at the exact authenticated host.
 * There is no page, browser or remote listener: only unguessable loopback URLs.
 */
internal object MobileRemoteBridge {
    private data class Ticket(val generation: Long, val smbPath: String? = null, val davUrl: String? = null, val authorization: String? = null)
    private val tickets = ConcurrentHashMap<String, Ticket>()
    @Volatile private var generation = 0L
    private val client = Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "reworked-remote-reader").apply { isDaemon = true } }
    private val serverInstance = lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = workers
            createContext("/") { handle(it) }
            start()
        }
    }
    private val server by serverInstance

    fun smbUrlFor(path: String): String = register(Ticket(generation, smbPath = safeSmbPath(path)))
    fun davUrlFor(url: String, username: String, password: String): String = register(
        Ticket(generation, davUrl = url, authorization = WebDavConfig.basicAuthHeader(username, password)),
    )
    fun invalidate() { generation++; tickets.clear() }
    fun close() { invalidate(); if (serverInstance.isInitialized()) server.stop(0); workers.shutdownNow() }

    private fun register(ticket: Ticket): String {
        // Reuse a ticket for the same live file so queue replay cannot grow this map indefinitely.
        val token = tickets.entries.firstOrNull { it.value == ticket }?.key ?: UUID.randomUUID().toString().also {
            tickets[it] = ticket
        }
        return "http://127.0.0.1:${server.address.port}/$token"
    }

    private fun handle(exchange: HttpExchange) {
        var sent = false
        fun headers(status: Int, length: Long) { exchange.sendResponseHeaders(status, length); sent = true }
        try {
            if (exchange.requestMethod !in setOf("GET", "HEAD")) { headers(405, -1); return }
            val ticket = tickets[exchange.requestURI.rawPath.removePrefix("/")]
            if (ticket == null || ticket.generation != generation) { headers(404, -1); return }
            if (ticket.smbPath != null) {
                SmbClient.openFile(ticket.smbPath).use { file ->
                    val size = file.length
                    require(size >= 0) { "The SMB server did not provide a file size" }
                    val rangeHeader = exchange.requestHeaders.getFirst("Range")
                    val range = remoteByteRange(rangeHeader, size)
                    if (range == null) {
                        exchange.responseHeaders.set("Content-Range", "bytes */$size")
                        headers(416, -1)
                        return
                    }
                    val count = range.last - range.first + 1
                    exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                    exchange.responseHeaders.set("Accept-Ranges", "bytes")
                    exchange.responseHeaders.set("Content-Length", count.toString())
                    if (rangeHeader != null) exchange.responseHeaders.set("Content-Range", "bytes ${range.first}-${range.last}/$size")
                    headers(if (rangeHeader == null) 200 else 206, if (exchange.requestMethod == "HEAD" || count == 0L) -1 else count)
                    if (exchange.requestMethod == "HEAD" || count == 0L) return
                    val bytes = ByteArray(64 * 1024)
                    var position = range.first
                    exchange.responseBody.use { output ->
                        while (position <= range.last && ticket.generation == generation) {
                            val wanted = minOf(bytes.size.toLong(), range.last - position + 1).toInt()
                            val read = file.read(bytes, position, 0, wanted)
                            if (read <= 0) throw IOException("SMB file ended before its declared length")
                            output.write(bytes, 0, read)
                            position += read
                        }
                    }
                }
            } else {
                val request = Request.Builder().url(checkNotNull(ticket.davUrl)).header("Accept-Encoding", "identity")
                ticket.authorization?.let { request.header("Authorization", it) }
                exchange.requestHeaders.getFirst("Range")?.let { request.header("Range", it) }
                if (exchange.requestMethod == "HEAD") request.head()
                client.newCall(request.build()).execute().use { response ->
                    // A redirected file must be reconfigured at its destination; do not send a
                    // private Authorization header to a host chosen by a redirect response.
                    if (response.code in 300..399) { headers(502, -1); return }
                    for (name in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) {
                        response.header(name)?.let { exchange.responseHeaders.set(name, it) }
                    }
                    val length = response.body.contentLength()
                    headers(response.code, if (exchange.requestMethod == "HEAD" || length == 0L) -1 else if (length > 0) length else 0)
                    if (exchange.requestMethod != "HEAD" && length != 0L) {
                        response.body.byteStream().use { input -> exchange.responseBody.use { output ->
                            val bytes = ByteArray(64 * 1024)
                            while (ticket.generation == generation) {
                                val read = input.read(bytes)
                                if (read < 0) break
                                output.write(bytes, 0, read)
                            }
                        } }
                    }
                }
            }
        } catch (_: Exception) {
            if (!sent) runCatching { headers(502, -1) }
        } finally { exchange.close() }
    }
}

/** Single RFC 7233 range, including suffix ranges. Invalid/multipart ranges are refused. */
internal fun remoteByteRange(header: String?, length: Long): LongRange? {
    if (length < 0) return null
    if (header == null) return 0 until length
    if (length == 0L || !header.startsWith("bytes=") || ',' in header) return null
    val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(header.trim()) ?: return null
    val startText = match.groupValues[1]
    val endText = match.groupValues[2]
    if (startText.isEmpty()) {
        val suffix = endText.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return maxOf(0, length - suffix)..length - 1
    }
    val start = startText.toLongOrNull()?.takeIf { it in 0 until length } ?: return null
    val end = if (endText.isBlank()) length - 1 else endText.toLongOrNull()?.coerceAtMost(length - 1) ?: return null
    return (start..end).takeIf { end >= start }
}
