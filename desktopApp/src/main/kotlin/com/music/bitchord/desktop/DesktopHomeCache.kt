package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One bounded first page of Home. No session credentials or continuation tokens are persisted. */
internal class DesktopHomeCache(private val path: Path) {
    data class Identity(val accountId: String?, val profileId: String?) {
        fun matchesSession(session: DesktopYouTubeAuth.Session?, selectedProfile: DesktopYouTubeProfile?): Boolean {
            if (accountId == null) return session == null
            if (session == null) return false
            val page = selectedProfile?.pageId?.takeIf(String::isNotBlank)
            if (page != null) return session.pageId == page
            // Browser renewal may return a different login from the same browser profile.
            // Only associate a personalized page with a saved identity the session identifies.
            val sessionAccount = session.dataSyncId?.takeIf(String::isNotBlank) ?: return false
            return session.pageId.isNullOrBlank() &&
                (sessionAccount == accountId || sessionAccount == selectedProfile?.dataSyncId)
        }

        fun fingerprint(): String {
            val key = listOf(accountId.orEmpty(), profileId.orEmpty()).joinToString("") { "${it.length}:$it" }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
        }
    }

    fun read(identity: Identity): List<HomeShelf> = runCatching {
        val content = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
        if (content.size > MAX_BYTES) return emptyList()
        val cached = Json.decodeFromString<Envelope>(content.decodeToString())
        if (cached.version != VERSION || cached.identity != identity.fingerprint()) return emptyList()
        cached.shelves.take(MAX_SHELVES).map { shelf ->
            HomeShelf(shelf.title, shelf.items.take(MAX_ITEMS).map { item ->
                ShelfItem(item.title, item.subtitle, item.thumbnailUrl, item.videoId, item.browseId, item.isVideo)
            }, shelf.subtitle, shelf.moreBrowseId, shelf.moreParams, shelf.layout)
        }
    }.getOrDefault(emptyList())

    fun write(identity: Identity, shelves: List<HomeShelf>) {
        if (shelves.isEmpty()) return
        runCatching {
            val cached = Envelope(VERSION, identity.fingerprint(), shelves.take(MAX_SHELVES).map { shelf ->
                Shelf(shelf.title, shelf.subtitle, shelf.moreBrowseId, shelf.moreParams, shelf.layout,
                    shelf.items.take(MAX_ITEMS).map { item ->
                        Item(item.title, item.subtitle, item.thumbnailUrl, item.videoId, item.browseId, item.isVideo)
                    })
            })
            val bytes = Json.encodeToString(cached).encodeToByteArray()
            if (bytes.size > MAX_BYTES) return
            Files.createDirectories(path.parent)
            val partial = Files.createTempFile(path.parent, ".home-", ".tmp")
            try {
                Files.write(partial, bytes)
                Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(partial)
            }
        }
    }

    @Serializable
    private data class Envelope(val version: Int, val identity: String, val shelves: List<Shelf>)

    @Serializable
    private data class Shelf(
        val title: String, val subtitle: String, val moreBrowseId: String?, val moreParams: String?,
        val layout: ShelfLayout, val items: List<Item>,
    )

    @Serializable
    private data class Item(
        val title: String, val subtitle: String, val thumbnailUrl: String?,
        val videoId: String?, val browseId: String?, val isVideo: Boolean,
    )

    companion object {
        private const val VERSION = 1
        private const val MAX_BYTES = 384 * 1024
        private const val MAX_SHELVES = 8
        private const val MAX_ITEMS = 16
    }
}
