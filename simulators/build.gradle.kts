import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// simulators — fake devices for every adapter, used by contract tests and the in-app demo home.
// Simulators only ever bind to loopback.
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
        namespace = "dev.patchbay.simulators"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    sourceSets {
        // JVM APIs (java.net, JCA) shared by the jvm and android targets (ADR 0001).
        val jvmShared = create("jvmShared") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmShared)
        getByName("androidMain").dependsOn(jvmShared)
        commonMain.dependencies {
            api(project(":core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
