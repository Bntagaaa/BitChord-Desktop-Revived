package com.music.bitchord.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import com.music.bitchord.ui.components.SoftSurfaceRole
import com.music.bitchord.ui.components.softElevatedSurface

internal fun Modifier.desktopPopupSurface(shape: Shape, fill: Color = DesktopCardFill): Modifier =
    softElevatedSurface(shape, fill, DesktopTheme.isLight, SoftSurfaceRole.POPUP)

internal fun Modifier.desktopFilledButtonSurface(shape: Shape, fill: Color, enabled: Boolean = true): Modifier =
    softElevatedSurface(shape, fill, fill.luminance() > 0.5f, SoftSurfaceRole.BUTTON, enabled)

internal fun Modifier.desktopQuietButtonSurface(shape: Shape, enabled: Boolean = true): Modifier =
    softElevatedSurface(shape, Color.Transparent, DesktopTheme.isLight, SoftSurfaceRole.QUIET_BUTTON, enabled)
