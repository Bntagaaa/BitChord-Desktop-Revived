package com.music.bitchord.data.innertube

import com.music.bitchord.data.Http
import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Direct zero-BotGuard `/youtubei/v1/player` fast-path using YouTube's `VISIONOS` client.
 *
 * Mirrors the web player's primary unauthenticated resolution path: a single `/player`
 * request yields direct audio and video adaptive stream URLs without waiting for QuickJS
 * cipher preprocessing or a JavaFX/Android BotGuard WebView. When `VISIONOS` refuses a
 * track (e.g. age gate, private, region, or cipher-only URLs), callers fall through to
 * [InnerTubeXResolver]'s full `InnerTubeExtractor` + PoToken pipeline.
 */
internal object VisionOsClient {

    private const val TAG = "BitChord"
    const val CLIENT_NAME = "VISIONOS"
    const val CLIENT_ID = "101"
    const val CLIENT_VERSION = "1.02"
    const val PROFILE_ID = "VISIONOS"
    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val ORIGIN = "https://www.youtube.com"
    private const val CALL_TIMEOUT_MS = 4_500L
    private const val CACHE_TTL_MS = 120_000L
    private const val MAX_CACHE_ENTRIES = 16

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    internal data class AdaptiveFormat(
        val itag: Int,
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val width: Int,
        val height: Int,
        val isAudio: Boolean,
        val isVideo: Boolean,
    ) {
        val kbps: Int get() = bitrate / 1000
        val isOpusWebm: Boolean
            get() = isAudio && ("webm" in mimeType.lowercase() || "opus" in mimeType.lowercase())
        val isM4a: Boolean
            get() = isAudio && ("mp4" in mimeType.lowercase() || "m4a" in mimeType.lowercase())
        val isAvcMp4: Boolean
            get() = isVideo && "mp4" in mimeType.lowercase() && "avc1" in mimeType.lowercase()
    }

    internal data class ParsedPlayer(
        val videoId: String,
        val formats: List<AdaptiveFormat>,
        val loudnessDb: Double?,
        val fetchedAtMs: Long,
        val earliestExpireMs: Long,
    ) {
        fun isFresh(nowMs: Long = MonotonicClock.nowMs()): Boolean =
            nowMs - fetchedAtMs < CACHE_TTL_MS && System.currentTimeMillis() < earliestExpireMs
    }

    private val cache = object : LinkedHashMap<String, ParsedPlayer>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedPlayer>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    private val fastHttpClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    @Volatile
    internal var playerEndpoint: String = PLAYER_URL

    @Volatile
    internal var httpClientProvider: () -> OkHttpClient = { fastHttpClient }

