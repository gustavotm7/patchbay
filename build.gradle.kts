// Patchbay — root build.
//
// The Kotlin Gradle plugin must share a classloader with the Android Gradle plugin, so both
// are loaded here. AGP sits on the root buildscript classpath only when Android modules
// are part of the build (i.e. not -Ppatchbay.jvmOnly=true), so JVM-only builds never resolve it.

buildscript {
    val jvmOnly = project.findProperty("patchbay.jvmOnly")?.toString() == "true"
    if (!jvmOnly) {
        dependencies { classpath(libs.android.gradlePlugin) }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.cyclonedx)
}

group = "dev.patchbay"
version = providers.gradleProperty("patchbay.version").getOrElse("0.0.0-phase0")

subprojects {
    // SBOM covers what ships: runtime classpaths only, not compiler/tooling configurations.
    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("jvmRuntimeClasspath", "desktopRuntimeClasspath", "runtimeClasspath", "releaseRuntimeClasspath", "androidRuntimeClasspath"))
    }

    group = rootProject.group
    version = rootProject.version

    // Reproducible archives: fixed timestamps and file order in every jar/zip we produce.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<Test>().configureEach {
        // Tests must never touch the real network. Simulators bind to loopback only.
        systemProperty("patchbay.test", "true")
    }
}

// Runs the repository policy checks (NetGuard bypass, trust-all TLS, banned SDKs) as part of `check`.
val checkPolicy = tasks.register<Exec>("checkPolicy") {
    group = "verification"
    description = "Fails if code bypasses NetGuard, disables TLS verification or adds a banned SDK."
    commandLine("bash", "${rootDir}/tools/check-policy.sh")
    workingDir = rootDir
}
tasks.register("check") {
    group = "verification"
    dependsOn(checkPolicy)
}

// Resolves every resolvable configuration in every project without compiling anything. Used by the
// verification-bootstrap workflow so checksums cover the whole graph even if a compile step fails.
tasks.register("resolveAllDependencies") {
    group = "help"
    description = "Resolves all configurations (for --write-verification-metadata)."
    notCompatibleWithConfigurationCache("walks all configurations at execution time")
    doLast {
        allprojects.forEach { p ->
            p.configurations.filter { it.isCanBeResolved }.forEach { c ->
                runCatching { c.incoming.artifactView { lenient(true) }.files.files }
            }
            p.buildscript.configurations.filter { it.isCanBeResolved }.forEach { c ->
                runCatching { c.incoming.artifactView { lenient(true) }.files.files }
            }
        }
    }
}
