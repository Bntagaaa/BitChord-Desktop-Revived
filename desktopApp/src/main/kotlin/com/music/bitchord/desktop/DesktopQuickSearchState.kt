package com.music.bitchord.desktop

import java.awt.event.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val QUICK_SEARCH_DEBOUNCE_MS = 300L
internal const val QUICK_SEARCH_RESULT_LIMIT = 5

/** A revision, not just a query: closing and reopening must invalidate an old response too. */
internal data class DesktopQuickSearchRequest(val revision: Long, val query: String)

internal data class DesktopQuickSearchSnapshot<T>(
    val query: String = "",
    val request: DesktopQuickSearchRequest? = null,
    val items: List<T> = emptyList(),
    val selectedIndex: Int = -1,
    val loading: Boolean = false,
    val error: String? = null,
    val scrollRevision: Long = 0,
)

/**
 * UI-thread-owned state for this overlay only. The old Search page owns its own query/results.
 * No network or playback lives here; the view calls the existing search service and the app
 * supplies its existing playSong action. Generic items keep the state machine independently testable.
 */
internal class DesktopQuickSearchState<T>(private val keyOf: (T) -> String) {
    private var revision = 0L
    private val mutable = MutableStateFlow(DesktopQuickSearchSnapshot<T>())
    val state: StateFlow<DesktopQuickSearchSnapshot<T>> = mutable.asStateFlow()

    fun reset() {
        revision++
        mutable.value = DesktopQuickSearchSnapshot()
    }

    fun edit(text: String) {
        if (text == mutable.value.query) return
        request(text)
    }

    fun retry() = request(mutable.value.query)

    private fun request(text: String) {
        val trimmed = text.trim()
        revision++
        // Old rows must stop being playable immediately, not after the debounce/network finishes.
        mutable.value = DesktopQuickSearchSnapshot(
            query = text,
            request = trimmed.takeIf { it.isNotEmpty() }?.let { DesktopQuickSearchRequest(revision, it) },
            loading = trimmed.isNotEmpty(),
            scrollRevision = mutable.value.scrollRevision + 1,
        )
    }

    fun complete(request: DesktopQuickSearchRequest, items: List<T>): Boolean {
        val old = mutable.value
        if (old.request != request) return false
        val unique = items.asSequence().filter { keyOf(it).isNotBlank() }
            .distinctBy(keyOf).take(QUICK_SEARCH_RESULT_LIMIT).toList()
        mutable.value = old.copy(
            items = unique,
            selectedIndex = 0, // Empty results select the persistent "Search all" footer.
            loading = false,
            error = null,
            scrollRevision = old.scrollRevision + 1,
        )
        return true
    }

    fun fail(request: DesktopQuickSearchRequest, message: String): Boolean {
        val old = mutable.value
        if (old.request != request) return false
        mutable.value = old.copy(items = emptyList(), selectedIndex = -1, loading = false, error = message)
        return true
    }

    fun playbackError(message: String) {
        mutable.value = mutable.value.copy(error = message)
    }

    fun move(delta: Int) {
        val old = mutable.value
        if (old.loading || old.query.isBlank()) return
        // The last keyboard target is the fixed "Search all" footer, after the media rows.
        val next = (old.selectedIndex + delta).coerceIn(0, old.items.size)
        if (next != old.selectedIndex) {
            mutable.value = old.copy(selectedIndex = next, scrollRevision = old.scrollRevision + 1)
        }
    }

    fun hover(index: Int) {
        val old = mutable.value
        if (!old.loading && index in old.items.indices) mutable.value = old.copy(selectedIndex = index)
    }

    fun hoverSearchAll() {
        val old = mutable.value
        if (old.query.isNotBlank()) mutable.value = old.copy(selectedIndex = old.items.size)
    }

    fun isSearchAllSelected(): Boolean = mutable.value.let {
        !it.loading && it.query.isNotBlank() && it.selectedIndex == it.items.size
    }

    fun selected(): T? = mutable.value.let { if (it.loading) null else it.items.getOrNull(it.selectedIndex) }

    fun canPlay(item: T): Boolean = mutable.value.let { snapshot ->
        !snapshot.loading && snapshot.items.any { keyOf(it) == keyOf(item) }
    }
}

internal enum class DesktopQuickSearchKeyAction {
    PASS, CONSUME, PREVIOUS, NEXT, PLAY, PLAY_KEEP_OPEN, DISMISS, FOCUS_QUERY,
}

/**
 * The root's modal key router. Both halves of a claimed press are consumed even if a mouse click
 * closes the overlay in between. Play/dismiss happen on release, so Escape cannot also close the
 * player behind us and Enter cannot activate a newly focused control after the overlay disappears.
 */
internal class DesktopQuickSearchKeys {
    private val releases = mutableMapOf<Int, DesktopQuickSearchKeyAction>()

    fun reset() = releases.clear()

    fun handle(
        keyCode: Int,
        pressed: Boolean,
        active: Boolean,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        globalShortcut: Boolean = false,
    ): DesktopQuickSearchKeyAction {
        if (!pressed) {
            val action = releases.remove(keyCode) ?: return DesktopQuickSearchKeyAction.PASS
            return if (active) action else DesktopQuickSearchKeyAction.CONSUME
        }
        if (!active) return DesktopQuickSearchKeyAction.PASS
        val plain = !ctrl && !alt && !shift && !meta
        if (plain && (keyCode == KeyEvent.VK_UP || keyCode == KeyEvent.VK_DOWN)) {
            releases[keyCode] = DesktopQuickSearchKeyAction.CONSUME
            return if (keyCode == KeyEvent.VK_UP) DesktopQuickSearchKeyAction.PREVIOUS else DesktopQuickSearchKeyAction.NEXT
        }
        // Keep the original intent when the OS repeats KeyDown or modifiers change mid-press.
        if (keyCode in releases) return DesktopQuickSearchKeyAction.CONSUME
        return when {
            keyCode == KeyEvent.VK_ESCAPE -> {
                releases[keyCode] = DesktopQuickSearchKeyAction.DISMISS
                DesktopQuickSearchKeyAction.CONSUME
            }
            keyCode == KeyEvent.VK_ENTER -> {
                releases[keyCode] = when {
                    ctrl || alt || meta -> DesktopQuickSearchKeyAction.CONSUME
                    shift -> DesktopQuickSearchKeyAction.PLAY_KEEP_OPEN
                    else -> DesktopQuickSearchKeyAction.PLAY
                }
                DesktopQuickSearchKeyAction.CONSUME
            }
            keyCode == KeyEvent.VK_TAB || (keyCode == KeyEvent.VK_K && ctrl && !alt && !shift && !meta) -> {
                releases[keyCode] = DesktopQuickSearchKeyAction.CONSUME
                DesktopQuickSearchKeyAction.FOCUS_QUERY
            }
            // Text editing keeps Space, selection arrows and word-navigation arrows. Other app
            // shortcuts stay blocked until this overlay closes (including Ctrl+/).
            globalShortcut && keyCode != KeyEvent.VK_SPACE && keyCode != KeyEvent.VK_LEFT && keyCode != KeyEvent.VK_RIGHT -> {
                releases[keyCode] = DesktopQuickSearchKeyAction.CONSUME
                DesktopQuickSearchKeyAction.CONSUME
            }
            else -> DesktopQuickSearchKeyAction.PASS
        }
    }
}
