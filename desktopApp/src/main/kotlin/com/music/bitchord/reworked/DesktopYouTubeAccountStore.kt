package com.music.bitchord.reworked

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * Fresh desktop equivalent of mobile AuthStore: Windows user-scoped DPAPI, and
 * owner-readable/writable files on Linux. Linux fallback is explicitly not
 * described as encrypted. No historical desktop credential code is reused.
 */
internal class DesktopYouTubeAccountStore(private val directory: Path = DesktopStore.root) : YouTubeAccountStore {
    @Serializable private data class Envelope(val protection: String, val payload: String)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val windows get() = System.getProperty("os.name").startsWith("Windows", true)
    override fun read(): YouTubeAccountRegistry? {
        val raw = DesktopStore.readText(directory, FILE) ?: return null
        return try {
            val envelope = json.decodeFromString<Envelope>(raw)
            val bytes = Base64.getDecoder().decode(envelope.payload)
            val plain = when (envelope.protection) {
                "windows-dpapi-user-v1" -> {
                    check(windows) { "A Windows account store cannot be opened on this operating system" }
                    WindowsYouTubeProtection.unprotect(bytes)
                }
                "linux-private-file-v1" -> {
                    check(!windows) { "Confirm the YouTube session again on this operating system" }
                    bytes
                }
                else -> error("Unsupported account-store protection")
            }
            try { json.decodeFromString<YouTubeAccountRegistry>(plain.toString(Charsets.UTF_8)) }
            finally { plain.fill(0); bytes.fill(0) }
        } catch (_: Exception) {
            // Do not let a JSON decoder put a fragment of credential data in the UI/log.
            throw IllegalStateException("Saved YouTube accounts could not be opened. Their file has been preserved.")
        }
    }
    override fun write(value: YouTubeAccountRegistry) {
        val plain = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        try {
            val encrypted = if (windows) WindowsYouTubeProtection.protect(plain) else plain
            try {
                val envelope = Envelope(if (windows) "windows-dpapi-user-v1" else "linux-private-file-v1", Base64.getEncoder().encodeToString(encrypted))
                val encoded = json.encodeToString(envelope)
                if (windows) DesktopStore.writeText(directory, FILE, encoded) else writePrivateFile(encoded)
            } finally { if (encrypted !== plain) encrypted.fill(0) }
        } finally { plain.fill(0) }
    }
    override fun legacyCookie(): String? = DesktopStore.readText(directory, "youtube-cookie.txt")
    override fun removeLegacyCookie() { Files.deleteIfExists(DesktopStore.resolveName(directory, "youtube-cookie.txt")) }

    private fun writePrivateFile(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16 * 1024 * 1024) { "The account store is too large" }
        Files.createDirectories(directory)
        check(Files.getFileStore(directory).supportsFileAttributeView("posix")) { "This folder cannot protect saved account files. Use a native Linux profile folder." }
        val target = DesktopStore.resolveName(directory, FILE)
        check(!Files.isSymbolicLink(target)) { "The account file cannot be a symbolic link" }
        val permissions = PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        val temp = Files.createTempFile(directory, ".youtube-accounts-", ".tmp", permissions)
        try {
            Files.write(temp, bytes)
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally { bytes.fill(0); Files.deleteIfExists(temp) }
    }
    companion object { const val FILE = "youtube-accounts.json" }
}

/** Win32 DATA_BLOB, using only the already declared JNA core dependency. */
@Structure.FieldOrder("cbData", "pbData")
internal class YouTubeDataBlob() : Structure() {
    @JvmField var cbData = 0
    @JvmField var pbData: Pointer? = null
    private var storage: Memory? = null
    constructor(bytes: ByteArray) : this() {
        cbData = bytes.size
        storage = Memory(bytes.size.coerceAtLeast(1).toLong()).also { it.write(0, bytes, 0, bytes.size) }
        pbData = storage; write()
    }
    fun erase() { storage?.clear(); storage?.close(); storage = null }
}

internal object WindowsYouTubeProtection {
    private interface Crypt32 : StdCallLibrary {
        fun CryptProtectData(input: YouTubeDataBlob, description: WString?, entropy: YouTubeDataBlob?, reserved: Pointer?, prompt: Pointer?, flags: Int, output: YouTubeDataBlob): Boolean
        fun CryptUnprotectData(input: YouTubeDataBlob, description: Pointer?, entropy: YouTubeDataBlob?, reserved: Pointer?, prompt: Pointer?, flags: Int, output: YouTubeDataBlob): Boolean
    }
    private interface Kernel32 : StdCallLibrary { fun LocalFree(pointer: Pointer?): Pointer? }
    private val crypt by lazy { Native.load("Crypt32", Crypt32::class.java) }
    private val kernel by lazy { Native.load("Kernel32", Kernel32::class.java) }
    fun protect(bytes: ByteArray) = transform(bytes, true)
    fun unprotect(bytes: ByteArray) = transform(bytes, false)
    private fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        require(bytes.size <= 16 * 1024 * 1024)
        val input = YouTubeDataBlob(bytes)
        val entropy = YouTubeDataBlob("BitChordReworked/YouTubeAccounts/v1".toByteArray())
        val output = YouTubeDataBlob()
        try {
            val succeeded = if (encrypt) crypt.CryptProtectData(input, WString("BitChord Reworked YouTube accounts"), entropy, null, null, 1, output)
                else crypt.CryptUnprotectData(input, null, entropy, null, null, 1, output)
            check(succeeded) { "Windows could not protect or open the saved YouTube session" }
            output.read()
            check(output.cbData in 1..(16 * 1024 * 1024) && output.pbData != null) { "Windows returned an invalid credential record" }
            return output.pbData!!.getByteArray(0, output.cbData)
        } finally {
            output.pbData?.let { pointer -> if (output.cbData > 0) pointer.clear(output.cbData.toLong()); kernel.LocalFree(pointer) }
            input.erase(); entropy.erase()
        }
    }
}
