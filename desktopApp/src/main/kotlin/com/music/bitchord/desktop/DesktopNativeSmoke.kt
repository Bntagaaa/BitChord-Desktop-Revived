package com.music.bitchord.desktop

/** CI entry point run using the packaged runtime and packaged jars. Does not initialize the UI. */
internal object DesktopNativeSmoke {
    @JvmStatic fun main(args: Array<String>) {
        check(DesktopAnalysisRuntime.available) { "Native AutoMix analyzer did not load" }
        check(org.bytedeco.ffmpeg.global.avcodec.avcodec_version() > 0)
        ai.onnxruntime.OrtEnvironment.getEnvironment().use { environment ->
            check(environment.version.isNotBlank())
        }
        println("PASS: native analyzer, FFmpeg and ONNX JNI loaded")
    }
}
