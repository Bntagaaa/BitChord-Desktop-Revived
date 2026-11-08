package com.music.bitchord.reworked

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopStoreTest {
    @Test fun atomicUtf8UpdatesAndInvalidNamesCannotEscapeTheProfile() {
        val root = Files.createTempDirectory("reworked-store-")
        try {
            assertNull(DesktopStore.readText(root, "library.json"))
            DesktopStore.writeText(root, "library.json", "{\"name\":\"Lagu 日本語\"}")
            assertEquals("{\"name\":\"Lagu 日本語\"}", DesktopStore.readText(root, "library.json"))
            DesktopStore.writeText(root, "library.json", "{\"name\":\"New\"}")
            assertEquals("{\"name\":\"New\"}", DesktopStore.readText(root, "library.json"))
            listOf("../outside", "a/b.json", "a\\b.json", ".", "..", "/tmp/name", "a:b").forEach { name ->
                assertFailsWith<IllegalArgumentException> { DesktopStore.writeText(root, name, "bad") }
            }
            assertEquals(listOf("library.json"), Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() })
        } finally {
            Files.deleteIfExists(root.resolve("library.json")); Files.deleteIfExists(root)
        }
    }
}
