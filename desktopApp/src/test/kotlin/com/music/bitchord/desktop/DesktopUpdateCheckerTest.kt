package com.music.bitchord.desktop

import kotlin.test.*

class DesktopUpdateCheckerTest {
    @Test fun stableAndPrereleaseOrdering() {
        val newer = DesktopUpdateChecker::isNewer
        for ((a,b) in listOf("1.9.0" to "1.8.99", "1.8.3" to "1.8.3-rc.9", "1.8.3-beta10" to "1.8.3-beta2", "1.8.3-beta.2" to "1.8.3-alpha.9", "1.8.3-rc.1" to "1.8.3-beta.9")) assertTrue(newer(a,b), "$a > $b")
        for ((a,b) in listOf("1.8.3+build2" to "1.8.3+build1", "1.8.3-beta.1" to "1.8.3", "unknown" to "1.8.3", "1.8.3" to "dev-test", "1.8.3" to "1.8.3", "1.8.2" to "1.8.3")) assertFalse(newer(a,b), "$a must not update $b")
    }
    @Test fun installationFormatTakesPriorityOverDistro() {
        fun detect(os: String="Linux", cpu: String="amd64", image: String?=null, portable: Boolean=false, installed: Boolean=false, owner: String="") = UpdateInstallation.classify(os,cpu,image,portable,installed,owner).format
        assertEquals(UpdateFormat.APPIMAGE, detect(image="/home/me/BitChord.AppImage", owner="pacman"))
        assertEquals(UpdateFormat.ARCH, detect(owner="pacman"))
        assertEquals(UpdateFormat.DEB, detect(owner="dpkg-query"))
        assertEquals(UpdateFormat.RPM, detect(owner="rpm"))
        assertEquals(UpdateFormat.WINDOWS_PORTABLE, detect("Windows 11", portable=true, installed=true))
        assertEquals(UpdateFormat.WINDOWS_SETUP, detect("Windows 11", installed=true))
        assertEquals(UpdateFormat.UNKNOWN, detect("Windows 11"))
        assertEquals(UpdateFormat.UNKNOWN, detect(cpu="aarch64", image="/tmp/a.AppImage"))
        assertEquals(UpdateFormat.UNKNOWN, detect())
    }
    private fun release(version: String, preview: Boolean=false, draft: Boolean=false, suffix: String="-linux-x86_64.AppImage") = """{"tag_name":"v$version","draft":$draft,"prerelease":$preview,"html_url":"https://github.com/${DesktopUpdateChecker.REPOSITORY}/releases/tag/v$version","assets":[{"name":"BitChord-$version$suffix","size":100,"state":"uploaded","browser_download_url":"https://github.com/${DesktopUpdateChecker.REPOSITORY}/releases/download/v$version/BitChord-$version$suffix"}]}"""
    @Test fun filtersDraftsChannelsAndSelectsCompatibleAssets() {
        val body="[${release("1.9.0-beta.2",true)},${release("1.8.4")},${release("9.0.0",draft=true)}]"
        val installed=UpdateInstallation(UpdateFormat.APPIMAGE)
        assertEquals("1.8.4", DesktopUpdateChecker.selectRelease(body,DesktopUpdateChecker.Channel.STABLE,installed,"1.8.3")?.version)
        assertEquals("1.9.0-beta.2", DesktopUpdateChecker.selectRelease(body,DesktopUpdateChecker.Channel.PREVIEW,installed,"1.8.3")?.version)
        assertNull(DesktopUpdateChecker.selectRelease(body,DesktopUpdateChecker.Channel.STABLE,installed,"1.8.4"))
        assertNull(DesktopUpdateChecker.selectRelease(body,DesktopUpdateChecker.Channel.STABLE,UpdateInstallation(UpdateFormat.DEB),"1.8.3")?.asset)
        assertNull(DesktopUpdateChecker.selectRelease(body,DesktopUpdateChecker.Channel.STABLE,UpdateInstallation(UpdateFormat.ARCH),"1.8.3")?.asset)
    }
    @Test fun everyDistributionSelectsOnlyItsOwnReleaseAssetAndPartialReleasesStaySafe() {
        for (format in listOf(UpdateFormat.APPIMAGE, UpdateFormat.WINDOWS_SETUP, UpdateFormat.WINDOWS_PORTABLE, UpdateFormat.DEB, UpdateFormat.RPM)) {
            val compatible = release("1.8.5", suffix = format.suffix!!)
            val incompatibleNewer = release("1.8.6", suffix = if (format == UpdateFormat.APPIMAGE) "-windows-x64-setup.exe" else "-linux-x86_64.AppImage")
            val selected = DesktopUpdateChecker.selectRelease("[$compatible,$incompatibleNewer]", DesktopUpdateChecker.Channel.STABLE, UpdateInstallation(format), "1.8.4")!!
            assertEquals("1.8.5", selected.version); assertTrue(selected.asset!!.name.endsWith(format.suffix!!))
            val wrongTag = compatible.replace("/download/v1.8.5/", "/download/v1.8.3/")
            assertNull(DesktopUpdateChecker.selectRelease("[$wrongTag]", DesktopUpdateChecker.Channel.STABLE, UpdateInstallation(format), "1.8.4")!!.asset)
        }
    }
    @Test fun rejectsForeignUrlsAndMalformedResponses() {
        assertFalse(DesktopUpdateChecker.officialAssetUrl("https://github.com/attacker/repo/releases/download/v1/evil.exe"))
        assertFalse(DesktopUpdateChecker.officialAssetUrl("https://github.com/${DesktopUpdateChecker.REPOSITORY}/releases/download/v1/../evil.exe"))
        assertFalse(DesktopUpdateChecker.officialAssetUrl("http://github.com/${DesktopUpdateChecker.REPOSITORY}/releases/download/v1/evil.exe"))
        assertFails { DesktopUpdateChecker.selectRelease("invalid",DesktopUpdateChecker.Channel.STABLE,UpdateInstallation(UpdateFormat.APPIMAGE),"1.0.0") }
    }
}
