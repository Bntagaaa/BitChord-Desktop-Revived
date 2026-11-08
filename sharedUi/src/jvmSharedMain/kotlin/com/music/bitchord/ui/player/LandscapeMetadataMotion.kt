package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics

internal data class MetadataPose(val y: Float = 0f, val alpha: Float = 1f) {
    fun towards(other: MetadataPose, fraction: Float) = MetadataPose(
        y + (other.y - y) * fraction,
        alpha + (other.alpha - alpha) * fraction,
    )
}

internal class MetadataFrame<T>(
    val incoming: T,
    val outgoing: T? = null,
    val incomingStart: MetadataPose = MetadataPose(),
    val outgoingStart: MetadataPose = MetadataPose(),
    val progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D> = Animatable(1f),
) {
    fun incomingPose() = incomingStart.towards(MetadataPose(), progress.value)
    fun outgoingPose() = outgoingStart.towards(MetadataPose(-1f, 0f), progress.value)
    fun refreshed(value: T) = MetadataFrame(value, outgoing, incomingStart, outgoingStart, progress)
}

/** At most two snapshots and one clock, including when a skip interrupts another skip. */
internal class LandscapeMetadataState<T>(initial: T, private val identity: (T) -> String) {
    var frame by mutableStateOf(MetadataFrame(initial))
        private set

    fun refresh(value: T) {
        if (identity(frame.incoming) == identity(value) && frame.incoming != value) {
            frame = frame.refreshed(value)
        }
    }

    fun begin(value: T): MetadataFrame<T>? {
        if (identity(frame.incoming) == identity(value)) {
            refresh(value)
            return null
        }
        val incomingPose = frame.incomingPose()
        val outgoingPose = frame.outgoingPose()
        val useOutgoing = frame.outgoing != null && outgoingPose.alpha > incomingPose.alpha
        val visible = if (useOutgoing) requireNotNull(frame.outgoing) else frame.incoming
        val pose = if (useOutgoing) outgoingPose else incomingPose
        // Returning to the currently visible track continues from its actual position.
        // Otherwise replace the less visible layer; an older third layer is never resurrected.
        frame = if (identity(visible) == identity(value)) {
            MetadataFrame(value, incomingStart = pose, progress = Animatable(0f))
        } else {
            MetadataFrame(value, visible, MetadataPose(1f, 0f), pose, Animatable(0f))
        }
        return frame
    }

    fun finish(finished: MetadataFrame<T>) {
        if (frame.progress === finished.progress) frame = MetadataFrame(frame.incoming)
    }
}

/** The caller's action buttons and the lyric list deliberately live outside this motion. */
@Composable
internal fun <T> LandscapeMetadataMotion(
    value: T,
    identity: (T) -> String,
    animate: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (value: T, moving: Boolean, interactive: Boolean) -> Unit,
) {
    if (!animate) {
        Box(modifier) { content(value, false, true) }
        return
    }
    val state = remember { LandscapeMetadataState(value, identity) }
    SideEffect { state.refresh(value) }
    LaunchedEffect(identity(value)) {
        val frame = state.begin(value) ?: return@LaunchedEffect
        frame.progress.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
        state.finish(frame)
    }
    val frame = state.frame
    val moving by remember(frame.progress) { derivedStateOf { frame.progress.value < 1f } }
    val layers = listOfNotNull(frame.outgoing, frame.incoming)
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            layers.forEachIndexed { index, item ->
                key(identity(item)) {
                    val incoming = index == layers.lastIndex
                    Box(
                        Modifier
                            .then(if (incoming) Modifier else Modifier.clearAndSetSemantics {})
                            .drawWithContent {
                                // On an interrupted slide the outgoing layer can start partway
                                // down the slot. Keep it above the incoming edge, never overlapping.
                                if (incoming) drawContent() else clipRect(
                                    bottom = (frame.incomingPose().y * size.height).coerceIn(0f, size.height),
                                ) { this@drawWithContent.drawContent() }
                            }
                            .graphicsLayer {
                                val pose = if (incoming) frame.incomingPose() else frame.outgoingPose()
                                translationY = pose.y * size.height
                                alpha = pose.alpha
                            },
                    ) {
                        content(item, moving, incoming && identity(item) == identity(value))
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // Only the current metadata determines the slot's height. No AnimatedContent size
        // transform, old-track height, or onGloballyPositioned state can move the lyric list.
        val incoming = measurables.last().measure(constraints)
        val outgoing = measurables.dropLast(1).map { it.measure(constraints) }
        layout(incoming.width, incoming.height) {
            outgoing.forEach { it.placeRelative(0, 0) }
            incoming.placeRelative(0, 0)
        }
    }
}
