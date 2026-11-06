package com.music.bitchord.desktop

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Extracts album art from local audio files and caches it on disk.
 *
 * The scan in [DesktopLocalMusic] runs on every launch and on every watcher
 * notification, so the extraction must not re-read a 40 MB FLAC each time.
 * The result is written once to a per-file sidecar under the app's own cache
 * directory, and the `file://` URI of that sidecar becomes the song's
 * [thumbnailUrl][com.music.bitchord.data.model.Song.thumbnailUrl] — from
 * there, [DesktopArtworkCache] handles it identically to a remote URL.
 *
 * Extraction order:
 * 1. Embedded artwork: FLAC PICTURE, MP3 ID3v2 APIC, M4A/MP4 covr.
 * 2. Folder cover image (case-insensitive): `cover.{jpg,jpeg,png}`, `folder.{jpg,jpeg,png}`,
 *    `front.{jpg,jpeg,png}`, or `album.{jpg,jpeg,png}` beside the file.
 *
 * Everything is best-effort and caught: a file whose artwork cannot be read
 * still appears in the library, just without a cover.
 */
internal object DesktopLocalArtwork {

    /** Where the files live, created on first use. */
    private val directory: Path by lazy {
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
            ?: "${System.getProperty("user.home")}/.cache"
        Path.of(base, "bitchord", "local-artwork").also {
            runCatching { Files.createDirectories(it) }
        }
    }

    /** Names, in priority order, that music folders use for a cover beside the tracks. */
    private val FOLDER_COVER_NAMES = listOf("cover", "folder", "front", "album", "artwork")
    private val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png")

    /** Used by the filesystem watcher as well as artwork discovery. */
    internal fun isFolderCoverFilename(filename: String): Boolean {
        val normalized = filename.lowercase(Locale.ROOT)
        return FOLDER_COVER_NAMES.any { name ->
            IMAGE_EXTENSIONS.any { extension -> normalized == name + "." + extension }
        }
    }

    /** Bump when extraction changes so PR #103's old negative markers are ignored. */
    private const val CACHE_VERSION = "2"
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

    /**
     * Returns a cached local image URI, or null when no cover exists.
     *
     * Cache identity includes the audio file's size/mtime AND the folder cover's
     * identity/mtime. In particular, a prior negative result must not hide a cover
     * added later, or an APIC/PICTURE tag written after the first library scan.
     * Changed artwork gets a different URI so Compose's produceState reloads it.
     *
     * cacheDirectory is injectable to keep regression tests out of the user's cache.
     */
    fun artworkUri(audioPath: Path, cacheDirectory: Path = directory): String? = runCatching {
        val absolute = audioPath.toAbsolutePath().normalize()
        val folderImage = folderCoverPath(absolute)
        val fingerprint = listOf(
            CACHE_VERSION,
            absolute.toString(),
            Files.size(absolute).toString(),
            Files.getLastModifiedTime(absolute).toString(),
            folderImage?.toString().orEmpty(),
            folderImage?.let { Files.size(it).toString() }.orEmpty(),
            folderImage?.let { Files.getLastModifiedTime(it).toString() }.orEmpty(),
        ).joinToString("|")
        val key = digest(fingerprint)
        Files.createDirectories(cacheDirectory)

        // Keep the extension truthful: desktop integrations may inspect the file
        // type, and a PNG should not be exported as a *.jpg sidecar.
        for (extension in listOf("jpg", "png")) {
            val existing = cacheDirectory.resolve(key + "." + extension)
            if (Files.isRegularFile(existing) && Files.size(existing) > 0) {
                return existing.toUri().toString()
            }
        }
        if (Files.isRegularFile(cacheDirectory.resolve(key + ".none"))) return null

        val bytes = extractEmbedded(absolute)?.takeIf { imageExtension(it) != null }
            ?: folderImage?.let(::readFolderCover)
        val extension = bytes?.let(::imageExtension)
        if (extension == null) {
            Files.write(cacheDirectory.resolve(key + ".none"), ByteArray(0))
            return null
        }
        val cached = cacheDirectory.resolve(key + "." + extension)
        Files.write(cached, bytes)
        cached.toUri().toString()
    }.getOrElse { failure ->
        System.err.println("BitChord: local artwork extraction failed for " + audioPath + ": " + failure.message)
        null
    }

