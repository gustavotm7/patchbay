import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// JVM-only variant of simulators/build.gradle.kts (see settings.gradle.kts).
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
        // JVM APIs (java.net, JCA) shared by the jvm and android targets (ADR 0001).
        val jvmShared = create("jvmShared") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmShared)
        commonMain.dependencies {
            api(project(":core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
