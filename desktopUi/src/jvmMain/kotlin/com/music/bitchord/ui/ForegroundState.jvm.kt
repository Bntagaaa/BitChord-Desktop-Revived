package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
actual fun rememberIsForeground(): Boolean =
    LocalDesktopPresentationVisible.current && LocalWindowInfo.current.isWindowFocused
