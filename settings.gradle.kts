pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Sprint 13B — pin plugin versions here so the module-level
    // build.gradle.kts can keep using `plugins { id("...") }` without a
    // per-module version (matches JitPack's expected layout). If you use
    // this SDK via `includeBuild` from a parent project that already
    // declares these plugins, these versions will be overridden by the
    // parent — the constraint only kicks in when the SDK is built stand-alone.
    plugins {
        // Sprint 14 — bumped from 8.5.2 to 8.7.3 to match modern MRVL app
        // AGP versions (Hawk Android is on 8.7.3). Composite Gradle builds
        // (`includeBuild`) require the root and included build to agree on
        // AGP version, so this pin drives Sprint 14 dogfood consumption.
        // Stand-alone builds still work; Kotlin stays at 1.9.24 (also
        // supported by AGP 8.7.x).
        id("com.android.library")           version "8.7.3"
        id("org.jetbrains.kotlin.android")  version "1.9.24"
    }
}

dependencyResolutionManagement {
    // JitPack builds via `includeBuild` semantics — its Gradle harness supports
    // both PREFER_SETTINGS and PREFER_PROJECT. We choose PREFER_PROJECT so that
    // the module-level build.gradle.kts's declared repositories are honoured.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "attribr-sdk"
include(":attribr")
