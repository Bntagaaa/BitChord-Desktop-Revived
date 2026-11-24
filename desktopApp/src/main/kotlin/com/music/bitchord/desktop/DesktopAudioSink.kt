package com.music.bitchord.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** The engine boundary; tests use a collecting sink while decoding real audio. */
internal interface DesktopAudioOutput {
    val format: DesktopPcmFormat
    var gain: Float
    val isOpen: Boolean
    val deviceName: String?
    val bufferBytes: Int
    fun isUsingSelection(id: String): Boolean
    fun open(requested: DesktopPcmFormat): Result<DesktopPcmFormat>
    fun write(samples: FloatArray, count: Int): Int
    fun framesPlayed(): Long
    fun pause()
    fun resume()
    fun flush()
    fun drain()
    fun close()
}

/** The speakers, and the negotiation to reach them. */
internal class DesktopAudioSink : DesktopAudioOutput {

    private var line: SourceDataLine? = null

    /** PipeWire's PulseAudio compatibility server (or native PulseAudio). */
    private var linuxPulse: DesktopLinuxPulseSink? = null

    /** True while the Windows shared-mode sink owns the output instead of Java Sound. */
    private var windowsOutput = false

    private var windowsBufferBytes = 0

    /** The selection for which [line] was opened, including the blank system-default choice. */
    private var openedForSelection: String? = null

    /** Reused conversion buffer; only the playback thread touches it. */
    private var staging = ByteArray(0)
    private var pulsePcm: DesktopNativePcm? = null

    /** Kept short: enough to distinguish real samples from a pipeline producing silence. */
    private var diagnosticBlocks = 0

    /** The format the mixer accepted, valid once [open] has succeeded. */
    override var format: DesktopPcmFormat = DesktopPcmFormat(44_100, 2, 2)
        private set

    /** Playback gain, applied to the samples rather than to a mixer control. */
    @Volatile
    override var gain: Float = 1f

    override val isOpen: Boolean get() = windowsOutput || linuxPulse != null || line != null

    /** Whether the live line already belongs to the choice currently shown in the player. */
    override fun isUsingSelection(id: String): Boolean = isOpen && openedForSelection == id

