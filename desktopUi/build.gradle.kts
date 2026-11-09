import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Preserve the established desktop ABI combinations; Android's UI module has
// its own versions and no longer selects a desktop renderer for this module.
val desktopCompose = if (System.getProperty("os.name").contains("Windows", true)) "1.10.3" else "1.12.0"
val sharedUiSources = rootProject.layout.projectDirectory.dir("sharedUi/src")

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    applyDefaultHierarchyTemplate()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    sourceSets {
        // Retain one copy of proven presentation algorithms and layouts. JVM
        // actuals, desktop lifecycle policy and tests are owned here.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir(sharedUiSources.dir("jvmSharedMain/kotlin"))
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        commonMain.dependencies {
            api(project(":shared"))
            api("org.jetbrains.compose.runtime:runtime:1.10.3")
            api("org.jetbrains.compose.foundation:foundation:1.10.3")
            api("org.jetbrains.compose.animation:animation:1.10.3")
            api("org.jetbrains.compose.ui:ui:1.10.3")
            api("org.jetbrains.compose.material3:material3:1.7.3")
            api("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            api("org.jetbrains.compose.components:components-resources:1.10.3")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
            api("io.coil-kt.coil3:coil-compose:3.0.4")
            api("dev.chrisbanes.haze:haze:1.3.1")
            api("dev.chrisbanes.haze:haze-materials:1.3.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        jvmMain.dependencies { api("org.jetbrains.compose.ui:ui-desktop:$desktopCompose") }
        jvmTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.music.bitchord.sharedui.resources"
    generateResClass = always
    customDirectory(sourceSetName = "commonMain", directoryProvider = provider {
        sharedUiSources.dir("commonMain/composeResources")
    })
}
