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
        id("com.android.library")           version "8.5.2"
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
