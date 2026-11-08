package com.music.bitchord.reworked

import com.music.bitchord.data.sources.addon.DetectedFormat
import com.music.bitchord.data.sources.addon.SourceFormats
import com.music.bitchord.data.sources.module.ModuleManager
import com.music.bitchord.data.sources.module.QuickJsExecutor
import com.music.bitchord.data.sources.module.SpineModule
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MobileModuleTest {
    @Test fun originalMobileQuickJsExportsRunOnJvmAndKeepQuotedArgumentsIntact() = runBlocking {
        val id = "desktop-fixture"
        val code = """
            module.exports = {
              searchTracks: async function(q, limit, context) {
                return {tracks:[{id:q,title:q,artist:context.settings.quality.value}],total:1};
              },
              getTrackStreamUrl: async function(id, quality, context) {
                return {streamUrl:'https://media.example/' + encodeURIComponent(id),track:{audioQuality:quality}};
              }
            };
        """.trimIndent()
        QuickJsExecutor.loadModule(id, code).getOrThrow()
        try {
            val manager = ModuleManager()
            val loaded = ModuleManager.LoadedModule(SpineModule(id = id, name = "Fixture"), code, "https://example.com")
            val query = "Quote \" and backslash \\ plus newline\n"
            val result = manager.searchTracks(loaded, query, settings = mapOf("quality" to "CD \"lossless\"")).getOrThrow()
            assertEquals(query, result.tracks.single().title)
            assertEquals("CD \"lossless\"", result.tracks.single().artist)
            val stream = manager.getStreamUrl(loaded, "id/1", "LOSSLESS").getOrThrow()
            assertEquals("https://media.example/id%2F1", stream.streamUrl)
        } finally { QuickJsExecutor.unload(id) }
    }

    @Test fun moduleIndexesAreRecognizedByOriginalMobileShape() {
        val detected = SourceFormats.detect("""{"category:music":[{"id":"fixture","name":"Fixture","download":"fixture.js"}]}""", "https://example.com/index.json")
        assertTrue(detected is DetectedFormat.ModuleIndex)
        assertEquals(1, detected.moduleCount)
    }
}
