package com.music.bitchord.desktop

import com.music.bitchord.ui.icons.BitChordIcons
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DesktopPrimaryNavigationIconsTest {
    @Test
    fun `all primary desktop destinations share exact mobile tab vectors`() {
        assertSame(BitChordIcons.Home, DesktopPrimaryNavigationIcons.Home)
        assertSame(BitChordIcons.TabExplore, DesktopPrimaryNavigationIcons.New)
        assertSame(BitChordIcons.TabSearch, DesktopPrimaryNavigationIcons.Search)
        assertSame(BitChordIcons.TabLibrary, DesktopPrimaryNavigationIcons.Library)
    }

    @Test
    fun `search and library do not regress to the older stroked icons`() {
        assertNotSame(BitChordIcons.Search, DesktopPrimaryNavigationIcons.Search)
        assertNotSame(BitChordIcons.Library, DesktopPrimaryNavigationIcons.Library)
    }
}
