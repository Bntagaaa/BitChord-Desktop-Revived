package com.music.bitchord.desktop

import com.music.bitchord.desktop.runtime.DesktopResourceOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Process/application ownership, never the lifetime of a tray-hidden window. */
internal object DesktopApplicationResources {
    private val instance = lazy {
        DesktopResourceOwner(onCloseFailure = { name, failure ->
            DesktopTrackLog.log("shutdown: $name: ${failure.message}")
        })
    }
    val owner: DesktopResourceOwner get() = instance.value
    private val hookInstalled = AtomicBoolean(false)

    fun installShutdownHook() {
        if (hookInstalled.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread(::close, "bitchord-resource-close"))
        }
    }

    fun close() { if (instance.isInitialized()) instance.value.close() }
}
