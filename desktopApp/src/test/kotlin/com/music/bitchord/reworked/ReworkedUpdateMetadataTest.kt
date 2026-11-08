package com.music.bitchord.reworked

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReworkedUpdateMetadataTest {
    private val digest = "a".repeat(64)

    private fun release(tag: String, draft: Boolean = false, preview: Boolean = false, assetName: String? = null) = buildJsonObject {
        put("tag_name", tag); put("draft", draft); put("prerelease", preview)
        put("body", "Release notes")
        putJsonArray("assets") {
            val name = assetName ?: "BitChordReworked-${tag.removePrefix("reworked-v")}-linux-x86_64.AppImage"
            add(buildJsonObject {
                put("name", name); put("state", "uploaded"); put("size", 100)
                put("browser_download_url", "https://github.com/$REWORKED_REPOSITORY/releases/download/$tag/$name")
                put("digest", "sha256:$digest")
            })
        }
    }

    @Test fun refusesRevivedMobileDraftAndPreviewReleasesOnStableChannel() {
        val json = buildJsonArray {
            add(release("v99.0.0"))
            add(release("reworked-v8.0.0", draft = true))
            add(release("reworked-v7.0.0-beta.1", preview = true))
            add(release("reworked-v1.9.2"))
        }
        val selected = ReworkedUpdateMetadata.select(json.toString(), "1.9.1-reworked.1", ReworkedUpdateFormat.LINUX_APPIMAGE, false)
        assertEquals("1.9.2", selected?.version)
        assertEquals(digest, selected?.asset?.sha256)
    }

    @Test fun mismatchedProductAssetFallsBackToReleaseInstructions() {
        val json = buildJsonArray { add(release("reworked-v1.9.2", assetName = "BitChord-1.9.2-linux-x86_64.AppImage")) }
        val selected = ReworkedUpdateMetadata.select(json.toString(), "1.9.1", ReworkedUpdateFormat.LINUX_APPIMAGE, false)
        assertNotNull(selected)
        assertNull(selected.asset)
    }

    @Test fun ordinaryReworkedRevisionUsesStableChannelButBetaDoesNot() {
        val json = buildJsonArray {
            add(release("reworked-v1.9.1-reworked.2"))
            add(release("reworked-v1.9.2-beta.1"))
        }
        assertEquals("1.9.1-reworked.2", ReworkedUpdateMetadata.select(json.toString(), "1.9.1-reworked.1", ReworkedUpdateFormat.LINUX_APPIMAGE, false)?.version)
    }

    @Test fun comparesPreviewNumbersNumericallyAndRejectsMalformedVersions() {
        assertTrue(ReworkedVersion.parse("1.9.2-rc.10")!! > ReworkedVersion.parse("1.9.2-rc.2")!!)
        assertTrue(ReworkedVersion.parse("1.9.2")!! > ReworkedVersion.parse("1.9.2-rc.10")!!)
        assertEquals(ReworkedVersion.parse("1.9.2+build.2"), ReworkedVersion.parse("1.9.2+build.9"))
        listOf("1.9", "1.9.2-", "01.9.2", "1.9.2-01", "1.9.2-a..b", "1.9.2+.").forEach { assertNull(ReworkedVersion.parse(it)) }
    }

    @Test fun refusesAmbiguousChecksumsAndHostConfusion() {
        val name = "BitChordReworked-1.9.2-linux-x86_64.AppImage"
        assertEquals(digest, ReworkedUpdateMetadata.checksum("$digest  $name\n", name))
        assertNull(ReworkedUpdateMetadata.checksum("$digest  $name\n$digest  $name\n", name))
        assertNull(ReworkedUpdateMetadata.checksum("$digest  $name.extra", name))
        assertFalse(ReworkedUpdateMetadata.validAssetUrl("https://github.com.evil.invalid/$name", "reworked-v1.9.2", name))
        assertFalse(ReworkedUpdateMetadata.validDownloadRedirect("https://github.com@evil.invalid/payload"))
        assertFalse(ReworkedUpdateMetadata.validDownloadRedirect("http://release-assets.githubusercontent.com/payload"))
        assertFalse(ReworkedUpdateMetadata.validDownloadRedirect("https://release-assets.githubusercontent.com:8443/payload"))
        assertTrue(ReworkedUpdateMetadata.validDownloadRedirect("https://release-assets.githubusercontent.com/payload?token=example"))
    }

    @Test fun verifiesBytesAndRejectsHtmlMasqueradingAsAnInstaller() {
        val file = Files.createTempFile("reworked-update-test-", ".bin")
        try {
            Files.writeString(file, "abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ReworkedUpdates.sha256(file))
            Files.writeString(file, "<html>error</html>")
            assertFailsWith<IllegalStateException> { ReworkedUpdates.validateHeader(file, ReworkedUpdateFormat.WINDOWS_SETUP) }
            Files.write(file, byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            ReworkedUpdates.validateHeader(file, ReworkedUpdateFormat.LINUX_APPIMAGE)
            assertFailsWith<IllegalStateException> { ReworkedUpdates.validateHeader(file, ReworkedUpdateFormat.WINDOWS_PORTABLE) }
        } finally { Files.deleteIfExists(file) }
    }
}
