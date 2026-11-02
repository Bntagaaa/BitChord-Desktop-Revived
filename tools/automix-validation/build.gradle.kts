plugins { kotlin("jvm") version "2.3.20" }
repositories { mavenCentral() }
dependencies { testImplementation(kotlin("test-junit")) }
kotlin { jvmToolchain(21) }
val repo = rootDir.parentFile.parentFile.absolutePath
sourceSets {
    main {
        kotlin.srcDirs("$repo/shared/src/commonMain/kotlin", "$repo/shared/src/jvmSharedMain/kotlin", "$repo/desktopApp/src/main/kotlin")
        kotlin.include("com/music/bitchord/playback/smart/*.kt", "com/music/bitchord/playback/TransitionFilter.kt",
            "com/music/bitchord/desktop/DesktopAudioSpeed.kt", "com/music/bitchord/desktop/DesktopTempoBuffer.kt",
            "com/music/bitchord/desktop/DesktopTransitionRide.kt", "com/music/bitchord/desktop/DesktopTransitionEcho.kt",
            "com/music/bitchord/desktop/DesktopPlayhead.kt", "com/music/bitchord/desktop/DesktopPcmWrite.kt")
        kotlin.exclude("**/AnalysisStore.kt", "**/TrackFeatures.kt", "**/BeatTracker.kt", "**/MelSpectrogram.kt", "**/NativeAnalysisLibrary.kt", "**/VocalTracker.kt")
    }
    test {
        kotlin.srcDirs("$repo/shared/src/commonTest/kotlin", "$repo/desktopApp/src/test/kotlin")
        kotlin.include("com/music/bitchord/playback/smart/*.kt", "**/DesktopTempoBufferTest.kt", "**/DesktopTransitionFilterTest.kt", "**/DesktopTransitionEchoTest.kt", "**/DesktopPlayheadTest.kt", "**/DesktopBeatAwarePcmTest.kt", "**/DesktopPcmWriteTest.kt")
    }
}
tasks.test { testLogging { events("passed", "skipped", "failed") } }
