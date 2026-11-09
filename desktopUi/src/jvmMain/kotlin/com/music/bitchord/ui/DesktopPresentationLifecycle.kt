package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

val LocalDesktopPresentationVisible = compositionLocalOf { true }

/** Window presentation lifetime; application services and playback live elsewhere. */
@Composable
fun ProvideDesktopPresentationLifecycle(visible: Boolean, content: @Composable () -> Unit) {
    val owner = remember { DesktopPresentationOwner() }
    val focused = LocalWindowInfo.current.isWindowFocused
    SideEffect { owner.update(visible, focused) }
    DisposableEffect(owner) { onDispose { owner.close() } }
    CompositionLocalProvider(
        LocalLifecycleOwner provides owner,
        LocalDesktopPresentationVisible provides visible,
        content = content,
    )
}

/** Mutated only by the composition's serialized SideEffect/disposal callbacks. */
internal class DesktopPresentationOwner : LifecycleOwner, AutoCloseable {
    private val registry = LifecycleRegistry.createUnsafe(this)
    init { registry.currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry
    fun update(visible: Boolean, focused: Boolean) {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = when {
            !visible -> Lifecycle.State.CREATED
            focused -> Lifecycle.State.RESUMED
            else -> Lifecycle.State.STARTED
        }
    }
    override fun close() { registry.currentState = Lifecycle.State.DESTROYED }
}
