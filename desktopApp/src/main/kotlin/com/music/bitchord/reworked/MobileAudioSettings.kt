package com.music.bitchord.reworked

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.EqualizerPreset
import kotlinx.coroutines.delay

@Composable
internal fun MobileAudioSettings(session: MobileSession) {
    val dsp by MobileDspSettings.state.collectAsState()
    val playback by session.player.state.collectAsState()
    SettingsSection("Equalizer and sound") {
        ReworkedToggle("Equalizer", dsp.equalizerEnabled, MobileDspSettings::setEqualizerEnabled)
        var presetsOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { presetsOpen = true }, enabled = dsp.equalizerEnabled) { Text(dsp.preset.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)) }
            DropdownMenu(presetsOpen, onDismissRequest = { presetsOpen = false }) {
                EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM }.forEach { preset ->
                    DropdownMenuItem(text = { Text(preset.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)) }, onClick = { MobileDspSettings.setPreset(preset); presetsOpen = false })
                }
            }
        }
        ReworkedToggle("Tone controls", dsp.toneMode, MobileDspSettings::setToneMode)
        if (dsp.toneMode) {
            Text("Warm ← Tone → Bright · ${dsp.toneX}")
            Slider(dsp.toneX.toFloat(), { MobileDspSettings.setTone(it.toInt(), dsp.toneY) }, valueRange = -5f..5f, steps = 9, enabled = dsp.equalizerEnabled)
            Text("Scooped ← Mids → Forward · ${dsp.toneY}")
            Slider(dsp.toneY.toFloat(), { MobileDspSettings.setTone(dsp.toneX, it.toInt()) }, valueRange = -5f..5f, steps = 9, enabled = dsp.equalizerEnabled)
            ReworkedToggle("Focused", dsp.focused, MobileDspSettings::setFocused)
        } else {
            listOf("60 Hz", "150 Hz", "400 Hz", "1 kHz", "2.5 kHz", "6 kHz", "14 kHz").forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                    Slider(dsp.bandsDb[index], { MobileDspSettings.setBand(index, it) }, valueRange = -12f..12f, modifier = Modifier.weight(1f), enabled = dsp.equalizerEnabled)
                    Text("${"%+.1f".format(dsp.bandsDb[index])} dB", Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Left / right balance")
        Slider(dsp.balance, MobileDspSettings::setBalance, valueRange = -1f..1f)
        TextButton(onClick = { MobileDspSettings.setBalance(0f) }) { Text("Center balance") }
        ReworkedToggle("Spatial widening", dsp.spatial, MobileDspSettings::setSpatial)
        ReworkedToggle("Loudness normalization", dsp.loudness, MobileDspSettings::setLoudness)
    }
    SettingsSection("AutoMix transition length") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Adaptive", 8 to "8 bars", 16 to "16 bars").forEach { (bars, label) ->
                FilterChip(playback.mixBars == bars, onClick = { session.player.setMixBars(bars); DesktopStore.preferences.putInt("mix_bars", bars) }, label = { Text(label) })
            }
        }
        session.analyzer?.let { analyzer ->
            val status by analyzer.status.collectAsState()
            val unavailable by analyzer.unavailable.collectAsState()
            Text(unavailable ?: status[playback.song?.videoId] ?: if (playback.automix) "Analysis will run for the current and next audio track." else "Enable AutoMix to analyze tracks.", style = MaterialTheme.typography.bodySmall)
        }
    }
    SettingsSection("Sleep timer") {
        var remaining by remember { mutableStateOf(session.player.sleepRemainingMs()) }
        LaunchedEffect(playback.sleepDeadlineNanos) {
            do { remaining = session.player.sleepRemainingMs(); if (remaining != null && remaining!! > 0) delay(1000) } while (remaining != null && remaining!! > 0)
        }
        Text(if (playback.sleepAfterTrack) "Playback will stop after this track." else remaining?.let { "${(it / 1000 + 59) / 60} minutes remaining" } ?: "Off")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60, 90).forEach { minutes -> OutlinedButton(onClick = { session.player.setSleepMinutes(minutes) }) { Text("$minutes min") } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = session.player::startSleepAfterTrack, enabled = playback.song != null) { Text("After this track") }
            TextButton(onClick = session.player::cancelSleep) { Text("Cancel timer") }
        }
    }
}
