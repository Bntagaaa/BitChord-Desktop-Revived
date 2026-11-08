// Loaded only when Android targets are enabled; the desktop build has no AGP
// dependency and keeps using the original JVM shared UI source sets.
extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "com.music.bitchord.sharedui"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
}
