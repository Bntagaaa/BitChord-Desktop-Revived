package com.music.bitchord.reworked

import com.music.bitchord.data.model.Account
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*

class YouTubeAccountStoreTest {
    @Test fun separateAccountVaultRoundTripsAndProtectsFileForCurrentPlatform() = withFolder { directory ->
        val value = YouTubeAccountRules.accepted(YouTubeAccountRegistry(), testCapture(pageId = "brand-fixture"),
            Account("Fixture", "fixture@example.invalid", null), YouTubeSessionMode.SIGN_IN, null)
        val store = DesktopYouTubeAccountStore(directory)
        store.write(value)
        assertEquals(value, store.read())
        val raw = Files.readString(directory.resolve(DesktopYouTubeAccountStore.FILE))
        assertFalse(raw.contains("fixture-secret"))
        if (System.getProperty("os.name").startsWith("Windows", true)) assertTrue(raw.contains("windows-dpapi-user-v1"))
        else {
            assertTrue(raw.contains("linux-private-file-v1"))
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(directory.resolve(DesktopYouTubeAccountStore.FILE)))
        }
        assertFalse(Files.exists(directory.resolve("youtube-cookie.txt")))
    }

    @Test fun malformedVaultRemainsOnDiskAndErrorsDoNotEchoItsContent() = withFolder { directory ->
        val target = directory.resolve(DesktopYouTubeAccountStore.FILE)
        val original = "{broken-json credential-fixture-sensitive}"
        Files.writeString(target, original)
        val failure = assertFailsWith<IllegalStateException> { DesktopYouTubeAccountStore(directory).read() }
        assertFalse(failure.toString().contains("credential-fixture-sensitive"))
        assertEquals(original, Files.readString(target))
    }

    @Test fun privateVaultRefusesSymlinkReplacement() = withFolder { directory ->
        if (!System.getProperty("os.name").startsWith("Windows", true)) {
            val external = directory.resolve("separate-target.txt")
            Files.writeString(external, "keep-me")
            Files.createSymbolicLink(directory.resolve(DesktopYouTubeAccountStore.FILE), external)
            assertFailsWith<IllegalStateException> { DesktopYouTubeAccountStore(directory).write(YouTubeAccountRegistry()) }
            assertEquals("keep-me", Files.readString(external))
        }
    }
}

private fun withFolder(action: (Path) -> Unit) {
    val directory = Files.createTempDirectory("reworked-account-fixtures-")
    try { action(directory) }
    finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}
