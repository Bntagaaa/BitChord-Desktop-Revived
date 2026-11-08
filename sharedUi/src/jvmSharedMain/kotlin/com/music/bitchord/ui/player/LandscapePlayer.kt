package com.music.bitchord.ui.player

import com.music.bitchord.ui.components.ExplicitBadge

import com.music.bitchord.sharedui.resources.*

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song

/**
 * The widest the two columns are allowed to get between them, centred in
 * whatever is left over.
 *
 * Without a cap the lyric column simply takes every pixel past the artwork,
 * and on a wide window that is a column of text with a hand's width of empty
 * backdrop trailing off the right of every line. Lyrics are read down, not
 * across: past a certain measure the extra width is further for the eye to
 * travel back rather than more room for the words.
 */
private val LANDSCAPE_PLAYER_MAX_WIDTH = 1100.dp

/**
 * Below this height the landscape player tightens its right column — smaller
 * transport, shorter gaps — so a phone on its side fits the credits, the
 * scrubber, the transport and the volume bar between its status and
 * navigation bars without any of them falling off the bottom.
 */
private val LANDSCAPE_COMPACT_HEIGHT = 440.dp

/**
 * Side margin of the landscape player's sleeve column and credits on a
 * phone-height window, in place of [PLAYER_GUTTER].
 */
private val LANDSCAPE_GUTTER_COMPACT = 20.dp

/** The landscape sleeve's corner radius, at rest. */
internal val LANDSCAPE_ART_CORNER = 10.dp

/** The landscape sleeve's shape at rest, for a caller with no docking to do. */
private val LandscapeArtShape = RoundedCornerShape(LANDSCAPE_ART_CORNER)

/** Room above the landscape columns for the sheet's drag handle. */
private val LANDSCAPE_HANDLE_STRIP = 24.dp

/**
 * The player in a landscape window — a tablet, or a phone on its side. See
 * [landscapePlayerAvailable].
 *
 * Two columns of equal width. The left one is the same in every state: the
 * sleeve, and under it the lyrics / output / queue row with the output name —
 * the controls for *which* of the three the right column shows. The right one
 * is that one thing: the credits and transport, the lyric sheet or the queue.
 * Nothing else is drawn twice and nothing moves between columns, so opening
 * the lyrics or the queue is only ever the right column's page being turned.
 *
 * Purely the arrangement. Every slot is built by [NowPlayingScreen], which is
 * where the state behind all of them lives — the same state the portrait
 * player reads, so rotating mid-song carries the open panel, the scrub and the
 * translation mode straight across.
 *
 * Held to [LANDSCAPE_PLAYER_MAX_WIDTH] and centred, inside the safe-drawing
 * insets: a phone on its side has its status bar, navigation bar and camera
 * cutout down the short edges, and the player's content keeps clear of all
 * three while its backdrop still runs underneath them.
 */
@Composable
internal fun LandscapePlayerLayout(
    pane: PlayerPane,
    trackKey: String,
    background: @Composable (Modifier) -> Unit,
    /** The sleeve; handed a modifier that already fixes its square size. */
    artwork: @Composable (Modifier) -> Unit,
    /** The row under the sleeve, emitted into its column. */
    actions: @Composable () -> Unit,
    /** Kept mounted across pane switches; its text moves independently of the controls. */
    header: @Composable (compact: Boolean, active: Boolean) -> Unit,
    /** Credits and transport. [compact] is a phone-height window. */
    mainPane: @Composable (compact: Boolean) -> Unit,
    lyricsPane: @Composable () -> Unit,
    queuePane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        background(Modifier.fillMaxSize())

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            val compact = maxHeight < LANDSCAPE_COMPACT_HEIGHT
            val gutter = if (compact) LANDSCAPE_GUTTER_COMPACT else PLAYER_GUTTER
            val reduceMotion by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
            LandscapePlayerColumns(
                pane = pane,
                trackKey = trackKey,
                compact = compact,
                gutter = gutter,
                reduceMotion = reduceMotion,
                artwork = artwork,
                actions = actions,
                header = { active -> header(compact, active) },
                mainPane = { mainPane(compact) },
                lyricsPane = lyricsPane,
                queuePane = queuePane,
                boundLyricsToArtwork = PlayerPlatform.host.musicVideoFullscreen != null,
                modifier = Modifier
                    .widthIn(max = LANDSCAPE_PLAYER_MAX_WIDTH)
                    .fillMaxSize()
                    .padding(
                        top = LANDSCAPE_HANDLE_STRIP,
                        bottom = if (compact) 8.dp else 20.dp,
                    ),
            )

            // The sheet closes from a downward drag anywhere nothing else
            // claims, as it does in portrait; this is only the promise.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 8.dp)
                    .width(38.dp)
                    .height(5.dp)
                    .shadow(2.dp, RoundedCornerShape(3.dp), clip = false)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Color.White.copy(
                            alpha = if (PlayerPlatform.host.mobileTextChrome) {
                                DESKTOP_PLAYER_HANDLE_ALPHA
                            } else 0.70f,
                        ),
                    ),
            )
        }
    }
}

