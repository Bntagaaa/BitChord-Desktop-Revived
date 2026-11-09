package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** Desktop can allow lyric animation while unfocused, independently of visibility. */
val LocalLyricAnimationAllowed = compositionLocalOf<Boolean?> { null }

/** Platform visibility/focus policy; playback never depends on this UI-only signal. */
@Composable
expect fun rememberIsForeground(): Boolean
