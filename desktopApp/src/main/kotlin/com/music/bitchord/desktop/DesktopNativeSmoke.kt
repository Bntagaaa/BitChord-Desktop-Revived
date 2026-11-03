package com.music.bitchord.desktop

/** CI entry point run using the packaged runtime and packaged jars. Does not initialize the UI. */
internal object DesktopNativeSmoke {
    @JvmStatic fun main(args: Array<String>) {
        check(DesktopAnalysisRuntime.available) { "Native AutoMix analyzer did not load" }
        check(org.bytedeco.ffmpeg.global.avcodec.avcodec_version() > 0)
        check(org.bytedeco.ffmpeg.global.avformat.avformat_version() > 0)
        check(org.bytedeco.ffmpeg.global.avutil.avutil_version() > 0)
        check(org.bytedeco.ffmpeg.global.swresample.swresample_version() > 0)
        if (DesktopPlatform.isMac) DesktopAnalysisRuntime.loadNative("bitchord_macos")
        ai.onnxruntime.OrtEnvironment.getEnvironment().use { environment ->
            check(environment.version.isNotBlank())
            for (asset in listOf("beat_this_final0_q8.onnx", "vocals_umxhq_int8.onnx")) {
                ai.onnxruntime.OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1)
                    options.setInterOpNumThreads(1)
                    environment.createSession(DesktopAnalysisRuntime.modelPath(asset), options).use { session ->
                        check(session.inputNames.isNotEmpty())
                        check(session.outputNames.isNotEmpty())
                    }
                }
            }
        }
        println("PASS: native analyzer, FFmpeg and ONNX JNI loaded")
    }
}
