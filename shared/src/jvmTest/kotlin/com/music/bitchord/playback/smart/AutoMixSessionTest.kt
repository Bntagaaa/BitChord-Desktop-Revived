package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutoMixSessionTest {
    @Test
    fun fullOptimizationIsUsedWhenSupported() {
        assertEquals(OptLevel.ALL_OPT, withAutoMixOptimization("Beat") { it })
    }

    @Test
    fun missingFusedOperatorRetriesAtBasicOptimization() {
        val attempts = mutableListOf<OptLevel>()
        val selected = withAutoMixOptimization("Vocal") {
            attempts += it
            if (it == OptLevel.ALL_OPT) throw OrtException("Missing fused operator")
            it
        }
        assertEquals(listOf(OptLevel.ALL_OPT, OptLevel.BASIC_OPT), attempts)
        assertEquals(OptLevel.BASIC_OPT, selected)
    }

    @Test
    fun bothFailuresArePreservedForDiagnostics() {
        val error = assertFailsWith<OrtException> {
            withAutoMixOptimization("Beat") { throw OrtException(it.name) }
        }
        assertEquals(true, error.message.orEmpty().contains("BASIC_OPT"))
        assertEquals(true, error.suppressed.single().message.orEmpty().contains("ALL_OPT"))
    }
}
