import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// app-windows — Compose Desktop shell, packaged as a per-user MSI with no auto-updater.
// Phase 0: demo-home UI. Tray, global hotkey, command bar and Windows Hello come in Phases 1–2.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":simulators"))
    // Always the Windows x64 runtime, so a Linux CI run can verify every checksum the MSI will contain.
    // For a local preview on another OS: -Ppatchbay.desktopCurrentOs=true :app-windows:run
    if (providers.gradleProperty("patchbay.desktopCurrentOs").orNull == "true") {
        implementation(compose.desktop.currentOs)
    } else {
        implementation(compose.desktop.windows_x64)
    }
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "dev.patchbay.windows.MainKt"
        jvmArgs += listOf("-Dpatchbay.platform=windows", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Patchbay"
            // MSI needs a purely numeric MAJOR.MINOR.BUILD.
            packageVersion = "0.1.0"
            description = "Local-only home control"
            vendor = "Patchbay (personal build)"
            // Minimal jlink runtime. Add modules deliberately (they are part of the attack surface).
            modules("java.sql", "jdk.unsupported")

            windows {
                perUserInstall = true
                dirChooser = true
                menu = true
                menuGroup = "Patchbay"
                shortcut = true
                // Stable across versions so MSI upgrades replace rather than duplicate installs.
                upgradeUuid = "6b0f3d1e-6d0b-4f7e-9a51-3c0a2f6f3b7a"
            }
        }

        buildTypes.release.proguard {
            // Off in Phase 0. Revisit with reproducibility checks in Phase 1.
            isEnabled.set(false)
        }
    }
}