    /** Opens the best line the mixer will give for [requested]. */
    override fun open(requested: DesktopPcmFormat): Result<DesktopPcmFormat> = runCatching {
        val selectedDevice = DesktopAudioDevices.selected.value
        if (DesktopPlatform.isWindows) {
            val native = DesktopWindowsAudio.open(selectedDevice, requested)
            if (native.isSuccess) {
                val previous = line
                line = null
                windowsOutput = true
                windowsBufferBytes = native.getOrThrow()
                // Read live from the native side instead: it moves the stream itself when the
                // Windows default changes or a chosen device is unplugged.
                openedOn = null
                openedForSelection = selectedDevice
                format = requested
                diagnosticBlocks = 0
                closeLine(previous)
                DesktopTrackLog.log(
                    "audio output opened: ${deviceName ?: "Windows system default"} · " +
                        "${requested.sampleRate} Hz · ${requested.channels} ch · WASAPI shared",
                )
                return@runCatching requested
            }
            DesktopTrackLog.log("WASAPI output unavailable; trying Java Sound: ${native.exceptionOrNull()?.message}")
        }

        if (DesktopPlatform.isLinux &&
            (selectedDevice.isEmpty() || selectedDevice.startsWith(DesktopLinuxPulseAudio.PREFIX))
        ) {
            val native = DesktopLinuxPulseSink.open(selectedDevice, requested)
            if (native.isSuccess) {
                val opened = native.getOrThrow()
                val previousLine = line
                val previousPulse = linuxPulse
                line = null
                linuxPulse = opened
                pulsePcm = DesktopNativePcm.open(requested)
                openedOn = null
                openedForSelection = selectedDevice
                format = requested
                diagnosticBlocks = 0
                closeLine(previousLine)
                runCatching { previousPulse?.close() }
                DesktopTrackLog.log(
                    "audio output opened: ${opened.outputName} · " +
                        "${requested.sampleRate} Hz · ${requested.channels} ch · PulseAudio / PipeWire",
                )
                return@runCatching requested
            }
            // A named Pulse sink must not silently render on an unrelated ALSA output.
            // Leave the current output audible when a newly selected sink cannot be opened.
            if (selectedDevice.startsWith(DesktopLinuxPulseAudio.PREFIX)) {
                throw IllegalStateException(
                    "Could not open selected audio output: ${native.exceptionOrNull()?.message}",
                    native.exceptionOrNull(),
                )
            }
            DesktopTrackLog.log(
                "PulseAudio default unavailable, trying Java Sound: " + native.exceptionOrNull()?.message,
            )
        }

        if (windowsOutput) {
            DesktopWindowsAudio.close()
            windowsOutput = false
            windowsBufferBytes = 0
        }
        val ladder = buildList {
            add(requested)
            if (requested.isFloat) add(requested.copy(bytesPerSample = 2, isFloat = false))
            // A device that will not take the source's own rate is rare, but a 44.1k-only or
            // 48k-only card does exist and silence is not the right answer for it.
            for (rate in intArrayOf(48_000, 44_100)) {
                if (rate != requested.sampleRate) {
                    add(requested.copy(sampleRate = rate))
                    add(requested.copy(sampleRate = rate, bytesPerSample = 2, isFloat = false))
                }
            }
            if (requested.channels != 2) add(requested.copy(channels = 2, bytesPerSample = 2, isFloat = false))
        }

        // The chosen device, when there is one and it is still plugged in; otherwise whatever the
        // system calls the default.
        val mixer = DesktopAudioDevices.mixerFor(selectedDevice)
        val supports: (DesktopPcmFormat) -> Boolean = { candidate ->
            if (mixer == null) {
                AudioSystem.isLineSupported(infoFor(candidate))
            } else {
                runCatching { mixer.isLineSupported(infoFor(candidate)) }.getOrDefault(false)
            }
        }
        val accepted = ladder.firstOrNull(supports)
            ?: error("no audio line for any supported format")

        val opened = if (mixer == null) {
            AudioSystem.getLine(infoFor(accepted)) as SourceDataLine
        } else {
            mixer.getLine(infoFor(accepted)) as SourceDataLine
        }
        try {
            // Roughly a fifth of a second in the device's hands.
            opened.open(accepted.toAudioFormat(), accepted.byteRate / 5)
            opened.start()
        } catch (failure: Throwable) {
            runCatching { opened.close() }
            throw failure
        }

        // Do not tear down the working route until its replacement is known to have opened. A
        // Bluetooth device can disappear between enumeration and this call; that must not turn a
        // failed switch into silent playback on every other device too.
        val previous = line
        val previousPulse = linuxPulse
        linuxPulse = null
        pulsePcm = null
        line = opened
        openedOn = mixer?.mixerInfo?.name
        openedForSelection = selectedDevice
        format = accepted
        diagnosticBlocks = 0
        closeLine(previous)
        runCatching { previousPulse?.close() }
        DesktopTrackLog.log(
            "audio output opened: ${deviceName ?: "system default"} · " +
                "${accepted.sampleRate} Hz · ${accepted.channels} ch · " +
                (if (accepted.isFloat) "32-bit float" else "${accepted.bytesPerSample * 8}-bit PCM"),
        )
        accepted
    }

    /**
     * What the samples are actually being written to.
     *
     * The mixer's own name, not `lineInfo`, which describes the line's capabilities — "interface
     * SourceDataLine supporting 36 audio formats" is not a device.
     */
    override val deviceName: String?
        get() = runCatching {
            when {
                windowsOutput -> DesktopWindowsAudio.deviceName()
                linuxPulse != null -> linuxPulse?.outputName
                else -> line?.let { openedOn ?: AudioSystem.getMixer(null).mixerInfo?.name?.takeIf(String::isNotBlank) }
            }
        }.getOrNull()

    /** The mixer this line was actually opened on, when it was not the system default. */
    private var openedOn: String? = null

    /** How much audio the device is holding, in bytes; 0 before the line is open. */
    override val bufferBytes: Int
        get() = when {
            windowsOutput -> windowsBufferBytes
            linuxPulse != null -> linuxPulse?.bufferBytes ?: 0
            else -> runCatching { line?.bufferSize ?: 0 }.getOrDefault(0)
        }

