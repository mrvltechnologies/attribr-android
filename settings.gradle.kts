pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
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