    fun mediaHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Origin" to ORIGIN,
        "Referer" to "$ORIGIN/",
    )

    fun clearCache(videoId: String? = null) {
        synchronized(cache) {
            if (videoId == null) cache.clear() else cache.remove(videoId)
        }
    }

    /**
     * Resolves an audio stream for [videoId] via `VISIONOS`, caching both audio and video
     * formats so a companion [resolveVideo] call for the same track pays zero extra requests.
     */
    suspend fun resolveAudio(
        videoId: String,
        maxKbps: Int,
        requireM4a: Boolean,
        visitorData: String?,
        hl: String,
        gl: String,
    ): InnerTubeXResolver.Extracted? {
        val parsed = getOrFetch(videoId, visitorData, hl, gl) ?: return null
        val picked = pickAudioFormat(parsed.formats, maxKbps, requireM4a) ?: return null
        return InnerTubeXResolver.Extracted(
            videoId = videoId,
            url = picked.url,
            kbps = picked.kbps,
            mimeType = picked.mimeType,
            loudnessDb = parsed.loudnessDb,
            clientName = CLIENT_NAME,
            profileId = PROFILE_ID,
            headers = mediaHeaders(),
        )
    }

    /**
     * Resolves a video-only stream for [videoId] at most [maxHeight] tall, reusing the
     * cached [ParsedPlayer] from [resolveAudio] when available.
     */
    suspend fun resolveVideo(
        videoId: String,
        maxHeight: Int,
        visitorData: String?,
        hl: String,
        gl: String,
    ): InnerTubeXResolver.VideoStream? {
        val parsed = getOrFetch(videoId, visitorData, hl, gl) ?: return null
        val videoFormats = parsed.formats.filter { it.isVideo && it.height > 0 }
        val picked = pickVideoFormat(videoFormats, maxHeight) ?: return null
        val heights = videoFormats.map { it.height }.distinct().sortedDescending()
        TrackLog.d(
            TAG,
            "video: ${picked.width}x${picked.height} ${picked.mimeType} via $CLIENT_NAME",
            about = videoId,
        )
        return InnerTubeXResolver.VideoStream(
            videoId = videoId,
            url = picked.url,
            width = picked.width,
            height = picked.height,
            mimeType = picked.mimeType,
            headers = mediaHeaders(),
            heights = heights,
        )
    }

    private suspend fun getOrFetch(
        videoId: String,
        visitorData: String?,
        hl: String,
        gl: String,
    ): ParsedPlayer? {
        synchronized(cache) {
            cache[videoId]?.let { entry ->
                if (entry.isFresh()) return entry
                cache.remove(videoId)
            }
        }
        val started = MonotonicClock.nowMs()
        val fetched = fetchPlayer(videoId, visitorData, hl, gl) ?: return null
        TrackLog.d(TAG, "$CLIENT_NAME answered OK in ${MonotonicClock.nowMs() - started}ms", about = videoId)
        synchronized(cache) {
            cache[videoId] = fetched
        }
        return fetched
    }

    private suspend fun fetchPlayer(
        videoId: String,
        visitorData: String?,
        hl: String,
        gl: String,
    ): ParsedPlayer? = withContext(Dispatchers.IO) {
        val bodyJson = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice17,1")
                    put("osName", "visionOS")
                    put("osVersion", "26.5.23O471")
                    put("hl", hl)
                    put("gl", gl)
                    visitorData?.takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()

        val request = Request.Builder()
            .url(playerEndpoint)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("Accept-Language", if (hl == "en") "en-US,en;q=0.9" else "$hl,en-US;q=0.8,en;q=0.7")
            .build()

        runCatching {
            httpClientProvider().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    TrackLog.w(TAG, "$CLIENT_NAME HTTP ${response.code} for $videoId", about = videoId)
                    return@use null
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) return@use null
                parsePlayerResponse(videoId, raw)
            }
        }.onFailure { error ->
            TrackLog.w(TAG, "$CLIENT_NAME request failed for $videoId: ${error.message}", about = videoId)
        }.getOrNull()
    }

    internal fun parsePlayerResponse(
        videoId: String,
        rawJson: String,
        nowMs: Long = MonotonicClock.nowMs(),
    ): ParsedPlayer? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val status = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        if (status != "OK") {
            val reason = root["playabilityStatus"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
            TrackLog.d(TAG, "$CLIENT_NAME playability=$status reason=$reason", about = videoId)
            return null
        }
        val details = root["videoDetails"]?.jsonObject
        val isLive = details?.get("isLive")?.jsonPrimitive?.booleanOrNull == true ||
            details?.get("isLiveContent")?.jsonPrimitive?.booleanOrNull == true
        if (isLive) return null

        val streamingData = root["streamingData"]?.jsonObject ?: return null
        val adaptive = streamingData["adaptiveFormats"]?.jsonArray ?: return null
        val loudnessDb = root["playerConfig"]?.jsonObject
            ?.get("audioConfig")?.jsonObject
            ?.get("loudnessDb")?.jsonPrimitive?.doubleOrNull

        var earliestExpireMs = Long.MAX_VALUE
        val formats = adaptive.mapNotNull { element ->
            val obj = element.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (StreamResolver.isLiveUrl(url)) return@mapNotNull null
            val mime = obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val lowerMime = mime.lowercase()
            val isAudio = lowerMime.startsWith("audio/")
            val isVideo = lowerMime.startsWith("video/")
            if (!isAudio && !isVideo) return@mapNotNull null
            val itag = obj["itag"]?.jsonPrimitive?.intOrNull ?: 0
            val bitrate = obj["averageBitrate"]?.jsonPrimitive?.intOrNull
                ?: obj["bitrate"]?.jsonPrimitive?.intOrNull
                ?: 0
            val width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0
            val height = obj["height"]?.jsonPrimitive?.intOrNull ?: 0
            val expireSec = url.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()
            if (expireSec != null && expireSec > 0) {
                earliestExpireMs = minOf(earliestExpireMs, expireSec * 1000L - 30_000L)
            }
            AdaptiveFormat(
                itag = itag,
                url = url,
                mimeType = mime,
                bitrate = bitrate,
                width = width,
                height = height,
                isAudio = isAudio,
                isVideo = isVideo,
            )
        }
        if (formats.none { it.isAudio }) return null
        return ParsedPlayer(
            videoId = videoId,
            formats = formats,
            loudnessDb = loudnessDb,
            fetchedAtMs = nowMs,
            earliestExpireMs = earliestExpireMs,
        )
    }

    internal fun pickAudioFormat(
        formats: List<AdaptiveFormat>,
        maxKbps: Int,
        requireM4a: Boolean,
    ): AdaptiveFormat? {
        val candidates = formats.filter { fmt ->
            if (!fmt.isAudio) return@filter false
            if (requireM4a) fmt.isM4a else true
        }
        if (candidates.isEmpty()) return null
        val withinBudget = candidates.filter { it.kbps <= maxKbps }
        val pool = withinBudget.ifEmpty { candidates }
        return if (requireM4a) {
            pool.maxByOrNull { it.bitrate }
        } else {
            // Prefer Opus/WebM at comparable bitrate (matches web VISIONOS itag=251), else highest bitrate.
            pool.maxWithOrNull(
                compareBy<AdaptiveFormat> { it.isOpusWebm }.thenBy { it.bitrate },
            )
        }
    }

    internal fun pickVideoFormat(
        formats: List<AdaptiveFormat>,
        maxHeight: Int,
    ): AdaptiveFormat? {
        val valid = formats.filter { it.isVideo && it.height > 0 }
        if (valid.isEmpty()) return null
        val underMax = valid.filter { it.height <= maxHeight }
        val targetHeight = (underMax.maxOfOrNull { it.height } ?: valid.minOf { it.height })
        val atHeight = valid.filter { it.height == targetHeight }
        // Prefer H.264/MP4 (avc1) for fast FFmpeg decode; fallback to highest bitrate at target height.
        return atHeight.maxWithOrNull(
            compareBy<AdaptiveFormat> { it.isAvcMp4 }.thenBy { it.bitrate },
        )
    }
}
