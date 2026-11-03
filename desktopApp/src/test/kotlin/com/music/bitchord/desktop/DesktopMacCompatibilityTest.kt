package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMacCompatibilityTest {
    @Test fun installerNeverReplacesRunningMacApplication() {
        for (arch in listOf("arm64", "aarch64", "x86_64", "amd64")) {
            val installation = UpdateInstallation.classify("Mac OS X", arch, null, false, true, "")
            assertEquals(UpdateFormat.MACOS, installation.format)
            assertFalse(DesktopUpdateInstaller.canInstall(installation))
            assertTrue(installation.format.guidance.contains("DMG"))
        }
        assertEquals(UpdateFormat.UNKNOWN, UpdateInstallation.classify("Mac OS X", "ppc", null, false, true, "").format)
    }

    @Test fun macPathsUseLibraryAndPreserveSpaces() {
        assertTrue(DesktopMacPaths.applicationSupport.endsWith("Library/Application Support"))
        assertTrue(DesktopMacPaths.caches.endsWith("Library/Caches"))
        assertTrue(DesktopMacPaths.logs.endsWith("Library/Logs"))
    }

    @Test fun macUsesCommandShortcutLabels() {
        if (!DesktopPlatform.isMac) return
        assertEquals(listOf("Cmd", "K"), DesktopShortcut.QUICK_SEARCH.keycaps)
    }

    @Test fun nativeMediaBridgeIsSafeOnOtherPlatforms() {
        if (DesktopPlatform.isMac) return
        DesktopMacMedia.stop()
        DesktopMacApplication.configure()
    }
}
