package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.innertube.potoken.PoTokenGenerator

/** Shared stream resolver bindings, installed once by startup or by the first lookup. */
internal object DesktopYouTubePlayback {
    private val configured by lazy {
        StreamResolver.maxKbps = {
            DesktopStreamClient.ceiling.get() ?: DesktopSourceRegistry.ceiling(null).maxKbps
        }
        InnerTubeXResolver.init(
            filesDir = DesktopMediaCache.directory.toFile(),
            store = DesktopInnerTubeXStore,
            poTokenProvider = PoTokenGenerator(
                createMinter = { DesktopPoTokenWebView.getNewPoTokenGenerator() },
                available = { DesktopPoTokenWebView.available },
            ).asTokenProvider(),
        )
    }

    fun configure() = configured
}
