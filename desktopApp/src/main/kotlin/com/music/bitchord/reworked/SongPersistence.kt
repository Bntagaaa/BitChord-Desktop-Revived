package com.music.bitchord.reworked

import com.music.bitchord.data.model.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Platform storage adapter. The upstream mobile Song model is kept unchanged. */
internal object SongSerializer : KSerializer<Song> {
    override val descriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Song) = encoder.encodeSerializableValue(JsonObject.serializer(), encode(value))
    override fun deserialize(decoder: Decoder) = decode(decoder.decodeSerializableValue(JsonObject.serializer()))

    fun encode(s: Song): JsonObject = buildJsonObject {
        put("videoId", s.videoId); put("title", s.title); put("artist", s.artist)
        put("thumbnailUrl", s.thumbnailUrl); put("durationText", s.durationText)
        put("artistId", s.artistId); put("albumId", s.albumId); put("albumName", s.albumName)
        putJsonArray("artists") { s.artists.forEach { artist -> add(buildJsonObject { put("name", artist.name); put("browseId", artist.browseId) }) } }
        put("isVideo", s.isVideo); put("isVideoOrigin", s.isVideoOrigin); put("setVideoId", s.setVideoId)
        put("queueTier", s.queueTier.name); put("queueEntryId", s.queueEntryId); put("radioName", s.radioName)
        put("localUri", s.localUri); put("localPath", s.localPath); put("downloadFormat", s.downloadFormat)
        put("localDateAddedSeconds", s.localDateAddedSeconds); put("localDateModifiedSeconds", s.localDateModifiedSeconds)
        put("trackNumber", s.trackNumber); put("sourceQuality", s.sourceQuality); put("sourceHiRes", s.sourceHiRes)
        put("isExplicit", s.isExplicit); put("playbackSource", s.playbackSource)
        put("playbackSourceType", s.playbackSourceType?.name); put("playbackSourceId", s.playbackSourceId)
        put("feedbackToken", s.feedbackToken)
    }

    fun decode(o: JsonObject): Song {
        fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
        fun bool(key: String) = (o[key] as? JsonPrimitive)?.booleanOrNull
        fun number(key: String) = (o[key] as? JsonPrimitive)?.longOrNull
        return Song(
            videoId = requireNotNull(str("videoId")), title = str("title").orEmpty(), artist = str("artist").orEmpty(),
            thumbnailUrl = str("thumbnailUrl"), durationText = str("durationText"), artistId = str("artistId"),
            artists = (o["artists"] as? JsonArray).orEmpty().mapNotNull { item -> (item as? JsonObject)?.let { artist ->
                artist["name"]?.jsonPrimitive?.contentOrNull?.let { ArtistRef(it, artist["browseId"]?.jsonPrimitive?.contentOrNull) }
            } },
            albumId = str("albumId"), albumName = str("albumName"), isVideo = bool("isVideo") ?: false,
            isVideoOrigin = bool("isVideoOrigin") ?: (bool("isVideo") ?: false), setVideoId = str("setVideoId"),
            queueTier = QueueTier.entries.find { it.name == str("queueTier") } ?: QueueTier.CONTEXT,
            queueEntryId = str("queueEntryId"), radioName = str("radioName"), localUri = str("localUri"),
            downloadFormat = str("downloadFormat"), localPath = str("localPath"),
            localDateAddedSeconds = number("localDateAddedSeconds"), localDateModifiedSeconds = number("localDateModifiedSeconds"),
            trackNumber = number("trackNumber")?.toInt(), sourceQuality = str("sourceQuality"), sourceHiRes = bool("sourceHiRes") ?: false,
            isExplicit = bool("isExplicit"), playbackSource = str("playbackSource"),
            playbackSourceType = PlaybackSourceType.entries.find { it.name == str("playbackSourceType") },
            playbackSourceId = str("playbackSourceId"), feedbackToken = str("feedbackToken"),
        )
    }
}

internal object SongListSerializer : KSerializer<List<Song>> by ListSerializer(SongSerializer)
