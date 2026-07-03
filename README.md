# Attribr SDK for Android

Install attribution + 30-day retention tracking, with first-class Google Play Install Referrer support.

**Version:** `1.3.0` — Sprint 3 Play Referrer support live in production.

## Consumption paths

Consume Attribr through **one** of the three paths below.

### 1. Local `includeBuild` — the MRVL default

In the consuming app's `settings.gradle.kts`:
```kotlin
includeBuild("/absolute/path/to/Attribr-by-MRVL/sdk/kotlin")
```
Then in `app/build.gradle.kts`:
```kotlin
implementation("com.mrvltechnologies:attribr:1.3.0")
```
This is what every MRVL dogfood app uses. Fastest iteration, no artifact publishing needed.

### 2. Maven Local — recommended for CI/CD dogfood

From `sdk/kotlin/`:
```bash
./gradlew :attribr:publishToMavenLocal
```
In the consuming app's `settings.gradle.kts` add `mavenLocal()` to `dependencyResolutionManagement { repositories { … } }`, then:
```kotlin
implementation("com.mrvltechnologies:attribr:1.3.0")
```

### 3. JitPack — recommended for external consumers

Tag a release in git:
```bash
git tag v1.3.0
git push origin v1.3.0
```
JitPack detects the tag and builds automatically. In the consuming app:
```kotlin
// settings.gradle.kts
repositories { maven { url = uri("https://jitpack.io") } }

// app/build.gradle.kts
implementation("com.github.MarvelTechnologies:Attribr-by-MRVL:1.3.0")
```

## Release checklist (v1.3.0 → v1.3.1+)

1. Bump `version` in `attribr/build.gradle.kts` (both the module `version = "…"` and inside the `afterEvaluate { publishing { … } }` block).
2. Add an entry to `CHANGELOG.md` describing the change.
3. Bump `SDK_VERSION` in `defaultConfig.buildConfigField` if you rely on it at runtime.
4. Commit and tag: `git tag v1.3.1 && git push origin v1.3.1`.
5. Verify JitPack build at `https://jitpack.io/#MarvelTechnologies/Attribr-by-MRVL`.
6. Update the release-log rows in each MRVL Android app's `marketing.md`.

## Integration snippet

```kotlin
// Application.onCreate()
Attribr.initialize(this, apiKey = "attr_live_<yourprefix>...")
Attribr.setConsent(ConsentState.GRANTED)

// From ProcessLifecycleOwner or Activity.onResume()
Attribr.trackLaunch()
```

Play Install Referrer is queried automatically on first launch. See `ATTRIBUTION_METHODOLOGY.md` for what fields are collected and what is NOT stored (raw IP, raw UA, GAID/Android ID — none of these ever leave the device).
