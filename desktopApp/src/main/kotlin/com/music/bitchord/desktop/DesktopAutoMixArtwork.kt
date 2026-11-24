package com.music.bitchord.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.player.ArtworkMeshBackdrop
import com.music.bitchord.ui.player.FullArtworkBlurBackdrop
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.ui.player.rememberArtworkColors
import com.music.bitchord.ui.player.rememberArtworkMesh
import com.music.bitchord.ui.player.rememberFullArtworkBlurImage
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader

@Composable
internal fun DesktopAutoMixArtwork(
    song: Song,
    px: Int,
    modifier: Modifier,
) {
    Box(modifier) {
        DesktopArtwork(song.thumbnailUrl, Modifier.matchParentSize(), px = px)
        DesktopAutoMixOverlay(song, Modifier.matchParentSize(), px, mini = true)
    }
}

@Composable
internal fun DesktopAutoMixOverlay(
    song: Song,
    modifier: Modifier,
    px: Int,
    mini: Boolean,
    canvasVisible: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val active = rememberDesktopAutoMixPair(mini) ?: return
    val showingIncoming = active.incoming.matches(song)
    if (!showingIncoming && !active.outgoing.matches(song)) return
    if (active.outgoing == active.incoming) return

    // Prime the same bounded image cache used by the mini player while the outgoing art is visible.
    val outgoingUrl = active.outgoing.url.artworkAt(px)
    val incomingUrl = active.incoming.url.artworkAt(px)
    val outgoing by produceState<ImageBitmap?>(null, active.generation, outgoingUrl) {
        value = null
        value = DesktopArtworkCache.load(outgoingUrl)
    }
    val incoming by produceState<ImageBitmap?>(null, active.generation, incomingUrl) {
        value = null
        value = DesktopArtworkCache.load(incomingUrl)
    }
    if (!showingIncoming) return
    val snapshot = if (mini) null else DesktopPlayerHost.autoMixCanvasFrame.collectAsState().value
        ?.takeIf { it.generation == active.generation }?.image
    val previous = snapshot ?: outgoing ?: return
    val next = incoming
    if (next == null && !canvasVisible) return
    if (snapshot == null && outgoingUrl == incomingUrl && !canvasVisible) return

    val progress = rememberDesktopAutoMixProgress(active.generation)
    val mask = remember(previous, active.generation, contentScale) {
        DesktopOrganicDissolve.make(previous, active.generation, contentScale == ContentScale.Fit)
    }
    DisposableEffect(mask) { onDispose { mask?.close() } }
    if (mask == null) return
    Box(modifier) {
        if (!canvasVisible && next != null) {
            Image(next, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = contentScale)
        }
        Canvas(Modifier.matchParentSize()) { mask.draw(this, progress()) }
    }
}

@Composable
internal fun DesktopAutoMixAmbient(song: Song, theme: PlayerTheme, modifier: Modifier, mini: Boolean = false) {
    val reduceBlur by DesktopPlayerSettings.reduceDynamicBlur.collectAsState()
    if (reduceBlur) return
    val pair = rememberDesktopAutoMixPair(mini) ?: return
    val showingIncoming = pair.incoming.matches(song)
    if ((!showingIncoming && !pair.outgoing.matches(song)) || pair.outgoing == pair.incoming) return
    val url = pair.outgoing.url ?: return
    val progress = if (showingIncoming) rememberDesktopAutoMixProgress(pair.generation) else { { 0f } }
    val fade = modifier.graphicsLayer { alpha = 1f - progress() }
    val snapshot = if (mini) null else DesktopPlayerHost.autoMixCanvasFrame.collectAsState().value
        ?.takeIf { it.generation == pair.generation }?.image
    when (theme) {
        PlayerTheme.DEFAULT -> {
            val mesh = rememberArtworkMesh(url, snapshot, AMBIENT_PX)
            if (showingIncoming && mesh != null) ArtworkMeshBackdrop(mesh, modifier = fade)
        }
        PlayerTheme.LEGACY -> {
            val palette = rememberArtworkColors(url, snapshot)
            if (showingIncoming) MeshGradientBackground(palette, fade, url)
        }
        PlayerTheme.SIMPLE -> {
            val image = rememberFullArtworkBlurImage(url, AMBIENT_PX, prepare = true, bitmapOverride = snapshot)
            if (showingIncoming && image != null) FullArtworkBlurBackdrop(image, modifier = fade)
        }
    }
}

@Composable
internal fun rememberDesktopAutoMixPair(mini: Boolean): DesktopAutoMixArtworkPair? {
    val enabled by (if (mini) DesktopPlayerSettings.automixMiniArtwork else DesktopPlayerSettings.automixArtwork)
        .collectAsState()
    val reduceMotion by DesktopPlayerSettings.reduceAnimation.collectAsState()
    if (!enabled || reduceMotion) return null
    val feed = DesktopPlayerHost.autoMixVisual
    val pairFlow = remember(feed) { feed.map { it?.pair }.distinctUntilChanged() }
    val pair by pairFlow.collectAsState(feed.value?.pair)
    val skip = if (mini) DesktopPlayerSettings.skipMiniArtworkThrough else DesktopPlayerSettings.skipArtworkThrough
    return pair?.takeIf { it.generation > skip }
}

