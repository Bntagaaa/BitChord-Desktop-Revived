package com.music.bitchord.reworked

import kotlinx.coroutines.CancellationException
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.*
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fresh JVM implementation of the mobile download boundary. FFmpeg demuxes
 * progressive, HLS and DASH transport; compressed audio packets are copied
 * into an audio container without decoding or reducing their quality. Exported
 * files use the actual codec's ordinary FLAC/MP3/M4A/Ogg/WAV container, following
 * mobile Downloads/ManifestFile rather than relabeling a manifest as audio.
 */
internal object MobileDownloadWriter {
    data class WrittenAudio(val extension: String, val codec: String)
    fun write(
        source: PlayableStream,
        output: Path,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
        title: String = "",
        artist: String = "",
        album: String? = null,
        export: Boolean = false,
    ): WrittenAudio {
        check(!Files.exists(output)) { "The temporary download already exists" }
        var input: AVFormatContext? = null
        var destination: AVFormatContext? = null
        var packet: AVPacket? = null
        var ranges: RangedMediaInput? = null
        var customIo: AVIOContext? = null
        var outputIo: AVIOContext? = null
        var reader: Read_packet_Pointer_BytePointer_int? = null
        var seeker: Seek_Pointer_long_int? = null
        val interrupt = object : AVIOInterruptCB.Callback_Pointer() {
            override fun call(opaque: Pointer?): Int = if (cancelled()) 1 else 0
        }
        fun ensureActive() { if (cancelled()) throw CancellationException("Download cancelled") }
        fun checkResult(result: Int, action: String) {
            ensureActive()
            ranges?.failure?.let { throw it }
            if (result < 0) {
                val bytes = ByteArray(256)
                av_strerror(result, bytes, bytes.size.toLong())
                throw IOException("$action: ${bytes.toString(Charsets.UTF_8).substringBefore('\u0000')}")
            }
        }
        try {
            ensureActive()
            avformat_network_init()
            val opened = avformat_alloc_context()
            input = opened
            opened.interrupt_callback().callback(interrupt)
            if (source.boundedRanges) {
                val media = RangedMediaInput(source.url, source.headers, cancelled)
                ranges = media
                val storage = ByteArray(64 * 1024)
                reader = object : Read_packet_Pointer_BytePointer_int() {
                    override fun call(opaque: Pointer?, into: BytePointer?, count: Int): Int {
                        if (into == null || cancelled()) return AVERROR_EOF
                        val read = media.read(storage, minOf(storage.size, count))
                        if (read <= 0) return AVERROR_EOF
                        into.put(storage, 0, read)
                        return read
                    }
                }
                seeker = object : Seek_Pointer_long_int() {
                    override fun call(opaque: Pointer?, offset: Long, mode: Int): Long {
                        if (mode and AVSEEK_SIZE != 0) return media.length
                        val position = when (mode and 0xffff) {
                            0 -> offset
                            1 -> media.position + offset
                            2 -> if (media.length >= 0) media.length + offset else return -1
                            else -> return -1
                        }
                        if (position < 0) return -1
                        media.position = position
                        return position
                    }
                }
                val buffer = BytePointer(av_malloc(storage.size.toLong()))
                customIo = avio_alloc_context(buffer, storage.size, 0, null, reader, null, seeker)
                check(customIo != null && !customIo.isNull) { "Cannot allocate the download reader" }
                opened.pb(customIo).flags(opened.flags() or AVFormatContext.AVFMT_FLAG_CUSTOM_IO)
            }
            val options = AVDictionary(null)
            av_dict_set(options, "rw_timeout", "15000000", 0)
            // A media URL must never dispatch to an arbitrary FFmpeg protocol.
            val remote = source.url.startsWith("http://") || source.url.startsWith("https://")
            av_dict_set(options, "protocol_whitelist", if (remote) "http,https,tcp,tls,crypto" else "file,pipe", 0)
            if (source.headers.isNotEmpty()) {
                require(source.headers.none { (key, value) -> '\r' in key || '\n' in key || '\r' in value || '\n' in value })
                av_dict_set(options, "headers", source.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }, 0)
            }
            try { checkResult(avformat_open_input(opened, if (source.boundedRanges) null as String? else source.url, null, options), "Cannot open the download") }
            finally { av_dict_free(options) }
            checkResult(avformat_find_stream_info(opened, null as AVDictionary?), "Cannot inspect the download")
            val index = av_find_best_stream(opened, AVMEDIA_TYPE_AUDIO, -1, -1, null as AVCodec?, 0)
            check(index >= 0) { "The source returned no audio track" }
            val sourceTrack = opened.streams(index)
            val codecId = sourceTrack.codecpar().codec_id()
            val codec = avcodec_get_name(codecId).string
            val container = if (export) exportContainer(codecId) else "matroska" to "mka"
            val duration = opened.duration().takeIf { it > 0 && it != AV_NOPTS_VALUE }?.div(1000)
                ?: sourceTrack.duration().takeIf { it > 0 && it != AV_NOPTS_VALUE }?.let {
                    it * sourceTrack.time_base().num() * 1000 / sourceTrack.time_base().den().coerceAtLeast(1)
                } ?: 0L
            val outputContext = AVFormatContext(null)
            destination = outputContext
            checkResult(avformat_alloc_output_context2(outputContext, null, container.first, output.toString()), "Cannot create the download")
            val track = avformat_new_stream(outputContext, null)
            check(track != null && !track.isNull) { "Cannot create the audio track" }
            checkResult(avcodec_parameters_copy(track.codecpar(), sourceTrack.codecpar()), "Cannot preserve the audio codec")
            track.codecpar().codec_tag(0)
            track.time_base(sourceTrack.time_base())
            val metadata = AVDictionary(null)
            av_dict_set(metadata, "title", title, 0)
            av_dict_set(metadata, "artist", artist, 0)
            album?.let { av_dict_set(metadata, "album", it, 0) }
            outputContext.metadata(metadata)
            val sink = AVIOContext(null)
            outputIo = sink
            checkResult(avio_open(sink, output.toString(), AVIO_FLAG_WRITE), "Cannot write the download file")
            outputContext.pb(sink)
            checkResult(avformat_write_header(outputContext, null as AVDictionary?), "Cannot write the audio container")
            val data = av_packet_alloc()
            packet = data
            var count = 0L
            var lastTime = 0L
            val start = sourceTrack.start_time().takeIf { it != AV_NOPTS_VALUE } ?: 0L
            while (true) {
                ensureActive()
                val result = av_read_frame(opened, data)
                if (result == AVERROR_EOF) break
                checkResult(result, "The download stopped before it finished")
                try {
                    if (data.stream_index() != index) continue
                    val time = data.pts().takeIf { it != AV_NOPTS_VALUE } ?: data.dts()
                    if (time != AV_NOPTS_VALUE) {
                        lastTime = maxOf(lastTime, (time + data.duration() - start) * sourceTrack.time_base().num() * 1000 /
                            sourceTrack.time_base().den().coerceAtLeast(1))
                    }
                    if (data.pts() != AV_NOPTS_VALUE) data.pts(data.pts() - start)
                    if (data.dts() != AV_NOPTS_VALUE) data.dts(data.dts() - start)
                    av_packet_rescale_ts(data, sourceTrack.time_base(), track.time_base())
                    data.stream_index(0).pos(-1)
                    checkResult(av_interleaved_write_frame(outputContext, data), "Cannot save the audio packet")
                    count++
                    if (count % 50L == 0L) {
                        if (duration > 0) progress(lastTime.toFloat() / duration)
                        else ranges?.takeIf { it.length > 0 }?.let { progress(it.position.toFloat() / it.length) }
                    }
                } finally { av_packet_unref(data) }
            }
            ensureActive()
            ranges?.failure?.let { throw it }
            check(count > 0) { "The source returned empty audio" }
            check(duration <= 0 || lastTime >= duration - maxOf(3000L, duration / 50)) {
                "The source ended before the complete track was downloaded"
            }
            checkResult(av_write_trailer(outputContext), "Cannot finish the audio file")
            avio_flush(sink)
            checkResult(sink.error(), "Cannot flush the audio file")
            progress(0.99f)
            return WrittenAudio(container.second, codec.uppercase())
        } finally {
            ranges?.close()
            packet?.let(::av_packet_free)
            input?.takeUnless { it.isNull }?.let(::avformat_close_input)
            customIo?.let { channel ->
                av_free(channel.buffer())
                channel.buffer(null as BytePointer?)
                avio_context_free(channel)
            }
            outputIo?.takeUnless { it.isNull }?.let(::avio_closep)
            destination?.takeUnless { it.isNull }?.let(::avformat_free_context)
            reader?.deallocate()
            seeker?.deallocate()
            interrupt.deallocate()
        }
    }

    /** Audio is packet copied; selecting an extension never introduces a hidden transcode. */
    internal fun exportContainer(codec: Int): Pair<String, String> = when (codec) {
        AV_CODEC_ID_FLAC -> "flac" to "flac"
        AV_CODEC_ID_MP3 -> "mp3" to "mp3"
        AV_CODEC_ID_AAC, AV_CODEC_ID_ALAC -> "ipod" to "m4a"
        AV_CODEC_ID_OPUS -> "ogg" to "opus"
        AV_CODEC_ID_VORBIS -> "ogg" to "ogg"
        AV_CODEC_ID_PCM_S16LE, AV_CODEC_ID_PCM_S24LE, AV_CODEC_ID_PCM_S32LE,
        AV_CODEC_ID_PCM_F32LE, AV_CODEC_ID_PCM_F64LE, AV_CODEC_ID_PCM_U8 -> "wav" to "wav"
        AV_CODEC_ID_AC3 -> "ac3" to "ac3"
        AV_CODEC_ID_EAC3 -> "eac3" to "eac3"
        else -> error("This source's audio codec cannot be exported. Turn off Export downloads to keep it in app storage.")
    }
}