/**
 * The landscape player's sleeve: the still cover, and over it the looping
 * clip when there is one.
 *
 * Draws from the screen's own [request][PlayerArtwork.request] rather than a
 * request of its own, so it shares the portrait sleeve's bitmap, its retries
 * and its [loaded][PlayerArtwork.loaded] — rotating never reloads the cover.
 *
 * The clip keeps playing whichever pane is up. The portrait player stops it
 * because its sleeve shrinks to a thumbnail behind a panel; this one never
 * shrinks.
 */
@Composable
internal fun LandscapeArtwork(
    artRequest: ImageRequest,
    artLoaded: Boolean,
    onArtState: (AsyncImagePainter.State) -> Unit,
    canvas: CanvasArtwork?,
    canvasRendered: Boolean,
    isPlaying: Boolean,
    onCanvasRenderedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Halts the clip for a transition — see [CanvasArtworkPlayer]'s own. */
    pausedForTransition: Boolean = false,
    /**
     * The sleeve's shape, asked for each frame: its corner rounds off into the
     * mini player's cover's as the player docks into it.
     */
    sleeveShape: () -> Shape = { LandscapeArtShape },
    /** How much of the sleeve's shadow is cast, 1 at rest. */
    shadowFraction: () -> Float = { 1f },
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val casts = artLoaded || canvasRendered
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // What `.shadow` and then `.clip` laid down, asked for in the
                // layers' own blocks so a docking player can move them
                // without recomposing the sleeve.
                //
                // Only cast once there is a picture: on the flat placeholder
                // tile a shadow reads as a second, darker square.
                .graphicsLayer {
                    shadowElevation = if (casts) 14.dp.toPx() * shadowFraction() else 0f
                    shape = sleeveShape()
                    // As `.shadow` did: it clips only when it casts.
                    clip = casts
                }
                .graphicsLayer {
                    shape = sleeveShape()
                    clip = true
                }
                .background(Color.Black.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!artLoaded && !canvasRendered) {
                Icon(
                    imageVector = BitChordIcons.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxSize(0.36f),
                )
            }
            AsyncImage(
                model = artRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = onArtState,
                // Not drawn under a clip that got there first — see the
                // portrait sleeve's note on TextureView stacking order.
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent { if (artLoaded || !canvasRendered) drawContent() },
            )
            canvas?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = isPlaying,
                    pausedForTransition = pausedForTransition,
                    onRenderedChanged = onCanvasRenderedChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        overlay()
    }
}

/**
 * The landscape player's right column in its main state: where the session
 * came from, the credits, the current line, the scrubber, the transport and
 * the volume bar — the portrait half-player, less the row the left column
 * already has.
 *
 * Its parent places it beneath the persistent track header, independently of
 * the metadata's slide and the lyrics/queue transition.
 */
@Composable
internal fun LandscapeMainPane(
    compact: Boolean,
    /** The one-line lyric over the scrubber; null where synced lyrics are off. */
    lyricStrip: (@Composable () -> Unit)?,
    scrubber: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    /** Null where Settings hides the volume bar. */
    volume: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = PLAYER_MAX_WIDTH)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        lyricStrip?.invoke()
        scrubber()
        Spacer(Modifier.height(if (compact) 0.dp else 8.dp))
        transport()
        if (volume != null) {
            Spacer(Modifier.height(if (compact) 0.dp else 12.dp))
            volume()
        }
    }
}

