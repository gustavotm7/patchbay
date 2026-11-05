import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// JVM-only variant of core/build.gradle.kts, selected by settings.gradle.kts when
// -Ppatchbay.jvmOnly=true. Keep the dependencies identical to build.gradle.kts.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
    sourceSets {
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
