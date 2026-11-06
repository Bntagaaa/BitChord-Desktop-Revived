package com.music.bitchord.desktop

import androidx.compose.ui.graphics.vector.ImageVector
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * One mapping for desktop's sidebar and compact navigation, using the exact vectors drawn by
 * BitChord mobile's bottom tabs. "New" is the desktop name for the mobile Explore destination.
 *
 * Keep legacy BitChordIcons.Search/Library for non-tab UI; these tab glyphs are intentionally
 * the filled/cupertino variants, not the older stroked icons.
 */
internal object DesktopPrimaryNavigationIcons {
    val Home: ImageVector get() = BitChordIcons.Home
    val New: ImageVector get() = BitChordIcons.TabExplore
    val Search: ImageVector get() = BitChordIcons.TabSearch
    val Library: ImageVector get() = BitChordIcons.TabLibrary
}