/** A single snapshot keeps the playback origin and both credit lines on the same track. */
private data class LandscapeTrackMetadata(val song: Song, val caption: String?)

@Composable
internal fun LandscapeTrackHeader(
    compact: Boolean,
    song: Song,
    caption: String?,
    onOpenCaption: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    active: Boolean,
    reduceMotion: Boolean,
    actions: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LandscapeMetadataMotion(
            value = LandscapeTrackMetadata(song, caption),
            identity = { it.song.videoId },
            animate = active && !reduceMotion,
            modifier = Modifier.weight(1f).additiveChrome(LocalPlayerChrome.current),
        ) { displayed, moving, interactive ->
            Column {
                displayed.caption?.let { label ->
                    PlaybackOriginCaption(
                        text = label,
                        onClick = { if (interactive) onOpenCaption() },
                        textAlign = TextAlign.Start,
                        contentPadding = PaddingValues(vertical = 2.dp),
                    )
                    Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                }
                LandscapeCreditsText(
                    song = displayed.song,
                    onOpenAlbum = { if (interactive) onOpenAlbum(it) },
                    onOpenArtist = { id, name -> if (interactive) onOpenArtist(id, name) },
                    marqueeEnabled = active && !reduceMotion,
                    marqueePaused = moving,
                )
            }
        }
        // These callbacks and icons always belong to the live track. They are outside the
        // moving/clipped metadata slot and never disappear with an outgoing title.
        actions()
    }
}

/** Credits in the music-video layout use the same motion without a second nested transition. */
/**
 * Android's narrow landscape layout keeps its compact 16sp artist line.
 * Desktop has the width for the 20sp portrait credit that the mobile screenshot
 * uses. Both keep upstream's logical W500 cut and the chosen SF Pro family.
 */
internal fun landscapeArtistTextStyle(
    mobilePortrait: TextStyle,
    compactLandscape: TextStyle,
    desktopPlayer: Boolean,
): TextStyle = (if (desktopPlayer) mobilePortrait else compactLandscape)
    .copy(fontWeight = FontWeight.W500)

@Composable
internal fun LandscapeCredits(
    song: Song,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    showRevertCue: Boolean,
    onToggleLike: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
) {
    val reduceMotion by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LandscapeMetadataMotion(song, { it.videoId }, !reduceMotion, Modifier.weight(1f).additiveChrome(LocalPlayerChrome.current)) { displayed, moving, interactive ->
            LandscapeCreditsText(
                song = displayed,
                onOpenAlbum = { if (interactive) onOpenAlbum(it) },
                onOpenArtist = { id, name -> if (interactive) onOpenArtist(id, name) },
                marqueeEnabled = !reduceMotion,
                marqueePaused = moving,
            )
        }
        if (showActions) LandscapeTrackActions(song, signedIn, likeStatus, showRevertCue, onToggleLike, onOpenMenu)
    }
}

@Composable
private fun LandscapeCreditsText(
    song: Song,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String?, String) -> Unit,
    marqueeEnabled: Boolean,
    marqueePaused: Boolean,
) {
    Column {
        var titleOverflowing by remember(song.videoId) { mutableStateOf(false) }
        val artistColor = playerSecondaryColor(0.55f)
        MarqueeText(
            text = song.title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            enabled = marqueeEnabled,
            paused = marqueePaused,
            onOverflowChange = { titleOverflowing = it },
            trailing = if (song.isExplicit == true) ({ ExplicitBadge(artistColor) }) else null,
            modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
        )
        Spacer(Modifier.height(2.dp))
        ArtistCreditsMarquee(
            text = song.artist,
            credits = remember(song.artist, song.artists, song.artistId) {
                artistCredits(song.artist, song.artists, song.artistId)
            },
            style = landscapeArtistTextStyle(
                mobilePortrait = MaterialTheme.typography.titleLarge,
                compactLandscape = MaterialTheme.typography.titleMedium,
                desktopPlayer = PlayerPlatform.host.mobileTextChrome,
            ),
            color = artistColor,
            enabled = marqueeEnabled,
            paused = marqueePaused,
            startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
            onOpenArtist = onOpenArtist,
        )
    }
}

