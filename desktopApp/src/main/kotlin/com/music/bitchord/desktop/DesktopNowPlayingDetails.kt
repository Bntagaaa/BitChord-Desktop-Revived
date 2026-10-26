package com.music.bitchord.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * The compact Spotify-style track details view. It deliberately uses BitChord's existing
 * playhead, lyrics and queue; no parallel lyric fetch or Spotify session is started.
 *
 * Artist facts come from YouTube Music's artist header, and unavailable fields are
 * omitted rather than guessed (in particular there is no Spotify-only follower count).
 * The surrounding Astra side-panel host owns the frosted material and its per-area
 * blur policy; this content adds no independent blur effect.
 */
@Composable
internal fun DesktopNowPlayingDetailsPanel(
    song: Song,
    isPlaying: Boolean,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    lyrics: List<LyricLine>?,
    lyricsLoading: Boolean,
    position: PlaybackPosition,
    queue: List<Song>,
    queueIndex: Int,
    onShowLyrics: () -> Unit,
    onShowQueue: () -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onOpenArtist: (String?, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var artistPage by remember(song.artistId, song.artist) { mutableStateOf<ArtistPage?>(null) }
    var artistLoading by remember(song.artistId, song.artist) { mutableStateOf(true) }
    LaunchedEffect(song.artistId, song.artist) {
        artistLoading = true
        artistPage = null
        // Prefer the credited channel id. Name matching is exact to avoid wrong-artist bios.
        val artistId = song.artistId
            ?: song.artists.firstOrNull()?.browseId
            ?: YtMusicRepository.findArtistPageId(song.artist).getOrNull()
        if (artistId != null) {
            artistPage = YtMusicRepository.artistOverview(artistId).getOrNull()
        }
        artistLoading = false
    }

    // Spotify Canvas is opt-in through the existing account session and the existing
    // Animated Canvas setting. Reuse the main player's Spotify hit when available.
    val animatedCanvas by DesktopPlayerSettings.animatedCanvas.collectAsState()
    var spotifyCanvas by remember(song.videoId, song.title, song.artist) {
        mutableStateOf<DesktopCanvasArtwork?>(null)
    }
    // Look for Canvas silently. When it is unavailable, the album artwork remains the
    // fallback; the details panel does not need to expose lookup diagnostics to listeners.
    LaunchedEffect(song.videoId, song.title, song.artist, song.albumName, animatedCanvas) {
        spotifyCanvas = null
        if (animatedCanvas && DesktopSpotifyToken.cookie().isNotBlank()) {
            spotifyCanvas = runCatching { DesktopCanvasClient.lookupSpotify(song) }.getOrNull()
        }
    }

    val shape = RoundedCornerShape(9.dp)
    val tile = if (DesktopTheme.isDark) Color(0xFF222222) else Color(0xFFE5E9F0)
    val foreground = DesktopForeground
    val secondary = DesktopSecondary
    // The first decoded Canvas frame is the best colour source. Until it exists, sample
    // the album artwork so the lyric card is never stuck on a hard-coded brand red.
    val canvasFrame = DesktopCanvasBackdrop.frameFor(spotifyCanvas?.url)
    val artworkPalette = rememberDesktopArtworkPalette(song.thumbnailUrl, canvasFrame)
    val lyricsCardColor = lyricsPreviewSurfaceColor(artworkPalette, DesktopTheme.isDark)
    val liked = likeStatus == LikeStatus.LIKE
    val showLike = signedIn && song.localUri == null
    val upcoming = queue.withIndex().filter { it.index > queueIndex }.take(2)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // Spotify Canvas clips are portrait loops. Keep the still cover underneath
        // until the video decoder produces its first frame, or whenever no clip exists.
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(if (spotifyCanvas != null) 0.78f else 1f)
                .clip(shape),
        ) {
            DesktopArtwork(
                url = song.thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
                px = 800,
                contentDescription = "Artwork for " + song.title,
            )
            spotifyCanvas?.let { clip ->
                key(clip.url) {
                    DesktopCanvasView(
                        url = clip.url,
                        fallbackUrl = clip.fallbackUrl,
                        modifier = Modifier.fillMaxSize(),
                        isPlaying = isPlaying,
                    )
                }
                // Blend only the lower edge into Astra's panel surface. The Canvas itself
                // stays sharp and keeps its original colours; no full-frame black mask or blur.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            // Let the Canvas emerge from the panel surface at the top as well
                            // as disappear into it at the bottom. The middle stays untouched.
                            0f to DesktopTheme.palette.lyricsSurface,
                            0.10f to DesktopTheme.palette.lyricsSurface.copy(alpha = 0.58f),
                            0.22f to Color.Transparent,
                            0.62f to Color.Transparent,
                            0.82f to DesktopTheme.palette.lyricsSurface.copy(alpha = 0.42f),
                            1f to DesktopTheme.palette.lyricsSurface,
                        ),
                    ),
                )
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 17.dp, end = 16.dp),
                )
                Row(
                    Modifier.fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, end = 10.dp, top = 42.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song.artist,
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onOpenArtist(song.artistId, song.artist) },
                        )
                    }
                    if (showLike) {
                        DesktopToolbarButton(onClick = onToggleLike, size = 32.dp) {
                            Icon(
                                imageVector = if (liked) BitChordIcons.StarFilled else BitChordIcons.Star,
                                contentDescription = if (liked) "Remove from liked" else "Add to liked",
                                tint = if (liked) DesktopAccent else Color.White,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                    DesktopToolbarButton(
                        onClick = { onOpenArtist(song.artistId, song.artist) },
                        size = 32.dp,
                    ) {
                        Icon(
                            Icons.Rounded.Info,
                            "Artist details",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
        // Keep the conventional title row for the static-cover fallback. With Spotify
        // Canvas present, title and artist are composited over its bottom fade like Spotify.
        if (spotifyCanvas == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        color = foreground,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        color = secondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpenArtist(song.artistId, song.artist) },
                    )
                }
                if (showLike) {
                    DesktopToolbarButton(onClick = onToggleLike, size = 34.dp) {
                        Icon(
                            imageVector = if (liked) BitChordIcons.StarFilled else BitChordIcons.Star,
                            contentDescription = if (liked) "Remove from liked" else "Add to liked",
                            tint = if (liked) DesktopAccent else secondary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                DesktopToolbarButton(onClick = { onOpenArtist(song.artistId, song.artist) }, size = 34.dp) {
                    Icon(Icons.Rounded.Info, "Artist details", tint = secondary, modifier = Modifier.size(19.dp))
                }
            }
        }
        LyricsPreviewCard(
            lyrics = lyrics,
            loading = lyricsLoading,
            position = position,
            onShowLyrics = onShowLyrics,
            surfaceColor = lyricsCardColor,
        )
        ArtistOverviewCard(
            artistName = artistPage?.name?.takeIf(String::isNotBlank) ?: song.artist,
            artistImage = artistPage?.thumbnailUrl,
            about = artistPage?.description,
            monthlyListeners = artistPage?.monthlyListenerCount,
            subscribers = artistPage?.subscriberCountText,
            loading = artistLoading,
            tile = tile,
            onOpenArtist = { onOpenArtist(song.artistId ?: song.artists.firstOrNull()?.browseId, song.artist) },
        )
        CreditsPreviewCard(
            artists = song.artists.ifEmpty { listOf(ArtistRef(song.artist, song.artistId)) },
            tile = tile,
            onOpenArtist = onOpenArtist,
        )
        Column(Modifier.fillMaxWidth().clip(shape).background(tile).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Next in queue", color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                PanelTextAction("Open queue", onShowQueue)
            }
            Spacer(Modifier.height(14.dp))
            if (upcoming.isEmpty()) {
                Text("Nothing up next", color = secondary, fontSize = 13.sp)
            } else {
                upcoming.forEach { (index, next) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                            .clickable { onPlayQueueIndex(index) }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        DesktopArtwork(next.thumbnailUrl, Modifier.size(47.dp).clip(RoundedCornerShape(4.dp)), px = 160)
                        Column(Modifier.weight(1f)) {
                            Text(next.title, color = foreground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(next.artist, color = secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

private const val LYRICS_PREVIEW_MOTION_MS = 400
private const val LYRICS_PREVIEW_EXIT_MS = 220
/** Match the same smooth handover curve used by the full lyric animation. */
private val LYRICS_PREVIEW_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)

/** Use only original, non-empty lyric lines; gaps are never previewed as fake verses. */
internal fun detailsLyricWindow(lyrics: List<LyricLine>?, positionMs: Long): List<LyricLine> {
    val lines = lyrics.orEmpty().filter { !it.isGap && it.text.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    val index = lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    return lines.drop(index).take(3)
}

@Composable
private fun LyricsPreviewCard(
    lyrics: List<LyricLine>?,
    loading: Boolean,
    position: PlaybackPosition,
    onShowLyrics: () -> Unit,
    surfaceColor: Color,
) {
    val dark = DesktopTheme.isDark
    val cardColor = surfaceColor
    val ink = if (dark) Color.White else Color(0xFF1B2230)
    val panelBlurEnabled = DesktopAppearanceSettings.blurEnabled(DesktopBlurArea.SIDE_PANELS)
    val lyricsBlur by DesktopPlayerSettings.lyricsBlur.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val allowLineBlur = panelBlurEnabled && lyricsBlur
    val motionMs = if (reduceAnimation) 0 else LYRICS_PREVIEW_MOTION_MS
    val exitMs = if (reduceAnimation) 0 else LYRICS_PREVIEW_EXIT_MS
    val lines = detailsLyricWindow(lyrics, position.positionMs)
    val cardShape = RoundedCornerShape(9.dp)
    Column(
        Modifier.fillMaxWidth().clip(cardShape)
            .background(cardColor).clickable(onClick = onShowLyrics).padding(17.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics preview", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ink)
            PanelTextAction("Show more", onShowLyrics, ink)
        }
        Spacer(Modifier.height(13.dp))
        if (lines.isEmpty()) {
            Text(
                if (loading) "Loading lyrics…" else "Lyrics aren't available for this track",
                fontSize = 14.sp, color = ink.copy(alpha = 0.78f),
            )
        } else {
            Box(Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = lines.map { it.text },
                transitionSpec = {
                    (fadeIn(tween(motionMs, easing = LYRICS_PREVIEW_EASING)) +
                        slideInVertically(
                            animationSpec = tween(motionMs, easing = LYRICS_PREVIEW_EASING),
                            initialOffsetY = { height -> height / 4 },
                        )) togetherWith
                        (fadeOut(tween(exitMs, easing = LYRICS_PREVIEW_EASING)) +
                            slideOutVertically(
                                animationSpec = tween(exitMs, easing = LYRICS_PREVIEW_EASING),
                                targetOffsetY = { height -> -height / 6 },
                            ))
                },
                label = "lyricsPreviewMotion",
            ) { visibleLines ->
                Column {
                    visibleLines.forEachIndexed { i, line ->
                        // Same settle treatment as the full Lyrics panel: active text is
                        // crisp; the following lines ease back in opacity, blur and scale.
                        val lineBlur by animateDpAsState(
                            targetValue = when {
                                !allowLineBlur || i == 0 -> 0.dp
                                i == 1 -> 1.dp
                                else -> 1.dp
                            },
                            animationSpec = tween(
                                durationMillis = if (reduceAnimation) 0 else LYRICS_PREVIEW_MOTION_MS,
                                easing = LYRICS_PREVIEW_EASING,
                            ),
                            label = "lyricsPreviewBlur$i",
                        )
                        val lineAlpha by animateFloatAsState(
                            targetValue = when (i) { 0 -> 1f; 1 -> 0.8f; else -> 0.7f },
                            animationSpec = tween(
                                durationMillis = if (reduceAnimation) 0 else LYRICS_PREVIEW_MOTION_MS,
                                easing = LYRICS_PREVIEW_EASING,
                            ),
                            label = "lyricsPreviewAlpha$i",
                        )
                        val lineScale by animateFloatAsState(
                            targetValue = if (i == 0) 1f else 0.98f,
                            animationSpec = tween(
                                durationMillis = if (reduceAnimation) 0 else LYRICS_PREVIEW_MOTION_MS,
                                easing = LYRICS_PREVIEW_EASING,
                            ),
                            label = "lyricsPreviewScale$i",
                        )
                        Text(
                            line,
                            color = ink,
                            fontSize = 20.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .graphicsLayer {
                                    alpha = lineAlpha
                                    scaleX = lineScale
                                    scaleY = lineScale
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                                .blur(lineBlur, BlurredEdgeTreatment.Unbounded)
                                .padding(bottom = 7.dp),
                        )
                    }
                }
            }
                // A second, card-local fade lets the final lyric recede into the same
                // surface, instead of leaving the last row visibly cut off.
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.58f to Color.Transparent,
                            0.82f to cardColor.copy(alpha = 0.58f),
                            1f to cardColor,
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ArtistOverviewCard(
    artistName: String,
    artistImage: String?,
    about: String?,
    monthlyListeners: String?,
    subscribers: String?,
    loading: Boolean,
    tile: Color,
    onOpenArtist: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(tile)) {
        Box(Modifier.fillMaxWidth().height(170.dp).clip(shape).clickable(onClick = onOpenArtist)) {
            DesktopArtwork(artistImage, Modifier.fillMaxSize(), px = 700)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.58f), Color.Transparent, Color.Black.copy(alpha = 0.12f))),
                ),
            )
            Text(
                "About the artist", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(15.dp),
            )
        }
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(artistName, color = DesktopForeground, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                PanelTextAction("View artist", onOpenArtist)
            }
            val audience = monthlyListeners?.takeIf(String::isNotBlank)
                ?: subscribers?.takeIf(String::isNotBlank)
            if (audience != null) {
                Text(audience, color = DesktopSecondary, fontSize = 12.sp)
            }
            val description = about?.takeIf(String::isNotBlank)
            if (description != null) {
                Text(description, color = DesktopSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            } else {
                Text(
                    if (loading) "Loading artist info…" else "View this artist on YouTube Music",
                    color = DesktopSecondary, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun CreditsPreviewCard(
    artists: List<ArtistRef>,
    tile: Color,
    onOpenArtist: (String?, String) -> Unit,
) {
    var expanded by remember(artists) { mutableStateOf(false) }
    val unique = artists.filter { it.name.isNotBlank() }.distinctBy { it.name.trim().lowercase() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(tile).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Credits", color = DesktopForeground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (unique.size > 2) PanelTextAction(
                label = if (expanded) "Show less" else "Show all",
                onClick = { expanded = !expanded },
            )
        }
        unique.take(if (expanded) unique.size else 2).forEachIndexed { index, artist ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenArtist(artist.browseId, artist.name) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(artist.name, color = DesktopForeground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (index == 0) "Main artist" else "Featured artist", color = DesktopSecondary, fontSize = 11.sp)
                }
                Icon(Icons.Rounded.ChevronRight, "View artist", tint = DesktopSecondary, modifier = Modifier.size(19.dp))
            }
        }
    }
}

internal fun lyricsPreviewSurfaceColor(artworkPalette: DesktopArtworkPalette, dark: Boolean): Color {
    val base = if (dark) Color(0xFF393939) else Color(0xFFE5E9F0)
    val source = if (artworkPalette == DesktopArtworkPalette.Default) Color(0xFF808080) else artworkPalette.primary
    return tintSurfaceFromArtwork(base, source, if (dark) 0.28f else 0.22f)
}

internal fun tintSurfaceFromArtwork(base: Color, source: Color, amount: Float): Color {
    val average = (source.red + source.green + source.blue) / 3f
    return Color(
        red = (base.red + (source.red - average) * amount).coerceIn(0f, 1f),
        green = (base.green + (source.green - average) * amount).coerceIn(0f, 1f),
        blue = (base.blue + (source.blue - average) * amount).coerceIn(0f, 1f),
    )
}

@Composable
private fun PanelTextAction(label: String, onClick: () -> Unit, tint: Color = DesktopSecondary) {
    Text(
        text = label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint,
        modifier = Modifier.clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 5.dp),
    )
}
