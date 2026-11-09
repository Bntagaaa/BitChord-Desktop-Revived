package com.music.bitchord.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LandscapeMetadataStateTest {
    private data class Metadata(val id: String, val title: String = id, val artist: String = "artist", val origin: String = "Search")
    private fun state() = LandscapeMetadataState(Metadata("a")) { it.id }

    @Test fun initialMetadataIsAtRestAndAllFieldsShareOneProgress() = runBlocking {
        val state = state()
        assertNull(state.frame.outgoing)
        assertEquals(MetadataPose(), state.frame.incomingPose())
        val frame = requireNotNull(state.begin(Metadata("b", "New title", "New artist", "Album")))
        assertEquals(Metadata("a"), frame.outgoing)
        assertEquals(MetadataPose(1f, 0f), frame.incomingPose())
        frame.progress.snapTo(.5f)
        assertEquals(MetadataPose(.5f, .5f), frame.incomingPose())
        assertEquals(MetadataPose(-.5f, .5f), frame.outgoingPose())
        assertEquals("Album", frame.incoming.origin)
        assertEquals("New artist", frame.incoming.artist)
    }

    @Test fun metadataCorrectionsDoNotRestartTheSongTransition() = runBlocking {
        val state = state()
        val frame = requireNotNull(state.begin(Metadata("b")))
        frame.progress.snapTo(.7f)
        assertNull(state.begin(Metadata("b", "Corrected title", "Corrected artist")))
        assertSame(frame.progress, state.frame.progress)
        assertEquals(.7f, state.frame.progress.value)
        assertEquals("Corrected title", state.frame.incoming.title)
        assertEquals("Corrected artist", state.frame.incoming.artist)
    }

    @Test fun interruptedSkipPreservesTheVisibleLayerPoseAndDropsOlderTracks() = runBlocking {
        val state = state()
        val first = requireNotNull(state.begin(Metadata("b")))
        first.progress.snapTo(.8f)
        val visiblePose = first.incomingPose()
        val next = requireNotNull(state.begin(Metadata("c")))
        assertEquals("b", next.outgoing?.id)
        assertEquals(visiblePose, next.outgoingPose())
        assertEquals("c", next.incoming.id)
        state.finish(first) // A cancelled completion must not clear the newest animation.
        assertSame(next.progress, state.frame.progress)
        next.progress.snapTo(1f)
        state.finish(next)
        assertEquals("c", state.frame.incoming.id)
        assertNull(state.frame.outgoing)
    }

    @Test fun returningToTheDominantOutgoingTrackContinuesRatherThanReappears() = runBlocking {
        val state = state()
        val first = requireNotNull(state.begin(Metadata("b")))
        first.progress.snapTo(.2f)
        val pose = first.outgoingPose()
        val back = requireNotNull(state.begin(Metadata("a", "Updated a")))
        assertNull(back.outgoing)
        assertEquals(pose, back.incomingPose())
        assertEquals("Updated a", back.incoming.title)
    }

    @Test fun rapidChangesKeepOnlyTheCurrentPairAndFinishAtTheLatestSong() = runBlocking {
        val state = state()
        repeat(30) { index ->
            val frame = requireNotNull(state.begin(Metadata("song-$index")))
            frame.progress.snapTo(.65f)
            assertTrue(frame.outgoing == null || frame.outgoing.id != frame.incoming.id)
            assertEquals("song-$index", frame.incoming.id)
        }
        val last = state.frame
        last.progress.snapTo(1f)
        state.finish(last)
        assertEquals("song-29", state.frame.incoming.id)
        assertEquals(MetadataPose(), state.frame.incomingPose())
        assertNull(state.frame.outgoing)
    }
}
