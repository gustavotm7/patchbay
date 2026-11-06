// app-android — Android shell. AGP 9 has built-in Kotlin support, so no kotlin-android plugin.
// Phase 0: demo-home UI only. Phase 1 adds Bootstrap, app lock, real adapters.
plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.patchbay.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.patchbay"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = project.version.toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release APKs are unsigned in CI; sign with your own key (docs/RELEASE.md).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // One copy of the fonts, shared with the desktop target: ui/src/fonts/font/*.ttf -> R.font.*
    sourceSets.getByName("main").res.srcDir(rootProject.file("ui/src/fonts"))

    buildFeatures {
        buildConfig = false
        compose = true
    }

    // Don't embed the Google-encrypted dependency metadata block in the APK: it's opaque to
    // users and hurts reproducibility.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // InvalidPackage catches JDK-only APIs leaking into core (which also targets desktop JVM).
        error += setOf("InvalidPackage", "NewApi", "HardcodedDebugMode", "AllowBackup", "ExportedReceiver", "ExportedService")
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":simulators"))
    implementation(libs.androidx.activity.compose)
    // App lock (BiometricPrompt: strong biometric or device PIN/pattern). Local only.
    implementation(libs.androidx.biometric)
    // Camera playback (ADR 0008). Sockets only through GuardedSocketFactory; see GuardedPlayer.kt.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.ui)
    // Encrypted storage (ADR 0003)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqlcipher.android)
}
