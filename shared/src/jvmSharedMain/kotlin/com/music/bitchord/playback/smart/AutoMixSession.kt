package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import java.io.File

internal fun openAutoMixSession(label: String, path: String, threads: Int): OrtSession {
    val model = File(path).absolutePath
    return withAutoMixOptimization(label) { level ->
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            options.setOptimizationLevel(level)
            // Keep the application from retaining each model's largest scratch allocation.
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            OrtEnvironment.getEnvironment().createSession(model, options)
        }
    }
}

internal fun <T> withAutoMixOptimization(label: String, open: (OptLevel) -> T): T =
    try {
        open(OptLevel.ALL_OPT)
    } catch (failure: OrtException) {
        // Full optimization can fuse operators that are absent in a reduced ORT build.
        AnalysisLog.warn("$label model failed at ALL_OPT; retrying at BASIC_OPT", failure)
        try {
            open(OptLevel.BASIC_OPT)
        } catch (retry: OrtException) {
            retry.addSuppressed(failure)
            throw retry
        }
    }
