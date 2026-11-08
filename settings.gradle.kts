pluginManagement {
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // NewPipeExtractor is published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "BitChord"
include(":shared")
include(":sharedUi")
include(":desktopApp")

// The Android module only when there is an SDK to build it against. A machine
// set up for the desktop alone has none, and including it there fails at
// configuration time — before the desktop module it has nothing to do with can
// build at all.
// Read as a Properties file, not line by line: Android Studio writes the path
// escaped (`C\:\Users\...` on Windows), and only Properties undoes that.
val androidSdk: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) } }
    ?.getProperty("sdk.dir")
    ?.trim()
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")

// Desktop CI runners may happen to have an Android SDK installed. Keep their
// graph independent of Android signing, Google services and SDK downloads with
// -Pbitchord.android=false. Developers can still build the upstream Android
// applications by enabling this flag or by leaving SDK auto-detection in place.
val androidEnabled = providers.gradleProperty("bitchord.android").orNull?.let { value ->
    value.toBooleanStrictOrNull()
        ?: error("bitchord.android must be true or false")
} ?: (androidSdk != null && file(androidSdk).isDirectory)

if (androidEnabled && (androidSdk == null || !file(androidSdk).isDirectory)) {
    error("Android targets require a valid SDK. Set ANDROID_HOME or sdk.dir, or use -Pbitchord.android=false.")
}
gradle.extra["bitchordAndroidEnabled"] = androidEnabled

if (androidEnabled) {
    include(":app")
    include(":tv")
    include(":wearos")
} else {
    logger.lifecycle("Android targets disabled — building the desktop targets only.")
}
