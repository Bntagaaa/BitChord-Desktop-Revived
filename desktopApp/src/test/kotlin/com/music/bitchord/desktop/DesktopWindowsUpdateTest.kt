package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class DesktopWindowsUpdateTest {
    @Test fun bothWindowsFormatsStageAnExternalHostAndExactLauncher() {
        val base = Files.createTempDirectory("windows-update-host-test")
        val priorLauncher = System.getProperty("jpackage.app-path")
        try {
            val app = Files.createDirectory(base.resolve("App with spaces"))
            val launcher = Files.write(app.resolve("BitChord.exe"), byteArrayOf(77, 90))
            Files.createDirectory(app.resolve("app"))
            Files.writeString(app.resolve("app/BitChord.cfg"), "java-options=-Dbitchord.version=1.8.7\n")
            Files.writeString(app.resolve(".bitchord-portable"), "")
            System.setProperty("jpackage.app-path", launcher.toString())
            for (format in listOf(UpdateFormat.WINDOWS_PORTABLE, UpdateFormat.WINDOWS_SETUP)) {
                val op = Files.createDirectory(base.resolve("operation-${format.name}"))
                val source = Files.write(base.resolve("source-${format.name}"), ByteArray(128) { it.toByte() })
                val hash = DesktopUpdateDownloader.sha256(source)
                val temporary = mutableListOf<Path>()
                val prepared = DesktopWindowsUpdate.prepare(
                    UpdateInstallation(format, portableRoot = if (format == UpdateFormat.WINDOWS_PORTABLE) app else null),
                    source, hash, "1.9.1", op, temporary,
                )
                try {
                    val request = Json.parseToJsonElement(Files.readString(op.resolve("request.json"))).jsonObject
                    assertEquals(launcher.toString(), request.getValue("application").jsonPrimitive.content)
                    assertEquals("1.9.1", request.getValue("version").jsonPrimitive.content)
                    assertEquals(hash, request.getValue("expected").jsonPrimitive.content)
                    assertEquals(if (format == UpdateFormat.WINDOWS_PORTABLE) "portable" else "setup", request.getValue("mode").jsonPrimitive.content)
                    assertEquals(32, request.getValue("token").jsonPrimitive.content.length)
                    assertEquals(DesktopUpdateDownloader.sha256(op.resolve("payload/worker.ps1")), request.getValue("workerSha256").jsonPrimitive.content)
                    assertTrue(Files.isRegularFile(op.resolve("windows-updater-host.ps1")))
                    assertEquals(1, temporary.size)
                    assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(temporary.single()))
                    assertFalse(temporary.single().startsWith(app))
                } finally { prepared.abort() }
                assertFalse(Files.exists(temporary.single()), "Cancelled preparation must remove staged payload")
                assertTrue(Files.exists(source), "Do not delete the user's downloaded source")
            }
        } finally {
            if (priorLauncher == null) System.clearProperty("jpackage.app-path") else System.setProperty("jpackage.app-path", priorLauncher)
            base.toFile().deleteRecursively()
        }
    }

    @Test fun windowsReadinessBindsVersionTokenProcessAndLauncher() {
        if (!DesktopPlatform.isWindows) return
        val base = Files.createTempDirectory("windows-update-readiness")
        val oldLauncher = System.getProperty("jpackage.app-path")
        try {
            val launcher = base.resolve("BitChord.exe").toAbsolutePath().normalize()
            System.setProperty("jpackage.app-path", launcher.toString())
            val token = "a".repeat(32)
            Files.writeString(base.resolve("request.token"), token)
            val env = mapOf(
                "BITCHORD_UPDATE_READY_FILE" to base.resolve("window-ready").toString(),
                "BITCHORD_UPDATE_TOKEN" to token,
                "BITCHORD_UPDATE_EXPECTED_VERSION" to "1.9.1",
                "BITCHORD_UPDATE_EXPECTED_LAUNCHER" to launcher.toString(),
            )
            assertFalse(DesktopUpdateReadiness.acknowledge(env, "1.8.7"))
            assertFalse(DesktopUpdateReadiness.acknowledge(env + ("BITCHORD_UPDATE_EXPECTED_LAUNCHER" to base.resolve("Other.exe").toString()), "1.9.1"))
            assertTrue(DesktopUpdateReadiness.acknowledge(env, "1.9.1"))
            val marker = Files.readString(base.resolve("window-ready"))
            assertTrue(marker.contains("pid=${ProcessHandle.current().pid()}\n"))
            assertTrue(marker.contains("start_millis="))
            assertTrue(marker.contains("launcher_path=$launcher\n"))
            assertFalse(DesktopUpdateReadiness.acknowledge(env, "1.9.1"), "Never overwrite an existing readiness marker")
        } finally {
            if (oldLauncher == null) System.clearProperty("jpackage.app-path") else System.setProperty("jpackage.app-path", oldLauncher)
            base.toFile().deleteRecursively()
        }
    }
}
