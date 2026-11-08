// Loaded only when Android targets are enabled. Keeping Android API types in
// this script lets a desktop-only build avoid resolving AGP altogether.
extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "com.music.bitchord.shared"
    compileSdk = 36
}
