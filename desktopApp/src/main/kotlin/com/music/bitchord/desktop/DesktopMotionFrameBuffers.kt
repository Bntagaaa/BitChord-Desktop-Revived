package com.music.bitchord.desktop

/**
 * Reused pixel storage for animated artwork. Skia keeps multiple submitted frames alive while the
 * renderer catches up, so give each frame a different buffer instead of overwriting the previous.
 * Snapshots used beyond a frame's lifetime must still be copied by their consumer.
 */
internal class DesktopMotionFrameBuffers(frameSize: Int, count: Int = 3) {
    init {
        require(frameSize > 0)
        require(count >= 3)
    }
    private val buffers = Array(count) { ByteArray(frameSize) }
    private var nextIndex = 0

    fun next(): ByteArray {
        val buffer = buffers[nextIndex]
        nextIndex = (nextIndex + 1) % buffers.size
        return buffer
    }
}