    /**
     * Removes every cached sidecar. Called when the listener changes their
     * local-music folder — the old folder's covers are no longer relevant,
     * and a full re-extraction on the next scan picks up any that are.
     */
    fun clearCache() {
        runCatching {
            Files.list(directory).use { stream ->
                stream.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    // ---- Embedded extraction ------------------------------------------------

    private fun extractEmbedded(path: Path): ByteArray? = when (path.extension.lowercase(Locale.ROOT)) {
        "flac" -> extractFlac(path)
        "mp3" -> extractMp3(path)
        "m4a", "aac" -> extractMp4(path)
        else -> null
    }

    /**
     * Reads the FLAC metadata block chain and returns the first `PICTURE`
     * block's image bytes, or null.
     *
     * Only the metadata region is read — on a 40 MB file that is usually
     * under 64 kB — and parsing reuses the same block-header layout
     * [FlacTagger][com.music.bitchord.download.FlacTagger] writes.
     */
    private fun extractFlac(path: Path): ByteArray? = runCatching {
        RandomAccessFile(path.toFile(), "r").use { raf ->
            val magic = ByteArray(4)
            if (raf.read(magic) != 4) return@runCatching null
            if (magic.decodeToString() != "fLaC") return@runCatching null

            while (true) {
                val flagByte = raf.read()
                if (flagByte == -1) return@runCatching null
                val isLast = flagByte and 0x80 != 0
                val type = flagByte and 0x7F
                val lenBytes = ByteArray(3)
                if (raf.read(lenBytes) != 3) return@runCatching null
                val length = ((lenBytes[0].toInt() and 0xFF) shl 16) or
                    ((lenBytes[1].toInt() and 0xFF) shl 8) or
                    (lenBytes[2].toInt() and 0xFF)

                if (type == TYPE_PICTURE) {
                    return@runCatching readFlacPicture(raf)
                }

                // Skip this block's payload.
                val skipped = raf.skipBytes(length)
                if (skipped < length) {
                    // File is truncated.
                    return@runCatching null
                }
                if (isLast) break
            }
            null
        }
    }.getOrNull()

    /**
     * Reads a FLAC `PICTURE` block payload and returns the raw image bytes.
     *
     * Layout: picture-type (4B BE), MIME length (4B BE), MIME string,
     * description length (4B BE), description string, width (4B), height (4B),
     * colour depth (4B), colours (4B), data length (4B BE), data.
     */
    private fun readFlacPicture(raf: RandomAccessFile): ByteArray? {
        fun readBe(): Int {
            val b = ByteArray(4)
            if (raf.read(b) != 4) return -1
            return ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
        }

        readBe() // picture type — don't care which, just take the first one
        val mimeLen = readBe(); if (mimeLen < 0 || mimeLen > 256) return null
        raf.skipBytes(mimeLen)
        val descLen = readBe(); if (descLen < 0 || descLen > 4096) return null
        raf.skipBytes(descLen)
        raf.skipBytes(16) // width, height, colour depth, colours
        val dataLen = readBe(); if (dataLen <= 0 || dataLen > 20 * 1024 * 1024) return null
        val data = ByteArray(dataLen)
        raf.readFully(data)
        return data
    }

    /**
     * Reads the first `APIC` frame from an ID3v2 tag at the front of an MP3.
     *
     * Only ID3v2.3 and 2.4 are handled — v2.2 uses a three-byte frame id
     * `PIC` that is virtually extinct. The tag is read in one pass up to
     * 4 MB, which is generous: a cover over 1 MB is unusual.
     */
    private fun extractMp3(path: Path): ByteArray? = runCatching {
        RandomAccessFile(path.toFile(), "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) != 10) return@runCatching null
            if (header.decodeToString(0, 3) != "ID3") return@runCatching null

            val major = header[3].toInt() and 0xFF
            if (major !in 3..4) return@runCatching null

            // Synchsafe size: 4 × 7-bit bytes.
            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)
            if (tagSize <= 0 || tagSize > 4 * 1024 * 1024) return@runCatching null

            val tag = ByteArray(tagSize)
            raf.readFully(tag)

            findApicFrame(tag, major, header[5].toInt() and 0xFF)
        }
    }.getOrNull()

    /**
     * Locates and extracts an `APIC` frame from an ID3v2.3 or v2.4 tag body.
     */
    private fun findApicFrame(tag: ByteArray, major: Int, headerFlags: Int): ByteArray? {
        var offset = 0
        // Without this, ID3 tags that have an extended header are treated as
        // frame headers and scanning stops before reaching APIC.
        if (headerFlags and 0x40 != 0) {
            if (tag.size < 4) return null
            val extendedSize = if (major == 4) {
                synchsafe(tag, 0)
            } else {
                ((tag[0].toInt() and 0xFF) shl 24) or
                    ((tag[1].toInt() and 0xFF) shl 16) or
                    ((tag[2].toInt() and 0xFF) shl 8) or
                    (tag[3].toInt() and 0xFF)
            }
            offset = if (major == 3) extendedSize + 4 else extendedSize
            if (offset < 4 || offset > tag.size) return null
        }

        while (offset + 10 <= tag.size) {
            val frameId = tag.decodeToString(offset, offset + 4)
            if (frameId[0] == '\u0000') break
            val frameSize = if (major == 4) synchsafe(tag, offset + 4) else {
                ((tag[offset + 4].toInt() and 0xFF) shl 24) or
                    ((tag[offset + 5].toInt() and 0xFF) shl 16) or
                    ((tag[offset + 6].toInt() and 0xFF) shl 8) or
                    (tag[offset + 7].toInt() and 0xFF)
            }
            if (frameSize <= 0 || frameSize > tag.size - offset - 10) break

            val formatFlags = tag[offset + 9].toInt() and 0xFF
            val dataStart = offset + 10
            offset = dataStart + frameSize
            if (frameId != "APIC") continue

            // Encrypted/compressed picture frames cannot be decoded as-is.
            if ((major == 3 && formatFlags and 0xC0 != 0) ||
                (major == 4 && formatFlags and 0x0C != 0)
            ) continue

            // A grouping byte and (v2.4) data-length indicator precede the payload.
            val prefixBytes = if (major == 3) {
                if (formatFlags and 0x20 != 0) 1 else 0
            } else {
                (if (formatFlags and 0x40 != 0) 1 else 0) +
                    (if (formatFlags and 0x01 != 0) 4 else 0)
            }
            if (frameSize <= prefixBytes) continue
            val parsed = parseApicPayload(tag, dataStart + prefixBytes, frameSize - prefixBytes)
                ?: continue
            val bytes = if (headerFlags and 0x80 != 0 ||
                major == 4 && formatFlags and 0x02 != 0
            ) removeUnsynchronization(parsed) else parsed
            if (imageExtension(bytes) != null) return bytes
        }
        return null
    }

    private fun synchsafe(bytes: ByteArray, start: Int): Int =
        ((bytes[start].toInt() and 0x7F) shl 21) or
            ((bytes[start + 1].toInt() and 0x7F) shl 14) or
            ((bytes[start + 2].toInt() and 0x7F) shl 7) or
            (bytes[start + 3].toInt() and 0x7F)

    private fun removeUnsynchronization(bytes: ByteArray): ByteArray {
        val result = ByteArray(bytes.size)
        var length = 0
        var i = 0
        while (i < bytes.size) {
            result[length++] = bytes[i]
            if (bytes[i].toInt() and 0xFF == 0xFF &&
                i + 1 < bytes.size && bytes[i + 1].toInt() == 0
            ) i++
            i++
        }
        return result.copyOf(length)
    }

    /**
     * Parses an `APIC` frame payload: encoding byte, null-terminated MIME
     * string, picture-type byte, null-terminated description, then image data.
     */
    private fun parseApicPayload(tag: ByteArray, start: Int, size: Int): ByteArray? {
        if (size < 4) return null
        val end = start + size
        var pos = start + 1 // skip text-encoding byte

        // Skip MIME type (null-terminated).
        while (pos < end && tag[pos].toInt() != 0) pos++
        if (pos >= end) return null
        pos++ // past null

        // Skip picture type byte.
        if (pos >= end) return null
        pos++

        // Skip description (null-terminated; for UTF-16 this is double-null,
        // but the image data that follows is recognisable either way — a stray
        // null byte at the front of a JPEG or PNG is harmless to the decoder).
        val encoding = tag[start].toInt() and 0xFF
        if (encoding == 1 || encoding == 2) {
            // UTF-16: look for double-null terminator.
            while (pos + 1 < end) {
                if (tag[pos].toInt() == 0 && tag[pos + 1].toInt() == 0) { pos += 2; break }
                pos++
            }
        } else {
            while (pos < end && tag[pos].toInt() != 0) pos++
            if (pos < end) pos++ // past null
        }

        if (pos >= end) return null
        return tag.copyOfRange(pos, end)
    }

    // ---- MPEG-4 / M4A embedded covers ---------------------------------------

    /**
     * M4A and MP4 files store artwork in moov/udta/meta/ilst/covr/data.
     * Walk only metadata atom boundaries: never read large mdat audio payloads.
     */
    private fun extractMp4(path: Path): ByteArray? = runCatching {
        RandomAccessFile(path.toFile(), "r").use { raf ->
            findMp4Cover(raf, 0L, raf.length(), 0, false)
        }
    }.getOrNull()

    private fun findMp4Cover(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        depth: Int,
        inCover: Boolean,
    ): ByteArray? {
        if (depth > 8) return null
        var offset = start
        while (end - offset >= 8) {
            raf.seek(offset)
            val smallSize = raf.readInt().toLong() and 0xFFFF_FFFFL
            val nameBytes = ByteArray(4)
            raf.readFully(nameBytes)
            val type = nameBytes.decodeToString()
            var headerSize = 8L
            val size = when (smallSize) {
                0L -> end - offset
                1L -> {
                    if (end - offset < 16) return null
                    headerSize = 16L
                    raf.readLong()
                }
                else -> smallSize
            }
            if (size < headerSize || size > end - offset) return null
            val dataStart = offset + headerSize
            val boxEnd = offset + size
            when (type) {
                "moov", "udta", "meta", "ilst", "covr" -> {
                    // 'meta' is a FullBox: its first 4 bytes are version/flags.
                    val childStart = dataStart + if (type == "meta") 4L else 0L
                    if (childStart <= boxEnd) {
                        findMp4Cover(raf, childStart, boxEnd, depth + 1, type == "covr")
                            ?.let { return it }
                    }
                }
                "data" -> if (inCover) {
                    // 'data' begins with data-type/locale (8 bytes).
                    val imageStart = dataStart + 8
                    val length = boxEnd - imageStart
                    if (length in 1..MAX_IMAGE_BYTES.toLong()) {
                        val bytes = ByteArray(length.toInt())
                        raf.seek(imageStart)
                        raf.readFully(bytes)
                        if (imageExtension(bytes) != null) return bytes
                    }
                }
            }
            offset = boxEnd
        }
        return null
    }

    // ---- Folder cover fallback ----------------------------------------------

    /**
     * Case-insensitive lookup is important on Linux: Cover.JPG and cover.jpg
     * are different files on disk, but both are valid conventional cover names.
     */
    private fun folderCoverPath(audioPath: Path): Path? {
        val parent = audioPath.parent ?: return null
        val files = runCatching { Files.list(parent).use { it.toList() } }.getOrNull() ?: return null
        for (name in FOLDER_COVER_NAMES) {
            for (extension in IMAGE_EXTENSIONS) {
                files.firstOrNull {
                    Files.isRegularFile(it) &&
                        it.fileName.toString().equals(name + "." + extension, ignoreCase = true)
                }?.let { return it }
            }
        }
        return null
    }

    private fun readFolderCover(path: Path): ByteArray? = runCatching {
        if (Files.size(path) !in 1..MAX_IMAGE_BYTES.toLong()) return null
        Files.readAllBytes(path).takeIf { imageExtension(it) != null }
    }.getOrNull()

    /** The bytes, not the filename, decide whether this is a JPEG or PNG. */
    private fun imageExtension(bytes: ByteArray): String? = when {
        bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF -> "jpg"
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte() -> "png"
        else -> null
    }

    // ---- Utilities ----------------------------------------------------------

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)

    /** FLAC metadata block type for a `PICTURE`. */
    private const val TYPE_PICTURE = 6
}
