import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Preserve the mobile Compose version and compile the new JVM host against
// the same renderer ABI on both supported desktop operating systems.
val cmp = "1.10.3"

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val androidEnabled = gradle.extra["bitchordAndroidEnabled"] as Boolean
if (androidEnabled) {
    apply(plugin = "com.android.library")
    apply(from = "android.gradle.kts")
}

kotlin {
    if (androidEnabled) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Both targets are JVMs, so the UI is written once against the JDK here
        // rather than in commonMain.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        if (androidEnabled) androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            api(project(":shared"))
            api("org.jetbrains.compose.runtime:runtime:$cmp")
            api("org.jetbrains.compose.foundation:foundation:$cmp")
            api("org.jetbrains.compose.animation:animation:$cmp")
            api("org.jetbrains.compose.ui:ui:$cmp")
            api("org.jetbrains.compose.material3:material3:1.7.3")
            api("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            api("org.jetbrains.compose.components:components-resources:$cmp")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
            api("io.coil-kt.coil3:coil-compose:3.0.4")
            api("dev.chrisbanes.haze:haze:1.3.1")
            api("dev.chrisbanes.haze:haze-materials:1.3.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        jvmMain.dependencies {
            api("org.jetbrains.compose.ui:ui-desktop:$cmp")
            api("dev.chrisbanes.haze:haze:1.7.2")
            api("dev.chrisbanes.haze:haze-materials:1.7.2")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
        }
        if (androidEnabled) {
            androidMain.dependencies {
                // The phone's own versions, so nothing here moves them.
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("androidx.palette:palette-ktx:1.0.0")
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.music.bitchord.sharedui.resources"
    generateResClass = always
}
