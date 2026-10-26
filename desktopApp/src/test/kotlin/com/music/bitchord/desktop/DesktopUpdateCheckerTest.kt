package com.music.bitchord.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUpdateCheckerTest {
    private val checker = DesktopUpdateChecker

    @Test
    fun newerVersionsAndPrereleases() {
        assertTrue(checker.isNewer("1.8.3", "1.8.2"))
        assertTrue(checker.isNewer("1.8.2", "1.8.2-beta1"))
        assertFalse(checker.isNewer("1.8.2", "1.8.2"))
        assertFalse(checker.isNewer("1.8.1", "1.8.2"))
        assertFalse(checker.isNewer("1.8.3", "test-build"))
        assertFalse(checker.isNewer("unknown", "1.8.2"))
    }

    @Test
    fun detectCachyOsAndArchLinuxRegardlessOfDesktop() {
        val linux = "Linux"
        val cpu = "amd64"
        assertEquals(
            DesktopUpdateChecker.PackagePlatform.ARCH_X64,
            checker.targetPlatform(linux, "NAME=CachyOS\nID=cachyos\nID_LIKE=arch", cpu),
        )
        assertEquals(
            DesktopUpdateChecker.PackagePlatform.ARCH_X64,
            checker.targetPlatform(linux, "ID=endeavouros\nID_LIKE=\"arch\"", "x86_64"),
        )
        assertEquals(
            DesktopUpdateChecker.PackagePlatform.LINUX_X64,
            checker.targetPlatform(linux, "ID=ubuntu\nID_LIKE=debian", cpu),
        )
        assertEquals(
            DesktopUpdateChecker.PackagePlatform.UNSUPPORTED,
            checker.targetPlatform(linux, "ID=cachyos\nID_LIKE=arch", "aarch64"),
        )
    }

    @Test
    fun nativeArchPackagePreferredWithoutDebianFallback() {
        val withNative = release(
            "BitChord-1.8.3-linux-x86_64.AppImage",
            "BitChord-1.8.3-linux-amd64.deb",
            "bitchord-desktop-revived-bin-1.8.3-1-x86_64.pkg.tar.zst",
        )
        assertEquals(
            "https://example.org/bitchord-desktop-revived-bin-1.8.3-1-x86_64.pkg.tar.zst",
            checker.installerUrl(withNative, DesktopUpdateChecker.PackagePlatform.ARCH_X64),
        )
        assertEquals(
            "https://example.org/BitChord-1.8.3-linux-x86_64.AppImage",
            checker.installerUrl(withNative, DesktopUpdateChecker.PackagePlatform.LINUX_X64),
        )
        assertNull(
            checker.installerUrl(
                release("BitChord-1.8.3-linux-amd64.deb"),
                DesktopUpdateChecker.PackagePlatform.ARCH_X64,
            ),
        )
        assertNull(
            checker.installerUrl(withNative, DesktopUpdateChecker.PackagePlatform.UNSUPPORTED),
        )
    }

    private fun release(vararg filenames: String) = Json.parseToJsonElement(
        """{"assets":[${filenames.joinToString(",") { name -> """{"name":"$name","state":"uploaded","browser_download_url":"https://example.org/$name"}""" }}]}"""
    ).jsonObject
}
