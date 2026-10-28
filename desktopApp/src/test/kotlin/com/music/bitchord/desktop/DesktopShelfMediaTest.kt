package com.music.bitchord.desktop

import com.music.bitchord.data.model.*
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopShelfMediaTest {
    private fun home(title: String, type: String?) = Json.parseToJsonElement("""
      {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
       {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"$title"}]}}},"contents":[
        {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Menawan"}]},"subtitle":{"runs":[{"text":"Adrian Khalif · 70K views"}]},
         "navigationEndpoint":{"watchEndpoint":{"videoId":"music-video","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":${if (type == null) "{}" else "{\"musicVideoType\":\"$type\"}"}}}},
         "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.test/crop.jpg","width":120,"height":120}]}}}}}
       ]}}
      ]}}}}]}}}
    """).jsonObject

    @Test fun squareYouTubeCropsDoNotOverrideWatchMetadata() {
        val videos = InnertubeParser.parseHome(home("For you", "MUSIC_VIDEO_TYPE_OMV"))
        val card = videos.single().items.single()
        assertTrue(card.isVideo)
        val song = card.toSong()
        assertTrue(song.isVideo); assertTrue(song.isVideoOrigin)
        assertEquals("music-video", song.videoId)
        assertEquals("Adrian Khalif", song.artist)
        assertEquals(ShelfLayout.VIDEO, videos.single().layout)
        val audio = InnertubeParser.parseHome(home("For you", "MUSIC_VIDEO_TYPE_ATV")).single().items.single()
        assertFalse(audio.isVideo); assertFalse(audio.toSong().isVideo)
    }

    @Test fun videoShelfWithoutWatchTypeStillPreservesItsIdentity() {
        val card = InnertubeParser.parseHome(home("Music Videos", null)).single().items.single()
        assertTrue(card.toSong().isVideo)
        val artistCard = InnertubeParser.parseArtistPage(home("Music Videos", null)).sections.single().items.single()
        assertTrue(artistCard.isVideo); assertTrue(artistCard.toSong().isVideo)
    }

    @Test fun manualVideoSelectionAcceptsAnIntroButRejectsCoversAndDifferentVersions() {
        val audio = Song("audio", "Menawan", "Adrian Khalif", null, durationText = "3:00")
        val video = Song("video", "Menawan (Official Music Video)", "Adrian Khalif", null, durationText = "4:30", isVideo = true)
        val cover = video.copy(videoId = "cover", artist = "Other Artist")
        val remix = video.copy(videoId = "remix", title = "Menawan (Remix)")
        val target = TrackMatcher.targetOf(audio)
        assertEquals(video, TrackMatcher.bestOfficialVideoForAudio(listOf(cover, remix, video), target))
        assertNull(TrackMatcher.bestOfficialVideoForAudio(listOf(cover, remix, audio), target))
    }
}
