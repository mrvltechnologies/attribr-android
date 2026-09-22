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
        // Reconciled 2026-09-22 (Volta Android Phase 3.1A, authorised shared-platform
        // repair) — reverted Sprint 14's bump to 8.7.3/1.9.24.
        //
        // Sprint 14's stated justification ("matches Hawk Android, which is on 8.7.3")
        // was checked directly against Hawk's own committed
        // `android/gradle/libs.versions.toml` and is factually incorrect: Hawk is on
        // AGP 8.12.0 / Kotlin 2.1.0, not 8.7.3. Sprint 14 also asserted that this pin
        // "will be overridden by the parent" when consumed via `includeBuild` and so
        // only matters for stand-alone builds — that is also not what Gradle actually
        // does: this pin IS honoured by `AgpVersionCompatibilityRule` even under
        // `includeBuild`, which is exactly what broke Volta Android's composite build
        // (see volta_ev_platform/apps/volta-android/MRVL_ANDROID_TOOLCHAIN_COMPATIBILITY.md
        // for the full blast-radius investigation across every known consumer).
        //
        // Direct-evidence consumers checked: Volta Android (8.12.0/2.0.21, composite
        // build, broken by Sprint 14, now fixed), Hawk Android (8.12.0/2.1.0, composite
        // build via symlink to this same checkout — would have broken identically),
        // CrashrKit's own sibling SDK (8.12.0/2.0.21, unaffected — used as the
        // portfolio's other reference pin). FlashSeat's native Android host module
        // declares AGP 8.7.3/Kotlin 2.1.0 (`apps/android/build.gradle.kts`) — genuinely
        // different from every other consumer checked, and NOT independently verified
        // here (no Gradle wrapper checked into that repo's android/ directory and no
        // system-wide `gradle` binary available in this environment, so it could not be
        // built/tested). This reconciliation matches the toolchain 3 of 4 known real
        // consumers actually use; FlashSeat's team should verify their own build
        // against this pin (or against a future immutable published Attribr artifact)
        // separately — that mismatch is disclosed, not silently overridden or ignored.
        id("com.android.library")           version "8.12.0"
        id("org.jetbrains.kotlin.android")  version "2.0.21"
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
