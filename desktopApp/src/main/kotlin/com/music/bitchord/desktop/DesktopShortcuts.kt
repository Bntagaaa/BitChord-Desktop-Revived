package com.music.bitchord.desktop

import androidx.compose.runtime.compositionLocalOf
import java.awt.event.KeyEvent

/** Lets the window know when any of its desktop search fields owns keyboard focus. */
internal val LocalDesktopShortcutTextFocus = compositionLocalOf<((Boolean) -> Unit)?> { null }

/** Checked when an editor receives text, including AWT's separate typed event after Ctrl+/. */
internal val LocalDesktopShortcutModalVisible = compositionLocalOf<(() -> Boolean)?> { null }

/** The sole list of desktop shortcuts, used by both the dispatcher and the help dialog. */
internal enum class DesktopShortcutCategory(val label: String) {
    GENERAL("General"),
    PLAYBACK("Playback"),
    NAVIGATION("Navigation"),
    LYRICS("Lyrics"),
}

internal enum class DesktopShortcut(
    val category: DesktopShortcutCategory,
    val label: String,
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val keyLabel: String,
) {
    QUICK_SEARCH(DesktopShortcutCategory.GENERAL, "Open Quick Search", KeyEvent.VK_K, ctrl = true, keyLabel = "K"),
    SHOW_SHORTCUTS(DesktopShortcutCategory.GENERAL, "Toggle Keyboard Shortcuts modal", KeyEvent.VK_SLASH, ctrl = true, keyLabel = "/"),
    PLAY_PAUSE(DesktopShortcutCategory.PLAYBACK, "Play / Pause", KeyEvent.VK_SPACE, keyLabel = "Space"),
    PREVIOUS(DesktopShortcutCategory.PLAYBACK, "Previous track", KeyEvent.VK_LEFT, ctrl = true, keyLabel = "←"),
    NEXT(DesktopShortcutCategory.PLAYBACK, "Next track", KeyEvent.VK_RIGHT, ctrl = true, keyLabel = "→"),
    SEEK_BACKWARD(DesktopShortcutCategory.PLAYBACK, "Seek backward", KeyEvent.VK_LEFT, shift = true, keyLabel = "←"),
    SEEK_FORWARD(DesktopShortcutCategory.PLAYBACK, "Seek forward", KeyEvent.VK_RIGHT, shift = true, keyLabel = "→"),
    VOLUME_UP(DesktopShortcutCategory.PLAYBACK, "Volume up", KeyEvent.VK_UP, alt = true, keyLabel = "↑"),
    VOLUME_DOWN(DesktopShortcutCategory.PLAYBACK, "Volume down", KeyEvent.VK_DOWN, alt = true, keyLabel = "↓"),
    SHUFFLE(DesktopShortcutCategory.PLAYBACK, "Shuffle", KeyEvent.VK_S, alt = true, keyLabel = "S"),
    REPEAT(DesktopShortcutCategory.PLAYBACK, "Repeat", KeyEvent.VK_R, alt = true, keyLabel = "R"),
    HOME(DesktopShortcutCategory.NAVIGATION, "Home", KeyEvent.VK_H, alt = true, shift = true, keyLabel = "H"),
    SEARCH_PAGE(DesktopShortcutCategory.NAVIGATION, "Search page", KeyEvent.VK_L, ctrl = true, shift = true, keyLabel = "L"),
    LYRICS(DesktopShortcutCategory.LYRICS, "Toggle lyrics", KeyEvent.VK_L, ctrl = true, alt = true, keyLabel = "L"),
    ;

    val keycaps: List<String>
        get() = buildList {
            if (ctrl) add(if (DesktopPlatform.isMac) "Cmd" else "Ctrl")
            if (alt) add(if (DesktopPlatform.isMac) "Option" else "Alt")
            if (shift) add("Shift")
            add(keyLabel)
        }

    companion object {
        fun matching(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): DesktopShortcut? =
            entries.firstOrNull { it.keyCode == keyCode && it.ctrl == ctrl && it.alt == alt && it.shift == shift }
    }
}

internal data class DesktopShortcutKey(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val pressed: Boolean = true,
    val secondaryModifier: Boolean = false,
)

/** Key-down dispatch, with one action per physical press even when the OS repeats key-downs. */
internal class DesktopShortcutDispatcher {
    private val heldKeys = mutableSetOf<Int>()

    /** A key-up can be lost when the native window itself loses focus. */
    fun reset() {
        heldKeys.clear()
    }

    /** Returns true only when this key-down was previously captured as a global shortcut. */
    fun release(keyCode: Int): Boolean = heldKeys.remove(keyCode)

    fun dispatch(
        key: DesktopShortcutKey,
        editableFocused: Boolean,
        shortcutsModalOpen: Boolean,
        anotherModalOpen: Boolean,
    ): DesktopShortcut? {
        if (!key.pressed) {
            release(key.keyCode)
            return null
        }
        if (key.secondaryModifier) return null
        val shortcut = DesktopShortcut.matching(key.keyCode, key.ctrl, key.alt, key.shift) ?: return null
        if (shortcutsModalOpen && shortcut != DesktopShortcut.SHOW_SHORTCUTS) return null
        if (anotherModalOpen) return null
        if (editableFocused && shortcut != DesktopShortcut.SHOW_SHORTCUTS &&
            shortcut != DesktopShortcut.QUICK_SEARCH
        ) return null
        if (!heldKeys.add(key.keyCode)) return null
        return shortcut
    }
}
