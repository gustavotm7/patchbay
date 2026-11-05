import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ui — Compose Multiplatform shared UI and the `designsystem` package (tokens, components).
// Fonts live once in src/fonts/font/: they're desktop classpath resources here, and Android
// resources via app-android's res source set.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "dev.patchbay.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns.named("test") { executionTask.configure { useJUnitPlatform() } }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopMain by getting {
            resources.srcDir("src/fonts")
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":simulators"))
            }
        }
    }
}

// Screenshot tests: render every key screen x theme x form factor to PNG.
//   ./gradlew :ui:desktopTest     renders to build/screenshots-actual and compares with goldens (if present)
//   ./gradlew :ui:recordScreens   re-renders straight into docs/design/screens (the committed goldens)
val goldenDir = rootProject.layout.projectDirectory.dir("docs/design/screens").asFile
val actualDir = layout.buildDirectory.dir("screenshots-actual")
val recordScreens = tasks.register("recordScreens") {
    group = "verification"
    description = "Re-renders design screenshots into docs/design/screens."
    dependsOn("desktopTest")
}
tasks.named<Test>("desktopTest") {
    val recording = gradle.startParameter.taskNames.any { it.endsWith("recordScreens") }
    systemProperty("patchbay.screens.actual", actualDir.get().asFile.absolutePath)
    systemProperty("patchbay.screens.golden", goldenDir.absolutePath)
    systemProperty("patchbay.screens.record", recording.toString())
    systemProperty("java.awt.headless", "true")
    inputs.dir("src/fonts")
    outputs.dir(actualDir)
    if (recording) outputs.upToDateWhen { false }
}
