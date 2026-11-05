// Patchbay — root settings.
//
// Repositories are content-filtered: Google's repository may only serve Android/Google groups,
// so a typo-squatted artifact in another group cannot be pulled from it (and vice versa).
// Project-level repositories are forbidden.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "patchbay"

// `-Ppatchbay.jvmOnly=true` builds only the pure-JVM modules (core, simulators, hub). They need
// neither the Android SDK nor Google Maven, so they build on a headless hub host or in restricted CI.
val jvmOnly = providers.gradleProperty("patchbay.jvmOnly").orNull == "true"

include(":core", ":simulators", ":hub")
if (jvmOnly) {
    // Same sources, but the build file has no Android target, so AGP is never resolved.
    project(":core").buildFileName = "build.jvm-only.gradle.kts"
    project(":simulators").buildFileName = "build.jvm-only.gradle.kts"
} else {
    include(":ui", ":app-android", ":app-windows")
    // :app-wear is Phase 3 and deliberately not included yet (see app-wear/README.md).
}
