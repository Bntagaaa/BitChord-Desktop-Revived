import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val productName = "BitChordReworked"
val productVersion = providers.gradleProperty("bitchord.version").getOrElse("1.9.1-reworked.1")
// MSI compares only a numeric three-part version. Preserve the Reworked revision
// there so installing reworked.2 can upgrade reworked.1 with the same UpgradeCode.
// The public version, launcher property and update asset names remain unchanged.
val installerVersion = providers.gradleProperty("bitchord.packageVersion").orNull ?: run {
    val parts = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:-reworked\\.(\\d+))?").matchEntire(productVersion)
        ?: error("Set a numeric bitchord.packageVersion for a custom or preview product version.")
    val major = parts.groupValues[1].toLongOrNull() ?: error("Invalid product major version.")
    val minor = parts.groupValues[2].toLongOrNull() ?: error("Invalid product minor version.")
    val patch = parts.groupValues[3].toLongOrNull() ?: error("Invalid product patch version.")
    val revision = parts.groupValues[4].takeIf(String::isNotEmpty)?.let {
        it.toLongOrNull()?.takeIf { number -> number in 1..999 }
            ?: error("Reworked revisions must be 1..999, or set bitchord.packageVersion explicitly.")
    } ?: 0L
    require(major in 0..255 && minor in 0..255 && patch in 0..65 && patch * 1_000 + revision <= 65_535) {
        "The mapped native package version exceeds MSI limits; set bitchord.packageVersion explicitly."
    }
    "$major.$minor.${patch * 1_000 + revision}"
}
val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val linux = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
val nativePlatform = if (windows) "windows-x86_64" else "linux-x86_64"
require(windows || linux) { "The Reworked desktop target supports Windows and Linux." }
require(System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
    "The initial Reworked desktop distribution targets x86_64."
}
require(installerVersion.matches(Regex("\\d+\\.\\d+\\.\\d+"))) {
    "bitchord.packageVersion must be MAJOR.MINOR.PATCH."
}
val installerParts = installerVersion.split('.').map { it.toLongOrNull() ?: -1L }
require(installerParts[0] in 0..255 && installerParts[1] in 0..255 && installerParts[2] in 0..65_535) {
    "bitchord.packageVersion must satisfy MSI limits: major/minor 0..255 and patch 0..65535."
}

version = productVersion
kotlin.jvmToolchain(21)