    /** Writes [count] interleaved samples, blocking until the device takes them. */
    override fun write(samples: FloatArray, count: Int): Int {
        if (windowsOutput) {
            val written = DesktopWindowsAudio.write(samples, count, gain)
            diagnose(samples, count, written)
            return written
        }
        if (count <= 0) return 0
        val pulse = linuxPulse
        val target = line
        if (pulse == null && target == null) return 0
        val wanted = count * format.bytesPerSample
        val direct = if (pulse != null) pulsePcm?.pack(samples, count, gain) else null
        val written = when {
            pulse != null && direct != null -> pulse.writeDirect(direct, wanted)
            pulse != null -> pulse.write(convert(samples, count), wanted)
            else -> target!!.write(convert(samples, count), 0, wanted)
        }
        diagnose(samples, count, written)
        return written
    }

    private fun diagnose(samples: FloatArray, count: Int, written: Int) {
        if (diagnosticBlocks < DIAGNOSTIC_BLOCK_LIMIT) {
            diagnosticBlocks++
            val peak = (0 until minOf(count, samples.size)).maxOfOrNull { kotlin.math.abs(samples[it]) } ?: 0f
            if (peak > AUDIBLE_SIGNAL_FLOOR || diagnosticBlocks == DIAGNOSTIC_BLOCK_LIMIT) {
                DesktopTrackLog.log(
                    "audio output signal: peak=${"%.4f".format(java.util.Locale.ROOT, peak)} " +
                        "gain=${"%.2f".format(java.util.Locale.ROOT, gain)} wrote=$written bytes",
                )
                diagnosticBlocks = DIAGNOSTIC_BLOCK_LIMIT
            }
        }
    }

    /** Floats to whatever the device accepted, scaled by [gain]. */
    private fun convert(samples: FloatArray, count: Int): ByteArray {
        val current = format
        val volume = gain
        val bytes = count * current.bytesPerSample
        if (staging.size < bytes) staging = ByteArray(bytes)
        packDesktopPcm(samples, count, volume, current, staging)
        return staging
    }

    /** Frames the device has actually rendered — the honest playback position. */
    override fun framesPlayed(): Long =
        when {
            windowsOutput -> DesktopWindowsAudio.framesPlayed()
            linuxPulse != null -> linuxPulse?.framesPlayed() ?: 0L
            else -> line?.longFramePosition ?: 0L
        }

    override fun pause() {
        if (windowsOutput) DesktopWindowsAudio.pause()
        else if (linuxPulse == null) line?.stop()
        // pa_simple cannot cork; the engine stops writing on pause.
    }

    override fun resume() {
        if (windowsOutput) DesktopWindowsAudio.resume()
        else if (linuxPulse == null) line?.start()
    }

    /** Throws away what is queued, so a seek is heard at once. */
    override fun flush() {
        when {
            windowsOutput -> DesktopWindowsAudio.flush()
            linuxPulse != null -> linuxPulse?.flush()
            else -> line?.flush()
        }
    }

    /** Waits for the queue to finish, so the end of a track is not clipped. */
    override fun drain() {
        when {
            windowsOutput -> DesktopWindowsAudio.drain()
            linuxPulse != null -> linuxPulse?.drain()
            else -> runCatching { line?.drain() }
        }
    }

    override fun close() {
        val pulse = linuxPulse
        if (pulse != null) {
            linuxPulse = null
            pulsePcm = null
            openedOn = null
            openedForSelection = null
            diagnosticBlocks = 0
            pulse.close()
            return
        }
        if (windowsOutput) {
            DesktopWindowsAudio.close()
            windowsOutput = false
            windowsBufferBytes = 0
            openedOn = null
            openedForSelection = null
            diagnosticBlocks = 0
            return
        }
        val target = line ?: return
        line = null
        openedOn = null
        openedForSelection = null
        diagnosticBlocks = 0
        closeLine(target)
    }

    private fun closeLine(target: SourceDataLine?) {
        if (target == null) return
        runCatching {
            target.stop()
            target.flush()
            target.close()
        }
    }

    private fun infoFor(candidate: DesktopPcmFormat) =
        DataLine.Info(SourceDataLine::class.java, candidate.toAudioFormat())

    private companion object {
        const val DIAGNOSTIC_BLOCK_LIMIT = 50
        const val AUDIBLE_SIGNAL_FLOOR = 0.001f
    }
}

/** The same format in JavaSound's vocabulary. */
internal fun DesktopPcmFormat.toAudioFormat(): AudioFormat = AudioFormat(
    if (isFloat) AudioFormat.Encoding.PCM_FLOAT else AudioFormat.Encoding.PCM_SIGNED,
    sampleRate.toFloat(),
    bytesPerSample * 8,
    channels,
    frameBytes,
    sampleRate.toFloat(),
    false,
)