@Composable
private fun rememberDesktopAutoMixProgress(generation: Long): () -> Float {
    val samples: State<DesktopAutoMixVisualSample?> = DesktopPlayerHost.autoMixVisual.collectAsState()
    val frame = remember(generation) { mutableLongStateOf(System.nanoTime()) }
    val animate by remember(samples, generation) {
        derivedStateOf {
            val sample = samples.value
            sample != null && sample.pair.generation == generation && sample.playing &&
                sample.revealStart != Long.MAX_VALUE && sample.progress(sample.sampledAtNanos) < 1f
        }
    }
    LaunchedEffect(animate, generation) {
        if (animate) while (true) withFrameNanos { frame.longValue = System.nanoTime() }
    }
    return remember(samples, frame, generation) {
        var shown = 0f
        {
            val sample = samples.value?.takeIf { it.pair.generation == generation }
            val next = sample?.progress(frame.longValue) ?: 1f
            shown = maxOf(shown, next)
            shown
        }
    }
}

private const val AMBIENT_PX = 128

/** BitChord PR #1870's mottled mask, applied to the outgoing image in Skia. */
internal class DesktopOrganicDissolve private constructor(
    private val image: SkiaImage,
    private val builder: RuntimeShaderBuilder,
    generation: Long,
    private val fit: Boolean,
) : AutoCloseable {
    private val paint = Paint()
    private var imageShader: Shader? = null
    private var width = 0f
    private var height = 0f
    private var bounds = Rect.makeWH(0f, 0f)
    private var shader: Shader? = null
    private var lastProgress = Float.NaN

    init {
        val random = Random(generation)
        builder.uniform("seedOffset", random.nextFloat() * 32f, random.nextFloat() * 32f)
    }

    fun draw(scope: DrawScope, progress: Float) = with(scope) {
        if (progress >= 1f || size.width <= 0f || size.height <= 0f) return@with
        if (width != size.width || height != size.height) resize(size.width, size.height)
        if (progress != lastProgress) {
            val eased = progress * progress * (3f - 2f * progress)
            builder.uniform("progress", eased + 0.25f * (progress - eased))
            val next = builder.makeShader()
            paint.shader = next
            shader?.close()
            shader = next
            lastProgress = progress
        }
        drawIntoCanvas { it.nativeCanvas.drawRect(bounds, paint) }
    }

    private fun resize(newWidth: Float, newHeight: Float) {
        width = newWidth
        height = newHeight
        val scale = (if (fit) minOf(width / image.width, height / image.height)
            else max(width / image.width, height / image.height))
        val left = (width - image.width * scale) / 2f
        val top = (height - image.height * scale) / 2f
        val matrix = Matrix33.makeTranslate(left, top).makePreScale(scale, scale)
        val next = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), matrix)
        builder.child("cover", next)
        imageShader?.close()
        imageShader = next
        builder.uniform("bounds", width, height)
        bounds = if (fit) Rect.makeXYWH(left, top, image.width * scale, image.height * scale)
            else Rect.makeWH(width, height)
        lastProgress = Float.NaN
    }

    override fun close() {
        paint.close()
        shader?.close()
        builder.close()
        imageShader?.close()
        image.close()
    }

    companion object {
        private val effect by lazy {
            runCatching { RuntimeEffect.makeForShader(organicDissolveShader) }
                .onFailure { DesktopTrackLog.log("AutoMix Skia shader unavailable: ${it.message}") }
                .getOrNull()
        }

        fun make(bitmap: ImageBitmap, generation: Long, fit: Boolean): DesktopOrganicDissolve? {
            val runtimeEffect = effect ?: return null
            return DesktopOrganicDissolve(SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap()),
                RuntimeShaderBuilder(runtimeEffect), generation, fit)
        }
    }
}

private val organicDissolveShader = """
    uniform shader cover;
    uniform float2 bounds;
    uniform float progress;
    uniform float2 seedOffset;

    float hashCell(float2 cell) {
        float n = mod(cell.x * 127.0 + cell.y * 197.0, 251.0);
        n = mod(n * (n + 73.0) + cell.x * 97.0 + cell.y * 53.0, 251.0);
        n = mod(n * (n + 37.0) + cell.x * 31.0 + cell.y * 109.0, 251.0);
        return n / 251.0;
    }

    float valueNoise(float2 point) {
        float2 cell = floor(point);
        float2 f = fract(point);
        f = f * f * (3.0 - 2.0 * f);
        return mix(mix(hashCell(cell), hashCell(cell + float2(1.0, 0.0)), f.x),
                   mix(hashCell(cell + float2(0.0, 1.0)), hashCell(cell + float2(1.0)), f.x), f.y);
    }

    half4 main(float2 xy) {
        float2 point = xy / bounds * 2.6 + seedOffset;
        float noise = 0.0;
        float weight = 0.5;
        float total = 0.0;
        for (int i = 0; i < 3; ++i) {
            noise += valueNoise(point) * weight;
            total += weight;
            point = point * 2.0 + float2(3.9, 5.1);
            weight *= 0.5;
        }
        float threshold = mix(1.2, -0.2, progress);
        float field = clamp((noise / total - 0.18) / 0.64, 0.0, 1.0);
        float coverage = smoothstep(threshold - 0.2, threshold + 0.2, field);
        return cover.eval(xy) * (1.0 - coverage);
    }
""".trimIndent()
