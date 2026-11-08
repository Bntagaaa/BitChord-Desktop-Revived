package com.music.bitchord.reworked

import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.playback.manualCurve
import com.music.bitchord.playback.toneCurve
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DspSettings(
    val equalizerEnabled: Boolean = false,
    val bandsDb: List<Float> = List(7) { 0f },
    val toneMode: Boolean = false,
    val toneX: Int = 0,
    val toneY: Int = 0,
    val focused: Boolean = false,
    val balance: Float = 0f,
    val spatial: Boolean = false,
    val loudness: Boolean = false,
) {
    val preset: EqualizerPreset get() = EqualizerPreset.matching(bandsDb)
}

/** New desktop persistence for the mobile EQ, tone, balance, widening and normalization controls. */
internal object MobileDspSettings {
    private val prefs get() = DesktopStore.preferences
    private val _state = MutableStateFlow(DspSettings(
        equalizerEnabled = prefs.getBoolean("dsp.eq_enabled", false),
        bandsDb = prefs.get("dsp.eq_bands", "").split(',').mapNotNull(String::toFloatOrNull).takeIf { it.size == 7 }
            ?.map { it.coerceIn(-12f, 12f) } ?: List(7) { 0f },
        toneMode = prefs.getBoolean("dsp.tone_mode", false),
        toneX = prefs.getInt("dsp.tone_x", 0).coerceIn(-5, 5),
        toneY = prefs.getInt("dsp.tone_y", 0).coerceIn(-5, 5),
        focused = prefs.getBoolean("dsp.focused", false),
        balance = prefs.getFloat("dsp.balance", 0f).coerceIn(-1f, 1f),
        spatial = prefs.getBoolean("dsp.spatial", false),
        loudness = prefs.getBoolean("dsp.loudness", false),
    ))
    val state = _state.asStateFlow()
    @Volatile var curve: EqCurve = calculate(_state.value)
        private set

    fun setEqualizerEnabled(value: Boolean) = edit { copy(equalizerEnabled = value) }
    fun setBands(value: List<Float>) = edit { copy(bandsDb = List(7) { value.getOrElse(it) { 0f }.coerceIn(-12f, 12f) }, toneMode = false) }
    fun setBand(index: Int, value: Float) {
        if (index !in 0..6) return
        edit { copy(bandsDb = bandsDb.toMutableList().apply { set(index, value.coerceIn(-12f, 12f)) }, toneMode = false) }
    }
    fun setPreset(value: EqualizerPreset) { if (value != EqualizerPreset.CUSTOM) setBands(value.bands) }
    fun setToneMode(value: Boolean) = edit { copy(toneMode = value) }
    fun setTone(x: Int, y: Int) = edit { copy(toneX = x.coerceIn(-5, 5), toneY = y.coerceIn(-5, 5), toneMode = true) }
    fun setFocused(value: Boolean) = edit { copy(focused = value) }
    fun setBalance(value: Float) = edit { copy(balance = value.coerceIn(-1f, 1f)) }
    fun setSpatial(value: Boolean) = edit { copy(spatial = value) }
    fun setLoudness(value: Boolean) = edit { copy(loudness = value) }

    @Synchronized private fun edit(change: DspSettings.() -> DspSettings) {
        val next = _state.value.change()
        curve = calculate(next)
        _state.value = next
        prefs.putBoolean("dsp.eq_enabled", next.equalizerEnabled)
        prefs.put("dsp.eq_bands", next.bandsDb.joinToString(","))
        prefs.putBoolean("dsp.tone_mode", next.toneMode)
        prefs.putInt("dsp.tone_x", next.toneX)
        prefs.putInt("dsp.tone_y", next.toneY)
        prefs.putBoolean("dsp.focused", next.focused)
        prefs.putFloat("dsp.balance", next.balance)
        prefs.putBoolean("dsp.spatial", next.spatial)
        prefs.putBoolean("dsp.loudness", next.loudness)
    }

    private fun calculate(settings: DspSettings) = if (settings.toneMode) toneCurve(settings.toneX, settings.toneY, settings.focused) else manualCurve(settings.bandsDb)
}
