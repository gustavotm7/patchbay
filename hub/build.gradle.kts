// hub — Phase 3 headless daemon running the same core on an always-on box (mini PC / Pi).
// Pure JVM: builds without the Android SDK.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.patchbay.hub.HubMainKt")
}

tasks.test { useJUnitPlatform() }
