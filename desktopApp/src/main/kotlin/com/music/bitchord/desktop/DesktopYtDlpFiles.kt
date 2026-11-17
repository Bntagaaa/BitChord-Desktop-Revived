package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** Secrets are written only after access has been restricted, including on Windows. */
internal object DesktopYtDlpFiles {
    fun privateDirectory(): Path {
        val directory = Files.createTempDirectory("bitchord-ytdlp-")
        try {
            restrict(directory, directory = true)
            return directory
        } catch (error: Exception) {
            Files.deleteIfExists(directory)
            throw error
        }
    }

    fun restrict(path: Path, directory: Boolean = false, executable: Boolean = false) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                if (directory || executable) "rwx------" else "rw-------",
            ))
        } else {
            val acl = checkNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java)) {
                "This filesystem cannot protect YouTube session files"
            }
            val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path))
                .setPermissions(*AclEntryPermission.values())
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
            acl.acl = listOf(entry.build())
        }
    }

    fun cookieJar(header: String): String {
        require(header.none { it == '\r' || it == '\n' || it == '\t' || it == '\u0000' }) {
            "Invalid YouTube cookie header"
        }
        val cookies = linkedMapOf<String, String>()
        header.split(';').forEach { part ->
            val entry = part.trim()
            if (entry.isEmpty()) return@forEach
            require('=' in entry) { "Invalid YouTube cookie entry" }
            val name = entry.substringBefore('=')
            val value = entry.substringAfter('=')
            require(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(name)) { "Invalid cookie name" }
            cookies[name] = value
        }
        return buildString {
            appendLine("# Netscape HTTP Cookie File")
            cookies.forEach { (name, value) ->
                val domain = if (name.startsWith("__Host-")) "music.youtube.com\tFALSE" else ".youtube.com\tTRUE"
                appendLine("$domain\t/\tTRUE\t0\t$name\t$value")
            }
        }
    }

    fun writeCookies(directory: Path, header: String): Path {
        val content = cookieJar(header)
        val path = Files.createFile(directory.resolve("cookies.txt"))
        restrict(path)
        Files.writeString(path, content)
        return path
    }

    fun removeDirectory(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
