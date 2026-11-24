package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopAutomixModelManagerTest {
    private val bytes = ByteArray(1024) { (it % 251).toByte() }
    private val model = DesktopAutomixModel("test.onnx", bytes.size.toLong(),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))

    @Test
    fun verifiesAndMigratesLegacyFilesWithoutDownloading() = runBlocking {
        withDirectories { installed, legacy ->
            Files.createDirectories(legacy)
            Files.write(legacy.resolve(model.name), bytes)
            val manager = DesktopAutomixModelManager(installed, legacy, listOf(model)) {
                throw AssertionError("A verified legacy model should not be downloaded")
            }
            manager.inspect()
            assertEquals(DesktopAutomixModelState.Ready, terminal(manager))
            assertContentEquals(bytes, Files.readAllBytes(Path.of(manager.path(model.name))))
            manager.delete()
            manager.inspect()
            withTimeout(5_000) {
                while (Files.exists(installed.resolve(model.name)) || Files.exists(legacy.resolve(model.name))) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }

    @Test
    fun resumesAfterInterruptionAndInstallsOnlyVerifiedBytes() = runBlocking {
        withDirectories { installed, legacy ->
            val ranges = CopyOnWriteArrayList<String>()
            val first = AtomicBoolean(true)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                try {
                    val range = exchange.requestHeaders.getFirst("Range").orEmpty()
                    ranges += range
                    if (first.getAndSet(false)) {
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes, 0, 512)
                    } else {
                        exchange.responseHeaders.set("Content-Range", "bytes 512-1023/1024")
                        exchange.sendResponseHeaders(206, 512)
                        exchange.responseBody.write(bytes, 512, 512)
                    }
                } finally {
                    exchange.close()
                }
            }
            server.start()
            try {
                val manager = DesktopAutomixModelManager(installed, legacy, listOf(model)) {
                    URI("http://127.0.0.1:${server.address.port}/")
                }
                manager.download()
                assertIs<DesktopAutomixModelState.Failed>(terminal(manager))
                assertFalse(Files.exists(installed.resolve(model.name)))
                assertEquals(512L, Files.size(installed.resolve("${model.name}.part")))
                manager.download()
                assertEquals(DesktopAutomixModelState.Ready, terminal(manager, awaitReady = true))
                assertContentEquals(bytes, Files.readAllBytes(Path.of(manager.path(model.name))))
                assertEquals(listOf("", "bytes=512-"), ranges.toList())
                assertFalse(Files.exists(installed.resolve("${model.name}.part")))
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun rejectsAFullSizeCorruptModel() = runBlocking {
        withDirectories { installed, legacy ->
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(ByteArray(bytes.size)) }
            }
            server.start()
            try {
                Files.createDirectories(legacy)
                Files.write(legacy.resolve(model.name), ByteArray(bytes.size))
                val manager = DesktopAutomixModelManager(installed, legacy, listOf(model)) {
                    URI("http://127.0.0.1:${server.address.port}/")
                }
                manager.download()
                val failed = assertIs<DesktopAutomixModelState.Failed>(terminal(manager))
                assertTrue(failed.reason.contains("SHA-256"))
                assertFalse(Files.exists(installed.resolve(model.name)))
                assertFalse(Files.exists(installed.resolve("${model.name}.part")))
            } finally {
                server.stop(0)
            }
        }
    }

    private suspend fun terminal(manager: DesktopAutomixModelManager, awaitReady: Boolean = false) =
        withTimeout(5_000) {
            manager.state.first {
                it == DesktopAutomixModelState.Ready || !awaitReady && it is DesktopAutomixModelState.Failed
            }
        }

    private suspend fun withDirectories(block: suspend (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("automix-model-test")
        try {
            block(root.resolve("current"), root.resolve("legacy"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