dependencies {
    implementation(project(":shared"))
    implementation(project(":sharedUi"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("io.coil-kt.coil3:coil-svg:3.0.4")
    implementation("org.json:json:20240303")
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    // Keep the shared mobile UI's JVM renderer and its blur backend on one ABI.
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    implementation("dev.chrisbanes.haze:haze-materials:1.7.2")

    // New platform adapter: decode through FFmpeg, then send PCM to Java Sound.
    // Only the current target's native libraries are included in its package.
    implementation("org.bytedeco:javacpp:1.5.12")
    runtimeOnly("org.bytedeco:javacpp:1.5.12:$nativePlatform")
    implementation("org.bytedeco:ffmpeg:7.1.1-1.5.12")
    runtimeOnly("org.bytedeco:ffmpeg:7.1.1-1.5.12:$nativePlatform")
    implementation("org.bytedeco:javacv:1.5.12")

    // The new desktop authentication adapter hosts the mobile web sign-in
    // flows in a JVM WebView. Use only the target platform's JavaFX binaries.
    val javafxPlatform = if (windows) "win" else "linux"
    listOf("base", "graphics", "controls", "media", "web", "swing").forEach { module ->
        implementation("org.openjfx:javafx-$module:21.0.10:$javafxPlatform")
    }

    // The mobile shared module declares these compile-only; the JVM host must
    // supply their desktop variants for stream fallback and model inference.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
    implementation("io.github.dokar3:quickjs-kt-jvm:1.0.14")
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("net.java.dev.jna:jna:5.18.1")
    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    runtimeOnly("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}

tasks.test {
    // Integration fixtures must never read or modify a listener's installed profile.
    val profile = layout.buildDirectory.dir("test-profile")
    environment("XDG_DATA_HOME", profile.get().dir("data").asFile.absolutePath)
    environment("XDG_CACHE_HOME", profile.get().dir("cache").asFile.absolutePath)
    environment("XDG_STATE_HOME", profile.get().dir("state").asFile.absolutePath)
    environment("LOCALAPPDATA", profile.get().dir("windows").asFile.absolutePath)
    systemProperty("java.util.prefs.userRoot", profile.get().dir("preferences").asFile.absolutePath)
    systemProperty("bitchord.preferencesNode", "com.music.bitchord.reworked.tests.${projectDir.absolutePath.hashCode().toUInt().toString(16)}")
    systemProperty("bitchord.partyServer", "")
    doFirst { delete(profile) }
}

// The native analyzer is the original mobile C++/JNI source. Gradle only supplies
// a new host toolchain and embeds the built library and the exact mobile models.
val cmake = providers.gradleProperty("bitchord.cmake").getOrElse("cmake")
val nativeBuild = layout.buildDirectory.dir("native/$nativePlatform")
val desktopJava = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
val configureNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Configures the mobile analyzer and new desktop media adapter."
    inputs.file("src/main/cpp/CMakeLists.txt")
    outputs.file(nativeBuild.map { it.file("CMakeCache.txt") })
    environment("JAVA_HOME", desktopJava.get().metadata.installationPath.asFile.absolutePath)
    commandLine(buildList {
        addAll(listOf(cmake, "-S", file("src/main/cpp").absolutePath,
            "-B", nativeBuild.get().asFile.absolutePath, "-DCMAKE_BUILD_TYPE=Release"))
        if (windows) addAll(listOf("-A", "x64"))
    })
}
val buildNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the mobile analyzer for this Windows or Linux host."
    dependsOn(configureNative)
    inputs.files(fileTree(rootProject.file("native/analyzer")))
    inputs.files(fileTree(rootProject.file("native/loopmix")))
    inputs.files(fileTree(rootProject.file("native/third_party")))
    inputs.files(fileTree(rootProject.file("app/src/main/cpp/jni")) { include("analysis_jni.cpp", "mel_jni.cpp", "vocal_jni.cpp", "loopmix_jni.cpp") })
    inputs.files(fileTree("src/main/cpp") { include("*.cpp", "CMakeLists.txt") })
    outputs.file(nativeBuild.map { it.file("lib/${if (windows) "bitchord_analysis.dll" else "libbitchord_analysis.so"}") })
    outputs.file(nativeBuild.map { it.file("lib/${if (windows) "bitchord_loopmix.dll" else "libbitchord_loopmix.so"}") })
    if (windows) outputs.file(nativeBuild.map { it.file("lib/bitchord_platform.dll") })
    commandLine(cmake, "--build", nativeBuild.get().asFile.absolutePath, "--config", "Release", "--parallel", "2")
}
val preparePlatformResources by tasks.registering(Sync::class) {
    dependsOn(buildNative)
    from(nativeBuild.map { it.dir("lib") }) {
        include("*.so", "*.dll")
        into("native/$nativePlatform")
    }
    from(rootProject.file("app/src/main/assets")) {
        include("beat_this_final0_q8.onnx", "vocals_umxhq_int8.onnx")
        into("models")
    }
    into(layout.buildDirectory.dir("generated/platformResources"))
}
sourceSets.named("main") { resources.srcDir(preparePlatformResources) }

compose.resources {
    packageOfResClass = "com.music.bitchord.reworked.resources"
    publicResClass = true
    generateResClass = always
}

compose.desktop {
    application {
        mainClass = "com.music.bitchord.reworked.MainKt"
        jvmArgs("-Dbitchord.product=reworked", "-Dbitchord.version=$productVersion")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = productName
            packageVersion = installerVersion
            description = "BitChord Desktop Reworked"
            vendor = "BitChord contributors"
            copyright = "Copyright © BitChord contributors"
            licenseFile.set(rootProject.file("LICENSE"))
            includeAllModules = true

            linux {
                iconFile.set(rootProject.file("app/src/main/ic_launcher-playstore.png"))
                appCategory = "AudioVideo"
                menuGroup = "AudioVideo"
                debMaintainer = "125988289+Bntagaaa@users.noreply.github.com"
                rpmLicenseType = "GPL-3.0"
            }
            windows {
                iconFile.set(project.file("packaging/mobile-icon.ico"))
                upgradeUuid = "7624266f-9d89-42fd-bb49-c3daef0388dc"
                menuGroup = "BitChord Desktop Reworked"
                perUserInstall = true
                shortcut = true
                dirChooser = true
            }
        }
    }
}

// A portable package uses the same jpackage image, including its own Java
// runtime. There is no legacy launcher or dependency on an installed JDK.
val portableMarker by tasks.registering {
    val marker = layout.buildDirectory.file("generated/portable/reworked-portable.marker")
    outputs.file(marker)
    inputs.property("version", productVersion)
    doLast {
        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText("BitChord Desktop Reworked $productVersion\n")
        }
    }
}

tasks.register<Zip>("portableDistribution") {
    group = "distribution"
    description = "Packages a standalone desktop image with its Java runtime."
    dependsOn("createDistributable", portableMarker)
    from(layout.buildDirectory.dir("compose/binaries/main/app/$productName")) {
        into(productName)
    }
    from(layout.buildDirectory.file("generated/portable/reworked-portable.marker")) {
        into(if (windows) productName else "$productName/bin")
    }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("$productName-$productVersion-${if (windows) "windows-x64" else "linux-x86_64"}-portable.zip")
}