@Composable
internal fun LandscapeTrackActions(song: Song, signedIn: Boolean, likeStatus: LikeStatus,
    showRevertCue: Boolean, onToggleLike: () -> Unit, onOpenMenu: () -> Unit,
    desktop: Boolean = PlayerPlatform.host.musicVideoFullscreen != null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(10.dp))
        // The desktop keeps a local liked library even without YouTube sign-in.
        // Keep Android's existing guest behavior; never hide the desktop heart.
        if (landscapeLikeVisible(
                signedIn = signedIn,
                localSong = song.localUri != null,
                desktop = desktop,
            )) {
            val liked = likeStatus == LikeStatus.LIKE
            CircleGlyph(
                // Desktop follows the current mobile star for the Liked
                // playlist; retain the fork's old heart on Android.
                icon = when {
                    PlayerPlatform.host.mobileTextChrome && liked -> BitChordIcons.StarFilled
                    PlayerPlatform.host.mobileTextChrome -> BitChordIcons.Star
                    liked -> BitChordIcons.HeartFilled
                    else -> BitChordIcons.Heart
                },
                contentDescription = stringResource(
                    if (liked) Res.string.remove_from_liked else Res.string.like,
                ),
                onClick = onToggleLike,
                active = liked,
                haptic = if (liked) Haptic.ToggleOff else Haptic.ToggleOn,
            )
            Spacer(Modifier.width(8.dp))
        }
        CircleGlyph(
            icon = if (showRevertCue) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.MoreHoriz,
            contentDescription = stringResource(Res.string.more),
            onClick = onOpenMenu,
        )
    }
}

/**
 * The landscape lyric sheet and its existing romanize / source / translate bar.
 * Desktop reveals the bar on hover and borrows space from the visible sheet;
 * touch and standalone sidebar callers keep their permanently visible bar.
 */
@Composable
internal fun LandscapeLyricsPane(
    hasLyrics: Boolean,
    /** Shown in place of the sheet while there are no lines to draw. */
    placeholder: String,
    status: String,
    onStatusClick: () -> Unit,
    /** Whether lines are being picked, which is what the bar stands in for. */
    picking: Boolean = false,
    /** The pick bar itself, drawn in place of the row below. */
    pickBar: @Composable () -> Unit = {},
    romanizationToggle: @Composable () -> Unit,
    translationToggle: @Composable () -> Unit,
    revealControlsOnHover: Boolean = false,
    controlsPinned: Boolean = false,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
    panel: @Composable (Modifier) -> Unit,
) {
    LandscapeLyricsFrame(
        revealControlsOnHover = revealControlsOnHover,
        controlsPinned = controlsPinned || picking,
        reduceMotion = reduceMotion,
        modifier = modifier,
        lyrics = {
            if (hasLyrics) {
                panel(Modifier.fillMaxSize())
            } else {
                // Held at a fixed line rather than through
                // [LyricsUnavailableLine] or [LyricsLoadingLine]: those fade
                // out after a few seconds, which here would leave the whole
                // column blank for as long as the track keeps playing.
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalLyricsPanelColors.current.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        },
        controls = {
            // While a pick is open the row below gives way to the bar: the two
            // toggles in it would redraw the very lines the pick is indexing, and
            // the status line is the one thing the bar's own Cancel and Share sit
            // where.
            if (picking) {
                Box(Modifier.fillMaxWidth()) { pickBar() }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Fixed slots either side whether or not the toggles are drawn, so
                    // the status line stays centred as lyrics arrive.
                    Box(Modifier.size(34.dp)) { if (hasLyrics) romanizationToggle() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LyricsStatusWithChange(
                            status = status,
                            onStatusClick = onStatusClick,
                        )
                    }
                    Box(Modifier.size(34.dp)) { if (hasLyrics) translationToggle() }
                }
            }
        },
    )
}
