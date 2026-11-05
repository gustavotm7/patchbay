import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// core — domain model, NetGuard, discovery, adapters, automation, storage, crypto, sync.
// Targets: jvm (desktop + hub) and android. See ADR 0001.
// A JVM-only variant of this file (build.jvm-only.gradle.kts) is used with -Ppatchbay.jvmOnly=true.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns.named("test") { executionTask.configure { useJUnitPlatform() } }
    }
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
    android {
        namespace = "dev.patchbay.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    sourceSets {
        // JVM APIs (java.net, JCA) shared by the jvm and android targets (ADR 0001).
        val jvmShared = create("jvmShared") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmShared)
        getByName("androidMain").dependsOn(jvmShared)
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
