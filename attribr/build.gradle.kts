plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

group   = "com.mrvltechnologies"
version = "1.4.0"

android {
    namespace = "com.mrvltechnologies.attribr"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        targetSdk = 35
        consumerProguardFiles("consumer-rules.pro")
        // Sprint 3 — bump to expose the structured play_referrer payload
        buildConfigField("String", "SDK_VERSION", "\"1.4.0\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Play Install Referrer — deterministic deferred attribution on Android
    implementation("com.android.installreferrer:installreferrer:2.2")
}

// Sprint 3C — maven-publish enables consumption via JitPack, Maven Local,
// or any Maven-compatible artifact repository. Coordinates:
//   com.mrvltechnologies:attribr:1.4.0
//
// Publish locally:   ./gradlew :attribr:publishToMavenLocal
// Publish via JitPack: push a git tag; jitpack.io builds automatically.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId    = "com.mrvltechnologies"
                artifactId = "attribr"
                version    = "1.4.0"
                pom {
                    name.set("Attribr SDK for Android")
                    description.set("Install attribution + 30-day retention tracking with Google Play Install Referrer support.")
                    url.set("https://attribr.dev")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
    }
}
