package com.music.bitchord.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopKdeDisplayScaleTest {
    @Test
    fun onlyKdeSessionsReadKdeConfiguration() {
        assertTrue(isKdeDesktop("KDE", null))
        assertTrue(isKdeDesktop("custom:KDE", null))
        assertTrue(isKdeDesktop(null, "true"))
        assertTrue(isKdeDesktop(null, "1"))
        assertFalse(isKdeDesktop("GNOME", null))
        assertFalse(isKdeDesktop("notKDE", "false"))
    }

    @Test
    fun usesTheClientScaleForEachSessionAndKeepsFractionalValues() {
        for (scale in listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)) {
            val kdeglobals = "[KScreen]\nScaleFactor=$scale\n[General]\nforceFontDPI=192\n"
            val kwinrc = "[Xwayland]\nScale=$scale\n[Other]\nScale=5\n"
            assertEquals(scale, parseKdeX11Scale(false, kdeglobals, "[Xwayland]\nScale=4\n"))
            assertEquals(scale, parseKdeX11Scale(true, "[KScreen]\nScaleFactor=4\n", kwinrc))
        }
    }

    @Test
    fun compositorScalingIsNotMultipliedByTheMonitorScaleAgain() {
        assertEquals(1f, parseKdeX11Scale(true,
            "[KScreen]\nXwaylandClientsScale=false\nScaleFactor=2\n", "[Xwayland]\nScale=2\n"))
        assertEquals(1f, parseKdeX11Scale(true, "[KScreen]\nXwaylandClientsScale=false\n", null))
        assertEquals(1f, parseKdeX11Scale(true, null, "[Xwayland]\nScale=1\n"))
        assertEquals(1.5f, parseKdeX11Scale(true,
            "[KScreen]\nXwaylandClientsScale=true\n", "[Xwayland]\nScale=1.5\n"))
    }

    @Test
    fun absentAndInvalidSettingsLeaveOtherScaleSourcesAvailable() {
        assertNull(parseKdeX11Scale(true, null, null))
        assertNull(parseKdeX11Scale(true, "[KScreen]\nScaleFactor=2\n", "[Other]\nScale=2\n"))
        assertNull(parseKdeX11Scale(false, "[KScreen][Nested]\nScaleFactor=2\n", "[Xwayland]\nScale=2\n"))
        for (value in listOf("0", "-1", "0.75", "6", "NaN", "Infinity", "broken", "")) {
            assertNull(parseKdeX11Scale(true, null, "[Xwayland]\nScale=$value\n"))
        }
        assertEquals(1.75f, parseKdeX11Scale(true, null,
            "# Scale=4\n[Xwayland]\nScale=1.25\nScale=1.75\n[Unrelated]\nScale=3\n"))
    }

    @Test
    fun usesAnAbsoluteXdgConfigHomeAndOtherwiseTheHomeDefault() {
        val home = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("scale-home")
        val custom = home.resolve("custom-config")
        assertEquals(custom, kdeScaleConfigDirectory(custom.toString(), home.toString()))
        assertEquals(home.resolve(".config"), kdeScaleConfigDirectory("relative-config", home.toString()))
        assertEquals(home.resolve(".config"), kdeScaleConfigDirectory("", home.toString()))
        assertNull(kdeScaleConfigDirectory(null, null))
    }

    @Test
    fun observesAtomicReplacementModeChangesAndDeletionAndCancelsWithoutAFileEvent() = runBlocking {
        val directory = Files.createTempDirectory("bitchord-kde-scale-test")
        val events = Channel<Float?>(Channel.UNLIMITED)
        Files.writeString(directory.resolve("kwinrc"), "[Xwayland]\nScale=1.25\n")
        val observer = launch { kdeX11ScaleUpdates(directory, wayland = true).collect { events.send(it) } }
        try {
            assertEquals(1.25f, withTimeout(5_000) { events.receive() })
            replaceConfig(directory, "kwinrc", "[Xwayland]\nScale=1.75\n")
            assertEquals(1.75f, withTimeout(5_000) { events.receive() })
            replaceConfig(directory, "kdeglobals", "[KScreen]\nXwaylandClientsScale=false\n")
            assertEquals(1f, withTimeout(5_000) { events.receive() })
            Files.delete(directory.resolve("kdeglobals"))
            assertEquals(1.75f, withTimeout(5_000) { events.receive() })
            Files.delete(directory.resolve("kwinrc"))
            assertNull(withTimeout(5_000) { events.receive() })
        } finally {
            observer.cancel()
            withTimeout(5_000) { observer.join() }
            events.close()
            for (file in listOf("kwinrc", "kwinrc.new", "kdeglobals", "kdeglobals.new")) {
                Files.deleteIfExists(directory.resolve(file))
            }
            Files.delete(directory)
        }
    }

    private fun replaceConfig(directory: Path, name: String, contents: String) {
        val temporary = directory.resolve("$name.new")
        Files.writeString(temporary, contents)
        try {
            Files.move(temporary, directory.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, directory.resolve(name), REPLACE_EXISTING)
        }
    }
}
