package com.music.bitchord.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Temporary visual debug settings, to remove after choosing final menu material. */
internal object DesktopMenuTuning {
    private val store = DesktopPersistence()
    private fun saved(key: String, fallback: Float, lo: Float, hi: Float): Float =
        store.string(key, "").toFloatOrNull()?.coerceIn(lo, hi) ?: fallback
    private val _tint = MutableStateFlow(saved("debug_menu_tint", 0.55f, 0.08f, 0.95f))
    private val _blur = MutableStateFlow(saved("debug_menu_blur", 30f, 0f, 60f))
    val tintAlpha: StateFlow<Float> = _tint
    val blurRadiusDp: StateFlow<Float> = _blur
    // Apply immediately while dragging but commit to disk only on release.
    // Persisting each mouse-motion event stutters fractional-scale UI.
    fun setTint(value: Float) { _tint.value = value.coerceIn(0.08f, 0.95f) }
    fun setBlur(value: Float) { _blur.value = value.coerceIn(0f, 60f) }
    fun saveTint() { store.saveString("debug_menu_tint", _tint.value.toString()) }
    fun saveBlur() { store.saveString("debug_menu_blur", _blur.value.toString()) }

}

@Composable
internal fun DesktopMenuDebugSettings() {
    val tint by DesktopMenuTuning.tintAlpha.collectAsState()
    val blur by DesktopMenuTuning.blurRadiusDp.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Context Menu Glass · Temporary debug", fontWeight = FontWeight.SemiBold)
        Text("Applies to menus, profile and lyrics provider picker. Remove after visual calibration.",
            style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Surface opacity")
            Text("${(tint * 100).toInt()}%")
        }
        DesktopBareSlider(
            tint, DesktopMenuTuning::setTint,
            valueRange = 0.08f..0.95f,
            onValueChangeFinished = DesktopMenuTuning::saveTint,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Blur strength")
            Text("${blur.toInt()} dp")
        }
        DesktopBareSlider(
            blur, DesktopMenuTuning::setBlur,
            valueRange = 0f..60f,
            onValueChangeFinished = DesktopMenuTuning::saveBlur,
        )
        Text("Reduce Dynamic Blur overrides the blur slider.", color = DesktopSecondary, style = MaterialTheme.typography.labelSmall)
    }
}
