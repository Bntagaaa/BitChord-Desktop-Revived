package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.ceil

/**
 * A GPU-side display list of Home, not a bitmap screenshot. It is owned by the
 * frame only while the queue pilot is visible, never by the playback engine.
 */
internal class HomeQueueGlassBackdrop(val layer: GraphicsLayer) {
    var coordinates: LayoutCoordinates? by mutableStateOf(null)
}

internal val LocalHomeQueueGlassBackdrop = staticCompositionLocalOf<HomeQueueGlassBackdrop?> { null }

@Composable
internal fun rememberHomeQueueGlassBackdrop(): HomeQueueGlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { HomeQueueGlassBackdrop(layer) }
}

internal fun Modifier.captureHomeQueueGlassBackdrop(backdrop: HomeQueueGlassBackdrop): Modifier =
    onGloballyPositioned { backdrop.coordinates = it }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

private val QueueGlassShape = RoundedCornerShape(22.dp)
private const val SAMPLE_SCALE = 0.5f

internal fun homeQueueGlassSampleSize(width: Float, height: Float): IntSize = IntSize(
    ceil(width * SAMPLE_SCALE).toInt().coerceAtLeast(1),
    ceil(height * SAMPLE_SCALE).toInt().coerceAtLeast(1),
)

/**
 * Replays the live page into a panel-sized, half-resolution layer. The Skia
 * image filter first blurs that sample, then refracts it near the curved
 * edges and applies adaptive tint and directional bevel lighting.
 * Queue text and controls are drawn afterwards at native resolution.
 */
@Composable
internal fun Modifier.homeQueueLiquidGlass(
    backdrop: HomeQueueGlassBackdrop?,
    fill: Color,
): Modifier {
    if (backdrop == null) return clip(QueueGlassShape).background(fill)
    val sampleLayer = rememberGraphicsLayer()
    var panelCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return clip(QueueGlassShape)
        .onGloballyPositioned { panelCoordinates = it }
        .drawWithCache {
            val sampleSize = homeQueueGlassSampleSize(size.width, size.height)
            val effect = createHomeQueueGlassRenderEffect(
                width = sampleSize.width,
                height = sampleSize.height,
                pixelsPerDp = density * SAMPLE_SCALE,
                fill = fill,
            )
            sampleLayer.renderEffect = effect
            onDrawWithContent {
                // Also gives an opaque, legible fallback before the source is
                // positioned, or if the installed Skia backend cannot build
                // the runtime shader. Never leave an unblurred page under text.
                drawRect(fill)
                val source = backdrop.coordinates
                val target = panelCoordinates
                if (effect != null && source?.isAttached == true && target?.isAttached == true) {
                    val offset = runCatching { source.localPositionOf(target, Offset.Zero) }.getOrNull()
                    if (offset != null && offset.x.isFinite() && offset.y.isFinite()) {
                        sampleLayer.record(size = sampleSize) {
                            clipRect(
                                right = sampleSize.width.toFloat(),
                                bottom = sampleSize.height.toFloat(),
                            ) {
                                drawRect(fill)
                                scale(SAMPLE_SCALE, SAMPLE_SCALE, pivot = Offset.Zero) {
                                    translate(-offset.x, -offset.y) { drawLayer(backdrop.layer) }
                                }
                            }
                        }
                        scale(1f / SAMPLE_SCALE, 1f / SAMPLE_SCALE, pivot = Offset.Zero) {
                            drawLayer(sampleLayer)
                        }
                    }
                }
                drawContent()
            }
        }
}

/**
 * Native desktop equivalent of the upstream blur + lens + highlight pipeline.
 * The filter retains the native references it needs; temporary builders and
 * the blur input are closed immediately. Cache the resulting RenderEffect in
 * the draw node, so scroll/playback do not recompile the shader.
 */
internal fun createHomeQueueGlassRenderEffect(
    width: Int,
    height: Int,
    pixelsPerDp: Float,
    fill: Color,
): RenderEffect? = runCatching {
    val program = RuntimeEffect.makeForShader(QUEUE_GLASS_SHADER) ?: return null
    program.use { runtime ->
        RuntimeShaderBuilder(runtime).use { builder ->
            builder.uniform("size", width.toFloat(), height.toFloat())
            builder.uniform("radius", 22f * pixelsPerDp)
            builder.uniform("bevel", 24f * pixelsPerDp)
            builder.uniform("bend", 18f * pixelsPerDp)
            builder.uniform("pixelScale", pixelsPerDp)
            builder.uniform("tint", fill.red, fill.green, fill.blue)
            val sigma = 7f * pixelsPerDp
            ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP).use { blur ->
                ImageFilter.makeRuntimeShader(
                    runtimeShaderBuilder = builder,
                    shaderNames = arrayOf("content"),
                    inputs = arrayOf<ImageFilter?>(blur),
                )?.asComposeRenderEffect()
            }
        }
    }
}.getOrNull()

/**
 * Rounded-rectangle signed distance and finite-difference normal. The lens
 * bends real backdrop pixels inward at the bevel; the center stays undistorted.
 * Lighting is broad and directional, not a white outline. This is intentionally
 * a single shader sample (no RGB dispersion) for a large desktop surface.
 */
private const val QUEUE_GLASS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float bend;
uniform float pixelScale;
uniform float3 tint;

float sdf(float2 p) {
    float r = min(radius, min(size.x, size.y) * 0.5);
    float2 q = abs(p) - size * 0.5 + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 p = coord - size * 0.5;
    float distance = max(0.0, -sdf(p));
    float2 gradient = float2(
        sdf(p + float2(0.5, 0.0)) - sdf(p - float2(0.5, 0.0)),
        sdf(p + float2(0.0, 0.5)) - sdf(p - float2(0.0, 0.5))
    );
    float2 normal = gradient / max(length(gradient), 0.001);
    float edge = 1.0 - smoothstep(0.0, max(bevel, 0.1), distance);
    float2 uv = clamp(coord - normal * bend * edge * edge,
                      float2(0.5), max(size - float2(0.5), float2(0.5)));
    half3 sampleColor = content.eval(uv).rgb;
    half luminance = dot(sampleColor, half3(0.2126, 0.7152, 0.0722));
    half3 vibrant = clamp(mix(half3(luminance), sampleColor, 1.22), 0.0, 1.0);

    // Bright art must not erase queue labels in dark mode, nor dark art in
    // light mode. Tint more only where it improves the foreground contrast.
    float lightMode = step(0.5, dot(tint, float3(0.2126, 0.7152, 0.0722)));
    float darkOpacity = mix(0.30, 0.68, smoothstep(0.18, 0.72, luminance));
    float lightOpacity = mix(0.72, 0.42, smoothstep(0.20, 0.80, luminance));
    half3 glass = mix(vibrant, half3(tint), mix(darkOpacity, lightOpacity, lightMode));

    float angle = dot(normal, normalize(float2(-0.65, -0.76)));
    float softness = max(pixelScale, 0.1);
    float glow = exp(-distance / (5.0 * softness));
    float specular = pow(max(angle, 0.0), 2.0) * glow;
    float shadow = pow(max(-angle, 0.0), 2.0) * glow;
    glass = mix(glass, half3(1.0), specular * mix(0.20, 0.46, lightMode));
    glass = mix(glass, half3(0.0), shadow * mix(0.12, 0.075, lightMode));
    return half4(clamp(glass, 0.0, 1.0), 1.0);
}
"""
