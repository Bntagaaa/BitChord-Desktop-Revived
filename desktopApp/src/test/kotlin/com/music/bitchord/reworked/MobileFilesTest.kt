package com.music.bitchord.reworked

import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.javacv.FFmpegFrameRecorder

class MobileFilesTest {
    @Test fun nativeDownloadPreservesAudioAndTags() = withDirectory { directory ->
        val wav = directory.resolve("fixture.wav")
        writeFixture(wav)
        val output = directory.resolve("complete.mka")
        MobileDownloadWriter.write(PlayableStream(wav.toString()), output, { false }, {},
            title = "Tagged title", artist = "Fixture artist", album = "Fixture album")
        val saved = MobileFiles.inspect(output)
        assertEquals("Tagged title", saved.title)
        assertEquals("Fixture artist", saved.artist)
        assertEquals("Fixture album", saved.albumName)
        assertEquals(output.toRealPath().toUri().toString(), saved.localUri)
        assertEquals(output.toRealPath().toString(), saved.localPath)
        assertEquals("0:01", saved.durationText)
        assertTrue(saved.downloadFormat.orEmpty().contains("PCM"), "Packet copying must preserve the original PCM codec")
    }

    @Test fun cancellationNeverCreatesCompletedAudio() = withDirectory { directory ->
        val input = directory.resolve("input.wav")
        val output = directory.resolve("incomplete.part")
        writeFixture(input)
        assertFailsWith<CancellationException> {
            MobileDownloadWriter.write(PlayableStream(input.toString()), output, { true }, {})
        }
        assertFalse(Files.exists(output))
    }

    @Test fun exportedFilesUseTheirActualAudioContainer() = withDirectory { directory ->
        val wav = directory.resolve("fixture.wav")
        writeFixture(wav)
        val output = directory.resolve("export.part")
        val written = MobileDownloadWriter.write(PlayableStream(wav.toString()), output, { false }, {},
            title = "Export title", artist = "Export artist", export = true)
        assertEquals("wav", written.extension)
        assertEquals("RIFF", Files.readAllBytes(output).copyOfRange(0, 4).toString(Charsets.US_ASCII))
        val media = MobileFiles.inspect(output)
        assertEquals("Export title", media.title)
        assertEquals("0:01", media.durationText)
        assertTrue(media.downloadFormat.orEmpty().contains("PCM"))
    }

    @Test fun exportedAacRepackagesAdtsIntoPlayableM4a() = withDirectory { directory ->
        val input = directory.resolve("fixture.aac")
        FFmpegFrameRecorder(input.toFile(), 1).use { recorder ->
            recorder.format = "adts"
            recorder.audioCodec = AV_CODEC_ID_AAC
            recorder.sampleRate = 44100
            recorder.audioBitrate = 128000
            recorder.start()
            val samples = ShortArray(44100) { (sin(2 * PI * 440 * it / 44100) * 4000).toInt().toShort() }
            recorder.recordSamples(44100, 1, ShortBuffer.wrap(samples))
            recorder.stop()
        }
        val output = directory.resolve("export.part")
        val written = MobileDownloadWriter.write(PlayableStream(input.toString()), output, { false }, {},
            title = "AAC export", export = true)
        assertEquals("m4a", written.extension)
        assertEquals("ftyp", Files.readAllBytes(output).copyOfRange(4, 8).toString(Charsets.US_ASCII))
        val media = MobileFiles.inspect(output)
        assertEquals("AAC export", media.title)
        assertEquals("AAC", media.downloadFormat)
        assertEquals("0:01", media.durationText)
    }

    @Test fun malformedMediaIsRejected() = withDirectory { directory ->
        val input = directory.resolve("fake.mp3")
        Files.writeString(input, "<html>This is not audio</html>")
        assertFails { MobileFiles.inspect(input) }
        assertFails { MobileDownloadWriter.write(PlayableStream(input.toString()), directory.resolve("rejected.mka"), { false }, {}) }
    }

    @Test fun fileTagsAndArtworkDoNotGuessTheWrongContainer() {
        assertNull(MobileFiles.cleanTag("<unknown>"))
        assertEquals("A Title", MobileFiles.cleanTag(" A Title "))
        assertTrue(MobileFiles.isAudioFile(Path.of("ALBUM.FLAC")))
        assertFalse(MobileFiles.isAudioFile(Path.of("song.mp3.part")))
        assertNull(MobileFiles.artworkExtension("HTML pretending to be artwork".toByteArray()))
        assertEquals("jpg", MobileFiles.artworkExtension(byteArrayOf(-1, -40, -1, 0)))
    }

    private fun writeFixture(path: Path) {
        val sampleRate = 8000
        val count = sampleRate
        val bytes = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(count * 2)
        repeat(count) { bytes.putShort((sin(2 * PI * 440 * it / sampleRate) * 4000).toInt().toShort()) }
        Files.write(path, bytes.array())
    }

    private fun withDirectory(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("reworked-media-test-")
        try { test(directory) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
