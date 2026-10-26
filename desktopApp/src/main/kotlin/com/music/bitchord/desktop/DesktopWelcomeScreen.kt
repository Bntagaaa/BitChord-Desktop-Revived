package com.music.bitchord.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.bitchord_logo_original
import bitchord.desktopapp.generated.resources.logo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * First-run intro: one continuous logo move, then title, subtitle and staggered actions.
 * All surfaces and buttons reuse the desktop's existing Compose components and theme.
 */
@Composable
internal fun DesktopWelcomeScreen(
    youtubeConnected: Boolean,
    spotifyConnected: Boolean,
    busy: String?,
    error: String?,
    onYouTube: () -> Unit,
    onSpotify: () -> Unit,
    onFinish: () -> Unit,
) {
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val logoMotion = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val titleMotion = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val subtitleMotion = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val youtubeMotion = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val spotifyMotion = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val finishMotion = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val ease = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            listOf(logoMotion, titleMotion, subtitleMotion, youtubeMotion, spotifyMotion, finishMotion)
                .forEach { it.snapTo(1f) }
        } else {
            // The BitChord mark stands alone first, then shrinks and rises in *one* motion.
            delay(1200)
            launch { logoMotion.animateTo(1f, tween(1550, easing = ease)) }
            delay(1220)
            launch { titleMotion.animateTo(1f, tween(740, easing = ease)) }
            delay(170)
            launch { subtitleMotion.animateTo(1f, tween(740, easing = ease)) }
            delay(160)
            launch { youtubeMotion.animateTo(1f, tween(740, easing = ease)) }
            delay(130)
            launch { spotifyMotion.animateTo(1f, tween(740, easing = ease)) }
            delay(130)
            launch { finishMotion.animateTo(1f, tween(740, easing = ease)) }
        }
    }

    // Use existing modal chrome. The intro can only be dismissed via its action, never by
    // clicking outside or pressing Escape, so a new listener doesn't accidentally miss it.
    DesktopDialogPanel(onDismiss = {}, maxWidth = 460) {
        Box(Modifier.fillMaxWidth().heightIn(min = 445.dp), contentAlignment = Alignment.TopCenter) {
            Image(
                // The new dark app icon keeps the white mark visible on light surfaces.
                // Dark mode keeps the original white waveform used by the approved prototype.
                painter = painterResource(
                    if (DesktopTheme.isLight) Res.drawable.logo else Res.drawable.bitchord_logo_original
                ),
                contentDescription = "BitChord",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 22.dp)
                    .size(width = 130.dp, height = 95.dp)
                    .graphicsLayer {
                        val progress = logoMotion.value
                        val scale = 1f - 0.57f * progress
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - progress) * 155.dp.toPx()
                    },
            )
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 26.dp, end = 26.dp, top = 113.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Welcome to BitChord",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DesktopForeground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .alpha(titleMotion.value)
                        .graphicsLayer { translationY = (1f - titleMotion.value) * 17.dp.toPx() },
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "Your music, your way. Let's get you connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DesktopSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .alpha(subtitleMotion.value)
                        .graphicsLayer { translationY = (1f - subtitleMotion.value) * 14.dp.toPx() },
                )
                Spacer(Modifier.height(33.dp))
                WelcomeAction(
                    text = when {
                        youtubeConnected -> "✓  YouTube Music Connected"
                        busy == "youtube" -> "Connecting YouTube Music…"
                        else -> "Connect YouTube Music"
                    },
                    connected = youtubeConnected,
                    enabled = busy == null && !youtubeConnected && youtubeMotion.value >= 0.99f,
                    progress = youtubeMotion.value,
                    onClick = onYouTube,
                )
                Spacer(Modifier.height(12.dp))
                WelcomeAction(
                    text = when {
                        spotifyConnected -> "✓  Spotify Connected"
                        busy == "spotify" -> "Connecting Spotify…"
                        else -> "Connect Spotify"
                    },
                    connected = spotifyConnected,
                    enabled = busy == null && !spotifyConnected && spotifyMotion.value >= 0.99f,
                    progress = spotifyMotion.value,
                    onClick = onSpotify,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .alpha(finishMotion.value)
                        .graphicsLayer { translationY = (1f - finishMotion.value) * 16.dp.toPx() },
                ) {
                    OutlinedButton(
                        onClick = onFinish,
                        enabled = busy == null && finishMotion.value >= 0.99f,
                        modifier = Modifier.fillMaxWidth().height(49.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (youtubeConnected || spotifyConnected) "Start Listening" else "Skip For Now")
                    }
                }
                Spacer(Modifier.height(13.dp))
                when {
                    busy != null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = DesktopForeground,
                        )
                        Text(
                            "Finish signing in in the browser window",
                            color = DesktopSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    error != null -> Text(
                        error,
                        color = DesktopDestructive,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    else -> Text(
                        "You can connect both accounts later in Settings.",
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(finishMotion.value),
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeAction(
    text: String,
    connected: Boolean,
    enabled: Boolean,
    progress: Float,
    onClick: () -> Unit,
) {
    val light = DesktopTheme.isLight
    val connectedBackground = if (light) Color(0xFFE3F3E8) else Color(0xFF254A37)
    val connectedForeground = if (light) Color(0xFF1D653E) else Color(0xFFB8F4CF)
    Box(
        Modifier.fillMaxWidth()
            .alpha(progress)
            .graphicsLayer { translationY = (1f - progress) * 20.dp.toPx() },
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(49.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (connected) connectedBackground else DesktopAccent,
                contentColor = if (connected) connectedForeground else DesktopOnAccent,
                disabledContainerColor = if (connected) connectedBackground else DesktopTheme.palette.surfaceRaised,
                disabledContentColor = if (connected) connectedForeground else DesktopSecondary,
            ),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
