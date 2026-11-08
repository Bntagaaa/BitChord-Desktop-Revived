package com.music.bitchord.reworked

import com.music.bitchord.data.sources.addon.AddonStream
import com.music.bitchord.data.sources.addon.AddonTrack
import com.music.bitchord.data.sources.module.ModuleManager
import com.music.bitchord.data.sources.module.SpineModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

/** JVM wiring for the original mobile QuickJS runtime, with one namespace per source. */
internal object MobileModuleSources {
    private val managers = ConcurrentHashMap<String, ModuleManager>()
    private val loadedIds = ConcurrentHashMap<String, MutableSet<String>>()

    suspend fun check(config: MobileSourceConfig) {
        val modules = managers.computeIfAbsent(config.id) { ModuleManager() }.fetchIndex(config.baseUrl).getOrThrow()
        check(modules.isNotEmpty()) { "The module index is empty" }
    }

    suspend fun search(config: MobileSourceConfig, query: String, quality: String): List<AddonTrack> {
        val manager = managers.computeIfAbsent(config.id) { ModuleManager() }
        val modules = manager.fetchIndex(config.baseUrl).getOrThrow()
        val tracks = mutableListOf<AddonTrack>()
        var failure: Throwable? = null
        var answered = false
        for (module in modules) {
            currentCoroutineContext().ensureActive()
            if (module.isDolbyAtmos || module.id.contains("::")) continue
            try {
                val loaded = load(manager, config, module)
                val found = manager.searchTracks(loaded, query, 50, mapOf("quality" to quality)).getOrThrow()
                answered = true
                tracks += found.tracks.map { track ->
                    AddonTrack(id = "${module.id}::${track.id}", title = track.title, artist = track.artist,
                        album = track.album, artworkURL = track.albumCover, duration = track.duration.toDouble(),
                        format = track.format, audioQuality = track.audioQuality)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        if (!answered && failure != null) throw failure
        return tracks
    }

    suspend fun stream(config: MobileSourceConfig, id: String, quality: String): AddonStream {
        val cut = id.indexOf("::")
        require(cut > 0 && cut < id.length - 2) { "The module track ID is incomplete" }
        val moduleId = id.substring(0, cut)
        val trackId = id.substring(cut + 2)
        val manager = managers.computeIfAbsent(config.id) { ModuleManager() }
        val module = manager.fetchIndex(config.baseUrl).getOrThrow().firstOrNull { it.id == moduleId }
            ?: error("The track's module is no longer in this index")
        val loaded = load(manager, config, module)
        val answer = manager.getStreamUrl(loaded, trackId, quality, mapOf("quality" to quality)).getOrThrow()
        return AddonStream(
            url = answer.streamUrl.orEmpty(), headers = answer.headers,
            quality = answer.track?.audioQuality.orEmpty(), mimeType = answer.track?.mimeType,
            sampleRate = answer.track?.sampleRate, bitDepth = answer.track?.bitDepth?.toDouble(),
            bitrate = answer.track?.bitrate?.toDouble(), audioModes = answer.track?.audioModes.orEmpty(),
        )
    }

    private suspend fun load(manager: ModuleManager, config: MobileSourceConfig, module: SpineModule): ModuleManager.LoadedModule {
        val namespaced = module.copy(id = "${config.id}/${module.id}")
        val result = manager.loadModule(namespaced) { config.baseUrl.substringBeforeLast('/') }.getOrThrow()
        loadedIds.computeIfAbsent(config.id) { ConcurrentHashMap.newKeySet() }.add(namespaced.id)
        return result
    }

    fun clear(id: String) {
        val manager = managers.remove(id) ?: return
        // Do not unload another configured source's engines from the global mobile pool.
        loadedIds.remove(id)?.forEach(manager::unloadModule)
    }
}
