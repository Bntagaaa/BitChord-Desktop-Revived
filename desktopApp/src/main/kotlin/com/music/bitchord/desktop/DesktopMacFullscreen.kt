package com.music.bitchord.desktop

import java.awt.EventQueue
import java.awt.Window
import java.lang.reflect.Proxy

/** Uses Apple's Spaces fullscreen API, leaving zoom and window restoration to AppKit. */
internal class DesktopMacFullscreen(private val window: Window) : AutoCloseable {
    private val utilities = Class.forName("com.apple.eawt.FullScreenUtilities")
    private val listenerType = Class.forName("com.apple.eawt.FullScreenListener")
    private val applicationType = Class.forName("com.apple.eawt.Application")
    private val application = applicationType.getMethod("getApplication").invoke(null)
    private var actual = false
    private var desired = false
    private var transitioning = false
    private var requested = false
    private val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "BitChord macOS fullscreen listener"
            else -> {
                when (method.name) {
                    "windowEnteringFullScreen", "windowExitingFullScreen" -> transitioning = true
                    "windowEnteredFullScreen", "windowExitedFullScreen" -> {
                        actual = method.name == "windowEnteredFullScreen"
                        transitioning = false
                        if (!requested || desired == actual) {
                            requested = false
                            desired = actual
                            DesktopWindowMode.adoptNativeFullscreen(actual)
                        }
                        reconcile()
                    }
                }
                null
            }
        }
    }

    init {
        utilities.getMethod("setWindowCanFullScreen", Window::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, window, true)
        utilities.getMethod("addFullScreenListenerTo", Window::class.java, listenerType).invoke(null, window, listener)
    }

    fun apply(fullscreen: Boolean) {
        check(EventQueue.isDispatchThread())
        desired = fullscreen
        requested = actual != desired
        reconcile()
    }

    private fun reconcile() {
        if (transitioning || desired == actual) return
        transitioning = true
        applicationType.getMethod("requestToggleFullScreen", Window::class.java).invoke(application, window)
    }

    override fun close() {
        utilities.getMethod("removeFullScreenListenerFrom", Window::class.java, listenerType).invoke(null, window, listener)
    }
}
