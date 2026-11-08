package com.music.bitchord.reworked

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avfilter.AVFilterContext
import org.bytedeco.ffmpeg.avfilter.AVFilterGraph
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avfilter.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import java.io.IOException
import java.util.Locale
import javax.sound.sampled.AudioFormat

/**
 * Fresh JVM equivalent of the mobile Media3 decode/DSP boundary. libavfilter provides the
 * mobile player's pitch-preserving speed and volume controls before PCM reaches Java Sound.
 */
internal class MobileAudioDecoder(
    private val source: PlayableStream,
    private val cancelled: () -> Boolean,
) : AutoCloseable {
    private var container: AVFormatContext? = null
    private var codec: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var decoded: AVFrame? = null
    private var filtered: AVFrame? = null
    private var graph: AVFilterGraph? = null
    private var graphSource: AVFilterContext? = null
    private var graphSink: AVFilterContext? = null
    private var media: RangedMediaInput? = null
    private var io: AVIOContext? = null
    private var readCallback: Read_packet_Pointer_BytePointer_int? = null
    private var seekCallback: Seek_Pointer_long_int? = null
    private var interruptCallback: AVIOInterruptCB.Callback_Pointer? = null
    private var streamIndex = -1
    private var timeNumerator = 1
    private var timeDenominator = 1
    private var startTimestamp = 0L
    private var targetTimestamp = 0L
    private var decoderEnded = false
    private var sentEnd = false
    private var graphEnded = false
    private var output: AudioFormat? = null
    private var speed = 1f
    private var volume = 1f

    var sampleRate: Int = 44_100
        private set
    var channels: Int = 2
        private set
    var bitDepth: Int = 16
        private set
    var durationMs: Long = 0
        private set
    var description: String = ""
        private set
    var info: DecodedAudioInfo? = null
        private set

    fun open() {
        check(!cancelled()) { "Playback cancelled" }
        avformat_network_init()
        av_log_set_level(AV_LOG_ERROR)
        val input = avformat_alloc_context()
        container = input
        val interrupt = object : AVIOInterruptCB.Callback_Pointer() {
            override fun call(opaque: Pointer?): Int = if (cancelled()) 1 else 0
        }
        interruptCallback = interrupt
        input.interrupt_callback().callback(interrupt)
        if (source.boundedRanges) installRanges(input)
        val options = AVDictionary(null)
        av_dict_set(options, "rw_timeout", "10000000", 0)
        if (source.headers.isNotEmpty()) {
            val block = source.headers.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }
            av_dict_set(options, "headers", block, 0)
        }
        val status = try {
            avformat_open_input(input, if (source.boundedRanges) null as String? else source.url, null, options)
        } finally {
            av_dict_free(options)
        }
        if (status < 0) throw failure("Cannot open audio", status)
        checkResult(avformat_find_stream_info(input, null as AVDictionary?), "Cannot inspect audio")
        streamIndex = av_find_best_stream(input, AVMEDIA_TYPE_AUDIO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0)
        check(streamIndex >= 0) { "This source does not contain a playable audio track" }
        val stream = input.streams(streamIndex)
        val parameters = stream.codecpar()
        val implementation = avcodec_find_decoder(parameters.codec_id())
        check(implementation != null && !implementation.isNull) { "The audio codec is unsupported" }
        val context = avcodec_alloc_context3(implementation)
        codec = context
        checkResult(avcodec_parameters_to_context(context, parameters), "Cannot configure audio decoder")
        context.pkt_timebase(stream.time_base())
        checkResult(avcodec_open2(context, implementation, null as AVDictionary?), "Cannot start audio decoder")
        for (index in 0 until input.nb_streams()) {
            if (index != streamIndex) input.streams(index).discard(AVDISCARD_ALL)
        }
        timeNumerator = stream.time_base().num().coerceAtLeast(1)
        timeDenominator = stream.time_base().den().coerceAtLeast(1)
        startTimestamp = stream.start_time().takeIf { it != AV_NOPTS_VALUE } ?: 0L
        targetTimestamp = startTimestamp
        sampleRate = context.sample_rate().coerceAtLeast(8_000)
        channels = context.ch_layout().nb_channels().coerceAtLeast(1)
        val sourceBits = context.bits_per_raw_sample().takeIf { it > 0 }
            ?: parameters.bits_per_raw_sample().takeIf { it > 0 }
            ?: parameters.bits_per_coded_sample().takeIf { it > 0 }
        bitDepth = sourceBits ?: (av_get_bytes_per_sample(context.sample_fmt()) * 8).coerceIn(16, 32)
        durationMs = input.duration().takeIf { it > 0 && it != AV_NOPTS_VALUE }?.div(1_000)
            ?: stream.duration().takeIf { it > 0 && it != AV_NOPTS_VALUE }
                ?.let { it * 1_000L * timeNumerator / timeDenominator } ?: 0
        val codecName = avcodec_get_name(parameters.codec_id())?.string.orEmpty().uppercase(Locale.ROOT)
        val properties = avcodec_descriptor_get(parameters.codec_id())?.props() ?: 0
        info = DecodedAudioInfo(codecName, sampleRate, channels, sourceBits,
            context.bit_rate().takeIf { it > 0 },
            properties and AV_CODEC_PROP_LOSSLESS != 0 && properties and AV_CODEC_PROP_LOSSY == 0)
        description = "$codecName${sourceBits?.let { " · $it-bit" }.orEmpty()} · ${sampleRate / 1_000.0} kHz"
        packet = av_packet_alloc()
        decoded = av_frame_alloc()
        filtered = av_frame_alloc()
    }

    fun configureOutput(format: AudioFormat) {
        output = format
        description += " → ${format.sampleSizeInBits}-bit · ${format.sampleRate.toInt() / 1_000.0} kHz shared output"
    }

    fun setControls(speed: Float, volume: Float) {
        this.speed = speed.coerceIn(0.5f, 2f)
        this.volume = volume.coerceIn(0f, 1f)
        graph?.let { filters ->
            checkResult(
                avfilter_graph_send_command(filters, "speed", "tempo", number(this.speed), null as BytePointer?, 0, 0),
                "Cannot change playback speed",
            )
            checkResult(
                avfilter_graph_send_command(filters, "gain", "volume", number(this.volume), null as BytePointer?, 0, 0),
                "Cannot change volume",
            )
        }
    }

    fun seek(milliseconds: Long) {
        val input = container ?: return
        targetTimestamp = startTimestamp + milliseconds.coerceAtLeast(0) * timeDenominator / (timeNumerator * 1_000L)
        checkResult(av_seek_frame(input, streamIndex, targetTimestamp, AVSEEK_FLAG_BACKWARD), "This stream could not seek")
        codec?.let(::avcodec_flush_buffers)
        packet?.let(::av_packet_unref)
        decoded?.let(::av_frame_unref)
        filtered?.let(::av_frame_unref)
        releaseFilters()
        decoderEnded = false
        sentEnd = false
        graphEnded = false
    }

    /** One interleaved PCM block, or null after both decoder and DSP have drained. */
    fun read(): ByteArray? {
        val outputFrame = filtered ?: return null
        while (!cancelled()) {
            graphSink?.let { sink ->
                val result = av_buffersink_get_frame(sink, outputFrame)
                if (result >= 0) {
                    val format = checkNotNull(output)
                    val count = outputFrame.nb_samples() * format.frameSize
                    val bytes = ByteArray(count)
                    outputFrame.data(0).get(bytes)
                    av_frame_unref(outputFrame)
                    return bytes
                }
                if (result == AVERROR_EOF || graphEnded) return null
                if (result != TRY_AGAIN) throw failure("Cannot read filtered audio", result)
            }
            if (!decodeFrame()) {
                media?.failure?.let { throw it }
                val sourceContext = graphSource ?: return null
                checkResult(av_buffersrc_add_frame_flags(sourceContext, null, 0), "Cannot drain audio filters")
                graphEnded = true
                continue
            }
            val frame = checkNotNull(decoded)
            if (graph == null) buildFilters(frame)
            checkResult(av_buffersrc_add_frame_flags(graphSource, frame, AV_BUFFERSRC_FLAG_KEEP_REF), "Cannot filter audio")
            av_frame_unref(frame)
        }
        return null
    }

    private fun decodeFrame(): Boolean {
        val context = codec ?: return false
        val frame = decoded ?: return false
        val data = packet ?: return false
        while (!cancelled()) {
            val received = avcodec_receive_frame(context, frame)
            if (received >= 0) return true
            if (received == AVERROR_EOF || decoderEnded) return false
            if (received != TRY_AGAIN) throw failure("Cannot decode audio frame", received)
            if (sentEnd) {
                decoderEnded = true
                return false
            }
            var found = false
            while (!found && !cancelled()) {
                val read = av_read_frame(container, data)
                if (read < 0) {
                    media?.failure?.let { throw it }
                    if (read != AVERROR_EOF && !cancelled()) throw failure("Audio stream interrupted", read)
                    avcodec_send_packet(context, null)
                    sentEnd = true
                    break
                }
                if (data.stream_index() == streamIndex) {
                    val sent = avcodec_send_packet(context, data)
                    av_packet_unref(data)
                    checkResult(sent, "Cannot decode audio packet")
                    found = true
                } else av_packet_unref(data)
            }
        }
        return false
    }

    private fun buildFilters(frame: AVFrame) {
        val destination = checkNotNull(output)
        val filters = avfilter_graph_alloc()
        graph = filters
        val rate = frame.sample_rate().takeIf { it > 0 } ?: sampleRate
        val count = frame.ch_layout().nb_channels().takeIf { it > 0 } ?: channels
        val formatName = av_get_sample_fmt_name(frame.format())?.string ?: error("Unknown decoded sample format")
        fun node(filter: String, name: String, arguments: String?): AVFilterContext {
            val node = AVFilterContext(null)
            checkResult(
                avfilter_graph_create_filter(node, avfilter_get_by_name(filter), name, arguments, null, filters),
                "Cannot create $filter audio filter",
            )
            return node
        }
        val input = node(
            "abuffer", "input",
            "time_base=$timeNumerator/$timeDenominator:sample_rate=$rate:sample_fmt=$formatName:channel_layout=${count}c",
        )
        val trim = node("atrim", "seek", "start_pts=$targetTimestamp")
        val tempo = node("atempo", "speed", number(speed))
        val gain = node("volume", "gain", number(volume))
        val outputLayout = if (destination.channels == 1) "mono" else "stereo"
        val outputFormat = if (destination.sampleSizeInBits > 16) "s32" else "s16"
        val conversion = node(
            "aformat", "output_format",
            "sample_fmts=$outputFormat:sample_rates=${destination.sampleRate.toInt()}:channel_layouts=$outputLayout",
        )
        val sink = node("abuffersink", "output", null)
        listOf(input, trim, tempo, gain, conversion, sink).zipWithNext().forEach { (left, right) ->
            checkResult(avfilter_link(left, 0, right, 0), "Cannot connect audio filters")
        }
        checkResult(avfilter_graph_config(filters, null), "Cannot configure audio output conversion")
        graphSource = input
        graphSink = sink
    }

    private fun installRanges(input: AVFormatContext) {
        val ranges = RangedMediaInput(source.url, source.headers, cancelled)
        media = ranges
        val storage = ByteArray(IO_BYTES)
        val reader = object : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer?, into: BytePointer?, count: Int): Int {
                if (into == null || cancelled()) return AVERROR_EOF
                val read = ranges.read(storage, minOf(storage.size, count))
                if (read < 0) return AVERROR_EOF
                into.put(storage, 0, read)
                return read
            }
        }
        val seeker = object : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer?, offset: Long, mode: Int): Long {
                if (mode and AVSEEK_SIZE != 0) return ranges.length
                val target = when (mode and 0xffff) {
                    0 -> offset
                    1 -> ranges.position + offset
                    2 -> if (ranges.length >= 0) ranges.length + offset else return -1L
                    else -> return -1L
                }
                if (target < 0) return -1L
                ranges.position = target
                return target
            }
        }
        val buffer = BytePointer(av_malloc(IO_BYTES.toLong()))
        val channel = avio_alloc_context(buffer, IO_BYTES, 0, null, reader, null, seeker)
        io = channel
        readCallback = reader
        seekCallback = seeker
        input.pb(channel)
        input.flags(input.flags() or AVFormatContext.AVFMT_FLAG_CUSTOM_IO)
    }

    fun abortNetwork() = media?.close() ?: Unit

    private fun releaseFilters() {
        graph?.let(::avfilter_graph_free)
        graph = null
        graphSource = null
        graphSink = null
    }

    override fun close() {
        releaseFilters()
        filtered?.let(::av_frame_free)
        decoded?.let(::av_frame_free)
        packet?.let(::av_packet_free)
        codec?.let(::avcodec_free_context)
        container?.takeUnless { it.isNull }?.let(::avformat_close_input)
        io?.let { channel ->
            av_free(channel.buffer())
            channel.buffer(null as BytePointer?)
            avio_context_free(channel)
        }
        readCallback?.deallocate()
        seekCallback?.deallocate()
        interruptCallback?.deallocate()
        media?.close()
        filtered = null
        decoded = null
        packet = null
        codec = null
        container = null
        io = null
        readCallback = null
        seekCallback = null
        interruptCallback = null
        media = null
    }

    private fun checkResult(result: Int, operation: String) {
        if (result < 0) throw failure(operation, result)
    }

    private fun failure(operation: String, result: Int): IOException {
        media?.failure?.let { return it }
        val bytes = ByteArray(256)
        av_strerror(result, bytes, bytes.size.toLong())
        return IOException("$operation: ${bytes.toString(Charsets.UTF_8).substringBefore('\u0000')}")
    }

    private fun number(value: Float): String = String.format(Locale.ROOT, "%.6f", value)
    private companion object {
        const val IO_BYTES = 64 * 1024
        // libavcodec/libavfilter use AVERROR(EAGAIN) while waiting for more input.
        const val TRY_AGAIN = -11
    }
}
